package com.guidehub.core

import com.guidehub.nav.RouteStep
import com.guidehub.voice.UserIntent

/** Everything that travels on the [EventBus]. */
sealed interface BusEvent {
    val ts: Long
}

enum class DataSource { PHONE, PI, SIM }

/** STT output (FR-2). */
data class Utterance(val text: String, val confidence: Float, override val ts: Long) : BusEvent

data class IntentEvent(val intent: UserIntent, val utterance: String, override val ts: Long) : BusEvent

/**
 * A fiducial marker seen by the headband camera.
 * [bearingDeg]: direction to the marker relative to head-forward, + = right.
 * [markerYawDeg]: observed rotation of the marker's viewing direction relative to the camera axis;
 * 0 when the user looks at the marker head-on. Null if the source could not estimate it.
 */
data class MarkerDetection(
    val id: Int,
    val rangeM: Double,
    val bearingDeg: Double,
    val markerYawDeg: Double?,
    override val ts: Long,
    val source: DataSource = DataSource.PHONE,
) : BusEvent

/** One VL53L1X reading. angle: + = right of head-forward. */
data class TofPoint(val angleDeg: Double, val rangeM: Double)

data class TofSweep(val points: List<TofPoint>, override val ts: Long, val source: DataSource = DataSource.PI) : BusEvent

/** Relative motion of the head since the previous VO update. */
data class VoDelta(
    val dYawDeg: Double,
    val dForwardM: Double,
    val quality: Double,
    override val ts: Long,
    val source: DataSource = DataSource.PHONE,
) : BusEvent

/** Camera quality flags (low light / motion blur) lower position confidence. */
data class FrameQuality(val lowLight: Boolean, val blurred: Boolean, val brightness: Double, val sharpness: Double, override val ts: Long) : BusEvent

/** A labelled object from the phone-side detector. */
data class CameraObject(
    val label: String,
    val score: Float,
    /** Horizontal angle of the box centre, + = right. */
    val bearingDeg: Double,
    /** Box height in pixels and frame height (for the size heuristic). */
    val boxHeightPx: Double,
    val frameHeightPx: Int,
    /** Normalised (0 top .. 1 bottom) vertical extent of the box. */
    val top: Double,
    val bottom: Double,
)

data class ObjectDetections(val objects: List<CameraObject>, override val ts: Long) : BusEvent

enum class HeightZone { HEAD, TORSO, GROUND }
enum class Severity(val level: Int) { NONE(0), INFO(1), WARNING(2), CRITICAL(3) }
enum class HazardType { OBSTACLE, PERSON, OBJECT }

data class Hazard(
    val type: HazardType,
    val label: String,
    val rangeM: Double,
    val bearingDeg: Double,
    val heightZone: HeightZone,
    val severity: Severity,
    override val ts: Long,
    val rangeFromTof: Boolean = true,
) : BusEvent {
    /** Stable-ish identity for cooldown tracking: label + 20° bearing bucket. */
    val trackKey: String get() = "$label@${Math.floorDiv(bearingDeg.toInt() + 90, 20)}"
}

data class HazardsUpdate(val hazards: List<Hazard>, override val ts: Long) : BusEvent

data class PositionUpdate(
    val edgeFrom: String?,
    val edgeTo: String?,
    val s: Double,
    val x: Double,
    val y: Double,
    val lateralOffsetM: Double,
    val yawDeg: Double,
    val confidence: Double,
    val headingConfidence: Double,
    val distanceSinceFixM: Double,
    val lastCorrectionM: Double?,
    override val ts: Long,
) : BusEvent

data class RouteStepEvent(val step: RouteStep, val index: Int, val total: Int, override val ts: Long) : BusEvent

data class CompassAckEvent(val seq: Int, val status: Int, val actualDeg: Int, val latencyMs: Long, override val ts: Long) : BusEvent

enum class LinkId { COMPASS, HEADBAND_BLE, HEADBAND_WIFI, EARBUDS }
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED }

data class LinkStatus(val link: LinkId, val state: LinkState, val detail: String = "", override val ts: Long) : BusEvent

enum class ButtonSource { EARBUD, HEADBAND, COMPASS, VOLUME_KEY, SCREEN }

data class ButtonEvent(val source: ButtonSource, val buttonId: Int, val event: Int, override val ts: Long) : BusEvent

/** Spoken output, for the debug tail and session recording. */
data class SpokenEvent(val text: String, val priority: String, override val ts: Long) : BusEvent

data class SessionStateEvent(val state: String, val degraded: Set<String>, val locked: Boolean, override val ts: Long) : BusEvent

data class LogEvent(val tag: String, val message: String, override val ts: Long) : BusEvent
