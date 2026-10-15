package com.guidehub.nav

import com.guidehub.vision.CameraIntrinsics
import kotlin.math.abs
import kotlin.math.atan

data class Pt(val u: Double, val v: Double)

data class VoResult(val dYawDeg: Double, val dForwardM: Double, val quality: Double, val tracked: Int)

/**
 * Pure part of visual odometry (FR-5 source 3). Takes tracked feature correspondences (from
 * Lucas-Kanade + RANSAC homography inlier filtering, see vision/FeatureTracker) and estimates:
 *  - yaw change from the median angular shift of features (+ = head turned right);
 *  - forward progress with metric scale from (a) ground-plane features and the known camera
 *    height, and (b) change of the ToF forward range, when both are available and consistent.
 */
class VoSolver(
    private val intrinsics: CameraIntrinsics,
    private val cameraHeightM: Double = 1.6,
) {
    fun solve(
        prev: List<Pt>,
        cur: List<Pt>,
        tofForwardPrevM: Double? = null,
        tofForwardCurM: Double? = null,
    ): VoResult {
        require(prev.size == cur.size)
        val n = prev.size
        if (n < 6) return VoResult(0.0, tofDelta(tofForwardPrevM, tofForwardCurM) ?: 0.0, 0.0, n)

        val fx = intrinsics.fx
        val cx = intrinsics.cx
        val fy = intrinsics.fy
        val cy = intrinsics.cy

        // Yaw: per-feature change of horizontal view angle; the median rejects translation parallax.
        val yawSamples = (0 until n).map { i ->
            Math.toDegrees(atan((prev[i].u - cx) / fx) - atan((cur[i].u - cx) / fx))
        }
        val dYaw = median(yawSamples)
        val mad = median(yawSamples.map { abs(it - dYaw) })

        // Forward: ground points (below the horizon) back-project to the floor plane:
        // Z = h * fy / (v - cy), X = (u - cx) * Z / fx. The current point is de-rotated by dYaw into the
        // previous camera orientation so head turns do not masquerade as translation.
        val psi = Math.toRadians(dYaw)
        val cosP = kotlin.math.cos(psi)
        val sinP = kotlin.math.sin(psi)
        val groundDz = (0 until n).mapNotNull { i ->
            val dvPrev = prev[i].v - cy
            val dvCur = cur[i].v - cy
            if (dvPrev < 8.0 || dvCur < 8.0) return@mapNotNull null
            val zPrev = cameraHeightM * fy / dvPrev
            val zCur = cameraHeightM * fy / dvCur
            if (zPrev !in 0.5..10.0 || zCur !in 0.5..10.0) return@mapNotNull null
            val xCur = (cur[i].u - cx) * zCur / fx
            val zCurInPrev = -xCur * sinP + zCur * cosP
            zPrev - zCurInPrev
        }
        val groundForward = if (groundDz.size >= 4) median(groundDz) else null
        val tof = tofDelta(tofForwardPrevM, tofForwardCurM)
        var consistent = true
        val forward = when {
            groundForward != null && tof != null ->
                if (abs(groundForward - tof) <= 0.3 * maxOf(abs(tof), 0.1)) (groundForward + tof) / 2
                else {
                    // Disagreement: a person moving in front of the ToF, or non-floor features below the
                    // horizon. Take the smaller motion (conservative) and lower the quality.
                    consistent = false
                    if (abs(groundForward) < abs(tof)) groundForward else tof
                }
            groundForward != null -> groundForward
            tof != null -> tof
            else -> 0.0
        }.coerceIn(-0.5, 1.5)

        val quality = (minOf(1.0, n / 40.0) * (1.0 - minOf(1.0, mad / 3.0)) * (if (consistent) 1.0 else 0.6)).coerceIn(0.0, 1.0)
        return VoResult(dYaw, forward, quality, n)
    }

    private fun tofDelta(prev: Double?, cur: Double?): Double? {
        if (prev == null || cur == null) return null
        if (prev !in 0.1..4.0 || cur !in 0.1..4.0) return null
        val d = prev - cur
        return if (d in -0.2..1.5) d else null
    }

    companion object {
        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }
    }
}
