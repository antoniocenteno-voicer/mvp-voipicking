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
    val tokens: List<WhisperToken>
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

    suspend fun transcribe(data: FloatArray, language: String = "pt"): WhisperResult =
        withContext(scope.coroutineContext) {
            require(ptr != 0L)
            val numThreads = WhisperCpuConfig.preferredThreadCount
            WhisperLib.fullTranscribe(ptr, numThreads, data, language)

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
            WhisperResult(text.toString().trim(), tokens)
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
    }
}

private class WhisperLib {
    companion object {
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
            val cpuFlags = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") cpuInfoFlags() else ""
            when {
                cpuFlags.contains("asimddp") -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8dotprod.so")
                    System.loadLibrary("whisper_v8dotprod")
                }
                cpuFlags.contains("fphp") -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        private fun cpuInfoFlags(): String = try {
            File("/proc/cpuinfo").readText()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
            ""
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTokenCount(contextPtr: Long, segmentIndex: Int): Int
        external fun getTokenText(contextPtr: Long, segmentIndex: Int, tokenIndex: Int): String
        external fun getTokenProb(contextPtr: Long, segmentIndex: Int, tokenIndex: Int): Float
        external fun getSystemInfo(): String
    }
}
