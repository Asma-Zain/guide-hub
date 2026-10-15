package com.guidehub.voice

/** Interpreted user instruction (FR-3). */
sealed class UserIntent {
    data class NavigateTo(val destinationQuery: String) : UserIntent()
    data object Cancel : UserIntent()
    data object Repeat : UserIntent()
    data object WhereAmI : UserIntent()
    data object HowFar : UserIntent()
    data object Pause : UserIntent()
    data object Resume : UserIntent()
    data object VolumeUp : UserIntent()
    data object VolumeDown : UserIntent()
    data object Help : UserIntent()
    data object Unknown : UserIntent()
}

/** Swappable parser interface so an on-device LLM parser could replace the rules later. */
interface IntentParser {
    fun parse(text: String): UserIntent
}

/** Keyword/regex parser. No network. */
class RuleBasedIntentParser : IntentParser {

    private fun Regex.hit(s: String) = containsMatchIn(s)

    private val cancel = Regex("\\b(cancel|abort|never ?mind|forget it|stop (the )?(navigation|navigating|guidance|route)|end (the )?(navigation|route)|exit navigation)\\b|^stop$")
    // Numbers are normalised before matching, so "one moment" arrives as "1 moment".
    private val pause = Regex("^(please )?(pause|wait|hold on|hold|1 (minute|moment|second)|wait a (minute|moment|second)|stop for a (minute|moment|second))( please)?$|\\bpause\\b")
    private val resume = Regex("^(please )?(resume|continue|carry on|go on|keep going|start again|let'?s go|unpause|go ahead)( please| now| navigation| navigating| walking)?$|\\bresume\\b")
    private val repeat = Regex("\\b(repeat|say (that |it )?again|what did you say|pardon|come again|once more|1 more time)\\b|^again$")
    private val whereAmI = Regex("\\b(where am i|where are we|my (current )?location|current location|what is my location|where i am)\\b")
    private val howFar = Regex("\\b(how far|how much (further|farther|more|longer)|distance (left|remaining)|how long|are we there|how many (metres|meters|steps)|remaining distance)\\b")
    private val volUp = Regex("\\b(volume up|louder|increase (the )?volume|speak up|turn (it |the volume )?up|raise (the )?volume)\\b")
    private val volDown = Regex("\\b(volume down|softer|quieter|lower (the )?volume|decrease (the )?volume|turn (it |the volume )?down|too loud)\\b")
    private val help = Regex("\\b(help|what can i say|commands|options|what can you do)\\b")

    private val navPrefixes = listOf(
        "can you (please )?(take|bring|guide|lead|show) me (to|the way to)",
        "(please )?(take|bring|guide|lead|walk|direct) me to",
        "let'?s go to",
        "(please )?(navigate|go|walk|head|get) to",
        "i (want to|need to|would like to|wanna|have to|gotta) (go|get) to",
        "i (want to|need to|would like to|wanna|have to) reach",
        "how (do|can) i (get|go|reach) to",
        "how (do|can) i reach",
        "(show me )?(the )?(way|route|directions|path) to",
        "where is",
        "wheres",
        "find",
        "navigate",
        "go",
    ).map { Regex("^(?:$it)\\b\\s*") }

    private val fillerWords = setOf("the", "please", "a", "an", "kindly", "now")

    override fun parse(text: String): UserIntent {
        val raw = text.lowercase().trim().replace(Regex("[?.!,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (raw.isBlank()) return UserIntent.Unknown
        val s = NumberNormalizer.normalize(raw)

        // Order matters: specific commands before the destination catch-all.
        if (whereAmI.hit(s)) return UserIntent.WhereAmI
        if (howFar.hit(s)) return UserIntent.HowFar
        if (volUp.hit(s)) return UserIntent.VolumeUp
        if (volDown.hit(s)) return UserIntent.VolumeDown
        if (cancel.hit(s)) return UserIntent.Cancel
        if (repeat.hit(s)) return UserIntent.Repeat
        if (help.hit(s)) return UserIntent.Help
        if (resume.hit(s)) return UserIntent.Resume
        if (pause.hit(s)) return UserIntent.Pause

        for (p in navPrefixes) {
            val m = p.find(s) ?: continue
            val dest = clean(s.substring(m.range.last + 1))
            if (dest.isNotBlank()) return UserIntent.NavigateTo(dest)
        }

        // Bare destination: a room number or a short phrase ("lab 204", "library please").
        val bare = clean(s)
        val words = bare.split(" ").filter { it.isNotBlank() }
        if (words.isNotEmpty() && (bare.any(Char::isDigit) || words.size <= 4)) return UserIntent.NavigateTo(bare)
        return UserIntent.Unknown
    }

    private fun clean(s: String): String =
        s.split(" ").filter { it.isNotBlank() }.let { w ->
            var words = w
            while (words.isNotEmpty() && words.first() in fillerWords) words = words.drop(1)
            while (words.isNotEmpty() && words.last() in fillerWords) words = words.dropLast(1)
            words
        }.joinToString(" ").trim()
}
