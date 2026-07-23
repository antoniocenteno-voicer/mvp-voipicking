package tech.voicer.voipicking.state

import com.voicer.numberunderstanding.PortugueseNumberParser
import kotlinx.serialization.json.Json
import tech.voicer.voipicking.data.model.EnderecoItem
import tech.voicer.voipicking.data.model.PedidoEnvelope
import tech.voicer.voipicking.data.model.Tarefa

private const val MAX_TENTATIVAS = 3

/**
 * Máquina de estados pura (sem side-effect de IO). Recebe (estado, evento) -> novo estado.
 * Quem orquestra TTS/STT/DB é a camada de ViewModel, reagindo às transições.
 */
class PickingStateMachine {

    private val json = Json { ignoreUnknownKeys = true }

    var estadoAtual: PickingState = PickingState.Ocioso
        private set

    /** Comandos aceitos no estado atual — usado pra STT filtrar/ignorar ruído fora de contexto. */
    fun comandosPermitidos(estado: PickingState = estadoAtual): Set<PickingCommand> = when (estado) {
        is PickingState.Ocioso -> setOf(PickingCommand.RECEBER_TAREFA)

        is PickingState.TarefaCarregada,
        is PickingState.AnunciandoEndereco,
        is PickingState.AnunciandoProduto,
        is PickingState.ItemConcluido,
        is PickingState.TarefaConcluida -> emptySet()

        // Sem isso o separador ficaria preso na tela após 3 tentativas não reconhecidas,
        // dependendo de um toque manual pra resetar — precisa dar pra sair de voz também.
        is PickingState.Erro -> setOf(PickingCommand.CANCELAR)

        is PickingState.AguardandoConfirmacaoEndereco ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.REPETIR, PickingCommand.CANCELAR)

        is PickingState.AguardandoConfirmacaoColeta ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.REPETIR, PickingCommand.DIVERGENCIA, PickingCommand.CANCELAR)

        is PickingState.DivergenciaReportada ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.CANCELAR)
    }

    /**
     * Prompt de priming pro decoder STT — só o vocabulário válido no estado atual (comandos +
     * números quando aplicável). Passar isso a cada transcrição, em vez de um prompt fixo com
     * todo o vocabulário do app, reduz a concorrência lexical no decoder e melhora acerto.
     *
     * Número falado (dígito verificador ou quantidade) pode vir tanto por extenso ("trezentos e
     * dezessete") quanto dígito a dígito ("três um sete") — não há convenção fixa, então o
     * vocabulário completo entra sempre que o estado espera um número; a tolerância a isso fica
     * por conta de tentar as duas leituras na hora de comparar com o valor esperado, não de
     * restringir o prompt.
     */
    fun promptDeVoz(estado: PickingState = estadoAtual): String {
        val termosDeComando = PickingCommand.vocabularioDePrompt(comandosPermitidos(estado))
        val vocabularioNumerico = if (aceitaNumero(estado)) PortugueseNumberParser.vocabularioPt else ""
        return "$vocabularioNumerico $termosDeComando".trim()
    }

    private fun aceitaNumero(estado: PickingState): Boolean =
        estado is PickingState.AguardandoConfirmacaoEndereco ||
            estado is PickingState.AguardandoConfirmacaoColeta

    /**
     * Gramática GBNF que RESTRINGE de forma dura a saída do decoder ao conjunto exato de falas
     * válidas no estado atual — os mesmos comandos de [comandosPermitidos] e, quando o estado
     * espera número, qualquer sequência de palavras numéricas (cobre leitura dígito a dígito
     * "tres um sete" e por extenso "trezentos e dezessete"). Diferente de [promptDeVoz] (viés
     * suave), aqui um token fora do conjunto tem o logit esmagado (grammar_penalty no jni.cpp),
     * então o erro de CLASSE — número ouvido como palavra do dia-a-dia ("dez"->"desce"),
     * comando ouvido como outra coisa — deixa de ser emitível. É o que torna o modelo base
     * (rápido) assertivo o bastante sem trocar por um modelo mais lento.
     *
     * Escopo deliberado: a gramática restringe o VOCABULÁRIO (classe de palavras), não o VALOR.
     * Qualquer número 0-999 continua emitível, então a fala real do separador chega fiel pra
     * comparação com o esperado — não força a saída a virar o dígito verificador/quantidade
     * esperada (isso mascararia o separador estar no endereço vizinho errado). A desambiguação
     * dentro do vocabulário ("tres"/"treze") segue na camada de matching (candidatosDigitos).
     *
     * Terminais em minúsculo sem acento, iguais aos [PickingCommand.termos] e ao
     * [PortugueseNumberParser.vocabularioPt] (ambos sem acento) — a penalidade força o decoder a
     * essa grafia, que é exatamente a que a camada de matching normaliza. String vazia = sem
     * gramática (estados que não esperam fala de confirmação).
     */
    fun gramaticaDeVoz(estado: PickingState = estadoAtual): String {
        val permitidos = comandosPermitidos(estado)
        if (permitidos.isEmpty() && !aceitaNumero(estado)) return ""

        val alternativasComando = permitidos.flatMap { it.termos }
            .joinToString(" | ") { "\"${it}\"" }
        val temComando = alternativasComando.isNotBlank()
        val temNumero = aceitaNumero(estado)

        val alvos = when {
            temComando && temNumero -> "cmd | numero"
            temComando -> "cmd"
            else -> "numero"
        }

        return buildString {
            append("root ::= ws (").append(alvos).append(") ws\n")
            // espaço/pontuação de borda que o whisper costuma emitir (" receber tarefa.")
            append("ws ::= [ \\t.,!?]*\n")
            if (temComando) {
                append("cmd ::= ").append(alternativasComando).append("\n")
            }
            if (temNumero) {
                // "e" entra como conector da leitura por extenso ("trezentos e dezessete");
                // o parser o descarta, então é inócuo na leitura dígito a dígito.
                val palavras = (PortugueseNumberParser.vocabularioPt.split(" ") + "e")
                    .filter { it.isNotBlank() }
                    .joinToString(" | ") { "\"${it}\"" }
                append("numero ::= palavra (\" \" palavra)*\n")
                append("palavra ::= ").append(palavras).append("\n")
            }
        }
    }

    fun transicionar(evento: PickingEvent): PickingState {
        val novo = calcularProximoEstado(estadoAtual, evento)
        estadoAtual = novo
        return novo
    }

    private fun calcularProximoEstado(estado: PickingState, evento: PickingEvent): PickingState {
        if (evento is PickingEvent.CancelarTarefa) {
            return PickingState.Ocioso
        }
        if (evento is PickingEvent.Falha) {
            return PickingState.Erro(evento.motivo, estado)
        }

        return when (estado) {
            is PickingState.Ocioso -> when (evento) {
                is PickingEvent.CarregarTarefa -> runCatching {
                    val tarefa = json.decodeFromString<PedidoEnvelope>(evento.tarefaJson).tarefa
                    PickingState.TarefaCarregada(tarefa)
                }.getOrElse { PickingState.Erro("JSON de tarefa inválido: ${it.message}", estado) }
                else -> estado
            }

            is PickingState.TarefaCarregada -> when (evento) {
                is PickingEvent.EnderecoAnunciado, is PickingEvent.AvancarProximoItem ->
                    PickingState.AnunciandoEndereco(estado.tarefa, estado.tarefa.enderecos.first())
                else -> estado
            }

            is PickingState.AnunciandoEndereco -> when (evento) {
                is PickingEvent.EnderecoAnunciado ->
                    PickingState.AguardandoConfirmacaoEndereco(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.AguardandoConfirmacaoEndereco -> when (evento) {
                is PickingEvent.ConfirmarEndereco -> {
                    // Além das palavras-comando, aceita o separador ditando o dígito verificador
                    // do endereço como confirmação — sem convenção fixa de como ele lê o número,
                    // então tenta tanto por extenso ("trezentos e dezessete") quanto dígito a
                    // dígito ("três um sete"), incluindo as leituras tolerantes a confusão
                    // teen/unidade ("treze" por "três") e teen/teen vizinho ("dezessete" por
                    // "dezesseis"), aceitando se qualquer uma bater com "317".
                    //
                    // Trade-off aceito conscientemente: como a tolerância compara contra o
                    // alvo já conhecido, ela não distingue "STT ouviu errado" de "separador
                    // está no endereço vizinho errado (317 em vez do 316 esperado) e leu
                    // certo o que via" — os dois casos produzem o mesmo dado e o segundo
                    // passaria despercebido. Decisão registrada: manter assim por ora.
                    val alvo = estado.item.endereco.digitoVerificacao
                    val digitoBateu = PortugueseNumberParser.candidatosSequence(evento.transcricao).contains(alvo) ||
                        PortugueseNumberParser.candidatosDigitos(evento.transcricao).contains(alvo)
                    val comando = PickingCommand.reconhecer(evento.transcricao, comandosPermitidos(estado))
                    when {
                        comando == PickingCommand.CONFIRMAR || digitoBateu ->
                            PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                        comando == PickingCommand.REPETIR ->
                            PickingState.AnunciandoEndereco(estado.tarefa, estado.item)
                        comando == PickingCommand.CANCELAR ->
                            PickingState.Ocioso
                        else -> reincidirOuFalhar(estado, estado.tentativas) {
                            estado.copy(tentativas = it)
                        }
                    }
                }
                is PickingEvent.RepetirEndereco -> PickingState.AnunciandoEndereco(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.AnunciandoProduto -> when (evento) {
                is PickingEvent.ProdutoAnunciado ->
                    PickingState.AguardandoConfirmacaoColeta(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.AguardandoConfirmacaoColeta -> when (evento) {
                is PickingEvent.ConfirmarColeta -> {
                    val comando = PickingCommand.reconhecer(evento.transcricao, comandosPermitidos(estado))
                    // Se não veio pré-parseado (evento.quantidadeDetectada), tenta extrair um
                    // número falado da própria transcrição — permite o separador simplesmente
                    // dizer a quantidade em vez de "confirmado". Sem convenção fixa de leitura,
                    // tenta por extenso e dígito a dígito; prioriza o que bater com o esperado.
                    val candidatosPrimarios = listOfNotNull(
                        evento.quantidadeDetectada,
                        PortugueseNumberParser.parse(evento.transcricao),
                        PortugueseNumberParser.parseDigitos(evento.transcricao)?.toIntOrNull()
                    ).distinct()
                    // Além dos candidatos primários, tenta as leituras tolerantes a confusão
                    // teen/unidade (ex.: "treze" ouvido em vez de "três") e teen/teen vizinho
                    // (ex.: "dezessete" ouvido em vez de "dezesseis") só pra achar um match — se
                    // nada bater, o valor reportado como divergência usa só os primários, pra
                    // não reportar um número "inventado" que ninguém de fato disse.
                    val candidatosComTolerancia = (candidatosPrimarios +
                        PortugueseNumberParser.candidatosDigitos(evento.transcricao).mapNotNull { it.toIntOrNull() } +
                        PortugueseNumberParser.candidatosSequence(evento.transcricao).mapNotNull { it.toIntOrNull() }).distinct()
                    val quantidadeFalada = candidatosComTolerancia.firstOrNull { it == estado.item.quantidadeSolicitada }
                        ?: candidatosPrimarios.firstOrNull()
                    when {
                        comando == PickingCommand.CONFIRMAR -> PickingState.ItemConcluido(estado.tarefa, estado.item)
                        comando == PickingCommand.REPETIR -> PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                        comando == PickingCommand.CANCELAR -> PickingState.Ocioso
                        comando == PickingCommand.DIVERGENCIA -> PickingState.DivergenciaReportada(
                            estado.tarefa, estado.item, quantidadeFalada ?: -1
                        )
                        quantidadeFalada != null && quantidadeFalada == estado.item.quantidadeSolicitada ->
                            PickingState.ItemConcluido(estado.tarefa, estado.item)
                        quantidadeFalada != null ->
                            PickingState.DivergenciaReportada(estado.tarefa, estado.item, quantidadeFalada)
                        else -> reincidirOuFalhar(estado, estado.tentativas) {
                            estado.copy(tentativas = it)
                        }
                    }
                }
                is PickingEvent.ReportarDivergencia ->
                    PickingState.DivergenciaReportada(estado.tarefa, estado.item, evento.quantidadeInformada)
                is PickingEvent.RepetirProduto -> PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.DivergenciaReportada -> when (evento) {
                is PickingEvent.ConfirmarDivergencia -> PickingState.ItemConcluido(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.ItemConcluido -> when (evento) {
                is PickingEvent.AvancarProximoItem -> proximoItemOuConclusao(estado.tarefa, estado.item)
                else -> estado
            }

            is PickingState.TarefaConcluida -> estado
            is PickingState.Erro -> when (evento) {
                is PickingEvent.AvancarProximoItem -> estado.estadoAnterior
                else -> estado
            }
        }
    }

    private fun proximoItemOuConclusao(tarefa: Tarefa, itemAtual: EnderecoItem): PickingState {
        val proximo = tarefa.enderecos.firstOrNull { it.sequencia == itemAtual.sequencia + 1 }
        return if (proximo != null) {
            PickingState.AnunciandoEndereco(tarefa, proximo)
        } else {
            PickingState.TarefaConcluida(tarefa)
        }
    }

    private inline fun <T : PickingState> reincidirOuFalhar(
        estado: T,
        tentativas: Int,
        copiarComTentativas: (Int) -> T
    ): PickingState {
        val novaTentativa = tentativas + 1
        return if (novaTentativa >= MAX_TENTATIVAS) {
            PickingState.Erro("Comando de voz não reconhecido após $MAX_TENTATIVAS tentativas", estado)
        } else {
            copiarComTentativas(novaTentativa)
        }
    }
}
