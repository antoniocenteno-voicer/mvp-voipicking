package tech.voicer.voipicking.voice

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Sinal sonoro curto tocado quando a fala do separador não é compreendida — tom nativo do
 * Android (sem asset/arquivo baixado), consistente com a escolha do TTS de não ter dependência
 * externa.
 */
class SinalSonoro {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    fun tocarErro() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 350)
    }

    fun liberar() {
        toneGenerator.release()
    }
}
