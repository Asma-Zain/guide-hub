package com.guidehub.core

import com.guidehub.FakeAudio
import com.guidehub.TestMaps
import com.guidehub.link.CompassLink
import com.guidehub.link.HeadbandLink
import com.guidehub.link.LinkSupervisor
import com.guidehub.nav.MapParser
import com.guidehub.protocol.CompassMode
import com.guidehub.sim.MockCompassTransport
import com.guidehub.sim.MockHeadbandTransport
import com.guidehub.sim.ScenarioParser
import com.guidehub.sim.ScenarioPlayer
import com.guidehub.sim.ScriptedSpeechToText
import com.guidehub.sim.SimWorld
import com.guidehub.vision.CameraIntrinsics
import com.guidehub.voice.SpeechToText
import com.guidehub.voice.SttErrorKind
import com.guidehub.voice.SttProgress
import com.guidehub.voice.SttResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records, at the moment the mic opens, whether TTS was paused and which earcons had played. */
private class RecordingMic(private val audio: FakeAudio) : MicRouting {
    var opened = 0
    var closed = 0
    val micOpenSnapshots = mutableListOf<Pair<Boolean, List<Earcon>>>()
    override suspend fun openMic(): String { opened++; micOpenSnapshots += audio.listening to audio.earcons.toList(); return "phone" }
    override suspend fun closeMic() { closed++ }
}

/** Programmable recogniser: returns queued results, optionally after a delay, and reports progress. */
private class FakeStt : SpeechToText {
    override val name = "fake"
    val results = ArrayDeque<SttResult>()
    var latencyMs = 500L
    var hang = false
    var sessions = 0
    override fun isAvailable() = true
    override suspend fun listen(timeoutMs: Long, onProgress: (SttProgress) -> Unit): SttResult {
        sessions++
        onProgress(SttProgress.Ready)
        if (hang) awaitCancellation()
        delay(latencyMs)
        val r = results.removeFirstOrNull() ?: SttResult.NoSpeech
        if (r is SttResult.Heard) {
            onProgress(SttProgress.Partial(r.utterance.text))
            delay(200) // real recognisers deliver the final result a little after the last partial
            onProgress(SttProgress.Processing)
        }
        return r
    }
}

/**
 * Focused tests for the Tap to Talk pipeline fixes: voice state progression, confirmation before
 * start, typed fallback, recoverable errors, debounce/cancel, speech discipline and the paced demo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceFlowTest {

    private class Rig(val scope: TestScope, confirm: Boolean = true, val useScripted: Boolean = false) {
        val clock = Clock { scope.testScheduler.currentTime }
        val bus = EventBus()
        val settings = InMemorySettingsRepository(AppSettings(disclaimerAccepted = true, confirmDestination = confirm))
        val audio = FakeAudio()
        val mic = RecordingMic(audio)
        val stt = FakeStt()
        val scripted = ScriptedSpeechToText(clock)
        val bg = scope.backgroundScope
        val compassT = MockCompassTransport(bg, clock)
        val headbandT = MockHeadbandTransport(bg, clock)
        val compass = CompassLink(compassT, bg, clock, bus)
        val headband = HeadbandLink(headbandT, bg, clock, bus)
        lateinit var player: ScenarioPlayer
        val orch = Orchestrator(
            bg, bus, clock, settings, audio, mic, { if (useScripted) scripted else stt },
            object : CompassControl { override fun setTarget(bearingDeg: Double, mode: CompassMode) = compass.setTarget(bearingDeg, mode) },
            CameraIntrinsics.DEFAULT_640x480, MutableStateFlow(false),
        )
        val world = SimWorld(bg, clock, bus, { orch.graph }, headbandT, {
            if (orch.session.current == SessionState.NAVIGATING) orch.route.value else null
        })
        val voiceStates = mutableListOf<VoiceState>()

        init {
            compass.start(); headband.start()
            orch.start()
            orch.loadMap(MapParser.parse(TestMaps.demoText()))
            LinkSupervisor(LinkId.COMPASS, compassT, bg, clock, bus).setWanted(true)
            LinkSupervisor(LinkId.HEADBAND_BLE, headbandT, bg, clock, bus).setWanted(true)
            player = ScenarioPlayer(bg, clock, bus, world, scripted, compassT, headbandT)
            bg.launch { orch.voice.collect { voiceStates += it } }
            world.placeAt("ENT", 90.0)
            world.start()
            scope.runCurrent()
        }

        fun heard(text: String, conf: Float = 0.9f) = stt.results.addLast(SttResult.Heard(Utterance(text, conf, 0)))
    }

    @Test fun tapToTalkShowsProgressThenAsksToConfirm() = runTest {
        val r = Rig(this)
        advanceTimeBy(2_000) // a marker fix gives a position for the distance preview
        r.heard("take me to lab two zero four")
        r.heard("yes") // answered while the automatic yes/no listening session is open
        r.orch.pushToTalk()
        runCurrent()
        assertTrue("UI shows listening immediately", r.orch.voice.value.micActive)
        advanceTimeBy(1_500)
        // Preparing -> Listening -> live partial transcript (Processing is momentary and conflated away).
        val kinds = r.voiceStates.map { it::class.simpleName }
        assertTrue(kinds.toString(), kinds.indexOf("Preparing") in 0 until kinds.indexOf("Listening"))
        assertTrue(r.voiceStates.any { it is VoiceState.Listening && it.partial == "take me to lab two zero four" })
        assertEquals("take me to lab two zero four", r.orch.lastHeard.value)
        // Confident match still waits for confirmation, with a route-preview distance.
        assertEquals(SessionState.CONFIRMING, r.orch.session.current)
        val p = r.orch.proposal.value!!
        assertFalse(p.ambiguous)
        assertEquals("R204", p.options.single().nodeId)
        assertNotNull(p.options.single().distanceM)
        assertTrue(r.audio.texts().toString(), r.audio.any("Lab 204, about") && r.audio.any("Say yes to start"))
        // The automatic re-listen hears "yes" and navigation starts.
        advanceTimeBy(5_000)
        assertEquals(SessionState.NAVIGATING, r.orch.session.current)
        assertNull(r.orch.proposal.value)
        assertNotNull(r.orch.navCard.value)
        assertEquals(r.mic.opened, r.mic.closed)
    }

    @Test fun cueAndSpeechStopHappenBeforeTheMicOpens() = runTest {
        val r = Rig(this)
        r.heard("where am i")
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        val (ttsPaused, earcons) = r.mic.micOpenSnapshots.first()
        assertTrue("TTS paused before mic opened", ttsPaused)
        assertEquals(listOf(Earcon.LISTEN_START), earcons)
        assertFalse(r.audio.listening)
    }

    @Test fun typedDestinationWorksWithoutTheMicrophone() = runTest {
        val r = Rig(this)
        advanceTimeBy(2_000)
        r.orch.submitText("library")
        runCurrent()
        assertEquals(SessionState.CONFIRMING, r.orch.session.current)
        assertEquals("Library", r.orch.proposal.value!!.options.single().name)
        r.orch.confirmDestination(0)
        advanceTimeBy(1_000)
        assertEquals(SessionState.NAVIGATING, r.orch.session.current)
        assertEquals("R101", r.orch.destinationFlow.value)
        assertEquals(0, r.mic.opened)
        assertEquals(0, r.stt.sessions)
        val stopsBefore = r.audio.speechStops
        r.orch.stopNavigationNow()
        runCurrent()
        assertEquals(SessionState.IDLE, r.orch.session.current)
        assertTrue(r.audio.any("Navigation stopped"))
        assertTrue("queued speech flushed on stop", r.audio.speechStops > stopsBefore)
        assertNull(r.orch.navCard.value)
    }

    @Test fun ambiguousTypedChoiceAndDecline() = runTest {
        val r = Rig(this)
        r.orch.submitText("lab")
        runCurrent()
        val p = r.orch.proposal.value!!
        assertTrue(p.ambiguous)
        assertEquals(listOf("Lab 204", "Lab 240"), p.options.map { it.name }.sorted())
        r.orch.declineDestination()
        runCurrent()
        assertEquals(SessionState.IDLE, r.orch.session.current)
        assertNull(r.orch.proposal.value)
        r.orch.submitText("lab")
        runCurrent()
        val idx240 = r.orch.proposal.value!!.options.indexOfFirst { it.nodeId == "R240" }
        r.orch.confirmDestination(idx240)
        runCurrent()
        assertEquals("R240", r.orch.destinationFlow.value)
    }

    @Test fun newDestinationWhileConfirmingReplacesTheProposal() = runTest {
        val r = Rig(this)
        r.orch.submitText("library")
        runCurrent()
        r.orch.submitText("take me to the washroom")
        runCurrent()
        assertEquals("W1", r.orch.proposal.value!!.options.single().nodeId)
    }

    @Test fun permissionErrorIsRecoverable() = runTest {
        val r = Rig(this)
        r.settings.update { it.copy(autoRelisten = false) }
        r.stt.results.addLast(SttResult.Error("Microphone permission is off.", SttErrorKind.PERMISSION))
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        val v = r.orch.voice.value
        assertTrue(v.toString(), v is VoiceState.Problem && v.kind == SttErrorKind.PERMISSION)
        assertTrue(r.audio.any("type the destination"))
        assertEquals(SessionState.IDLE, r.orch.session.current)
        assertEquals(r.mic.opened, r.mic.closed)
        // Not frozen: the next tap starts a fresh session.
        r.heard("library")
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        assertEquals(2, r.stt.sessions)
        assertEquals(SessionState.CONFIRMING, r.orch.session.current)
    }

    @Test fun noSpeechAndUnavailableGiveClearMessages() = runTest {
        val r = Rig(this)
        r.stt.results.addLast(SttResult.NoSpeech)
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        assertTrue((r.orch.voice.value as VoiceState.Problem).message.contains("didn't hear"))
        r.stt.results.addLast(SttResult.Error("This phone has no speech recognition service.", SttErrorKind.UNAVAILABLE))
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        assertEquals(SttErrorKind.UNAVAILABLE, (r.orch.voice.value as VoiceState.Problem).kind)
        assertTrue(r.audio.any("Please type the destination"))
    }

    @Test fun lowConfidenceIsReportedNotActedOn() = runTest {
        val r = Rig(this)
        r.settings.update { it.copy(autoRelisten = false) }
        r.heard("lab two oh four", 0.2f)
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        assertTrue(r.orch.voice.value is VoiceState.Problem)
        assertNull(r.orch.proposal.value)
        assertEquals(SessionState.IDLE, r.orch.session.current)
    }

    @Test fun oneTapStartsOneSessionAndSecondTapCancels() = runTest {
        val r = Rig(this)
        r.settings.update { it.copy(autoRelisten = false) }
        r.stt.hang = true
        r.orch.pushToTalk()
        r.orch.pushToTalk() // bounce within the debounce window: ignored
        advanceTimeBy(300)
        assertEquals(1, r.stt.sessions)
        assertTrue(r.orch.voice.value.micActive)
        advanceTimeBy(1_000)
        r.orch.pushToTalk() // deliberate second tap: cancel
        advanceTimeBy(1_000)
        assertEquals(VoiceState.Cancelled, r.orch.voice.value)
        assertEquals(1, r.mic.opened)
        assertEquals(1, r.mic.closed)
        assertFalse(r.audio.listening)
        assertEquals(SessionState.IDLE, r.orch.session.current)
        r.stt.hang = false
        r.heard("library")
        advanceTimeBy(1_000)
        r.orch.pushToTalk()
        advanceTimeBy(2_000)
        assertEquals(2, r.stt.sessions)
        assertEquals(SessionState.CONFIRMING, r.orch.session.current)
    }

    @Test fun cancelListeningButton() = runTest {
        val r = Rig(this)
        r.stt.hang = true
        r.orch.pushToTalk()
        advanceTimeBy(500)
        r.orch.cancelListening()
        advanceTimeBy(1_000)
        assertEquals(VoiceState.Cancelled, r.orch.voice.value)
        assertEquals(r.mic.opened, r.mic.closed)
    }

    @Test fun resetSessionClearsNavigationAndQueuedSpeech() = runTest {
        val r = Rig(this, confirm = false)
        advanceTimeBy(2_000)
        r.orch.submitText("take me to the washroom")
        advanceTimeBy(3_000)
        assertEquals(SessionState.NAVIGATING, r.orch.session.current)
        val stops = r.audio.speechStops
        r.orch.resetSession()
        runCurrent()
        assertEquals(SessionState.IDLE, r.orch.session.current)
        assertNull(r.orch.route.value)
        assertNull(r.orch.navCard.value)
        assertTrue(r.audio.speechStops > stops)
        val said = r.audio.said.size
        advanceTimeBy(10_000)
        assertTrue("no navigation speech after reset: ${r.audio.texts().drop(said)}", r.audio.texts().drop(said).none { it.contains("Turn") || it.contains("arrived") })
    }

    @Test fun pacedDemoRunsInOrderWithSingleAnnouncements() = runTest {
        val r = Rig(this, useScripted = true)
        r.player.play(ScenarioParser.parse(TestMaps.asset("scenarios/corridor_demo_scenario.json")))
        val cards = mutableListOf<String>()
        var arrivalTs = -1L
        repeat(700) {
            advanceTimeBy(100)
            r.orch.navCard.value?.let { cards += it.instruction }
            if (arrivalTs < 0 && r.orch.arrivedAt.value != null) arrivalTs = testScheduler.currentTime
        }
        val said = r.audio.texts()
        fun idx(s: String) = said.indexOfFirst { it.contains(s) }.also { assertTrue("missing '$s' in $said", it >= 0) }
        // Selection & alternatives -> navigation -> one person warning -> compass drop -> recovery -> arrival.
        assertTrue(idx("Did you mean Lab 204 or Lab 240?") < idx("Navigating to Lab 204"))
        assertTrue(idx("Navigating to Lab 204") < idx("Person ahead"))
        assertTrue(idx("Person ahead") < idx("Compass disconnected"))
        assertTrue(idx("Compass disconnected") < idx("Compass reconnected"))
        assertTrue(idx("Compass reconnected") < idx("You have arrived at Lab 204"))
        assertEquals(said.toString(), 1, r.audio.count("Compass disconnected"))
        assertEquals(said.toString(), 1, r.audio.count("Compass reconnected"))
        assertEquals("Lab 204", r.orch.arrivedAt.value)
        assertTrue(cards.contains("Turn left"))
        // Paced for a presenter, not a burst: the journey takes over half a minute.
        assertTrue("arrived at $arrivalTs", arrivalTs in 30_000..65_000)
    }

    @Test fun simulatedCorridorWallsDoNotBeepAfterArrival() = runTest {
        val r = Rig(this, useScripted = true)
        r.player.play(ScenarioParser.parse(TestMaps.asset("scenarios/corridor_demo_scenario.json")))
        advanceTimeBy(65_000)
        assertNotNull(r.orch.arrivedAt.value)
        r.player.stop()
        var hazardSamples = 0
        repeat(100) { advanceTimeBy(100); if (r.orch.hazards.value.isNotEmpty()) hazardSamples++ }
        assertEquals("simulated user standing at the door should hear no beeps", 0, hazardSamples)
    }
}
