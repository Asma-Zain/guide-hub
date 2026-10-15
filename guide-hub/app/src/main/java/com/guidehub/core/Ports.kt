package com.guidehub.core

import com.guidehub.audio.Priority
import com.guidehub.protocol.CompassMode

/** Hardware/OS boundaries used by the [Orchestrator]; each has an Android implementation and a fake. */

enum class Earcon { LISTEN_START, LISTEN_END, CONFIRM, ERROR, ARRIVED }

interface AudioOutput {
    /** Stereo earbuds connected (hazard direction by panning) vs mono phone speaker fallback. */
    val stereo: Boolean
    fun speak(text: String, priority: Priority, key: String? = null)
    fun earcon(e: Earcon)
    /** Continuous spatial beeping for the current hazard set (empty = silence). */
    fun hazardCue(hazards: List<Hazard>)
    /** Mic is about to open: stop/duck TTS and earcons (CRITICAL hazards may still play). */
    fun beginListening()
    fun endListening()
    fun stopSpeech()
    /** Returns the new volume 0..1. */
    fun volumeStep(delta: Int): Float
    /** Phone haptic, used when earbuds disconnect. */
    fun vibrate()
    /** Suspends until queued speech has finished (used before auto-reopening the mic). */
    suspend fun awaitSpeechDone()
}

interface MicRouting {
    /** Routes input to the earbud mic (HFP/LE Audio) if connected, else phone mic. Returns the route name. */
    suspend fun openMic(): String
    suspend fun closeMic()
}

interface CompassControl {
    fun setTarget(bearingDeg: Double, mode: CompassMode)
}
