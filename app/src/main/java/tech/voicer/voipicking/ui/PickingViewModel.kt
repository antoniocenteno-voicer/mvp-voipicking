package tech.voicer.voipicking.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.voicer.voipicking.data.model.EnderecoItem
import tech.voicer.voipicking.data.model.Tarefa
import tech.voicer.voipicking.repository.PedidoRepository
import tech.voicer.voipicking.state.PickingCommand
import tech.voicer.voipicking.state.PickingEvent
import tech.voicer.voipicking.state.PickingState
import tech.voicer.voipicking.state.PickingStateMachine
import tech.voicer.voipicking.voice.AudioRecorder
import tech.voicer.voipicking.voice.ModelManager
import tech.voicer.voipicking.voice.SinalSonoro
import tech.voicer.voipicking.voice.SttEngine
import tech.voicer.voipicking.voice.TtsManager
import tech.voicer.voipicking.voice.VoiceActivityDetector

/** Ciclo de vida do motor de voz — independente do [PickingState] do pedido. */
enum class SttFase { NAO_INICIALIZADO, BAIXANDO_MODELO, CARREGANDO_MOTOR, PRONTO, GRAVANDO, TRANSCREVENDO, ERRO }

/**
 * Métricas de reconhecimento de voz pra validar latência/acerto em campo (device real, onde
 * logcat não é prático de acompanhar) — exibidas na tela em vez de só no log.
 */
data class DiagnosticoVoz(
    val ultimaTranscricao: String = "",
    val ultimoComandoReconhecido: String = "—",
    val ultimaLatenciaMs: Long = 0,
    val latenciaMediaMs: Long = 0,
    val totalTranscricoes: Int = 0,
    /** Split da última latência (ms): quanto foi encoder vs decoder — pra saber o que otimizar. */
    val ultimoEncodeMs: Int = 0,
    val ultimoDecodeMs: Int = 0
)

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
    private val sinalSonoro = SinalSonoro()

    private val _estado = MutableStateFlow<PickingState>(PickingState.Ocioso)
    val estado: StateFlow<PickingState> = _estado.asStateFlow()

    private val _sttFase = MutableStateFlow(SttFase.NAO_INICIALIZADO)
    val sttFase: StateFlow<SttFase> = _sttFase.asStateFlow()

    private val _sttMensagem = MutableStateFlow("")
    val sttMensagem: StateFlow<String> = _sttMensagem.asStateFlow()

    private val _escutaContinuaAtiva = MutableStateFlow(false)
    /** Liga/desliga escuta contínua. Controlado pelo usuário via toggle na UI. */
    val escutaContinuaAtiva: StateFlow<Boolean> = _escutaContinuaAtiva.asStateFlow()

    private var loopEscutaJob: Job? = null
    private val vad = VoiceActivityDetector()

    private val _diagnosticoVoz = MutableStateFlow(DiagnosticoVoz())
    val diagnosticoVoz: StateFlow<DiagnosticoVoz> = _diagnosticoVoz.asStateFlow()

    private val _infoMotor = MutableStateFlow("")
    /** Build nativo carregado + threads — só disponível depois que o modelo é carregado. */
    val infoMotor: StateFlow<String> = _infoMotor.asStateFlow()

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
                _infoMotor.value = sttEngine.descricaoMotor()
                if (_escutaContinuaAtiva.value) iniciarLoopEscutaContinua()
            } catch (e: Exception) {
                _sttFase.value = SttFase.ERRO
                _sttMensagem.value = "Erro ao preparar motor de voz: ${e.message}"
            }
        }
    }

    /** Liga/desliga o loop de escuta contínua (grava em chunks, transcreve, roteia, repete). */
    fun alternarEscutaContinua(ativa: Boolean) {
        _escutaContinuaAtiva.value = ativa
        if (ativa) iniciarLoopEscutaContinua() else pararLoopEscutaContinua()
    }

    private fun iniciarLoopEscutaContinua() {
        if (loopEscutaJob?.isActive == true) return
        if (_sttFase.value != SttFase.PRONTO) return // reengatado por prepararStt() quando ficar pronto
        recorder.iniciarEscutaContinua(vad)
        loopEscutaJob = viewModelScope.launch {
            recorder.segmentos().consumeEach { segmento ->
                if (!_escutaContinuaAtiva.value || !escutaPermitidaNoEstadoAtual()) return@consumeEach
                _sttFase.value = SttFase.TRANSCREVENDO
                val resultado = sttEngine.transcrever(
                    segmento,
                    prompt = maquina.promptDeVoz(),
                    grammar = maquina.gramaticaDeVoz()
                )
                _sttFase.value = SttFase.PRONTO
                roteirarTranscricao(resultado.texto, resultado.duracaoMs, resultado.encodeMs, resultado.decodeMs)
            }
        }
    }

    private fun pararLoopEscutaContinua() {
        recorder.pararEscutaContinua()
        loopEscutaJob?.cancel()
        loopEscutaJob = null
        if (_sttFase.value == SttFase.TRANSCREVENDO) _sttFase.value = SttFase.PRONTO
    }

    /**
     * Hook p/ suspender a escuta contínua em determinados [PickingState] (ex.: enquanto o TTS
     * está anunciando). Regra de quais estados bloqueiam ainda não definida — por ora sempre libera.
     */
    private fun escutaPermitidaNoEstadoAtual(): Boolean = true

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
            val resultado = sttEngine.transcrever(
                amostras,
                prompt = maquina.promptDeVoz(),
                grammar = maquina.gramaticaDeVoz()
            )
            _sttFase.value = SttFase.PRONTO
            roteirarTranscricao(resultado.texto, resultado.duracaoMs, resultado.encodeMs, resultado.decodeMs)
        }
    }

    private fun roteirarTranscricao(transcricao: String, duracaoMs: Long, encodeMs: Float = 0f, decodeMs: Float = 0f) {
        val permitidos = maquina.comandosPermitidos()
        val comando = PickingCommand.reconhecer(transcricao, permitidos)
        Log.d("VoxPicking", "transcrição='$transcricao' estado=${_estado.value::class.simpleName} comando=$comando duracaoMs=$duracaoMs")
        val diagnosticoAnterior = _diagnosticoVoz.value
        val novoTotal = diagnosticoAnterior.totalTranscricoes + 1
        val novaMedia = ((diagnosticoAnterior.latenciaMediaMs * diagnosticoAnterior.totalTranscricoes) + duracaoMs) / novoTotal
        _diagnosticoVoz.value = DiagnosticoVoz(
            ultimaTranscricao = transcricao,
            ultimoComandoReconhecido = comando?.name ?: "(nenhum)",
            ultimaLatenciaMs = duracaoMs,
            latenciaMediaMs = novaMedia,
            totalTranscricoes = novoTotal,
            ultimoEncodeMs = encodeMs.toInt(),
            ultimoDecodeMs = decodeMs.toInt()
        )
        when (_estado.value) {
            is PickingState.Ocioso -> onFalaOcioso(transcricao)
            is PickingState.AguardandoConfirmacaoEndereco -> onFalaConfirmacaoEndereco(transcricao)
            is PickingState.AguardandoConfirmacaoColeta -> onFalaConfirmacaoColeta(transcricao)
            is PickingState.DivergenciaReportada -> onFalaDivergencia(transcricao)
            is PickingState.Erro -> onFalaErro(transcricao)
            else -> {}
        }
    }

    private var tarefaPendenteJson: String? = null

    /** Deixa uma tarefa pronta pra ser puxada por voz ("receber tarefa") assim que chegar do backend. */
    fun definirTarefaDisponivel(tarefaJson: String) {
        tarefaPendenteJson = tarefaJson
    }

    /** Chamado pela UI/STT ao reconhecer fala do separador em [PickingState.Ocioso]. */
    fun onFalaOcioso(transcricao: String) {
        val comando = PickingCommand.reconhecer(transcricao, maquina.comandosPermitidos(PickingState.Ocioso))
        if (comando != PickingCommand.RECEBER_TAREFA) return
        val tarefaJson = tarefaPendenteJson ?: run {
            tts.falar("Nenhuma tarefa disponível no momento.")
            return
        }
        carregarTarefa(tarefaJson)
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

    /**
     * Chamado pela UI/STT ao reconhecer fala do separador durante confirmação de endereço.
     * Único estado onde a máquina espera exatamente uma de duas coisas — dígito certo ou não —
     * então feedback sonoro de erro entra aqui: se a fala não bateu (nem comando, nem dígito
     * certo), a máquina só tem como resultado permanecer em [PickingState.AguardandoConfirmacaoEndereco]
     * (nova tentativa) ou cair em [PickingState.Erro] (esgotou tentativas) — os dois casos em que
     * o separador precisa saber que precisa repetir o dígito.
     */
    fun onFalaConfirmacaoEndereco(transcricao: String) {
        val estadoAntes = _estado.value
        aplicarEvento(PickingEvent.ConfirmarEndereco(transcricao))
        val estadoDepois = _estado.value
        if (estadoAntes is PickingState.AguardandoConfirmacaoEndereco &&
            (estadoDepois is PickingState.AguardandoConfirmacaoEndereco || estadoDepois is PickingState.Erro)
        ) {
            sinalSonoro.tocarErro()
        }
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

    /** Chamado pela UI/STT ao reconhecer fala do separador em [PickingState.DivergenciaReportada]. */
    fun onFalaDivergencia(transcricao: String) {
        when (PickingCommand.reconhecer(transcricao, maquina.comandosPermitidos())) {
            PickingCommand.CONFIRMAR -> confirmarDivergencia()
            PickingCommand.CANCELAR -> cancelar()
            else -> {} // ignora ruído fora de contexto — segue esperando confirmação por voz
        }
    }

    fun cancelar() {
        aplicarEvento(PickingEvent.CancelarTarefa)
        falarEstadoAtual()
    }

    /** Chamado pela UI/STT ao reconhecer fala do separador em [PickingState.Erro]. */
    fun onFalaErro(transcricao: String) {
        if (PickingCommand.reconhecer(transcricao, maquina.comandosPermitidos()) == PickingCommand.CANCELAR) {
            cancelar()
        }
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
        Log.d("VoxPicking", "evento=${evento::class.simpleName} -> novoEstado=${_estado.value::class.simpleName}")
    }

    private fun falarEstadoAtual() {
        when (val e = _estado.value) {
            is PickingState.AnunciandoEndereco ->
                falarEAvancarAoConcluir(
                    "Vá até o endereço ${e.item.endereco.formatado}. Confirme dígito ${e.item.endereco.digitoVerificacao}."
                ) { onEnderecoAnunciadoConcluido() }
            is PickingState.AnunciandoProduto ->
                falarEAvancarAoConcluir(
                    "Separe ${e.item.quantidadeSolicitada} ${e.item.unidade} de ${e.item.produto.nome}."
                ) { onProdutoAnunciadoConcluido() }
            is PickingState.DivergenciaReportada ->
                tts.falar("Divergência registrada: ${e.quantidadeInformada} unidades informadas.")
            is PickingState.TarefaConcluida ->
                tts.falar("Tarefa concluída. Bom trabalho.")
            is PickingState.Erro ->
                tts.falar("Erro: ${e.motivo}")
            else -> {}
        }
    }

    /** Fala [texto] e só chama [aoConcluir] quando o TTS efetivamente termina de falar. */
    private fun falarEAvancarAoConcluir(texto: String, aoConcluir: () -> Unit) {
        viewModelScope.launch {
            tts.falarEAguardar(texto).first()
            aoConcluir()
        }
    }

    override fun onCleared() {
        pararLoopEscutaContinua()
        sinalSonoro.liberar()
        tts.liberar()
        // viewModelScope já está cancelado neste ponto do ciclo de vida — usa um
        // escopo próprio só p/ garantir que o contexto nativo do whisper.cpp libere.
        CoroutineScope(Dispatchers.Default).launch { sttEngine.liberar() }
        super.onCleared()
    }
}
