package tech.voicer.voipicking.voice

/** Resultado de uma transcrição de trecho de áudio. */
data class SttResultado(val texto: String, val confianca: Float, val duracaoMs: Long = 0)

/** Contrato de STT — desacopla state machine/ViewModel da engine concreta (whisperlib/whisper.cpp). */
interface SttEngine {
    /** Carrega modelo (ex.: ggml-base-q8_0.bin) do caminho informado. Chamar antes de transcrever. */
    suspend fun carregarModelo(caminhoModelo: String)

    /**
     * Transcreve buffer PCM mono float32 [-1, 1] a 16kHz. [prompt] primeia o decoder com o
     * vocabulário esperado nesse momento (ver [tech.voicer.voipicking.state.PickingStateMachine.promptDeVoz]) —
     * passar só o vocabulário válido no estado atual, em vez de um prompt fixo genérico, reduz
     * concorrência lexical no decoder e melhora acerto de primeira tentativa.
     */
    suspend fun transcrever(pcm16kFloat: FloatArray, idioma: String = "pt", prompt: String = ""): SttResultado

    suspend fun liberar()

    /**
     * Diagnóstico do motor (build nativo carregado, threads) — pra exibir em tela e entender,
     * num device sem logcat à mão, se a lentidão vem de ter caído num fallback não acelerado.
     */
    fun descricaoMotor(): String
}
