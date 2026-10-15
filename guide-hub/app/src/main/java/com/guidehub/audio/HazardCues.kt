package com.guidehub.audio

import com.guidehub.core.Hazard
import com.guidehub.core.Severity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Spoken hazard phrases and TTS cooldown (FR-7). */
class HazardAnnouncer(var cooldownMs: Long = 3_000) {
    private data class Track(val lastSpokenTs: Long, val severity: Severity)

    private val spoken = mutableMapOf<String, Track>()
    private var present = setOf<String>()

    /**
     * Returns hazards that should be spoken now: labelled only, not repeated while the same hazard
     * persists unless its severity rises, and at most once per [cooldownMs] per hazard.
     */
    fun update(hazards: List<Hazard>, now: Long): List<Hazard> {
        val keys = hazards.map { it.trackKey }.toSet()
        val out = mutableListOf<Hazard>()
        for (h in hazards) {
            if (h.label == "obstacle") continue // unlabelled: beeps only
            if (h.severity.level < Severity.WARNING.level) continue
            val t = spoken[h.trackKey]
            val persisting = h.trackKey in present
            val speak = when {
                t == null -> true
                persisting -> h.severity.level > t.severity.level
                else -> now - t.lastSpokenTs >= cooldownMs
            }
            if (speak) {
                spoken[h.trackKey] = Track(now, h.severity)
                out += h
            } else if (t != null && h.severity.level < t.severity.level) {
                spoken[h.trackKey] = t.copy(severity = h.severity) // allow re-escalation later
            }
        }
        present = keys
        return out
    }

    fun reset() { spoken.clear(); present = emptySet() }

    companion object {
        fun direction(bearingDeg: Double): String = when {
            abs(bearingDeg) < 10 -> "ahead"
            abs(bearingDeg) < 35 -> if (bearingDeg > 0) "slightly right" else "slightly left"
            else -> if (bearingDeg > 0) "on your right" else "on your left"
        }

        fun distanceWords(m: Double): String {
            val r = (m * 2).roundToInt() / 2.0
            return when {
                m < 0.75 -> "very close"
                r == 1.0 -> "one metre"
                r % 1.0 == 0.0 -> "${r.toInt()} metres"
                else -> "$r metres"
            }
        }

        fun phrase(h: Hazard): String {
            val what = h.label.replaceFirstChar(Char::uppercase)
            return "$what ${direction(h.bearingDeg)}, ${distanceWords(h.rangeM)}."
        }
    }
}

/** Synthesises earcons and hazard beeps as 16-bit stereo PCM. Pure, so it is unit-testable. */
object ToneSynth {
    const val SAMPLE_RATE = 22_050

    /**
     * Stereo tone. [pan]: -1 = full left, +1 = full right (constant-power). [volume] 0..1.
     * Short attack/release envelope avoids clicks.
     */
    fun tone(freqHz: Double, durationMs: Int, pan: Double = 0.0, volume: Double = 0.6, endFreqHz: Double = freqHz): ShortArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val out = ShortArray(n * 2)
        val p = pan.coerceIn(-1.0, 1.0)
        val angle = (p + 1) * PI / 4
        val gl = cos(angle)
        val gr = sin(angle)
        val ramp = (SAMPLE_RATE * 0.005).toInt().coerceAtLeast(1)
        var phase = 0.0
        for (i in 0 until n) {
            val f = freqHz + (endFreqHz - freqHz) * i / n
            phase += 2 * PI * f / SAMPLE_RATE
            val env = minOf(1.0, i.toDouble() / ramp, (n - i).toDouble() / ramp)
            val s = sin(phase) * env * volume * Short.MAX_VALUE
            out[2 * i] = (s * gl).toInt().toShort()
            out[2 * i + 1] = (s * gr).toInt().toShort()
        }
        return out
    }

    fun silence(durationMs: Int): ShortArray = ShortArray(SAMPLE_RATE * durationMs / 1000 * 2)

    /** Interval between hazard beeps: faster as range drops (100 ms at 0 m .. 800 ms at 3.5 m). */
    fun beepIntervalMs(rangeM: Double, infoM: Double = 3.5): Int =
        (100 + 700 * (rangeM / infoM).coerceIn(0.0, 1.0)).roundToInt()

    /** Stereo pan from bearing: +-60 deg maps to full right/left. */
    fun panFor(bearingDeg: Double): Double = (bearingDeg / 60.0).coerceIn(-1.0, 1.0)

    /** Mono fallback: direction by pitch (low = left, mid = centre, high = right). */
    fun pitchFor(bearingDeg: Double): Double = when {
        bearingDeg < -10 -> 520.0
        bearingDeg > 10 -> 1320.0
        else -> 880.0
    }

    fun severityFreq(s: Severity): Double = when (s) {
        Severity.CRITICAL -> 1200.0
        Severity.WARNING -> 900.0
        else -> 650.0
    }
}
