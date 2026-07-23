package tech.voicer.voipicking.voice

import android.util.Log
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

    override suspend fun transcrever(pcm16kFloat: FloatArray, idioma: String, prompt: String, grammar: String): SttResultado {
        val ctx = contexto ?: error("Modelo Whisper não carregado — chame carregarModelo() antes")
        val inicio = System.currentTimeMillis()
        val resultado = ctx.transcribe(pcm16kFloat, idioma, prompt, grammar)
        val duracaoMs = System.currentTimeMillis() - inicio
        // Latência de transcrição por amostra — usado pra comparar modelos (ex.: base vs small)
        // em latência vs. acerto, sem precisar cronometrar no olhômetro.
        Log.d("VoxPicking", "transcrição levou ${duracaoMs}ms (encode=${resultado.encodeMs}ms " +
            "decode=${resultado.decodeMs}ms) pra ${pcm16kFloat.size} amostras")
        return SttResultado(
            texto = resultado.text,
            confianca = resultado.avgConfidence,
            duracaoMs = duracaoMs,
            encodeMs = resultado.encodeMs,
            decodeMs = resultado.decodeMs
        )
    }

    override suspend fun liberar() {
        contexto?.release()
        contexto = null
    }

    override fun descricaoMotor(): String = WhisperContext.infoMotor()
}
