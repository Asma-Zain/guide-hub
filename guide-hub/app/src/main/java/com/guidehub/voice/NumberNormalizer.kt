package com.guidehub.voice

/**
 * Converts spoken numbers to digits, covering Indian-English room-number styles:
 *  "two oh four" / "two zero four" / "two nought four" -> 204
 *  "two hundred and four" -> 204, "two forty" -> 240, "double four" -> 44, "triple one" -> 111
 *  "2 0 4" -> 204, "twenty four" -> 24
 */
object NumberNormalizer {
    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "nought" to 0, "naught" to 0, "nil" to 0,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val MULTIPLIERS = mapOf("double" to 2, "triple" to 3)

    private fun isNumberWord(t: String) = t in UNITS || t in TEENS || t in TENS || t == "hundred" || t in MULTIPLIERS || t.all(Char::isDigit)

    fun normalize(text: String): String {
        val tokens = text.lowercase()
            .replace("'", "").replace("’", "")
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .replace("-", " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (!isNumberWord(tokens[i]) || isLoneO(tokens, i)) { out += tokens[i]; i++; continue }
            var j = i
            val run = mutableListOf<String>()
            while (j < tokens.size) {
                val t = tokens[j]
                when {
                    isNumberWord(t) && !isLoneO(tokens, j) -> run += t
                    t == "and" && run.lastOrNull() == "hundred" && j + 1 < tokens.size && isNumberWord(tokens[j + 1]) -> {}
                    else -> break
                }
                j++
            }
            val converted = convertRun(run)
            if (converted != null) out += converted else out += run
            i = j
        }
        return out.joinToString(" ")
    }

    /** "o" is a number only next to other number words ("two o four"), not as a lone letter. */
    private fun isLoneO(tokens: List<String>, i: Int): Boolean {
        if (tokens[i] != "o" && tokens[i] != "oh") return false
        val prev = tokens.getOrNull(i - 1)?.let { isNumberWord(it) && it != "o" && it != "oh" } ?: false
        val next = tokens.getOrNull(i + 1)?.let { isNumberWord(it) && it != "o" && it != "oh" } ?: false
        return !(prev || next)
    }

    private fun convertRun(run: List<String>): String? {
        if (run.isEmpty()) return null
        if ("hundred" in run) return arithmetic(run)?.toString()
        // Digit-by-digit concatenation, with tens+unit pairs ("forty four") and double/triple.
        val sb = StringBuilder()
        var k = 0
        while (k < run.size) {
            val t = run[k]
            when {
                t.all(Char::isDigit) -> sb.append(t)
                t in MULTIPLIERS -> {
                    val nxt = run.getOrNull(k + 1) ?: return null
                    val d = UNITS[nxt] ?: nxt.takeIf { it.length == 1 && it[0].isDigit() }?.toInt() ?: return null
                    repeat(MULTIPLIERS.getValue(t)) { sb.append(d) }
                    k++
                }
                t in UNITS -> sb.append(UNITS.getValue(t))
                t in TEENS -> sb.append(TEENS.getValue(t))
                t in TENS -> {
                    val nxt = run.getOrNull(k + 1)
                    val u = nxt?.let { UNITS[it] }
                    if (u != null && u != 0 && nxt != "oh" && nxt != "o") { sb.append(TENS.getValue(t) + u); k++ } else sb.append(TENS.getValue(t))
                }
                else -> return null
            }
            k++
        }
        return sb.toString()
    }

    private fun arithmetic(run: List<String>): Int? {
        var total = 0
        var current = 0
        for (t in run) {
            when {
                t in UNITS -> current += UNITS.getValue(t)
                t in TEENS -> current += TEENS.getValue(t)
                t in TENS -> current += TENS.getValue(t)
                t == "hundred" -> { current = (if (current == 0) 1 else current) * 100; total += current; current = 0 }
                t.all(Char::isDigit) -> current += t.toInt()
                else -> return null
            }
        }
        return total + current
    }
}
