package tech.voicer.voipicking.state

/**
 * Vocabulário de comandos de voz aceitos. [termos] é a fonte única usada tanto pro matching
 * quanto pra montar o prompt de priming do decoder STT (ver [vocabularioDePrompt]) — evita ter
 * a mesma lista de palavras duplicada em dois lugares que podem sair de sincronia.
 */
enum class PickingCommand(val termos: Set<String>) {
    RECEBER_TAREFA(setOf("receber tarefa", "nova tarefa", "buscar tarefa", "iniciar tarefa")),
    CONFIRMAR(setOf("confirmado", "confirmo", "ok", "certo", "cheguei", "beleza")),
    REPETIR(setOf("repete", "repetir", "de novo", "nao entendi")),
    DIVERGENCIA(setOf("faltou", "divergencia", "diferente", "quebrado", "avariado")),
    CANCELAR(setOf("cancelar", "para", "parar", "sair"));

    companion object {
        private val PONTUACAO = Regex("[.,!?;:]")
        private val ESPACOS = Regex("\\s+")

        private fun normalizar(texto: String): String =
            texto.trim().lowercase().replace(PONTUACAO, "").replace(ESPACOS, " ")

        /** Quanto uma palavra curta pode ter de erro de transcrição antes de virar falso-positivo. */
        private fun limiteDeErro(palavra: String): Int = when {
            palavra.length <= 3 -> 0
            palavra.length <= 6 -> 1
            else -> 2
        }

        /**
         * Reconhece o comando dito dentre [permitidos] (tipicamente
         * [PickingStateMachine.comandosPermitidos] do estado atual) — restringir ao estado evita
         * colisão com vocabulário de outro estado e reduz o espaço de busca do fallback.
         *
         * Cada palavra do termo precisa ter, na fala transcrita, uma palavra a uma distância de
         * edição tolerável (0 pra palavras curtas, até 2 pra palavras longas) — não precisa ser
         * substring contíguo, então tolera erro de ASR ("recebei" por "receber") e palavras extras
         * intercaladas ("de receber tarefa"), sem exigir frase exata.
         */
        fun reconhecer(transcricaoBruta: String, permitidos: Set<PickingCommand>): PickingCommand? {
            if (permitidos.isEmpty()) return null
            val palavrasFaladas = normalizar(transcricaoBruta).split(" ").filter { it.isNotBlank() }
            if (palavrasFaladas.isEmpty()) return null

            var melhorComando: PickingCommand? = null
            var melhorErro = Int.MAX_VALUE
            for (comando in permitidos) {
                for (termo in comando.termos) {
                    val erro = erroDoTermo(termo, palavrasFaladas) ?: continue
                    if (erro < melhorErro) {
                        melhorErro = erro
                        melhorComando = comando
                    }
                }
            }
            return melhorComando
        }

        /** Vocabulário pra priming do decoder do whisper — só as palavras válidas em [permitidos]. */
        fun vocabularioDePrompt(permitidos: Set<PickingCommand>): String =
            permitidos.flatMap { it.termos }.joinToString(" ")

        /** Soma das distâncias mínimas de cada palavra de [termo]; null se alguma não bate. */
        private fun erroDoTermo(termo: String, palavrasFaladas: List<String>): Int? {
            var erroTotal = 0
            for (palavraTermo in termo.split(" ")) {
                val distanciaMinima = palavrasFaladas.minOf { distanciaEdicao(it, palavraTermo) }
                if (distanciaMinima > limiteDeErro(palavraTermo)) return null
                erroTotal += distanciaMinima
            }
            return erroTotal
        }

        /** Distância de Levenshtein clássica — usada só pra tolerar erro de transcrição, não fonética. */
        private fun distanciaEdicao(a: String, b: String): Int {
            val custos = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var anterior = custos[0]
                custos[0] = i
                for (j in 1..b.length) {
                    val temp = custos[j]
                    custos[j] = minOf(
                        custos[j] + 1,
                        custos[j - 1] + 1,
                        anterior + if (a[i - 1] == b[j - 1]) 0 else 1
                    )
                    anterior = temp
                }
            }
            return custos[b.length]
        }
    }
}
