package tech.voicer.voipicking.voice

import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Implementação de [SttEngine] sobre o motor real do módulo `whisperlib` (whisper.cpp via JNI). */
class WhisperSttEngine : SttEngine {

    private var contexto: WhisperContext? = null

    override suspend fun carregarModelo(caminhoModelo: String) {
        contexto = withContext(Dispatchers.Default) {
            WhisperContext.createContextFromFile(caminhoModelo)
        }
    }

    override suspend fun transcrever(pcm16kFloat: FloatArray, idioma: String): SttResultado {
        val ctx = contexto ?: error("Modelo Whisper não carregado — chame carregarModelo() antes")
        val resultado = ctx.transcribe(pcm16kFloat, idioma)
        return SttResultado(texto = resultado.text, confianca = resultado.avgConfidence)
    }

    override suspend fun liberar() {
        contexto?.release()
        contexto = null
    }
}
