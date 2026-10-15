package com.guidehub.core

import com.guidehub.auth.AuthManager
import com.guidehub.recording.EventJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {
    @Test fun happyPathTransitions() {
        val s = NavigationSession()
        assertEquals(SessionState.LOCKED, s.current)
        assertFalse(s.transition(SessionState.NAVIGATING)) // cannot skip auth
        s.unlock()
        assertEquals(SessionState.IDLE, s.current)
        assertTrue(s.transition(SessionState.AWAITING_DESTINATION))
        assertTrue(s.transition(SessionState.CONFIRMING))
        assertTrue(s.transition(SessionState.NAVIGATING))
        assertTrue(s.transition(SessionState.PAUSED))
        assertFalse(s.transition(SessionState.ARRIVED)) // must resume first
        assertTrue(s.transition(SessionState.NAVIGATING))
        assertTrue(s.transition(SessionState.ARRIVED))
        assertTrue(s.transition(SessionState.IDLE))
    }

    @Test fun lockKeepsActiveNavigation() {
        val s = NavigationSession()
        s.unlock()
        s.transition(SessionState.NAVIGATING)
        s.lock()
        assertEquals(SessionState.NAVIGATING, s.current)
        assertTrue(s.locked.value)
        s.transition(SessionState.ARRIVED)
        s.transition(SessionState.IDLE)
        s.settleAfterNavigation()
        assertEquals(SessionState.LOCKED, s.current)
    }

    @Test fun lockFromIdleOrConfirming() {
        val s = NavigationSession()
        s.unlock()
        s.transition(SessionState.CONFIRMING)
        s.lock()
        assertEquals(SessionState.LOCKED, s.current)
    }

    @Test fun degradedFlags() {
        val s = NavigationSession()
        s.setFlag(DegradedFlag.COMPASS_LOST, true)
        s.setFlag(DegradedFlag.TOF_ERROR, true)
        s.setFlag(DegradedFlag.COMPASS_LOST, false)
        assertEquals(setOf(DegradedFlag.TOF_ERROR), s.degraded.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerTest {
    @Test fun autoLockAfterBackgroundTimeout() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val auth = AuthManager(clock, backgroundScope) { 60 }
        assertTrue(auth.locked.value)
        auth.onAuthenticated()
        assertFalse(auth.locked.value)
        auth.onBackground()
        advanceTimeBy(30_000); runCurrent()
        auth.onForeground()
        assertFalse(auth.locked.value) // back within 60 s
        auth.onBackground()
        advanceTimeBy(61_000); runCurrent()
        assertTrue(auth.locked.value) // locked while still in background
    }

    @Test fun screenOffAndExplicitLock() = runTest {
        val auth = AuthManager(Clock { testScheduler.currentTime }, backgroundScope) { 60 }
        auth.onAuthenticated(); auth.onScreenOff()
        assertTrue(auth.locked.value)
        auth.onAuthenticated(); auth.lock()
        assertTrue(auth.locked.value)
    }
}

class EventJsonTest {
    @Test fun inputsRoundTrip() {
        val events = listOf(
            TofSweep(listOf(TofPoint(-60.0, 1.39), TofPoint(0.0, 0.0)), 10, DataSource.PI),
            VoDelta(-1.5, 0.12, 0.8, 11, DataSource.PHONE),
            MarkerDetection(12, 3.2, -4.5, 7.0, 12, DataSource.PHONE),
            MarkerDetection(13, 2.0, 1.0, null, 13, DataSource.PI),
            ObjectDetections(listOf(CameraObject("person", 0.9f, 3.0, 200.0, 480, 0.1, 0.8)), 14),
            Utterance("take me to lab 204", 0.9f, 15),
            ButtonEvent(ButtonSource.EARBUD, 85, 0, 16),
            LinkStatus(LinkId.COMPASS, LinkState.DEGRADED, "x", 17),
        )
        events.forEach { assertEquals(it, EventJson.decode(EventJson.encode(it)!!)) }
    }

    @Test fun outputsAreRecordedButNotReplayed() {
        val line = EventJson.encode(SpokenEvent("hi", "INFO", 1))!!
        assertTrue(line.contains("\"spoken\""))
        assertNull(EventJson.decode(line))
        assertNull(EventJson.decode("garbage"))
    }
}
