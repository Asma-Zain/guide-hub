package com.guidehub.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.guidehub.core.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * What the TTS engine is actually using, read back from the engine after selection (not what was
 * asked for). [requestedActive] is true only if the engine reports the requested voice as current.
 */
data class TtsVoiceStatus(
    val ready: Boolean = false,
    val engine: String = "",
    val voiceName: String? = null,
    val locale: String? = null,
    val requested: String = "",
    val requestedActive: Boolean = false,
    val note: String = "Starting text-to-speech…",
    /** Installed on-device English voices, for Settings. */
    val localVoices: List<String> = emptyList(),
)

/**
 * Android [TextToSpeech] (en-IN preferred) on the accessibility stream, behind the [Speaker] boundary.
 * Completion is reported to [onDone] so the [PriorityArbiter] can play the next item.
 *
 * Voice selection (see docs/DECISIONS.md, "Astra voice"): the requested voice is used only if an
 * installed engine or on-device voice provides it. Network-only voices are rejected (PRD: no cloud
 * in v1). Otherwise the best installed local English voice is used and [status] says so.
 */
class AndroidTts(
    context: Context,
    private val onDone: (Long) -> Unit,
    requestedVoice: String = Constants.PREFERRED_VOICE,
) : Speaker {
    private val appContext = context.applicationContext
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: SpeechItem? = null
    @Volatile private var requested = requestedVoice
    private var triedEngineSwitch = false
    /** Engine package this instance was created with; null = the system default engine. */
    @Volatile private var enginePkg: String? = null

    var rate: Float = 0.95f
        set(v) {
            field = v.coerceIn(Constants.TTS_RATE_MIN, Constants.TTS_RATE_MAX)
            if (ready) tts.setSpeechRate(field)
        }
    var volume: Float = 1.0f

    private val _status = MutableStateFlow(TtsVoiceStatus(requested = requestedVoice))
    val status: StateFlow<TtsVoiceStatus> = _status.asStateFlow()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) { utteranceId.toLongOrNull()?.let(onDone) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) { utteranceId.toLongOrNull()?.let(onDone) }
        override fun onError(utteranceId: String, errorCode: Int) { utteranceId.toLongOrNull()?.let(onDone) }
    }

    @Volatile private var tts: TextToSpeech = create(null)

    private fun create(enginePackage: String?): TextToSpeech {
        enginePkg = enginePackage
        lateinit var t: TextToSpeech
        val init = TextToSpeech.OnInitListener { status -> onInit(t, status) }
        t = if (enginePackage == null) TextToSpeech(appContext, init) else TextToSpeech(appContext, init, enginePackage)
        t.setOnUtteranceProgressListener(listener)
        return t
    }

    private fun onInit(t: TextToSpeech, status: Int) {
        if (t !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            if (triedEngineSwitch) {
                // The requested engine failed to start: go back to the default engine.
                triedEngineSwitch = false
                runCatching { t.shutdown() }
                tts = create(null)
                return
            }
            failed = true
            _status.value = TtsVoiceStatus(requested = requested, note = "Text-to-speech could not start on this phone.")
            synchronized(this) { pending?.let { pending = null; onDone(it.id) } }
            return
        }
        // An installed engine named after the requested voice: switch to it once.
        val want = requested.trim().lowercase()
        val engineMatch = if (want.isEmpty()) null else t.engines.firstOrNull {
            it.label.lowercase().contains(want) || it.name.lowercase().contains(want)
        }
        if (engineMatch != null && (enginePkg ?: t.defaultEngine) != engineMatch.name && !triedEngineSwitch) {
            triedEngineSwitch = true
            runCatching { t.shutdown() }
            tts = create(engineMatch.name)
            return
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setSpeechRate(rate)
        selectVoice(t)
        ready = true
        synchronized(this) { pending?.let { pending = null; speak(it) } }
    }

    /** Re-run voice selection, e.g. after the preferred voice setting changes. */
    fun setRequestedVoice(name: String) {
        if (name == requested) return
        requested = name
        if (ready) selectVoice(tts)
    }

    private fun isLocal(v: Voice) = !v.isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()

    private fun selectVoice(t: TextToSpeech) {
        val engineName = enginePkg ?: t.defaultEngine.orEmpty()
        val engineLabel = runCatching { t.engines.firstOrNull { it.name == engineName }?.label }.getOrNull() ?: engineName
        val all = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
        val english = all.filter { it.locale.language == "en" }
        val localEnglish = english.filter(::isLocal)
        val want = requested.trim().lowercase()

        val named = if (want.isEmpty()) emptyList() else all.filter { it.name.lowercase().contains(want) }
        val namedLocal = named.firstOrNull(::isLocal)
        val engineIsRequested = want.isNotEmpty() && engineLabel.lowercase().contains(want)

        val (chosen, why) = when {
            namedLocal != null -> namedLocal to null
            engineIsRequested -> bestLocal(localEnglish) to null
            named.isNotEmpty() -> bestLocal(localEnglish) to
                "A \"$requested\" voice exists but needs the network, which this app does not use (on-device only)."
            want.isEmpty() -> bestLocal(localEnglish) to null
            else -> bestLocal(localEnglish) to "No on-device voice or engine named \"$requested\" is installed on this phone."
        }
        if (chosen != null) t.voice = chosen
        else {
            val lang = listOf(Locale.forLanguageTag("en-IN"), Locale.UK, Locale.US).firstOrNull {
                t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
            } ?: Locale.US
            t.language = lang
        }
        // Verify what the engine actually uses rather than trusting the request.
        val active = runCatching { t.voice }.getOrNull()
        val requestedActive = want.isNotEmpty() && (
            engineIsRequested || (active != null && namedLocal != null && active.name == namedLocal.name)
        )
        val activeDesc = active?.let { "${it.name} (${it.locale.toLanguageTag()})" } ?: "engine default"
        val note = when {
            requestedActive -> "$requested is active: $activeDesc on $engineLabel."
            why != null -> "$why Using fallback voice $activeDesc on $engineLabel."
            else -> "Using $activeDesc on $engineLabel."
        }
        _status.value = TtsVoiceStatus(
            ready = true,
            engine = engineLabel,
            voiceName = active?.name,
            locale = active?.locale?.toLanguageTag(),
            requested = requested,
            requestedActive = requestedActive,
            note = note,
            localVoices = localEnglish.map { it.name }.sorted().take(40),
        )
    }

    /** en-IN first, then en-GB, en-US, any English; higher quality, then lower latency. */
    private fun bestLocal(voices: List<Voice>): Voice? {
        fun rank(v: Voice) = when (v.locale.country) { "IN" -> 0; "GB" -> 1; "US" -> 2; else -> 3 }
        return voices.sortedWith(compareBy<Voice>({ rank(it) }, { -it.quality }, { it.latency })).firstOrNull()
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
