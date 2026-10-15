package com.guidehub.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState { LOCKED, IDLE, AWAITING_DESTINATION, CONFIRMING, NAVIGATING, PAUSED, ARRIVED }

enum class DegradedFlag {
    COMPASS_LOST, HEADBAND_LOST, CAMERA_LOST, EARBUDS_LOST,
    LOW_POSITION_CONFIDENCE, HEADING_UNCERTAIN, TOF_ERROR,
}

/**
 * FR-9 state machine: LOCKED -> IDLE -> AWAITING_DESTINATION -> CONFIRMING -> NAVIGATING <-> PAUSED
 * -> ARRIVED -> IDLE, with DEGRADED flag overlays.
 *
 * Locking while NAVIGATING/PAUSED keeps the state (navigation audio continues) and sets [locked];
 * locking from any other state moves to LOCKED. See docs/DECISIONS.md.
 */
class NavigationSession {
    private val _state = MutableStateFlow(SessionState.LOCKED)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val _degraded = MutableStateFlow<Set<DegradedFlag>>(emptySet())
    val degraded: StateFlow<Set<DegradedFlag>> = _degraded.asStateFlow()
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val current: SessionState get() = _state.value
    val navigationActive: Boolean get() = current == SessionState.NAVIGATING || current == SessionState.PAUSED

    private val allowed: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.LOCKED to setOf(SessionState.IDLE),
        SessionState.IDLE to setOf(SessionState.AWAITING_DESTINATION, SessionState.CONFIRMING, SessionState.NAVIGATING, SessionState.LOCKED),
        SessionState.AWAITING_DESTINATION to setOf(SessionState.IDLE, SessionState.CONFIRMING, SessionState.NAVIGATING, SessionState.LOCKED),
        SessionState.CONFIRMING to setOf(SessionState.IDLE, SessionState.AWAITING_DESTINATION, SessionState.NAVIGATING, SessionState.LOCKED),
        SessionState.NAVIGATING to setOf(SessionState.PAUSED, SessionState.ARRIVED, SessionState.IDLE, SessionState.CONFIRMING, SessionState.NAVIGATING),
        SessionState.PAUSED to setOf(SessionState.NAVIGATING, SessionState.IDLE, SessionState.CONFIRMING),
        SessionState.ARRIVED to setOf(SessionState.IDLE, SessionState.AWAITING_DESTINATION, SessionState.CONFIRMING, SessionState.NAVIGATING, SessionState.LOCKED),
    )

    fun canTransition(to: SessionState) = to in allowed.getValue(current)

    /** Returns false (and leaves the state unchanged) for illegal transitions. */
    fun transition(to: SessionState): Boolean {
        if (!canTransition(to)) return false
        _state.value = to
        return true
    }

    fun lock() {
        _locked.value = true
        if (!navigationActive && current != SessionState.LOCKED) _state.value = SessionState.LOCKED
    }

    fun unlock() {
        _locked.value = false
        if (current == SessionState.LOCKED) _state.value = SessionState.IDLE
    }

    /** After navigation ends while the app is locked, fall back to LOCKED. */
    fun settleAfterNavigation() {
        if (_locked.value && !navigationActive) _state.value = SessionState.LOCKED
    }

    fun setFlag(flag: DegradedFlag, on: Boolean) {
        _degraded.value = if (on) _degraded.value + flag else _degraded.value - flag
    }

    fun hasFlag(flag: DegradedFlag) = flag in _degraded.value
}
