package com.guidehub.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android [TextToSpeech] (en-IN preferred) on the accessibility stream, behind the [Speaker] boundary.
 * Completion is reported to [onDone] so the [PriorityArbiter] can play the next item.
 */
class AndroidTts(context: Context, private val onDone: (Long) -> Unit) : Speaker {
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: SpeechItem? = null
    var rate: Float = 1.0f
        set(v) { field = v; if (ready) tts.setSpeechRate(v) }
    var volume: Float = 1.0f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val lang = listOf(Locale.forLanguageTag("en-IN"), Locale.UK, Locale.US).firstOrNull {
                tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
            } ?: Locale.US
            tts.language = lang
            tts.setSpeechRate(rate)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ready = true
            synchronized(this) { pending?.let { pending = null; speak(it) } }
        } else {
            failed = true
            synchronized(this) { pending?.let { pending = null; onDone(it.id) } }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { utteranceId.toLongOrNull()?.let(onDone) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { utteranceId.toLongOrNull()?.let(onDone) }
            override fun onError(utteranceId: String, errorCode: Int) { utteranceId.toLongOrNull()?.let(onDone) }
        })
    }

    override fun speak(item: SpeechItem) {
        if (failed) { onDone(item.id); return }
        if (!ready) { synchronized(this) { pending = item }; return }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val r = tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, item.id.toString())
        if (r != TextToSpeech.SUCCESS) onDone(item.id)
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
