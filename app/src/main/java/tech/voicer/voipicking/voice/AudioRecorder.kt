package tech.voicer.voipicking.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

private const val SAMPLE_RATE = 16000

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
}
