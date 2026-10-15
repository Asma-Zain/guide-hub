package com.guidehub.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.guidehub.core.Clock
import com.guidehub.core.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline Vosk backend. Reads its own [AudioRecord] (VOICE_COMMUNICATION source so the earbud
 * route applies) with NoiseSuppressor + AGC where the phone supports them, and constrains the
 * recogniser grammar to room names + command words for accuracy in noisy corridors.
 *
 * Model: copy e.g. `vosk-model-small-en-in-0.4` to <filesDir>/vosk-model (see docs/STT.md).
 */
class VoskSpeechToText(private val context: Context, private val clock: Clock) : SpeechToText {
    override val name = "vosk"
    private var model: Model? = null
    private var grammar: String? = null
    private val modelDir get() = File(context.filesDir, "vosk-model")

    override fun isAvailable() = modelDir.isDirectory

    override fun setVocabulary(phrases: List<String>) {
        val words = (phrases.map { it.lowercase() } + COMMANDS + NUMBER_WORDS + "[unk]").distinct()
        grammar = JsonArray(words.map { JsonPrimitive(it) }).toString()
    }

    @SuppressLint("MissingPermission")
    override suspend fun listen(timeoutMs: Long): SttResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext SttResult.Error("Vosk model not installed")
        val m = model ?: Model(modelDir.absolutePath).also { model = it }
        val rate = 16_000
        val rec = grammar?.let { Recognizer(m, rate.toFloat(), it) } ?: Recognizer(m, rate.toFloat())
        rec.setWords(true)
        val bufSize = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } else null
        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.apply { enabled = true } else null
        try {
            ar.startRecording()
            val buf = ShortArray(bufSize / 2)
            val deadline = clock.now() + timeoutMs
            var resultJson: String? = null
            while (clock.now() < deadline) {
                ensureActive()
                val n = ar.read(buf, 0, buf.size)
                if (n > 0 && rec.acceptWaveForm(buf, n)) { resultJson = rec.result; break }
            }
            parse(resultJson ?: rec.finalResult)
        } finally {
            runCatching { ar.stop() }
            ar.release(); ns?.release(); agc?.release(); rec.close()
        }
    }

    private fun parse(json: String): SttResult {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return SttResult.NoSpeech
        val text = o["text"]?.jsonPrimitive?.content.orEmpty().replace("[unk]", "").trim()
        if (text.isBlank()) return SttResult.NoSpeech
        val confs = o["result"]?.jsonArray?.mapNotNull { it.jsonObject["conf"]?.jsonPrimitive?.doubleOrNull }.orEmpty()
        val conf = if (confs.isEmpty()) 0.7f else confs.average().toFloat()
        return SttResult.Heard(Utterance(text, conf, clock.now()))
    }

    companion object {
        val COMMANDS = listOf(
            "take", "me", "to", "the", "go", "navigate", "where", "is", "am", "i", "how", "far", "repeat",
            "cancel", "stop", "pause", "resume", "continue", "volume", "up", "down", "louder", "softer",
            "help", "yes", "no", "first", "second", "third", "one", "lab", "room", "number", "please", "again",
        )
        val NUMBER_WORDS = listOf(
            "zero", "oh", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "and", "double", "triple",
        )
    }
}
