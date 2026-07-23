package tech.voicer.voipicking.state

/** Vocabulário de comandos de voz aceitos. STT mapeia transcrição livre -> comando via matcher. */
enum class PickingCommand {
    CONFIRMAR,       // "confirmado", "ok", "certo"
    REPETIR,         // "repete", "de novo"
    DIVERGENCIA,     // "faltou", "divergência", "quantidade diferente"
    CANCELAR;        // "cancelar", "para"

    companion object {
        private val CONFIRMAR_TERMOS = setOf("confirmado", "confirmo", "ok", "certo", "cheguei", "beleza")
        private val REPETIR_TERMOS = setOf("repete", "repetir", "de novo", "não entendi", "nao entendi")
        private val DIVERGENCIA_TERMOS = setOf("faltou", "divergencia", "divergência", "diferente", "quebrado", "avariado")
        private val CANCELAR_TERMOS = setOf("cancelar", "para", "parar", "sair")

        fun reconhecer(transcricaoBruta: String): PickingCommand? {
            val t = transcricaoBruta.trim().lowercase()
            return when {
                CANCELAR_TERMOS.any { t.contains(it) } -> CANCELAR
                DIVERGENCIA_TERMOS.any { t.contains(it) } -> DIVERGENCIA
                REPETIR_TERMOS.any { t.contains(it) } -> REPETIR
                CONFIRMAR_TERMOS.any { t.contains(it) } -> CONFIRMAR
                else -> null
            }
        }
    }
}
