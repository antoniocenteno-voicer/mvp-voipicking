package com.whispercpp.whisper

import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val LOG_TAG = "WhisperContext"

data class WhisperToken(
    val text: String,
    /** Model confidence for this token, 0f..1f. */
    val probability: Float
)

data class WhisperResult(
    val text: String,
    val tokens: List<WhisperToken>,
    /** Tempo médio (ms) por etapa da última transcrição — diagnóstico de latência. */
    val encodeMs: Float = 0f,
    val decodeMs: Float = 0f,
    val sampleMs: Float = 0f
) {
    /** Average token confidence across the whole transcription, 0f..1f. */
    val avgConfidence: Float
        get() = if (tokens.isEmpty()) 0f else tokens.map { it.probability }.average().toFloat()
}

class WhisperContext private constructor(private var ptr: Long) {
    // whisper.cpp is not safe to call from more than one thread at a time.
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * [prompt] primes whisper's decoder LM with the vocabulary expected right now (see
     * WhisperSttEngine/PickingStateMachine.promptDeVoz on the app side) — narrower prompt means
     * less lexical competition, so the decoder is more likely to land on the right word.
     */
    suspend fun transcribe(
        data: FloatArray,
        language: String = "pt",
        prompt: String = "",
        grammar: String = ""
    ): WhisperResult =
        withContext(scope.coroutineContext) {
            require(ptr != 0L)
            val numThreads = WhisperCpuConfig.preferredThreadCount
            WhisperLib.fullTranscribe(ptr, numThreads, data, language, prompt, grammar)

            val text = StringBuilder()
            val tokens = mutableListOf<WhisperToken>()
            val segmentCount = WhisperLib.getTextSegmentCount(ptr)
            for (s in 0 until segmentCount) {
                text.append(WhisperLib.getTextSegment(ptr, s))
                val tokenCount = WhisperLib.getTokenCount(ptr, s)
                for (t in 0 until tokenCount) {
                    val tokenText = WhisperLib.getTokenText(ptr, s, t)
                    // skip special/control tokens such as [_BEG_], [_TT_123], etc.
                    if (tokenText.startsWith("[_")) continue
                    tokens += WhisperToken(tokenText, WhisperLib.getTokenProb(ptr, s, t))
                }
            }
            val timings = WhisperLib.getTimings(ptr) // [sample, encode, decode] ms
            WhisperResult(
                text.toString().trim(), tokens,
                sampleMs = timings.getOrElse(0) { 0f },
                encodeMs = timings.getOrElse(1) { 0f },
                decodeMs = timings.getOrElse(2) { 0f }
            )
        }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) throw RuntimeException("Falha ao carregar modelo em $filePath")
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()

        /**
         * Build nativo carregado (dotprod/fp16/genérico) + threads em uso — pra diagnosticar em
         * tela, sem logcat, se um device caiu no fallback lento (ex.: ABI não é arm64-v8a, ou o
         * device não reporta a CPU flag esperada em /proc/cpuinfo) mesmo sendo hardware ARM real.
         * Inclui a ABI e as flags cruas detectadas — se caiu no fallback, isso mostra se foi
         * porque a ABI não é arm64-v8a ou porque a flag esperada simplesmente não apareceu no
         * /proc/cpuinfo desse device (kernel/vendor não expõe, ou o SoC de fato não suporta).
         */
        fun infoMotor(): String {
            val temAsimddp = WhisperLib.cpuFlagsDetectadas.contains("asimddp")
            val temFphp = WhisperLib.cpuFlagsDetectadas.contains("fphp")
            // Linha "Features" crua do /proc/cpuinfo — se vier vazia/"(sem leitura)", a leitura
            // do arquivo falhou (não é que a flag esteja ausente); se vier preenchida mas sem
            // asimddp/fphp, o kernel desse device realmente não expõe essas flags.
            val linhaFeatures = WhisperLib.cpuFlagsDetectadas.lineSequence()
                .firstOrNull { it.contains("Features", ignoreCase = true) }
                ?.trim()
                ?: if (WhisperLib.cpuFlagsDetectadas.isBlank()) "(sem leitura de /proc/cpuinfo)" else "(linha Features não encontrada)"
            return "${WhisperLib.bibliotecaCarregada} · ${WhisperCpuConfig.preferredThreadCount} threads · " +
                "abi=${WhisperLib.abiDetectado} asimddp=$temAsimddp fphp=$temFphp\n$linhaFeatures"
        }
    }
}

private class WhisperLib {
    companion object {
        val bibliotecaCarregada: String
        val abiDetectado: String
        val cpuFlagsDetectadas: String

        init {
            // arm64-v8a builds extra libraries targeting optional ARMv8.2 CPU
            // features (see whisperlib CMakeLists.txt), each only safe to load
            // if the device actually implements that feature (checked via the
            // matching /proc/cpuinfo flag) -- mirrors upstream whisper.cpp's
            // examples/whisper.android/.../LibWhisper.kt selection logic.
            //   - dotprod ("asimddp"): speeds up the int8 matmuls the q8_0
            //     quantized model uses, and is available on more devices than
            //     fp16 (most Android CPUs since ~2018), so it's preferred.
            //   - fp16 ("fphp"): faster half-precision arithmetic, narrower
            //     hardware support.
            abiDetectado = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
            cpuFlagsDetectadas = if (abiDetectado == "arm64-v8a") cpuInfoFlags() else ""
            bibliotecaCarregada = when {
                cpuFlagsDetectadas.contains("asimddp") -> "whisper_v8dotprod"
                cpuFlagsDetectadas.contains("fphp") -> "whisper_v8fp16_va"
                else -> "whisper"
            }
            Log.d(LOG_TAG, "Loading lib$bibliotecaCarregada.so (ABI=$abiDetectado, cpuFlags='$cpuFlagsDetectadas')")
            System.loadLibrary(bibliotecaCarregada)
        }

        private fun cpuInfoFlags(): String = try {
            File("/proc/cpuinfo").readText()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
            ""
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, prompt: String, grammar: String)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTokenCount(contextPtr: Long, segmentIndex: Int): Int
        external fun getTokenText(contextPtr: Long, segmentIndex: Int, tokenIndex: Int): String
        external fun getTokenProb(contextPtr: Long, segmentIndex: Int, tokenIndex: Int): Float
        external fun getSystemInfo(): String
        external fun getTimings(contextPtr: Long): FloatArray
    }
}
