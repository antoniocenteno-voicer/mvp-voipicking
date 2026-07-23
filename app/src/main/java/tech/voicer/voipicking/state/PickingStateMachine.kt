package tech.voicer.voipicking.state

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
                    when (PickingCommand.reconhecer(evento.transcricao)) {
                        PickingCommand.CONFIRMAR -> PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                        PickingCommand.REPETIR -> PickingState.AnunciandoEndereco(estado.tarefa, estado.item)
                        PickingCommand.CANCELAR -> PickingState.Ocioso
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
                    when (PickingCommand.reconhecer(evento.transcricao)) {
                        PickingCommand.CONFIRMAR -> PickingState.ItemConcluido(estado.tarefa, estado.item)
                        PickingCommand.REPETIR -> PickingState.AnunciandoProduto(estado.tarefa, estado.item)
                        PickingCommand.DIVERGENCIA -> PickingState.DivergenciaReportada(
                            estado.tarefa, estado.item, evento.quantidadeDetectada ?: -1
                        )
                        PickingCommand.CANCELAR -> PickingState.Ocioso
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
