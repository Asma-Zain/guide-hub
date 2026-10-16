package com.guidehub.core

import com.guidehub.voice.SttErrorKind

/** What voice/typed input is doing right now, for the Guide screen. */
sealed class VoiceState {
    data object Idle : VoiceState()
    /** Tap received: stopping speech, playing the cue, routing the mic. */
    data object Preparing : VoiceState()
    /** The recogniser has the mic. [partial] is the live transcript, if the backend provides one. */
    data class Listening(val partial: String? = null, val speechDetected: Boolean = false) : VoiceState()
    /** Speech ended, or typed text submitted; resolving it. */
    data class Processing(val text: String?) : VoiceState()
    /**
     * Something went wrong but the user can recover: tap to talk again, type, or (for
     * [SttErrorKind.PERMISSION]) grant the microphone. [kind] is null for "no speech", low
     * confidence, "not understood" and "no such room".
     */
    data class Problem(val message: String, val kind: SttErrorKind? = null) : VoiceState()
    data object Cancelled : VoiceState()

    val micActive: Boolean get() = this is Preparing || this is Listening
}

/** One destination the user can confirm. [distanceM] is the route length from the current position estimate, if known. */
data class DestinationOption(val nodeId: String, val name: String, val distanceM: Double?)

/** Waiting for the user to confirm a destination (one option) or pick one of several (ambiguous). */
data class DestinationProposal(val query: String, val options: List<DestinationOption>) {
    val ambiguous: Boolean get() = options.size > 1
}
