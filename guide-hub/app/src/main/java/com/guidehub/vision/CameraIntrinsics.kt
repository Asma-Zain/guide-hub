package com.guidehub.vision

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pinhole intrinsics + distortion for the headband camera (JSON files in assets/calibration).
 * The shipped values are PLACEHOLDERS for a Camera Module V2 at 640x480; run the procedure in
 * docs/calibration.md and replace them before trusting marker ranges.
 */
@Serializable
data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
    val distortion: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0),
    /** Camera optical centre height above the floor, metres (headband on an average adult). */
    val cameraHeightM: Double = 1.6,
    val placeholder: Boolean = false,
) {
    /** Horizontal angle of a pixel column, + = right. */
    fun bearingOfColumn(u: Double): Double = Math.toDegrees(kotlin.math.atan((u - cx) / fx))

    /** Scale to a different frame size (e.g. headband sends 320x240). */
    fun scaledTo(w: Int, h: Int): CameraIntrinsics {
        if (w == width && h == height) return this
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        return copy(fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy, width = w, height = h)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): CameraIntrinsics = json.decodeFromString(serializer(), text)

        /** Camera Module V2 (IMX219, 3.04 mm lens, ~62.2 deg HFOV) at 640x480, nominal values. */
        val DEFAULT_640x480 = CameraIntrinsics(fx = 530.0, fy = 530.0, cx = 320.0, cy = 240.0, width = 640, height = 480, placeholder = true)
    }
}
