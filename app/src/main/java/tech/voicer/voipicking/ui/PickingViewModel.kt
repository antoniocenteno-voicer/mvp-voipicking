package tech.voicer.voipicking.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import tech.voicer.voipicking.voice.AudioRecorder
import tech.voicer.voipicking.voice.ModelManager
import tech.voicer.voipicking.voice.SttEngine
import tech.voicer.voipicking.voice.TtsManager

/** Ciclo de vida do motor de voz — independente do [PickingState] do pedido. */
enum class SttFase { NAO_INICIALIZADO, BAIXANDO_MODELO, CARREGANDO_MOTOR, PRONTO, GRAVANDO, TRANSCREVENDO, ERRO }

/**
 * Orquestra state machine + TTS + STT + persistência. A UI só observa [estado]/[sttFase]
 * e aciona [prepararStt], [iniciarGravacao] e [pararGravacaoEAvaliar] a partir do microfone.
 */
class PickingViewModel(
    application: Application,
    private val repository: PedidoRepository,
    private val tts: TtsManager,
    private val sttEngine: SttEngine
) : AndroidViewModel(application) {

    private val maquina = PickingStateMachine()
    private val recorder = AudioRecorder()

    private val _estado = MutableStateFlow<PickingState>(PickingState.Ocioso)
    val estado: StateFlow<PickingState> = _estado.asStateFlow()

    private val _sttFase = MutableStateFlow(SttFase.NAO_INICIALIZADO)
    val sttFase: StateFlow<SttFase> = _sttFase.asStateFlow()

    private val _sttMensagem = MutableStateFlow("")
    val sttMensagem: StateFlow<String> = _sttMensagem.asStateFlow()

    /** Baixa o modelo (1ª execução) e carrega o motor whisper.cpp. Chamar uma vez ao abrir a tela. */
    fun prepararStt() {
        if (_sttFase.value != SttFase.NAO_INICIALIZADO) return
        viewModelScope.launch {
            try {
                _sttFase.value = SttFase.BAIXANDO_MODELO
                val modelFile = ModelManager.ensureModel(getApplication()) { baixado, total ->
                    val pct = if (total > 0) (baixado * 100 / total).toInt() else 0
                    _sttMensagem.value = "Baixando modelo de voz... $pct%"
                }
                _sttFase.value = SttFase.CARREGANDO_MOTOR
                _sttMensagem.value = "Carregando motor de voz..."
                sttEngine.carregarModelo(modelFile.absolutePath)
                _sttFase.value = SttFase.PRONTO
                _sttMensagem.value = "Motor de voz pronto."
            } catch (e: Exception) {
                _sttFase.value = SttFase.ERRO
                _sttMensagem.value = "Erro ao preparar motor de voz: ${e.message}"
            }
        }
    }

    fun iniciarGravacao() {
        if (_sttFase.value != SttFase.PRONTO) return
        recorder.start()
        _sttFase.value = SttFase.GRAVANDO
    }

    /** Para a gravação, transcreve e roteia o texto pro passo de confirmação atual. */
    fun pararGravacaoEAvaliar() {
        if (_sttFase.value != SttFase.GRAVANDO) return
        val amostras = recorder.stop()
        _sttFase.value = SttFase.TRANSCREVENDO
        viewModelScope.launch {
            val resultado = sttEngine.transcrever(amostras)
            _sttFase.value = SttFase.PRONTO
            roteirarTranscricao(resultado.texto)
        }
    }

    private fun roteirarTranscricao(transcricao: String) {
        when (_estado.value) {
            is PickingState.AguardandoConfirmacaoEndereco -> onFalaConfirmacaoEndereco(transcricao)
            is PickingState.AguardandoConfirmacaoColeta -> onFalaConfirmacaoColeta(transcricao)
            else -> {}
        }
    }

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
        // viewModelScope já está cancelado neste ponto do ciclo de vida — usa um
        // escopo próprio só p/ garantir que o contexto nativo do whisper.cpp libere.
        CoroutineScope(Dispatchers.Default).launch { sttEngine.liberar() }
        super.onCleared()
    }
}
