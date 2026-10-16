package com.guidehub.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android [SpeechRecognizer], en-IN, offline preferred. See docs/STT.md for tradeoffs vs Vosk.
 *
 * Tries, in order, until one session actually runs:
 *  1. the on-device recogniser (API 31+), which is offline but often lacks an en-IN language pack;
 *  2. the system recogniser with EXTRA_PREFER_OFFLINE and en-IN;
 *  3. the system recogniser in the phone's default language.
 * A later backend is only tried when the earlier one failed *before* hearing speech (language
 * missing, service could not start), so the user is never asked to speak twice for one tap.
 *
 * SpeechRecognizer must only be touched on the main thread, including cancel/destroy when the
 * listening coroutine is cancelled from the orchestrator's thread.
 */
class AndroidSpeechToText(
    private val context: Context,
    private val clock: Clock,
    private val hasMicPermission: () -> Boolean,
) : SpeechToText {
    override val name = "android"
    private val main = Handler(Looper.getMainLooper())

    /** The on-device recogniser rejected en-IN once; skip it for the rest of this process. */
    @Volatile private var onDeviceRejected = false

    private val _activeBackend = MutableStateFlow("not used yet")
    /** Which recogniser produced the last session, for Diagnostics. */
    val activeBackend: StateFlow<String> = _activeBackend.asStateFlow()

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private data class Attempt(val onDevice: Boolean, val language: String?, val label: String)

    /** [retryable]: failed before any speech was captured, for a reason another backend may not share. */
    private data class Outcome(val result: SttResult, val retryable: Boolean)

    override suspend fun listen(timeoutMs: Long, onProgress: (SttProgress) -> Unit): SttResult {
        if (!hasMicPermission()) return SttResult.Error("Microphone permission is off.", SttErrorKind.PERMISSION)
        if (!isAvailable()) return SttResult.Error("This phone has no speech recognition service.", SttErrorKind.UNAVAILABLE)
        val attempts = buildList {
            if (onDeviceAvailable() && !onDeviceRejected) add(Attempt(true, LANGUAGE, "on-device ($LANGUAGE)"))
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                add(Attempt(false, LANGUAGE, "system recogniser ($LANGUAGE, offline preferred)"))
                add(Attempt(false, null, "system recogniser (phone language)"))
            }
        }
        var last: SttResult = SttResult.NoSpeech
        for (a in attempts) {
            val o = session(a, timeoutMs, onProgress)
            if (!o.retryable) return o.result
            if (a.onDevice) onDeviceRejected = true
            last = o.result
        }
        return last
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private suspend fun session(a: Attempt, timeoutMs: Long, onProgress: (SttProgress) -> Unit): Outcome = withContext(Dispatchers.Main) {
        var heardSpeech = false
        val outcome = withTimeoutOrNull(timeoutMs + MAX_UTTERANCE_MS) {
            suspendCancellableCoroutine { cont ->
                val rec = if (a.onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
                var released = false
                fun release() = onMain {
                    if (released) return@onMain
                    released = true
                    runCatching { rec.cancel() }
                    runCatching { rec.destroy() }
                }
                fun finish(o: Outcome) {
                    if (cont.isActive) cont.resume(o)
                    release()
                }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _activeBackend.value = a.label
                        onProgress(SttProgress.Ready)
                    }
                    override fun onBeginningOfSpeech() {
                        heardSpeech = true
                        onProgress(SttProgress.SpeechStarted)
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = onProgress(SttProgress.Processing)
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }?.let { onProgress(SttProgress.Partial(it)) }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) = finish(mapError(error, heardSpeech, a))
                    override fun onResults(results: Bundle) {
                        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val text = texts.firstOrNull()
                        if (text.isNullOrBlank()) { finish(Outcome(SttResult.NoSpeech, false)); return }
                        // Many engines report 0 or -1 when they have no score; treat that as "unknown".
                        val conf = scores?.firstOrNull()?.takeIf { it > 0f } ?: Constants.STT_DEFAULT_CONFIDENCE
                        finish(Outcome(SttResult.Heard(Utterance(text, conf, clock.now())), false))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    a.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                    if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                }
                // Runs on whichever thread cancels us (often not main): release() hops to main.
                cont.invokeOnCancellation { release() }
                rec.startListening(intent)
            }
        }
        outcome ?: Outcome(if (heardSpeech) SttResult.Error("Recognition took too long.", SttErrorKind.OTHER) else SttResult.NoSpeech, false)
    }

    private fun mapError(error: Int, heardSpeech: Boolean, a: Attempt): Outcome {
        val retry = !heardSpeech
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome(SttResult.NoSpeech, false)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                Outcome(SttResult.Error("Microphone permission is off.", SttErrorKind.PERMISSION), false)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
                Outcome(SttResult.Error("The ${a.language ?: "phone's"} speech pack is not installed for ${if (a.onDevice) "on-device" else "offline"} recognition.", SttErrorKind.UNAVAILABLE), retry)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                Outcome(SttResult.Error("The recogniser needs a network connection or an offline speech pack.", SttErrorKind.NETWORK), retry)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                Outcome(SttResult.Error("The speech recogniser is busy. Try again.", SttErrorKind.BUSY), false)
            SpeechRecognizer.ERROR_AUDIO -> Outcome(SttResult.Error("The microphone could not be opened.", SttErrorKind.AUDIO), retry)
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                Outcome(SttResult.Error("The speech service stopped unexpectedly (code $error).", SttErrorKind.OTHER), retry)
            else -> Outcome(SttResult.Error("Speech recogniser error $error.", SttErrorKind.OTHER), retry)
        }
    }

    companion object {
        const val LANGUAGE = "en-IN"
        /** Extra time allowed after the no-speech window for a long utterance to finish. */
        private const val MAX_UTTERANCE_MS = 7_000L
    }
}
