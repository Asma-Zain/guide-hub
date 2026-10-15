package com.guidehub.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Map frame conventions: x = east, y = north, metres.
 * Headings/yaw: degrees clockwise from +y (compass style): 0 = +y, 90 = +x.
 * Relative bearings: + = right of the reference direction.
 */
object Geo {
    /** Normalise to (-180, 180]. */
    fun norm180(deg: Double): Double {
        var d = deg % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    /** Normalise to [0, 360). */
    fun norm360(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }

    fun bearing(x1: Double, y1: Double, x2: Double, y2: Double): Double =
        norm360(Math.toDegrees(atan2(x2 - x1, y2 - y1)))

    fun dx(headingDeg: Double) = sin(Math.toRadians(headingDeg))
    fun dy(headingDeg: Double) = cos(Math.toRadians(headingDeg))

    fun dist(x1: Double, y1: Double, x2: Double, y2: Double) = hypot(x2 - x1, y2 - y1)

    /** Shortest-arc blend: moves [from] toward [to] by [alpha] (0..1). */
    fun blendAngle(from: Double, to: Double, alpha: Double): Double = norm360(from + alpha * norm180(to - from))

    fun angleDiff(a: Double, b: Double): Double = kotlin.math.abs(norm180(a - b))
}
