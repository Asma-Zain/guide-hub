package com.guidehub.audio

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import com.guidehub.core.AudioOutput
import com.guidehub.core.Earcon
import com.guidehub.core.Hazard
import com.guidehub.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Android implementation of [AudioOutput]: TTS through the [PriorityArbiter], earcons, hazard beeps, haptics. */
class AudioFeedback(
    private val context: Context,
    scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val earbuds: StateFlow<Boolean>,
) : AudioOutput {
    private lateinit var arbiter: PriorityArbiter
    private val tts = AndroidTts(context) { id -> arbiter.onDone(id) }
    private val player = EarconPlayer()
    private val beeper = HazardBeeper(player, scope)

    init {
        arbiter = PriorityArbiter(tts)
        beeper.start()
        scope.launch {
            settings.settings.collect { s ->
                tts.rate = s.ttsRate
                tts.volume = s.ttsVolume
                player.volume = s.hazardVolume
                beeper.infoM = s.infoM
            }
        }
        scope.launch { earbuds.collect { beeper.stereo = it } }
    }

    override val stereo: Boolean get() = earbuds.value

    override fun speak(text: String, priority: Priority, key: String?) {
        arbiter.submit(text, priority, key)
    }

    override fun earcon(e: Earcon) = player.earcon(e)

    override fun hazardCue(hazards: List<Hazard>) {
        beeper.hazard = hazards.firstOrNull()
    }

    override fun beginListening() {
        arbiter.pause()
        beeper.criticalOnly = true
        player.clear()
    }

    override fun endListening() {
        beeper.criticalOnly = false
        arbiter.resume()
    }

    override fun stopSpeech() = arbiter.clear()

    override fun volumeStep(delta: Int): Float {
        var v = 0f
        settings.update { s ->
            v = ((s.ttsVolume * 10).roundToInt() + delta).coerceIn(1, 10) / 10f
            s.copy(ttsVolume = v, hazardVolume = (v * 0.6f).coerceIn(0.1f, 0.8f))
        }
        return v
    }

    override fun vibrate() {
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
    }

    override suspend fun awaitSpeechDone() {
        withTimeoutOrNull(15_000) {
            while (arbiter.current != null || arbiter.queued().isNotEmpty()) delay(50)
        }
    }

    fun release() {
        beeper.stop()
        player.release()
        tts.shutdown()
    }
}
