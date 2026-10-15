package com.guidehub.voice

import com.guidehub.TestMaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberNormalizerTest {
    private fun n(s: String) = NumberNormalizer.normalize(s)

    @Test fun indianEnglishRoomNumbers() {
        assertEquals("lab 204", n("lab two oh four"))
        assertEquals("lab 204", n("lab two zero four"))
        assertEquals("lab 204", n("lab two nought four"))
        assertEquals("lab 204", n("lab two hundred and four"))
        assertEquals("lab 204", n("lab two hundred four"))
        assertEquals("lab 240", n("lab two forty"))
        assertEquals("lab 240", n("lab two four zero"))
        assertEquals("room 44", n("room double four"))
        assertEquals("room 111", n("room triple one"))
        assertEquals("room 204", n("room 2 0 4"))
        assertEquals("room 24", n("room twenty four"))
        assertEquals("room 1100", n("room eleven hundred"))
        assertEquals("room 2044", n("room two oh double four"))
    }

    @Test fun lettersStayLetters() {
        assertEquals("block o", n("block o"))
        assertEquals("go to the lab", n("go to the lab"))
        assertEquals("lets go", n("let's go"))
    }
}

class IntentParserTest {
    private val p = RuleBasedIntentParser()
    private fun nav(text: String, expected: String) {
        val i = p.parse(text)
        assertTrue("'$text' -> $i", i is UserIntent.NavigateTo)
        assertEquals("'$text'", expected, (i as UserIntent.NavigateTo).destinationQuery)
    }
    private fun intent(text: String, expected: UserIntent) = assertEquals("'$text'", expected, p.parse(text))

    @Test fun navigationPhrasings() {
        nav("Take me to Lab 204", "lab 204")
        nav("take me to lab two oh four", "lab 204")
        nav("Please take me to the library", "library")
        nav("take me to the washroom please", "washroom")
        nav("Bring me to room two zero four", "room 204")
        nav("guide me to the staff room", "staff room")
        nav("lead me to HOD office", "hod office")
        nav("Navigate to lab 240", "lab 240")
        nav("go to the robotics lab", "robotics lab")
        nav("I want to go to the library", "library")
        nav("I need to go to room two hundred and four", "room 204")
        nav("I would like to go to the staff room", "staff room")
        nav("i wanna go to lab 204", "lab 204")
        nav("how do I get to the washroom", "washroom")
        nav("how can I reach lab 204", "lab 204")
        nav("where is the toilet", "toilet")
        nav("Where's the library?", "library")
        nav("show me the way to the office", "office")
        nav("directions to lab two forty", "lab 240")
        nav("find the robotics lab", "robotics lab")
        nav("can you take me to the restroom", "restroom")
        nav("let's go to lab 204", "lab 204")
        nav("two oh four", "204")
        nav("Lab 204", "lab 204")
        nav("library please", "library")
        nav("room number two zero four", "room number 204")
        nav("walk me to the entrance", "entrance")
        nav("head to the exit", "exit")
    }

    @Test fun commandPhrasings() {
        intent("cancel", UserIntent.Cancel)
        intent("Stop navigation", UserIntent.Cancel)
        intent("stop", UserIntent.Cancel)
        intent("never mind", UserIntent.Cancel)
        intent("abort", UserIntent.Cancel)
        intent("repeat", UserIntent.Repeat)
        intent("say that again", UserIntent.Repeat)
        intent("pardon?", UserIntent.Repeat)
        intent("come again", UserIntent.Repeat)
        intent("one more time", UserIntent.Repeat)
        intent("where am I", UserIntent.WhereAmI)
        intent("Where am I right now?", UserIntent.WhereAmI)
        intent("what is my location", UserIntent.WhereAmI)
        intent("how far", UserIntent.HowFar)
        intent("how much further", UserIntent.HowFar)
        intent("how far is it", UserIntent.HowFar)
        intent("are we there yet", UserIntent.HowFar)
        intent("how many metres left", UserIntent.HowFar)
        intent("pause", UserIntent.Pause)
        intent("wait", UserIntent.Pause)
        intent("hold on", UserIntent.Pause)
        intent("one moment please", UserIntent.Pause)
        intent("resume", UserIntent.Resume)
        intent("continue", UserIntent.Resume)
        intent("carry on", UserIntent.Resume)
        intent("let's go", UserIntent.Resume)
        intent("go on", UserIntent.Resume)
        intent("volume up", UserIntent.VolumeUp)
        intent("louder please", UserIntent.VolumeUp)
        intent("turn up the volume", UserIntent.VolumeUp)
        intent("volume down", UserIntent.VolumeDown)
        intent("too loud", UserIntent.VolumeDown)
        intent("speak softer", UserIntent.VolumeDown)
        intent("help", UserIntent.Help)
        intent("what can I say", UserIntent.Help)
        intent("", UserIntent.Unknown)
        intent("the weather is quite nice today in the city", UserIntent.Unknown)
    }
}

class DestinationMatcherTest {
    private val graph = TestMaps.demoGraph()
    private val m = DestinationMatcher(graph.destinations)

    private fun confident(q: String, nodeId: String) {
        val r = m.match(q)
        assertTrue("'$q' -> $r", r is MatchResult.Confident)
        assertEquals("'$q'", nodeId, (r as MatchResult.Confident).candidate.node.id)
    }

    @Test fun confidentMatches() {
        confident("lab 204", "R204")
        confident("204", "R204")
        confident("lab two oh four", "R204")
        confident("two zero four", "R204")
        confident("lab 240", "R240")
        confident("robotics lab", "R204")
        confident("robotics", "R204")
        confident("library", "R101")
        confident("librery", "R101") // STT typo
        confident("washroom", "W1")
        confident("toilet", "W1")
        confident("staff room", "R110")
        confident("hod office", "R250")
        confident("room 110", "R110")
    }

    @Test fun digitsNeverSilentlySwap() {
        // "lab 204" must not resolve to 240 and vice versa.
        assertEquals("R204", (m.match("lab 204") as MatchResult.Confident).candidate.node.id)
        assertEquals("R240", (m.match("lab 240") as MatchResult.Confident).candidate.node.id)
    }

    @Test fun ambiguousNeedsConfirmation() {
        val r = m.match("lab")
        assertTrue(r is MatchResult.Ambiguous)
        val ids = (r as MatchResult.Ambiguous).candidates.map { it.node.id }
        assertTrue(ids.containsAll(listOf("R204", "R240")))
    }

    @Test fun noMatch() {
        assertEquals(MatchResult.NoMatch, m.match("cafeteria on the moon"))
        assertEquals(MatchResult.NoMatch, m.match(""))
    }
}

class ConfirmationParserTest {
    private val names = listOf("Lab 204", "Lab 240")

    @Test fun answers() {
        assertEquals(Confirmation.Yes, ConfirmationParser.parse("yes", names))
        assertEquals(Confirmation.Yes, ConfirmationParser.parse("haan", names))
        assertEquals(Confirmation.Yes, ConfirmationParser.parse("that's right", names))
        assertEquals(Confirmation.No, ConfirmationParser.parse("no", names))
        assertEquals(Confirmation.No, ConfirmationParser.parse("nahi", names))
        assertEquals(Confirmation.Choice(0), ConfirmationParser.parse("the first one", names))
        assertEquals(Confirmation.Choice(1), ConfirmationParser.parse("second", names))
        assertEquals(Confirmation.Choice(1), ConfirmationParser.parse("two", names))
        assertEquals(Confirmation.Choice(1), ConfirmationParser.parse("option 2", names))
        assertEquals(Confirmation.Choice(1), ConfirmationParser.parse("lab two forty", names))
        assertEquals(Confirmation.Choice(0), ConfirmationParser.parse("yes lab two oh four", names))
        assertEquals(Confirmation.Choice(1), ConfirmationParser.parse("the last one", names))
        assertEquals(Confirmation.Unclear, ConfirmationParser.parse("banana", names))
    }
}
