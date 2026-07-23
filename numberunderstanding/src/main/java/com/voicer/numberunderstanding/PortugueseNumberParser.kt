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

    /** Returns a number from 0 through 100, or null when none is present. */
    fun parse(text: String): Int? = parseSequence(text)?.toIntOrNull()?.takeIf { it in 0..100 }

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
            when (tokens[index]) {
                "cem" -> {
                    chunks += "100"
                    index++
                }
                "cento" -> {
                    val complement = readUpTo99(tokens, index + 1)
                    if (complement == null) {
                        chunks += "100"
                        index++
                    } else {
                        chunks += (100 + complement.first).toString()
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
