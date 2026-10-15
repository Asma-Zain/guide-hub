package com.guidehub.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.guidehub.core.Earcon
import com.guidehub.core.Hazard
import com.guidehub.core.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Low-latency stereo PCM output on the accessibility stream (FR-7). A single streaming [AudioTrack]
 * fed from a queue keeps hazard-cue latency to roughly one small buffer.
 */
class EarconPlayer {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val track: AudioTrack
    @Volatile private var running = true
    @Volatile var volume: Float = 0.5f

    init {
        val minBuf = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .build()
        track.play()
        Thread({
            while (running) {
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                track.write(chunk, 0, chunk.size)
            }
        }, "earcon-player").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    fun play(pcm: ShortArray) { queue.offer(pcm) }

    fun earcon(e: Earcon) {
        val v = volume.toDouble()
        val pcm = when (e) {
            Earcon.LISTEN_START -> ToneSynth.tone(600.0, 120, volume = v, endFreqHz = 1000.0)
            Earcon.LISTEN_END -> ToneSynth.tone(1000.0, 120, volume = v, endFreqHz = 600.0)
            Earcon.CONFIRM -> ToneSynth.tone(880.0, 80, volume = v) + ToneSynth.silence(40) + ToneSynth.tone(1175.0, 100, volume = v)
            Earcon.ERROR -> ToneSynth.tone(300.0, 250, volume = v)
            Earcon.ARRIVED -> ToneSynth.tone(660.0, 120, volume = v) + ToneSynth.tone(880.0, 120, volume = v) + ToneSynth.tone(1320.0, 200, volume = v)
        }
        play(pcm)
    }

    fun clear() {
        queue.clear()
        track.pause(); track.flush(); track.play()
    }

    fun release() {
        running = false
        track.release()
    }
}

/**
 * Repeating hazard beep: rate increases as range drops; stereo-panned by bearing on earbuds, or
 * pitch-coded (low = left, mid = centre, high = right) on the mono phone speaker.
 */
class HazardBeeper(private val player: EarconPlayer, private val scope: CoroutineScope) {
    @Volatile var hazard: Hazard? = null
    @Volatile var stereo: Boolean = true
    @Volatile var infoM: Double = 3.5
    /** While the mic is open only CRITICAL beeps play. */
    @Volatile var criticalOnly = false
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val h = hazard
                if (h == null || (criticalOnly && h.severity != Severity.CRITICAL)) { delay(40); continue }
                val v = player.volume.toDouble()
                val pcm = if (stereo) ToneSynth.tone(ToneSynth.severityFreq(h.severity), 60, ToneSynth.panFor(h.bearingDeg), v)
                else ToneSynth.tone(ToneSynth.pitchFor(h.bearingDeg), 60, 0.0, v)
                player.play(pcm)
                // Sleep in small slices so a closer hazard speeds the beeping up immediately.
                var waited = 0
                while (waited < ToneSynth.beepIntervalMs(h.rangeM, infoM) && isActive) {
                    delay(20); waited += 20
                    val now = hazard
                    if (now == null || now.rangeM < h.rangeM - 0.3 || now.severity.level > h.severity.level) break
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
