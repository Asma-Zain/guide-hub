package com.guidehub.sim

import com.guidehub.FakeAudio
import com.guidehub.FakeMic
import com.guidehub.TestMaps
import com.guidehub.audio.Priority
import com.guidehub.core.AppSettings
import com.guidehub.core.ButtonEvent
import com.guidehub.core.ButtonSource
import com.guidehub.core.Clock
import com.guidehub.core.CompassControl
import com.guidehub.core.Constants
import com.guidehub.core.DegradedFlag
import com.guidehub.core.EventBus
import com.guidehub.core.HazardsUpdate
import com.guidehub.core.InMemorySettingsRepository
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.core.Orchestrator
import com.guidehub.core.PositionUpdate
import com.guidehub.core.SessionState
import com.guidehub.core.Severity
import com.guidehub.core.SpokenEvent
import com.guidehub.link.CompassLink
import com.guidehub.link.HeadbandLink
import com.guidehub.link.LinkSupervisor
import com.guidehub.nav.Geo
import com.guidehub.nav.MapParser
import com.guidehub.protocol.ButtonPacket
import com.guidehub.protocol.CompassMode
import com.guidehub.vision.CameraIntrinsics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD 11 integration: scripted corridor scenario end-to-end with mocks:
 * utterance -> confirmation -> route -> markers -> hazard interrupt -> compass drop -> arrival.
 * Everything below the orchestrator is real code (codecs, links, supervisors, estimators).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorridorSimulationTest {

    private class Rig(val scope: TestScope, locked: Boolean = false) {
        val clock = Clock { scope.testScheduler.currentTime }
        val bus = EventBus()
        val settings = InMemorySettingsRepository(AppSettings(disclaimerAccepted = true))
        val audio = FakeAudio()
        val mic = FakeMic()
        val stt = ScriptedSpeechToText(clock)
        val lock = MutableStateFlow(locked)
        val bg = scope.backgroundScope
        val compassT = MockCompassTransport(bg, clock)
        val headbandT = MockHeadbandTransport(bg, clock)
        val compass = CompassLink(compassT, bg, clock, bus)
        val headband = HeadbandLink(headbandT, bg, clock, bus)
        val orch = Orchestrator(
            bg, bus, clock, settings, audio, mic, { stt },
            object : CompassControl { override fun setTarget(bearingDeg: Double, mode: CompassMode) = compass.setTarget(bearingDeg, mode) },
            CameraIntrinsics.DEFAULT_640x480, lock,
        )
        val world = SimWorld(bg, clock, bus, { orch.graph }, headbandT, {
            if (orch.session.current == SessionState.NAVIGATING) orch.route.value else null
        })
        val player = ScenarioPlayer(bg, clock, bus, world, stt, compassT, headbandT)
        val hazardUpdates = mutableListOf<HazardsUpdate>()
        val positions = mutableListOf<PositionUpdate>()
        val spoken = mutableListOf<SpokenEvent>()

        init {
            compass.start(); headband.start()
            orch.start()
            orch.loadMap(MapParser.parse(TestMaps.demoText()))
            LinkSupervisor(LinkId.COMPASS, compassT, bg, clock, bus).setWanted(true)
            LinkSupervisor(LinkId.HEADBAND_BLE, headbandT, bg, clock, bus).setWanted(true)
            bg.launch {
                bus.events.collect {
                    when (it) {
                        is HazardsUpdate -> hazardUpdates += it
                        is PositionUpdate -> positions += it
                        is SpokenEvent -> spoken += it
                        else -> Unit
                    }
                }
            }
            world.placeAt("ENT", 90.0)
            world.start()
            scope.runCurrent()
        }
    }

    @Test fun fullCorridorRun() = runTest {
        val r = Rig(this)
        r.player.play(ScenarioParser.parse(TestMaps.asset("scenarios/corridor_demo_scenario.json")))
        advanceTimeBy(60_000)

        val said = r.audio.texts()
        // Ambiguous request -> spoken confirmation -> readback.
        assertTrue(said.toString(), r.audio.any("Did you mean Lab 204 or Lab 240?"))
        assertTrue(said.toString(), said.any { it.startsWith("Navigating to Lab 204, about 2") && it.endsWith("metres.") })
        assertTrue(said.toString(), r.audio.any("Walk straight ahead"))
        // Turn guidance with landmark, then arrival.
        assertTrue(said.toString(), r.audio.any("Turn left in"))
        assertTrue(said.toString(), r.audio.any("Turn left now"))
        assertTrue(said.toString(), r.audio.any("Water cooler on your left"))
        assertTrue(said.toString(), r.audio.any("You have arrived at Lab 204"))
        // Person in the corridor: labelled CRITICAL warning that interrupts at top priority.
        assertTrue(said.toString(), r.audio.said.any { it.priority == Priority.CRITICAL && it.text.startsWith("Person ahead") })
        assertTrue(r.hazardUpdates.any { u -> u.hazards.any { it.label == "person" && it.severity == Severity.CRITICAL && it.rangeFromTof } })
        // Compass drop announced once, recovery announced once.
        assertEquals(said.toString(), 1, r.audio.count("Compass disconnected"))
        assertEquals(said.toString(), 1, r.audio.count("Compass reconnected"))
        // Every compass command was ACKed (or logged as not connected during the drop).
        val log = r.compass.log.snapshot()
        assertTrue(log.size > 20)
        assertTrue(log.all { it.ack != null || it.outcome == "NOT_CONNECTED" })
        assertTrue(r.compassT.received.snapshot().any { it.mode == CompassMode.HAZARD })
        assertTrue(r.compassT.received.snapshot().any { it.mode == CompassMode.ARRIVED })
        // The simulated user physically reached the lab door (arrival radius 1.5 m) and the estimate agrees.
        assertTrue("world at ${r.world.x},${r.world.y}", Geo.dist(r.world.x, r.world.y, 20.0, 3.0) <= Constants.ARRIVAL_RADIUS_M + 0.3)
        val last = r.positions.last()
        assertTrue("estimate ${last.x},${last.y} vs truth ${r.world.x},${r.world.y}", Geo.dist(last.x, last.y, r.world.x, r.world.y) < 1.0)
        val corrections = r.orch.position!!.corrections
        assertTrue(corrections.isNotEmpty())
        assertTrue("marker corrections $corrections", corrections.drop(3).all { it.second < 1.0 })
        // Session returned to idle after arrival.
        assertEquals(SessionState.IDLE, r.orch.session.current)
        // Mic was opened only for push-to-talk windows and always closed again.
        assertEquals(r.mic.opened, r.mic.closed)
        assertEquals(2, r.stt.listenCount)
    }

    @Test fun headingErrorStaysSmallWhileWalking() = runTest {
        val r = Rig(this)
        r.player.play(ScenarioParser.parse(TestMaps.asset("scenarios/corridor_demo_scenario.json")))
        val errors = mutableListOf<Double>()
        repeat(250) {
            advanceTimeBy(100)
            val p = r.orch.positionFlow.value
            if (p != null && r.orch.session.current == SessionState.NAVIGATING) errors += Geo.angleDiff(p.yawDeg, r.world.yaw)
        }
        assertTrue(errors.isNotEmpty())
        val sorted = errors.sorted()
        val p90 = sorted[(sorted.size * 0.9).toInt()]
        assertTrue("p90 heading error $p90", p90 < 15.0)
    }

    @Test fun lockedAppRejectsVoice() = runTest {
        val r = Rig(this, locked = true)
        r.stt.enqueue("take me to the library")
        r.bus.emit(ButtonEvent(ButtonSource.EARBUD, 0, ButtonPacket.PRESS, 0))
        advanceTimeBy(2_000)
        assertEquals(0, r.stt.listenCount)
        assertTrue(r.audio.any("locked"))
        assertEquals(SessionState.LOCKED, r.orch.session.current)
    }

    @Test fun lowConfidenceRepromptsThenDigitByDigit() = runTest {
        val r = Rig(this)
        r.stt.enqueue("mumble", 0.2f)
        r.stt.enqueue("mumble", 0.2f)
        r.stt.enqueue("lab two zero four", 0.9f)
        r.orch.pushToTalk()
        advanceTimeBy(5_000)
        assertTrue(r.audio.any("Please repeat"))
        assertTrue(r.audio.any("digit by digit"))
        assertTrue(r.audio.texts().toString(), r.audio.any("Navigating to Lab 204"))
    }

    @Test fun headbandLossDegradesAndRecovers() = runTest {
        val r = Rig(this)
        advanceTimeBy(2_000)
        r.headbandT.simulateDrop(4_000)
        advanceTimeBy(1_000)
        assertTrue(r.audio.any("Headband disconnected"))
        assertTrue(DegradedFlag.HEADBAND_LOST in r.orch.session.degraded.value)
        assertTrue(r.orch.hazards.value.isEmpty())
        advanceTimeBy(10_000)
        assertEquals(1, r.audio.count("Headband disconnected"))
        assertTrue(r.audio.any("Headband reconnected"))
        assertTrue(DegradedFlag.HEADBAND_LOST !in r.orch.session.degraded.value)
    }

    @Test fun unresponsiveCompassIsAnnouncedOnce() = runTest {
        val r = Rig(this)
        r.orch.submitText("take me to the library")
        advanceTimeBy(2_000)
        r.compassT.responsive = false
        advanceTimeBy(5_000)
        assertEquals(r.audio.texts().toString(), 1, r.audio.count("Compass not responding"))
        r.compassT.responsive = true
        advanceTimeBy(3_000)
        assertTrue(r.audio.any("Compass reconnected"))
    }

    @Test fun earbudsDisconnectVibratesAndAnnounces() = runTest {
        val r = Rig(this)
        r.bus.emit(LinkStatus(LinkId.EARBUDS, LinkState.CONNECTED, "", 0))
        r.bus.emit(LinkStatus(LinkId.EARBUDS, LinkState.DISCONNECTED, "", 1))
        runCurrent()
        assertEquals(1, r.audio.vibrations)
        assertTrue(r.audio.any("Earbuds disconnected"))
    }

    @Test fun cancelPauseResumeWhereAmIHowFar() = runTest {
        val r = Rig(this)
        r.orch.submitText("take me to the washroom")
        advanceTimeBy(3_000)
        assertEquals(SessionState.NAVIGATING, r.orch.session.current)
        r.orch.submitText("how far"); runCurrent()
        assertTrue(r.audio.any("to Washroom"))
        r.orch.submitText("where am i"); runCurrent()
        assertTrue(r.audio.texts().last(), r.audio.texts().last().contains("AB1 floor 1"))
        r.orch.submitText("pause"); runCurrent()
        assertEquals(SessionState.PAUSED, r.orch.session.current)
        r.orch.submitText("resume"); runCurrent()
        assertEquals(SessionState.NAVIGATING, r.orch.session.current)
        r.orch.submitText("cancel"); runCurrent()
        assertEquals(SessionState.IDLE, r.orch.session.current)
        assertTrue(r.audio.any("Navigation cancelled"))
    }

    @Test fun rerouteWhenUserGoesTheWrongWay() = runTest {
        val r = Rig(this)
        r.orch.submitText("take me to the library")
        runCurrent()
        // User ends up beside the other lab instead: a marker-free teleport via a fix at N3.
        r.world.placeAt(17.0, 0.0, 90.0)
        advanceTimeBy(3_000)
        assertTrue(r.audio.texts().toString(), r.audio.any("Re-routing") || r.orch.route.value?.nodes?.first() in setOf("N2", "N3"))
    }
}
