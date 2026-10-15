package com.guidehub.voice

import com.guidehub.core.Utterance

sealed class SttResult {
    data class Heard(val utterance: Utterance) : SttResult()
    data object NoSpeech : SttResult()
    data class Error(val message: String) : SttResult()
}

/** STT backend boundary (FR-2). Implementations: AndroidSpeechToText, VoskSpeechToText, sim fakes. */
interface SpeechToText {
    val name: String
    fun isAvailable(): Boolean

    /** Listen for one utterance. Cancelling the coroutine aborts recognition. */
    suspend fun listen(timeoutMs: Long = 8_000): SttResult

    /** Optional vocabulary hint (room names + commands) for grammar-constrained backends. */
    fun setVocabulary(phrases: List<String>) {}
}
