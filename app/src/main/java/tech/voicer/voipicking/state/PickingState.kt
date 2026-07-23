package tech.voicer.voipicking.state

import tech.voicer.voipicking.data.model.EnderecoItem
import tech.voicer.voipicking.data.model.Tarefa

/**
 * Estados possíveis do fluxo de separação. Cada estado define, via
 * [PickingStateMachine.comandosPermitidos], quais comandos de voz/UI
 * são aceitos naquele momento — o restante é ignorado ou gera reprompt.
 */
sealed class PickingState {

    object Ocioso : PickingState()

    data class TarefaCarregada(val tarefa: Tarefa) : PickingState()

    data class AnunciandoEndereco(val tarefa: Tarefa, val item: EnderecoItem) : PickingState()

    data class AguardandoConfirmacaoEndereco(
        val tarefa: Tarefa,
        val item: EnderecoItem,
        val tentativas: Int = 0
    ) : PickingState()

    data class AnunciandoProduto(val tarefa: Tarefa, val item: EnderecoItem) : PickingState()

    data class AguardandoConfirmacaoColeta(
        val tarefa: Tarefa,
        val item: EnderecoItem,
        val tentativas: Int = 0
    ) : PickingState()

    data class DivergenciaReportada(
        val tarefa: Tarefa,
        val item: EnderecoItem,
        val quantidadeInformada: Int
    ) : PickingState()

    data class ItemConcluido(val tarefa: Tarefa, val item: EnderecoItem) : PickingState()

    data class TarefaConcluida(val tarefa: Tarefa) : PickingState()

    data class Erro(val motivo: String, val estadoAnterior: PickingState) : PickingState()
}
