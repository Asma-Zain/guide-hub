package com.guidehub.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android [SpeechRecognizer], en-IN, offline preferred (on-device recogniser on API 31+ when
 * available). Must run on the main thread. See docs/STT.md for tradeoffs vs Vosk.
 */
class AndroidSpeechToText(private val context: Context, private val clock: Clock) : SpeechToText {
    override val name = "android"

    override fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun create(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    override suspend fun listen(timeoutMs: Long): SttResult = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext SttResult.Error("No speech recogniser on this phone")
        withTimeoutOrNull(timeoutMs + 2_000) {
            suspendCancellableCoroutine { cont ->
                val rec = create()
                fun finish(r: SttResult) {
                    if (cont.isActive) cont.resume(r)
                    rec.destroy()
                }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        finish(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttResult.NoSpeech
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttResult.Error("Microphone permission missing")
                                else -> SttResult.Error("Recogniser error $error")
                            }
                        )
                    }
                    override fun onResults(results: Bundle) {
                        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val text = texts.firstOrNull()
                        if (text.isNullOrBlank()) { finish(SttResult.NoSpeech); return }
                        // Many engines report 0 or -1 when they have no score; treat that as "unknown".
                        val conf = scores?.firstOrNull()?.takeIf { it > 0f } ?: Constants.STT_DEFAULT_CONFIDENCE
                        finish(SttResult.Heard(Utterance(text, conf, clock.now())))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                    if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                }
                cont.invokeOnCancellation { runCatching { rec.cancel(); rec.destroy() } }
                rec.startListening(intent)
            }
        } ?: SttResult.NoSpeech
    }
}
