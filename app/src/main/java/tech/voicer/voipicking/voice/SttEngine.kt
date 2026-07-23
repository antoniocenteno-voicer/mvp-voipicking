package tech.voicer.voipicking.voice

/** Resultado de uma transcrição de trecho de áudio. */
data class SttResultado(val texto: String, val confianca: Float)

/** Contrato de STT — desacopla state machine/ViewModel da engine concreta (whisperlib/whisper.cpp). */
interface SttEngine {
    /** Carrega modelo (ex.: ggml-base-q8_0.bin) do caminho informado. Chamar antes de transcrever. */
    suspend fun carregarModelo(caminhoModelo: String)

    /** Transcreve buffer PCM mono float32 [-1, 1] a 16kHz. */
    suspend fun transcrever(pcm16kFloat: FloatArray, idioma: String = "pt"): SttResultado

    suspend fun liberar()
}
