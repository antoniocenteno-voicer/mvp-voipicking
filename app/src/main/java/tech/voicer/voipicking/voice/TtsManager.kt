package tech.voicer.voipicking.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wrapper sobre android.speech.tts.TextToSpeech (motor nativo do Android, offline,
 * sem dependência externa). Suficiente pro MVP; troque por engine dedicado depois
 * se qualidade de voz virar requisito.
 */
class TtsManager(context: Context) {

    private var pronto = false
    private val filaPendente = mutableListOf<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                pronto = true
                tts.language = Locale("pt", "BR")
                filaPendente.forEach { falarInterno(it) }
                filaPendente.clear()
            }
        }
    }

    fun falar(texto: String) {
        if (pronto) falarInterno(texto) else filaPendente.add(texto)
    }

    private fun falarInterno(texto: String) {
        tts.speak(texto, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Emite Unit quando a fala termina — usado pra transicionar estado só após o TTS acabar. */
    fun falarEAguardar(texto: String) = callbackFlow {
        val id = UUID.randomUUID().toString()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) trySend(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) close()
            }
        }
        tts.setOnUtteranceProgressListener(listener)
        tts.speak(texto, TextToSpeech.QUEUE_ADD, null, id)
        awaitClose { tts.setOnUtteranceProgressListener(null) }
    }

    fun parar() {
        tts.stop()
    }

    fun liberar() {
        tts.stop()
        tts.shutdown()
    }
}
