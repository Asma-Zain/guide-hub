package com.guidehub.nav

import com.guidehub.core.TofSweep
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Result of fitting corridor side walls to one ToF sweep.
 * [yawOffsetDeg]: head heading minus corridor axis (+ = head turned right of the axis), in (-90, 90].
 * [lateralOffsetM]: + = user is right of the corridor centre line.
 */
data class WallEstimate(
    val yawOffsetDeg: Double,
    val lateralOffsetM: Double?,
    val wallsSeen: Int,
    val inlierRatio: Double,
    val leftDistM: Double?,
    val rightDistM: Double?,
    val fullCoverage: Boolean,
    val ts: Long,
) {
    /** 0..1 trust in the heading, used by the fusion filter. */
    val quality: Double get() = (inlierRatio * (if (wallsSeen == 2) 1.0 else 0.6) * (if (fullCoverage) 1.0 else 0.8)).coerceIn(0.0, 1.0)
}

/** A fitted line through points: unit direction (ux, uy) with uy >= 0, passing through (px, py). */
internal data class Line(val px: Double, val py: Double, val ux: Double, val uy: Double, val inliers: List<Int>) {
    /** + = line lies to the right of the sensor. */
    val signedOffset: Double get() = px * uy - py * ux
    /** Angle of the line direction from head-forward, + = rotated right. */
    val angleDeg: Double get() = Math.toDegrees(atan2(ux, uy))
}

/**
 * Corridor-axis heading from a head-height ToF sweep (FR-5 source 2): RANSAC line fits to the side walls.
 * Sweep frame: x = right, y = forward (head), angles + = right.
 */
class WallHeadingEstimator(
    private val maxRangeM: Double = 4.0,
    private val inlierTolM: Double = 0.06,
    private val iterations: Int = 150,
    private val minInlierRatio: Double = 0.5,
    private val minInliersPerWall: Int = 4,
    private val maxWallAngleDeg: Double = 60.0,
    private val parallelTolDeg: Double = 15.0,
    private val requiredCoverageDeg: Double = 60.0,
    seed: Int = 42,
) {
    private val rnd = Random(seed)

    fun estimate(sweep: TofSweep, corridorWidthM: Double? = null): WallEstimate? {
        val pts = sweep.points.filter { it.rangeM > 0.05 && it.rangeM < maxRangeM }
        if (pts.size < minInliersPerWall) return null
        val xs = DoubleArray(pts.size) { pts[it].rangeM * sin(Math.toRadians(pts[it].angleDeg)) }
        val ys = DoubleArray(pts.size) { pts[it].rangeM * cos(Math.toRadians(pts[it].angleDeg)) }
        val angles = sweep.points.map { it.angleDeg }
        val fullCoverage = (angles.minOrNull() ?: 0.0) <= -requiredCoverageDeg + 5 && (angles.maxOrNull() ?: 0.0) >= requiredCoverageDeg - 5

        val all = pts.indices.toList()
        val first = ransac(xs, ys, all, null) ?: return null
        val remaining = all - first.inliers.toSet()
        val second = ransac(xs, ys, remaining, first)

        val walls = listOfNotNull(first, second)
        val inlierRatio = walls.sumOf { it.inliers.size }.toDouble() / pts.size
        if (inlierRatio < minInlierRatio) return null // dominated by people/obstacles

        val totalInliers = walls.sumOf { it.inliers.size }.toDouble()
        val angle = walls.sumOf { it.angleDeg * it.inliers.size } / totalInliers
        val right = walls.firstOrNull { it.signedOffset > 0 }?.signedOffset
        val left = walls.firstOrNull { it.signedOffset < 0 }?.signedOffset?.let { -it }
        val lateral = when {
            left != null && right != null -> (left - right) / 2.0
            left != null && corridorWidthM != null -> left - corridorWidthM / 2.0
            right != null && corridorWidthM != null -> corridorWidthM / 2.0 - right
            else -> null
        }
        return WallEstimate(
            yawOffsetDeg = -angle,
            lateralOffsetM = lateral,
            wallsSeen = walls.size,
            inlierRatio = inlierRatio,
            leftDistM = left,
            rightDistM = right,
            fullCoverage = fullCoverage,
            ts = sweep.ts,
        )
    }

    private fun ransac(xs: DoubleArray, ys: DoubleArray, idx: List<Int>, partner: Line?): Line? {
        if (idx.size < minInliersPerWall) return null
        var best: List<Int> = emptyList()
        repeat(iterations) {
            val i = idx[rnd.nextInt(idx.size)]
            val j = idx[rnd.nextInt(idx.size)]
            if (i == j) return@repeat
            val cand = lineThrough(xs[i], ys[i], xs[j], ys[j]) ?: return@repeat
            if (!acceptable(cand, partner)) return@repeat
            val inl = idx.filter { k -> abs(distance(cand, xs[k], ys[k])) < inlierTolM }
            if (inl.size > best.size) best = inl
        }
        if (best.size < minInliersPerWall) return null
        val refined = fit(xs, ys, best)
        if (!acceptable(refined, partner)) return null
        // Re-collect inliers against the refined line.
        val inl = idx.filter { k -> abs(distance(refined, xs[k], ys[k])) < inlierTolM }
        return if (inl.size >= minInliersPerWall) refined.copy(inliers = inl) else null
    }

    private fun acceptable(l: Line, partner: Line?): Boolean {
        if (abs(l.angleDeg) > maxWallAngleDeg) return false // far wall / clutter, not a side wall
        if (abs(l.signedOffset) < 0.25) return false        // a line through the user is not a wall
        if (partner != null) {
            if (abs(Geo.norm180(l.angleDeg - partner.angleDeg)) > parallelTolDeg) return false
            if (l.signedOffset * partner.signedOffset > 0) return false // must be the opposite wall
        }
        return true
    }

    private fun lineThrough(x1: Double, y1: Double, x2: Double, y2: Double): Line? {
        var dx = x2 - x1
        var dy = y2 - y1
        val n = sqrt(dx * dx + dy * dy)
        if (n < 0.05) return null
        dx /= n; dy /= n
        if (dy < 0) { dx = -dx; dy = -dy }
        return Line(x1, y1, dx, dy, emptyList())
    }

    /** Total least squares (principal axis) fit. */
    private fun fit(xs: DoubleArray, ys: DoubleArray, idx: List<Int>): Line {
        val mx = idx.sumOf { xs[it] } / idx.size
        val my = idx.sumOf { ys[it] } / idx.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        idx.forEach { val dx = xs[it] - mx; val dy = ys[it] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy) // direction angle from +x
        var ux = cos(theta)
        var uy = sin(theta)
        if (uy < 0) { ux = -ux; uy = -uy }
        return Line(mx, my, ux, uy, idx)
    }

    private fun distance(l: Line, x: Double, y: Double): Double = (x - l.px) * l.uy - (y - l.py) * l.ux
}
