package com.voicer.numberunderstanding

import java.text.Normalizer

/** Parses Portuguese (pt-BR) number words and digit runs from any text source. */
object PortugueseNumberParser {
    private val units = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3,
        "quatro" to 4, "cinco" to 5, "seis" to 6, "meia" to 6, "sete" to 7,
        "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
        "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezasseis" to 16, "dezessete" to 17,
        "dezoito" to 18, "dezenove" to 19
    )
    private val tens = mapOf(
        "vinte" to 20, "trinta" to 30, "treinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60, "setenta" to 70, "oitenta" to 80,
        "noventa" to 90
    )
    // "cento" (not "cem") is the prefix form that takes a complement ("cento e vinte") —
    // grouped with the other hundreds since it behaves the same way; "cem" stands alone.
    private val hundreds = mapOf(
        "cento" to 100,
        "duzentos" to 200, "duzentas" to 200,
        "trezentos" to 300, "trezentas" to 300,
        "quatrocentos" to 400, "quatrocentas" to 400,
        "quinhentos" to 500, "quinhentas" to 500,
        "seiscentos" to 600, "seiscentas" to 600,
        "setecentos" to 700, "setecentas" to 700,
        "oitocentos" to 800, "oitocentas" to 800,
        "novecentos" to 900, "novecentas" to 900
    )

    // Only the 0-9 word forms — used where a code is read digit-by-digit (ex.: "três um
    // sete" for "317"), never as a magnitude. Keeping this separate from the full [units]
    // map (which also has ten..nineteen) matters for [parseDigitos] below.
    private val digitosUnicos = units.filterValues { it in 0..9 }

    // Teen words are phonetically "unit + suffix" (três/treze, sete/dezessete, seis/
    // dezesseis...) and whisper commonly confuses the pair when the picker meant the short
    // unit word -- the suffix is a quick, easy-to-blur syllable in a short, out-of-context
    // utterance. Used only as a fallback reading in [candidatosDigitos] when the strict
    // reading doesn't match, so a legitimate full-number "dezessete" (as in "trezentos e
    // dezessete") is never affected -- that path never calls this map.
    private val confusaoTeenComUnidade = mapOf(
        "onze" to 1, "doze" to 2, "treze" to 3, "catorze" to 4, "quatorze" to 4, "quinze" to 5,
        "dezesseis" to 6, "dezasseis" to 6, "dezessete" to 7, "dezoito" to 8, "dezenove" to 9
    )

    // Adjacent teen words that differ only by a short, easy-to-blur final syllable
    // ("-sseis" vs "-ssete") — a distinct confusion from the one above, this one shows up in
    // full-number reading too (ex.: "trezentos e dezessete" heard as "...dezesseis"), not
    // just digit-by-digit. Used only as an alternate reading in [candidatosSequence].
    private val teensConfusiveisEntreSi = listOf("dezesseis" to "dezessete", "dezasseis" to "dezessete")

    /** Word vocabulary this parser understands — useful to prime an STT decoder's prompt/bias. */
    val vocabularioPt: String by lazy { (units.keys + tens.keys + hundreds.keys + setOf("cem")).joinToString(" ") }

    /** Returns a number from 0 through 999, or null when none is present. */
    fun parse(text: String): Int? = parseSequence(text)?.toIntOrNull()?.takeIf { it in 0..999 }

    /**
     * Parses a code read digit-by-digit — each token must be one of the 0-9 word forms
     * ("três um sete" -> "317"); tokens that aren't a lone digit (tens, hundreds, filler
     * words) are skipped rather than combined, so a misheard "trezentos" doesn't get folded
     * into an unrelated bigger number. Use [parseSequence]/[parse] instead for magnitudes
     * (ex.: quantidade separada), where full-number reading is expected.
     */
    fun parseDigitos(text: String): String? {
        val normalized = stripAccents(text.lowercase())
        val tokens = normalized.split(Regex("[^a-z]+")).filter { it.isNotBlank() && it != "e" }
        if (tokens.isEmpty()) {
            return Regex("\\d").findAll(normalized).map { it.value }.toList()
                .takeIf { it.isNotEmpty() }?.joinToString("")
        }
        return tokens.mapNotNull { digitosUnicos[it]?.toString() }
            .takeIf { it.isNotEmpty() }?.joinToString("")
    }

    /**
     * Every digit-by-digit reading [text] could plausibly mean: the strict reading
     * ([parseDigitos]) plus, as a fallback, a reading where each teen-number word ("treze",
     * "dezessete"...) is instead heard as its confusable unit digit — a systematic ASR
     * confusion (see [confusaoTeenComUnidade]), not a random one, so it's safe to offer as an
     * alternative rather than a strict requirement. Compare each candidate against the known
     * expected digit sequence and accept if any matches; don't use this to guess a value with
     * no expected target to check against.
     */
    fun candidatosDigitos(text: String): List<String> {
        val normalized = stripAccents(text.lowercase())
        val tokens = normalized.split(Regex("[^a-z]+")).filter { it.isNotBlank() && it != "e" }
        val estrito = parseDigitos(text)
        val tolerante = tokens.mapNotNull { digitosUnicos[it] ?: confusaoTeenComUnidade[it] }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("") { it.toString() }
        return listOfNotNull(estrito, tolerante).distinct()
    }

    /** Converts consecutive spoken numbers into digits; "vinte e dois doze" becomes "2212". */
    fun parseSequence(text: String): String? {
        val normalized = stripAccents(text.lowercase())
        val tokens = normalized.split(Regex("[^a-z]+")).filter { it.isNotBlank() && it != "e" }
        if (tokens.isEmpty()) {
            return Regex("\\d+").findAll(normalized).map { it.value }.toList()
                .takeIf { it.isNotEmpty() }?.joinToString("")
        }

        val chunks = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == "cem" -> {
                    chunks += "100"
                    index++
                }
                hundreds.containsKey(token) -> {
                    val base = hundreds.getValue(token)
                    val complement = readUpTo99(tokens, index + 1)
                    if (complement == null) {
                        chunks += base.toString()
                        index++
                    } else {
                        chunks += (base + complement.first).toString()
                        index += complement.second + 1
                    }
                }
                else -> {
                    val value = readUpTo99(tokens, index)
                    if (value == null) index++ else {
                        chunks += value.first.toString()
                        index += value.second
                    }
                }
            }
        }
        return chunks.takeIf { it.isNotEmpty() }?.joinToString("")
    }

    /**
     * Every full-number reading [text] could plausibly mean: the strict reading
     * ([parseSequence]) plus, as a fallback, readings where an adjacent-confusable teen word
     * pair (see [teensConfusiveisEntreSi]) is swapped — a systematic ASR confusion, so it's
     * safe to offer as an alternative. Compare each candidate against the known expected
     * value and accept if any matches; don't use this to guess a value with no target.
     */
    fun candidatosSequence(text: String): List<String> {
        val normalized = stripAccents(text.lowercase())
        val estrito = parseSequence(text)
        val variantes = teensConfusiveisEntreSi.flatMap { (a, b) ->
            listOfNotNull(
                if (normalized.contains(a)) parseSequence(normalized.replace(a, b)) else null,
                if (normalized.contains(b)) parseSequence(normalized.replace(b, a)) else null
            )
        }
        return (listOfNotNull(estrito) + variantes).distinct()
    }

    private fun readUpTo99(tokens: List<String>, index: Int): Pair<Int, Int>? {
        val token = tokens.getOrNull(index) ?: return null
        tens[token]?.let { tensValue ->
            val unit = tokens.getOrNull(index + 1)?.let(units::get)
            return if (unit != null && unit in 1..9) (tensValue + unit) to 2 else tensValue to 1
        }
        return units[token]?.let { it to 1 }
    }

    private fun stripAccents(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
