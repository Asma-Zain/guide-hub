package com.guidehub.nav

import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.FrameQuality
import com.guidehub.core.MarkerDetection
import com.guidehub.core.PositionUpdate
import com.guidehub.core.VoDelta
import kotlin.math.abs

/**
 * Fusion filter over (edge, s, lateralOffset, yaw, confidence), constrained to the map graph (FR-5).
 * Inputs come only from the headband: marker fixes, ToF wall fits and visual odometry.
 * No phone IMU / magnetometer / step detector is used anywhere.
 */
class PositionEstimator(
    private val graph: MapGraph,
    private val clock: Clock,
    private val decayDistanceM: Double = Constants.CONFIDENCE_DECAY_DISTANCE_M,
) {
    var edge: DirectedEdge? = null; private set
    var s: Double = 0.0; private set
    var lateral: Double = 0.0; private set
    /** Head yaw in the map frame, degrees [0, 360). Null until the first heading fix. */
    var yaw: Double? = null; private set
    var confidence: Double = 0.0; private set
    var headingConfidence: Double = 0.0; private set
    var distanceSinceFix: Double = 0.0; private set
    var lastCorrectionM: Double? = null; private set
    var lastFixNode: String? = null; private set
    var lastFixTs: Long = 0L; private set
    private var lastWallTs: Long = 0L
    private var lastDecayTs: Long = 0L
    val hasFix: Boolean get() = edge != null

    private var route: Route? = null

    /** Corrections logged for drift analysis (test report: drift before/after marker corrections). */
    val corrections = mutableListOf<Pair<Int, Double>>()

    fun reset() {
        edge = null; s = 0.0; lateral = 0.0; yaw = null; confidence = 0.0; headingConfidence = 0.0
        distanceSinceFix = 0.0; lastCorrectionM = null; lastFixNode = null; route = null
    }

    /** Re-express the current edge in the route's direction so progress is measured along it. */
    fun setRoute(r: Route?) {
        route = r
        val e = edge ?: return
        if (r == null) return
        val match = r.edges.firstOrNull { it.sameSegment(e.from, e.to) } ?: return
        if (match.from != e.from) {
            s = e.lengthM - s
            lateral = -lateral
            edge = match
        }
    }

    fun position(): Pair<Double, Double>? = edge?.let { e ->
        val (px, py) = graph.pointOn(e, s)
        // Apply lateral offset along the right-hand normal.
        val h = e.headingDeg
        (px + lateral * Geo.dy(h)) to (py - lateral * Geo.dx(h))
    }

    /** Absolute fix: position and heading from a marker with a known map pose. */
    fun onMarker(det: MarkerDetection): PositionUpdate? {
        val node = graph.nodeForMarker(det.id) ?: return null
        val marker = node.marker ?: return null
        val observedYaw = det.markerYawDeg?.let { Geo.norm360(marker.facingDeg - it) }
        val headYaw = observedYaw ?: yaw ?: marker.facingDeg
        val dir = headYaw + det.bearingDeg
        val ux = node.x - det.rangeM * Geo.dx(dir)
        val uy = node.y - det.rangeM * Geo.dy(dir)

        val predicted = position()
        val proj = graph.nearestEdge(ux, uy, headYaw, route?.edges.orEmpty()) ?: return null
        edge = proj.edge
        s = proj.s
        lateral = proj.lateral.coerceIn(-proj.edge.width / 2, proj.edge.width / 2)
        yaw = headYaw
        lastCorrectionM = predicted?.let { Geo.dist(it.first, it.second, ux, uy) }
        lastCorrectionM?.let { corrections += det.id to it }
        confidence = 1.0
        if (observedYaw != null) headingConfidence = 1.0
        distanceSinceFix = 0.0
        lastFixNode = node.id
        lastFixTs = det.ts
        return snapshot(det.ts)
    }

    /** Corridor-axis heading from the ToF wall fit; only meaningful while on an edge. */
    fun onWall(est: WallEstimate) {
        val e = edge ?: return
        if (est.wallsSeen == 0) return
        val c1 = Geo.norm360(e.headingDeg + est.yawOffsetDeg)
        val c2 = Geo.norm360(c1 + 180.0)
        val current = yaw
        val candidate = if (current == null) c1 else if (Geo.angleDiff(c1, current) <= Geo.angleDiff(c2, current)) c1 else c2
        // A fit far from the current heading most likely belongs to a different corridor (e.g. mid-turn
        // at a junction before the filter has moved onto the next edge): ignore it.
        if (current != null && Geo.angleDiff(candidate, current) > MAX_WALL_JUMP_DEG) return
        val alpha = 0.6 * est.quality
        yaw = if (current == null) candidate else Geo.blendAngle(current, candidate, alpha)
        est.lateralOffsetM?.let { lat ->
            // Lateral measured relative to the walls; flip if facing against the edge direction.
            val facingWithEdge = Geo.angleDiff(candidate, e.headingDeg) < 90
            val l = if (facingWithEdge) lat else -lat
            lateral = (lateral + 0.5 * (l - lateral)).coerceIn(-e.width / 2, e.width / 2)
        }
        headingConfidence = (headingConfidence + 0.4 * est.quality).coerceAtMost(1.0)
        lastWallTs = est.ts
    }

    /** Relative motion between markers. */
    fun onVo(d: VoDelta) {
        yaw = yaw?.let { Geo.norm360(it + d.dYawDeg) }
        var e = edge ?: return
        val y = yaw ?: e.headingDeg
        // Turned at a junction: the head now points down an outgoing corridor near the end of this edge.
        if (Geo.angleDiff(y, e.headingDeg) >= 60) junctionExit(e, y)?.let { next ->
            edge = next; s = 0.0; lateral = 0.0; e = next
        }
        val off = Geo.angleDiff(y, e.headingDeg)
        // Head roughly along the corridor: camera-forward motion is progress. Looking sideways: ignore.
        val progress = when {
            off < 60 -> d.dForwardM
            off > 120 -> -d.dForwardM
            else -> 0.0
        }
        advance(progress)
        distanceSinceFix += abs(d.dForwardM)
        val qualityPenalty = if (d.quality < 0.4) 2.0 else 1.0
        confidence = (confidence - abs(d.dForwardM) / decayDistanceM * qualityPenalty).coerceAtLeast(0.0)
        if (d.ts - lastWallTs > 2_000) {
            // No wall fit recently (junction / open space): heading relies on VO integration only.
            headingConfidence = (headingConfidence - 0.01 * (1.0 + (1.0 - d.quality))).coerceAtLeast(0.0)
        }
    }

    fun onFrameQuality(q: FrameQuality) {
        if (q.lowLight || q.blurred) {
            confidence *= 0.99
            headingConfidence *= 0.97
        }
    }

    /** Time-based decay when no headband data is arriving at all (headband lost). */
    fun decayForSilence(now: Long) {
        if (lastDecayTs == 0L) lastDecayTs = now
        val dt = (now - lastDecayTs) / 1000.0
        lastDecayTs = now
        confidence = (confidence - 0.02 * dt).coerceAtLeast(0.0)
        headingConfidence = (headingConfidence - 0.05 * dt).coerceAtLeast(0.0)
    }

    private fun advance(ds: Double) {
        var e = edge ?: return
        s += ds
        // Move onto the next/previous edge of the route (or the best-aligned edge if no route).
        var guard = 0
        while (s > e.lengthM && guard++ < 10) {
            val next = nextEdge(e) ?: run { s = e.lengthM; return }
            s -= e.lengthM
            e = next
            edge = next
        }
        while (s < 0 && guard++ < 20) {
            val prev = prevEdge(e) ?: run { s = 0.0; return }
            s += prev.lengthM
            e = prev
            edge = prev
        }
    }

    private fun nextEdge(e: DirectedEdge): DirectedEdge? {
        route?.let { r ->
            val i = r.edges.indexOfFirst { it.from == e.from && it.to == e.to }
            if (i >= 0) return r.edges.getOrNull(i + 1)
        }
        val y = yaw ?: e.headingDeg
        return graph.neighbours(e.to).filter { it.to != e.from }.minByOrNull { Geo.angleDiff(it.headingDeg, y) }
            ?.takeIf { Geo.angleDiff(it.headingDeg, y) < 60 }
    }

    /**
     * Outgoing edge at the end of [e] aligned (< 45 deg) with [yawDeg], if we are within
     * [JUNCTION_SNAP_M] of that end. Prefers the route's next edge.
     */
    private fun junctionExit(e: DirectedEdge, yawDeg: Double): DirectedEdge? {
        if (e.lengthM - s > JUNCTION_SNAP_M) return null
        route?.let { r ->
            val i = r.edges.indexOfFirst { it.from == e.from && it.to == e.to }
            val next = r.edges.getOrNull(i + 1)
            if (i >= 0 && next != null && Geo.angleDiff(next.headingDeg, yawDeg) < 45) return next
        }
        return graph.neighbours(e.to).filter { it.to != e.from }
            .minByOrNull { Geo.angleDiff(it.headingDeg, yawDeg) }
            ?.takeIf { Geo.angleDiff(it.headingDeg, yawDeg) < 45 }
    }

    private fun prevEdge(e: DirectedEdge): DirectedEdge? {
        route?.let { r ->
            val i = r.edges.indexOfFirst { it.from == e.from && it.to == e.to }
            if (i > 0) return r.edges[i - 1]
        }
        return null
    }

    companion object {
        const val MAX_WALL_JUMP_DEG = 35.0
        const val JUNCTION_SNAP_M = 2.5
    }

    fun snapshot(ts: Long = clock.now()): PositionUpdate? {
        val e = edge ?: return null
        val (x, y) = position() ?: return null
        return PositionUpdate(
            edgeFrom = e.from, edgeTo = e.to, s = s, x = x, y = y,
            lateralOffsetM = lateral, yawDeg = yaw ?: e.headingDeg,
            confidence = confidence, headingConfidence = headingConfidence,
            distanceSinceFixM = distanceSinceFix, lastCorrectionM = lastCorrectionM, ts = ts,
        )
    }
}

/** FR-5 escape hatch: heading providers. Only the headband vision/ToF source is enabled. */
interface HeadingSource {
    val name: String
    val enabled: Boolean
    fun headingDeg(): Double?
    fun confidence(): Double
}

class HeadbandVisionHeading(private val estimator: PositionEstimator) : HeadingSource {
    override val name = "headband-vision"
    override val enabled = true
    override fun headingDeg() = estimator.yaw
    override fun confidence() = estimator.headingConfidence
}

/** Placeholder for a future headband IMU. Disabled; never use phone sensors here. */
class HeadbandImuHeading : HeadingSource {
    override val name = "headband-imu"
    override val enabled = false
    override fun headingDeg(): Double? = null
    override fun confidence() = 0.0
}

/**
 * Low-passes the needle bearing and holds the last stable value during fast head turns
 * (> [maxYawRateDps]) so the needle does not whip around.
 */
class NeedleSmoother(private val alpha: Double = 0.5, private val maxYawRateDps: Double = Constants.NEEDLE_MAX_YAW_RATE_DPS) {
    private var output: Double? = null
    private var lastYaw: Double? = null
    private var lastTs: Long = 0

    fun reset() { output = null; lastYaw = null }

    fun update(targetRelDeg: Double, yawDeg: Double, ts: Long): Double {
        val prevYaw = lastYaw
        val dt = (ts - lastTs).coerceAtLeast(1) / 1000.0
        lastYaw = yawDeg
        lastTs = ts
        val rate = if (prevYaw == null) 0.0 else Geo.angleDiff(yawDeg, prevYaw) / dt
        val out = output
        if (out != null && rate > maxYawRateDps) return out
        val next = if (out == null) targetRelDeg else Geo.norm180(out + alpha * Geo.norm180(targetRelDeg - out))
        output = next
        return next
    }
}
