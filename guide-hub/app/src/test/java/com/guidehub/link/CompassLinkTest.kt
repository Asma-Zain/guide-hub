package com.guidehub.link

import com.guidehub.core.Clock
import com.guidehub.core.EventBus
import com.guidehub.core.LinkId
import com.guidehub.core.LinkState
import com.guidehub.core.LinkStatus
import com.guidehub.protocol.CompassMode
import com.guidehub.protocol.CompassOpcode
import com.guidehub.sim.MockCompassTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompassLinkTest {
    private class Rig(scope: TestScope) {
        val clock = Clock { scope.testScheduler.currentTime }
        val bus = EventBus()
        val mock = MockCompassTransport(scope.backgroundScope, clock, latencyMs = 20)
        val link = CompassLink(mock, scope.backgroundScope, clock, bus)
    }

    private suspend fun connect(r: Rig) { r.mock.connect(); r.link.start() }

    @Test fun commandIsAckedAndLogged() = runTest {
        val r = Rig(this); connect(r); runCurrent()
        val e = r.link.send(CompassOpcode.SET_BEARING, -45, CompassMode.NAVIGATE)
        assertEquals("OK", e.outcome)
        assertEquals(1, e.attempts)
        assertEquals(20L, e.latencyMs)
        assertEquals(-45, r.mock.needleDeg)
        assertEquals(-45, r.link.needleDeg.value)
        assertEquals(1, r.link.log.size)
    }

    @Test fun retriesOnceThenDegradesAndRecovers() = runTest {
        val r = Rig(this); connect(r); runCurrent()
        val statuses = mutableListOf<LinkStatus>()
        backgroundScope.launch { r.bus.events.collect { if (it is LinkStatus) statuses += it } }
        runCurrent()
        r.mock.responsive = false
        val start = testScheduler.currentTime
        val e = r.link.send(CompassOpcode.SET_BEARING, 30, CompassMode.NAVIGATE)
        assertEquals("NO_ACK", e.outcome)
        assertEquals(2, e.attempts)
        assertEquals(600L, testScheduler.currentTime - start) // 300 ms + retry 300 ms
        assertEquals(2, r.mock.received.size)
        assertTrue(r.link.degraded.value)
        runCurrent()
        assertEquals(LinkState.DEGRADED, statuses.last { it.link == LinkId.COMPASS }.state)

        r.mock.responsive = true
        r.link.send(CompassOpcode.PING, 0, CompassMode.IDLE)
        runCurrent()
        assertFalse(r.link.degraded.value)
        assertEquals(LinkState.CONNECTED, statuses.last().state)
    }

    @Test fun throttleLimitsRateAndChatter() = runTest {
        val r = Rig(this); connect(r); runCurrent()
        // 100 updates at 100 Hz sweeping 0..99 degrees over one second.
        repeat(100) { i -> r.link.setTarget(i.toDouble(), CompassMode.NAVIGATE); advanceTimeBy(10) }
        advanceTimeBy(500)
        val bearings = r.mock.received.snapshot().filter { it.opcode == CompassOpcode.SET_BEARING }
        assertTrue("sent ${bearings.size}", bearings.size in 4..7) // <= 5 Hz
        assertEquals(99, bearings.last().bearingDeg) // latest target wins
        // Tiny changes are suppressed.
        fun bearingCount() = r.mock.received.snapshot().count { it.opcode == CompassOpcode.SET_BEARING }
        val before = bearingCount()
        r.link.setTarget(100.0, CompassMode.NAVIGATE); advanceTimeBy(300)
        r.link.setTarget(101.0, CompassMode.NAVIGATE); advanceTimeBy(300)
        assertEquals(before, bearingCount())
        assertEquals(99, r.mock.needleDeg)
        // Mode change always goes through.
        r.link.setTarget(99.0, CompassMode.ARRIVED); advanceTimeBy(300)
        assertEquals(CompassMode.ARRIVED, r.mock.mode)
    }

    @Test fun keepAlivePingsBelowFailsafe() = runTest {
        val r = Rig(this); connect(r); runCurrent()
        advanceTimeBy(5_000)
        val pings = r.mock.received.snapshot().count { it.opcode == CompassOpcode.PING }
        assertTrue("pings=$pings", pings >= 4)
    }

    @Test fun notConnectedIsLoggedNotDegraded() = runTest {
        val r = Rig(this); r.link.start(); runCurrent()
        val e = r.link.send(CompassOpcode.SET_BEARING, 10, CompassMode.NAVIGATE)
        assertEquals("NOT_CONNECTED", e.outcome)
        assertFalse(r.link.degraded.value)
    }

    @Test fun backoffDoublesAndCaps() {
        val b = Backoff(1_000, 8_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), List(5) { b.next() })
        b.reset()
        assertEquals(1_000L, b.next())
    }
}
