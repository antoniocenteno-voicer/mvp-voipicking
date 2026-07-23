package tech.voicer.voipicking.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

private const val SAMPLE_RATE = 16000
private const val TAMANHO_FRAME_VAD = 480 // ~30ms @ 16kHz, granularidade de análise do VAD
private const val DURACAO_MAXIMA_SEGMENTO_MS = 12_000L // corta segmento mesmo sem silêncio (ruído contínuo)

/**
 * Gravador mono 16kHz PCM mínimo, produz buffer float32 [-1, 1] no formato que
 * whisper.cpp espera. whisper.cpp exige exatamente essa taxa/formato; sem resample aqui.
 */
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var isRecording = false

    fun start() {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 4
        )
        buffer.reset()
        audioRecord = record
        isRecording = true
        record.startRecording()

        recordingThread = Thread {
            val chunk = ByteArray(minBufSize)
            while (isRecording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                }
            }
        }.also { it.start() }
    }

    /** Para a gravação e devolve o áudio capturado como amostras float32 em [-1, 1]. */
    fun stop(): FloatArray {
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        val bytes = synchronized(buffer) { buffer.toByteArray() }
        val shortCount = bytes.size / 2
        val samples = FloatArray(shortCount)
        for (i in 0 until shortCount) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            samples[i] = sample / 32768f
        }
        return samples
    }

    private var vadThread: Thread? = null
    @Volatile private var escutaContinuaAtiva = false
    private val segmentosDeFala = Channel<FloatArray>(Channel.BUFFERED)

    /** Canal de segmentos de fala completos (início->silêncio), um item por utterance detectada. */
    fun segmentos(): ReceiveChannel<FloatArray> = segmentosDeFala

    /**
     * Escuta contínua com VAD: grava o tempo todo em background, mas só emite pro canal
     * quando o [vad] detecta um segmento de fala completo — sem janela fixa.
     */
    fun iniciarEscutaContinua(vad: VoiceActivityDetector) {
        if (escutaContinuaAtiva) return
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize * 4
        )
        vad.reiniciar()
        escutaContinuaAtiva = true
        record.startRecording()

        vadThread = Thread {
            val frame = ShortArray(TAMANHO_FRAME_VAD)
            var segmento = ArrayList<Float>()
            var inicioSegmentoMs = 0L

            while (escutaContinuaAtiva) {
                val lidos = record.read(frame, 0, frame.size)
                if (lidos <= 0) continue

                val amostras = FloatArray(lidos) { frame[it] / 32768f }
                val somaQuadrados = amostras.fold(0.0) { acc, s -> acc + s * s }
                val rms = sqrt(somaQuadrados / amostras.size).toFloat()

                when (vad.processarFrame(rms)) {
                    VadEvento.FALA_INICIADA -> {
                        segmento = ArrayList<Float>().apply { addAll(amostras.toList()) }
                        inicioSegmentoMs = System.currentTimeMillis()
                    }
                    VadEvento.FALA_EM_ANDAMENTO -> {
                        if (segmento.isNotEmpty()) {
                            segmento.addAll(amostras.toList())
                            if (System.currentTimeMillis() - inicioSegmentoMs >= DURACAO_MAXIMA_SEGMENTO_MS) {
                                segmentosDeFala.trySend(segmento.toFloatArray())
                                segmento = ArrayList()
                                vad.reiniciar()
                            }
                        }
                    }
                    VadEvento.FALA_FINALIZADA -> {
                        if (segmento.isNotEmpty()) {
                            segmentosDeFala.trySend(segmento.toFloatArray())
                        }
                        segmento = ArrayList()
                    }
                    VadEvento.SILENCIO -> Unit
                }
            }
            record.stop()
            record.release()
        }.also { it.start() }
    }

    fun pararEscutaContinua() {
        escutaContinuaAtiva = false
        vadThread?.join()
        vadThread = null
    }
}
