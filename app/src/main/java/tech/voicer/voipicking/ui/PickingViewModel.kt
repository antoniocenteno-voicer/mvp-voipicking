package tech.voicer.voipicking.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.voicer.voipicking.data.model.EnderecoItem
import tech.voicer.voipicking.data.model.Tarefa
import tech.voicer.voipicking.repository.PedidoRepository
import tech.voicer.voipicking.state.PickingEvent
import tech.voicer.voipicking.state.PickingState
import tech.voicer.voipicking.state.PickingStateMachine
import tech.voicer.voipicking.voice.SttEngine
import tech.voicer.voipicking.voice.TtsManager

/**
 * Orquestra state machine + TTS + STT + persistência. A UI só observa [estado]
 * e envia comandos de voz reconhecidos (ou toques equivalentes no MVP).
 */
class PickingViewModel(
    private val repository: PedidoRepository,
    private val tts: TtsManager,
    private val sttEngine: SttEngine
) : ViewModel() {

    private val maquina = PickingStateMachine()

    private val _estado = MutableStateFlow<PickingState>(PickingState.Ocioso)
    val estado: StateFlow<PickingState> = _estado.asStateFlow()

    fun carregarTarefa(tarefaJson: String) {
        aplicarEvento(PickingEvent.CarregarTarefa(tarefaJson))
        val carregado = _estado.value
        if (carregado is PickingState.TarefaCarregada) {
            viewModelScope.launch { repository.persistirTarefa(carregado.tarefa, System.currentTimeMillis()) }
            aplicarEvento(PickingEvent.AvancarProximoItem)
        }
        falarEstadoAtual()
    }

    /** Chamado pela UI/STT ao reconhecer fala do separador durante confirmação de endereço. */
    fun onFalaConfirmacaoEndereco(transcricao: String) {
        aplicarEvento(PickingEvent.ConfirmarEndereco(transcricao))
        posTransicao()
    }

    fun onFalaConfirmacaoColeta(transcricao: String, quantidadeDetectada: Int? = null) {
        aplicarEvento(PickingEvent.ConfirmarColeta(transcricao, quantidadeDetectada))
        posTransicao()
    }

    fun onProdutoAnunciadoConcluido() {
        aplicarEvento(PickingEvent.ProdutoAnunciado)
        falarEstadoAtual()
    }

    fun onEnderecoAnunciadoConcluido() {
        aplicarEvento(PickingEvent.EnderecoAnunciado)
        falarEstadoAtual()
    }

    fun confirmarDivergencia() {
        val atual = _estado.value
        if (atual is PickingState.DivergenciaReportada) {
            viewModelScope.launch {
                registrarItemAtual(atual.tarefa, atual.item, atual.quantidadeInformada, confirmadoPorVoz = true, status = "DIVERGENCIA")
            }
        }
        aplicarEvento(PickingEvent.ConfirmarDivergencia)
        posTransicao()
    }

    fun cancelar() {
        aplicarEvento(PickingEvent.CancelarTarefa)
        falarEstadoAtual()
    }

    private fun posTransicao() {
        val atual = _estado.value
        if (atual is PickingState.ItemConcluido) {
            viewModelScope.launch {
                registrarItemAtual(atual.tarefa, atual.item, atual.item.quantidadeSolicitada, confirmadoPorVoz = true, status = "OK")
            }
            aplicarEvento(PickingEvent.AvancarProximoItem)
        }
        if (_estado.value is PickingState.TarefaConcluida) {
            val tarefaId = (_estado.value as PickingState.TarefaConcluida).tarefa.id
            viewModelScope.launch { repository.finalizarTarefa(tarefaId) }
        }
        falarEstadoAtual()
    }

    private suspend fun registrarItemAtual(
        tarefa: Tarefa,
        item: EnderecoItem,
        quantidade: Int,
        confirmadoPorVoz: Boolean,
        status: String
    ) {
        repository.registrarSeparacao(
            tarefaId = tarefa.id,
            enderecoItemId = item.sequencia.toLong(),
            quantidadeSeparada = quantidade,
            confirmadoPorVoz = confirmadoPorVoz,
            transcricao = null,
            agora = System.currentTimeMillis(),
            status = status
        )
    }

    private fun aplicarEvento(evento: PickingEvent) {
        _estado.value = maquina.transicionar(evento)
    }

    private fun falarEstadoAtual() {
        when (val e = _estado.value) {
            is PickingState.AnunciandoEndereco ->
                tts.falar("Vá até o endereço ${e.item.endereco.formatado}. Confirme dígito ${e.item.endereco.digitoVerificacao}.")
            is PickingState.AnunciandoProduto ->
                tts.falar("Separe ${e.item.quantidadeSolicitada} ${e.item.unidade} de ${e.item.produto.nome}.")
            is PickingState.DivergenciaReportada ->
                tts.falar("Divergência registrada: ${e.quantidadeInformada} unidades informadas.")
            is PickingState.TarefaConcluida ->
                tts.falar("Tarefa concluída. Bom trabalho.")
            is PickingState.Erro ->
                tts.falar("Erro: ${e.motivo}")
            else -> {}
        }
    }

    override fun onCleared() {
        tts.liberar()
        sttEngine.liberar()
        super.onCleared()
    }
}
