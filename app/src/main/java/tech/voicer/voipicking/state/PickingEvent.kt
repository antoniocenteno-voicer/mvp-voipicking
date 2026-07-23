package tech.voicer.voipicking.state

/** Eventos que disparam transição de estado. Origem: STT (voz) ou UI (toque). */
sealed class PickingEvent {
    data class CarregarTarefa(val tarefaJson: String) : PickingEvent()
    object EnderecoAnunciado : PickingEvent()
    data class ConfirmarEndereco(val transcricao: String) : PickingEvent()
    object RepetirEndereco : PickingEvent()
    object ProdutoAnunciado : PickingEvent()
    data class ConfirmarColeta(val transcricao: String, val quantidadeDetectada: Int?) : PickingEvent()
    object RepetirProduto : PickingEvent()
    data class ReportarDivergencia(val quantidadeInformada: Int) : PickingEvent()
    object ConfirmarDivergencia : PickingEvent()
    object AvancarProximoItem : PickingEvent()
    object CancelarTarefa : PickingEvent()
    data class Falha(val motivo: String) : PickingEvent()
}
