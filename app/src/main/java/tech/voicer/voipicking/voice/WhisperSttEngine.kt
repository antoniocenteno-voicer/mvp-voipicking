package tech.voicer.voipicking.voice

/**
 * Binding JNI pro whisper.cpp. Implementação nativa em
 * app/src/main/cpp/whisper_jni.cpp — depende do submódulo whisper.cpp
 * (ver README para clonar) sendo buildado pelo CMakeLists.txt.
 *
 * MVP: stub retorna resultado vazio até o submódulo ser vendorizado e o
 * .so nativo compilado — permite o app compilar e a state machine ser
 * testada com STT mockado antes da integração nativa completa.
 */
class WhisperSttEngine : SttEngine {

    private var handleNativo: Long = 0L
    private var nativoDisponivel = NATIVO_DISPONIVEL

    companion object {
        private val NATIVO_DISPONIVEL: Boolean = try {
            System.loadLibrary("whisper_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun carregarModelo(caminhoModelo: String): Boolean {
        if (!nativoDisponivel) return false
        handleNativo = nativeCarregarModelo(caminhoModelo)
        return handleNativo != 0L
    }

    override fun transcrever(pcm16k: ShortArray): SttResultado {
        if (!nativoDisponivel || handleNativo == 0L) {
            return SttResultado(texto = "", confianca = 0f)
        }
        val texto = nativeTranscrever(handleNativo, pcm16k)
        return SttResultado(texto = texto, confianca = 1f)
    }

    override fun liberar() {
        if (nativoDisponivel && handleNativo != 0L) {
            nativeLiberar(handleNativo)
            handleNativo = 0L
        }
    }

    private external fun nativeCarregarModelo(caminhoModelo: String): Long
    private external fun nativeTranscrever(handle: Long, pcm16k: ShortArray): String
    private external fun nativeLiberar(handle: Long)
}
