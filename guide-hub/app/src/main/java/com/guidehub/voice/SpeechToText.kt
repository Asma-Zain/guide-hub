package com.guidehub.voice

import com.guidehub.core.Utterance

/** Why a recognition session failed, so the UI and voice prompts can offer the right recovery. */
enum class SttErrorKind {
    /** RECORD_AUDIO not granted. Recovery: grant the permission. */
    PERMISSION,
    /** No recognition service / language pack / model on this phone. Recovery: type instead. */
    UNAVAILABLE,
    /** The recogniser needed the network (or a missing download) and could not get it. */
    NETWORK,
    /** Another app or a previous session still holds the recogniser. Recovery: retry. */
    BUSY,
    /** The microphone could not be opened or delivered no audio. */
    AUDIO,
    OTHER,
}

sealed class SttResult {
    data class Heard(val utterance: Utterance) : SttResult()
    data object NoSpeech : SttResult()
    data class Error(val message: String, val kind: SttErrorKind = SttErrorKind.OTHER) : SttResult()
}

/** Live progress from a recognition session, for the listening UI. */
sealed class SttProgress {
    /** The recogniser has the microphone and is waiting for speech. */
    data object Ready : SttProgress()
    data object SpeechStarted : SttProgress()
    /** Best partial transcript so far. */
    data class Partial(val text: String) : SttProgress()
    /** Speech ended; the final result is being computed. */
    data object Processing : SttProgress()
}

/** STT backend boundary (FR-2). Implementations: AndroidSpeechToText, VoskSpeechToText, sim fakes. */
interface SpeechToText {
    val name: String
    fun isAvailable(): Boolean

    /**
     * Listen for one utterance. Cancelling the coroutine aborts recognition. [onProgress] may be
     * called from any thread.
     */
    suspend fun listen(timeoutMs: Long = 8_000, onProgress: (SttProgress) -> Unit = {}): SttResult

    /** Optional vocabulary hint (room names + commands) for grammar-constrained backends. */
    fun setVocabulary(phrases: List<String>) {}
}
