package com.guidehub.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FakeSpeaker : Speaker {
    val log = mutableListOf<String>()
    override fun speak(item: SpeechItem) { log += "speak:${item.text}" }
    override fun stop() { log += "stop" }
}

class PriorityArbiterTest {
    private val sp = FakeSpeaker()
    private val a = PriorityArbiter(sp)

    @Test fun criticalInterruptsAndNavigationIsReplayed() {
        a.submit("Turn left in 5 metres", Priority.NAVIGATION)
        a.submit("Person ahead, one metre", Priority.CRITICAL)
        assertEquals(listOf("speak:Turn left in 5 metres", "stop", "speak:Person ahead, one metre"), sp.log)
        a.onDone(a.current!!.id)
        assertEquals("speak:Turn left in 5 metres", sp.log.last())
    }

    @Test fun interruptedInfoIsDropped() {
        a.submit("Volume 5", Priority.INFO)
        a.submit("Chair ahead", Priority.WARNING)
        a.onDone(a.current!!.id)
        assertNull(a.current)
        assertEquals(3, sp.log.size)
    }

    @Test fun lowerPriorityQueuesInOrder() {
        a.submit("Chair ahead", Priority.WARNING)
        a.submit("info", Priority.INFO)
        a.submit("nav", Priority.NAVIGATION)
        a.onDone(a.current!!.id)
        assertEquals("speak:nav", sp.log.last())
        a.onDone(a.current!!.id)
        assertEquals("speak:info", sp.log.last())
    }

    @Test fun keyedItemsReplaceStaleQueuedOnes() {
        a.submit("Chair ahead", Priority.WARNING)
        a.submit("Turn left in 6 metres", Priority.NAVIGATION, key = "nav")
        a.submit("Turn left now", Priority.NAVIGATION, key = "nav")
        assertEquals(listOf("Turn left now"), a.queued().map { it.text })
    }

    @Test fun pausedForListeningOnlyAllowsCritical() {
        a.submit("Walk straight", Priority.NAVIGATION)
        a.pause()
        a.submit("Chair ahead", Priority.WARNING)
        assertNull(a.current)
        a.submit("Person ahead", Priority.CRITICAL)
        assertEquals("Person ahead", a.current!!.text)
        a.onDone(a.current!!.id)
        assertNull(a.current)
        a.resume()
        assertEquals("Chair ahead", a.current!!.text) // higher priority first
        a.onDone(a.current!!.id)
        assertEquals("Walk straight", a.current!!.text)
    }

    @Test fun staleDoneIgnored() {
        val first = a.submit("one", Priority.INFO)
        a.submit("two", Priority.NAVIGATION)
        a.onDone(first.id) // already interrupted
        assertEquals("two", a.current!!.text)
    }
}

class ToneSynthTest {
    @Test fun panAndRate() {
        val left = ToneSynth.tone(1000.0, 50, pan = -1.0)
        val l = (0 until left.size / 2).sumOf { abs(left[2 * it].toInt()) }
        val r = (0 until left.size / 2).sumOf { abs(left[2 * it + 1].toInt()) }
        assertTrue(l > 100 * maxOf(r, 1))
        assertTrue(ToneSynth.beepIntervalMs(0.5) < ToneSynth.beepIntervalMs(3.0))
        assertEquals(ToneSynth.SAMPLE_RATE * 50 / 1000 * 2, left.size)
        assertTrue(ToneSynth.pitchFor(-30.0) < ToneSynth.pitchFor(0.0))
        assertTrue(ToneSynth.pitchFor(30.0) > ToneSynth.pitchFor(0.0))
        assertEquals(1.0, ToneSynth.panFor(90.0), 0.0)
    }
}
