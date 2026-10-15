package com.guidehub.voice

import com.guidehub.nav.MapNode
import kotlin.math.max
import kotlin.math.min

data class Candidate(val node: MapNode, val name: String, val score: Double)

sealed class MatchResult {
    data class Confident(val candidate: Candidate) : MatchResult()
    data class Ambiguous(val candidates: List<Candidate>) : MatchResult()
    data object NoMatch : MatchResult()
}

/**
 * Fuzzy match of a spoken destination against room names + aliases (FR-3):
 * normalised Levenshtein similarity and token-set ratio, with digit-aware scoring so
 * "lab 204" never silently resolves to "lab 240".
 */
class DestinationMatcher(
    private val destinations: List<MapNode>,
    private val confidentThreshold: Double = 0.8,
    private val minThreshold: Double = 0.5,
    private val closeMargin: Double = 0.1,
) {
    fun match(query: String): MatchResult {
        val q = normalize(query)
        if (q.isBlank()) return MatchResult.NoMatch
        val best = destinations.mapNotNull { node ->
            node.names.map { name -> Candidate(node, name, score(q, normalize(name))) }.maxByOrNull { it.score }
        }.sortedByDescending { it.score }

        val top = best.firstOrNull() ?: return MatchResult.NoMatch
        if (top.score < minThreshold) return MatchResult.NoMatch
        val close = best.filter { it.score >= minThreshold && top.score - it.score < closeMargin }
        return if (top.score >= confidentThreshold && close.size == 1) MatchResult.Confident(top)
        else MatchResult.Ambiguous(best.filter { it.score >= minThreshold && top.score - it.score < 0.25 }.take(3))
    }

    companion object {
        private val STOP = setOf("the", "room", "number", "no")

        fun normalize(s: String): String =
            NumberNormalizer.normalize(s).split(" ").filter { it.isNotBlank() && it !in STOP }.joinToString(" ")

        fun score(q: String, n: String): Double {
            var base = max(levSim(q, n), tokenSetRatio(q, n))
            val qd = q.split(" ").filter { t -> t.any(Char::isDigit) }.toSet()
            val nd = n.split(" ").filter { t -> t.any(Char::isDigit) }.toSet()
            if (qd.isNotEmpty() && nd.isNotEmpty()) {
                base = if (qd.intersect(nd).isNotEmpty()) max(base, 0.95)
                else if (qd.any { a -> nd.any { b -> a.toList().sorted() == b.toList().sorted() } }) min(base, 0.55) // transposed digits
                else base * 0.6
            } else if (qd.isNotEmpty() && nd.isEmpty()) {
                base *= 0.8
            }
            return base
        }

        fun levSim(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        }

        fun levenshtein(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

        /** fuzzywuzzy-style token set ratio. */
        fun tokenSetRatio(a: String, b: String): Double {
            val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
            val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
            val inter = ta.intersect(tb).sorted().joinToString(" ")
            val da = (ta - tb).sorted().joinToString(" ")
            val db = (tb - ta).sorted().joinToString(" ")
            val t1 = listOf(inter, da).filter { it.isNotBlank() }.joinToString(" ")
            val t2 = listOf(inter, db).filter { it.isNotBlank() }.joinToString(" ")
            val scores = mutableListOf(levSim(t1, t2))
            if (inter.isNotBlank()) {
                scores += levSim(inter, t1)
                scores += levSim(inter, t2)
            }
            return scores.max()
        }
    }
}

/** Answer to "Did you mean A or B?" (FR-3). */
sealed class Confirmation {
    data object Yes : Confirmation()
    data object No : Confirmation()
    data class Choice(val index: Int) : Confirmation()
    data object Unclear : Confirmation()
}

object ConfirmationParser {
    private val ORDINALS = mapOf(
        "first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2,
        "last" to -1,
    )
    private val YES = Regex("^(yes|yeah|yep|yup|ya|yah|haan|han|ha|haa|ji|correct|right|sure|ok|okay|confirm|affirmative|that'?s (it|right|correct)|exactly|go ahead)\\b")
    private val NO = Regex("^(no|nope|nah|nahi|nahin|wrong|not (that|this)|neither|none|cancel|incorrect)\\b")

    fun parse(text: String, candidateNames: List<String>): Confirmation {
        val s = NumberNormalizer.normalize(text.lowercase().replace(Regex("[?.!,]"), " ")).trim()
        if (s.isBlank()) return Confirmation.Unclear

        // Naming one of the candidates beats yes/no ("yes, lab 240").
        if (candidateNames.size > 1) {
            val scored = candidateNames.mapIndexed { i, n -> i to DestinationMatcher.score(DestinationMatcher.normalize(s.replace(YES, "").trim()), DestinationMatcher.normalize(n)) }
            val best = scored.maxBy { it.second }
            val second = scored.filter { it.first != best.first }.maxOfOrNull { it.second } ?: 0.0
            if (best.second >= 0.9 && best.second - second >= 0.1) return Confirmation.Choice(best.first)
        }
        val words = s.split(" ")
        for (w in words) {
            ORDINALS[w]?.let { idx -> return Confirmation.Choice(if (idx < 0) candidateNames.lastIndex else idx) }
        }
        val numberWord = words.firstOrNull { it.length == 1 && it[0].isDigit() }
        if (numberWord != null && (words.size <= 3 || words.any { it == "option" || it == "number" })) {
            val idx = numberWord.toInt() - 1
            if (idx in candidateNames.indices) return Confirmation.Choice(idx)
        }
        if (NO.containsMatchIn(s)) return Confirmation.No
        if (YES.containsMatchIn(s)) return Confirmation.Yes
        return Confirmation.Unclear
    }
}
