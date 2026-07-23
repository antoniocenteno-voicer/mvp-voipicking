package tech.voicer.voipicking.voice

/** Resultado de processar um frame de áudio no detector de atividade de voz. */
enum class VadEvento { SILENCIO, FALA_INICIADA, FALA_EM_ANDAMENTO, FALA_FINALIZADA }

/**
 * Detector de atividade de voz por energia (RMS) — sem dependência externa/nativa.
 * Pura: decide estado só a partir da sequência de níveis de energia recebidos, frame a frame.
 * Precisa de [framesMinimoFala] consecutivos acima do limiar pra confirmar início (ignora
 * estalos/ruído curto) e de [framesSilencioParaFinalizar] consecutivos abaixo pra fechar o
 * segmento (evita cortar no meio de uma pausa natural da fala).
 */
class VoiceActivityDetector(
    private val limiarEnergia: Float = 0.02f,
    private val framesMinimoFala: Int = 3,
    private val framesSilencioParaFinalizar: Int = 15
) {
    private var emFala = false
    private var framesFalaConsecutivos = 0
    private var framesSilencioConsecutivos = 0

    fun processarFrame(rms: Float): VadEvento {
        val temEnergia = rms >= limiarEnergia
        if (temEnergia) {
            framesSilencioConsecutivos = 0
            framesFalaConsecutivos++
            if (!emFala && framesFalaConsecutivos >= framesMinimoFala) {
                emFala = true
                return VadEvento.FALA_INICIADA
            }
            return if (emFala) VadEvento.FALA_EM_ANDAMENTO else VadEvento.SILENCIO
        }

        framesFalaConsecutivos = 0
        if (!emFala) return VadEvento.SILENCIO

        framesSilencioConsecutivos++
        if (framesSilencioConsecutivos >= framesSilencioParaFinalizar) {
            emFala = false
            framesSilencioConsecutivos = 0
            return VadEvento.FALA_FINALIZADA
        }
        return VadEvento.FALA_EM_ANDAMENTO
    }

    fun reiniciar() {
        emFala = false
        framesFalaConsecutivos = 0
        framesSilencioConsecutivos = 0
    }
}
