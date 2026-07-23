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
        is PickingState.Ocioso,
        is PickingState.TarefaCarregada,
        is PickingState.AnunciandoEndereco,
        is PickingState.AnunciandoProduto,
        is PickingState.ItemConcluido,
        is PickingState.TarefaConcluida,
        is PickingState.Erro -> emptySet()

        is PickingState.AguardandoConfirmacaoEndereco ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.REPETIR, PickingCommand.CANCELAR)

        is PickingState.AguardandoConfirmacaoColeta ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.REPETIR, PickingCommand.DIVERGENCIA, PickingCommand.CANCELAR)

        is PickingState.DivergenciaReportada ->
            setOf(PickingCommand.CONFIRMAR, PickingCommand.CANCELAR)
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
                    // Além das palavras-comando, aceita o separador ditando o dígito
                    // verificador do endereço (ex.: "três um sete" p/ "317") como
                    // confirmação — checagem extra de que ele está no lugar certo.
                    val digitoFalado = PortugueseNumberParser.parseSequence(evento.transcricao)
                    when {
                        PickingCommand.reconhecer(evento.transcricao) == PickingCommand.CONFIRMAR ||
                            digitoFalado == estado.item.endereco.digitoVerificacao ->
                            PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                        PickingCommand.reconhecer(evento.transcricao) == PickingCommand.REPETIR ->
                            PickingState.AnunciandoEndereco(estado.tarefa, estado.item)
                        PickingCommand.reconhecer(evento.transcricao) == PickingCommand.CANCELAR ->
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
                    val comando = PickingCommand.reconhecer(evento.transcricao)
                    // Se não veio pré-parseado (evento.quantidadeDetectada), tenta extrair
                    // um número falado da própria transcrição — permite o separador
                    // simplesmente dizer a quantidade que separou em vez de "confirmado".
                    val quantidadeFalada = evento.quantidadeDetectada
                        ?: PortugueseNumberParser.parse(evento.transcricao)
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
