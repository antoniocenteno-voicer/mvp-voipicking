package tech.voicer.voipicking.voice

/** Resultado de uma transcrição de trecho de áudio. */
data class SttResultado(val texto: String, val confianca: Float)

/** Contrato de STT — desacopla state machine/ViewModel da engine concreta (whisper.cpp). */
interface SttEngine {
    /** Carrega modelo (ex.: ggml-tiny/base) do caminho informado. Chamar antes de transcrever. */
    fun carregarModelo(caminhoModelo: String): Boolean

    /** Transcreve buffer PCM 16kHz mono 16-bit. Bloqueante — chamar fora da main thread. */
    fun transcrever(pcm16k: ShortArray): SttResultado

    fun liberar()
}
