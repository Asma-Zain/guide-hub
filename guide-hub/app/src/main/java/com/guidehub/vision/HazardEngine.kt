package com.guidehub.vision

import com.guidehub.core.CameraObject
import com.guidehub.core.Hazard
import com.guidehub.core.HazardType
import com.guidehub.core.HeightZone
import com.guidehub.core.ObjectDetections
import com.guidehub.core.Severity
import com.guidehub.core.TofSweep
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class HazardConfig(
    val criticalM: Double = 1.0,
    val warningM: Double = 2.0,
    val infoM: Double = 3.5,
    /** Half-width of the walking path checked for obstacles. Walls outside it are not hazards. */
    val pathHalfWidthM: Double = 0.45,
    /** Head/torso-height obstacles (cane-blind zone) escalate one severity level. */
    val escalateHeadTorso: Boolean = true,
    /** Angle bin (deg) for attaching a camera label to a ToF return. */
    val labelBinDeg: Double = 10.0,
)

/**
 * Distance fusion (FR-6): ToF is authoritative for range; the camera identifies the object and gives a
 * rough range from bounding-box height when ToF is unavailable.
 *
 * Size assumption: typical real-world heights below; range = H * fy / boxHeightPx. This is only a rough
 * estimate (partial occlusion, sitting people, cropped boxes all bias it), hence ToF wins when present.
 */
class DistanceFusion(private val intrinsics: CameraIntrinsics) {
    fun cameraRange(obj: CameraObject): Double? {
        val h = KNOWN_HEIGHTS_M[obj.label] ?: return null
        if (obj.boxHeightPx < 4) return null
        val fy = intrinsics.fy * obj.frameHeightPx / intrinsics.height
        // A box touching the frame edge is cropped: the estimate is a lower bound on size, so clamp.
        return (h * fy / obj.boxHeightPx).coerceIn(0.3, 15.0)
    }

    companion object {
        val KNOWN_HEIGHTS_M = mapOf(
            "person" to 1.65, "chair" to 0.9, "door" to 2.0, "bench" to 0.45, "pole" to 2.5,
            "dining table" to 0.75, "table" to 0.75, "bicycle" to 1.0, "potted plant" to 0.8,
            "suitcase" to 0.6, "backpack" to 0.5, "trash can" to 0.8, "fire hydrant" to 0.7,
            "couch" to 0.85, "bed" to 0.6, "tv" to 0.6, "refrigerator" to 1.7, "stop sign" to 2.1,
        )
        /** Labels worth warning about; everything else is ignored. */
        val HAZARD_LABELS = KNOWN_HEIGHTS_M.keys
    }
}

class HazardEngine(private val fusion: DistanceFusion, var config: HazardConfig = HazardConfig()) {

    data class TofObstacle(val rangeM: Double, val bearingDeg: Double, val points: Int)

    /** Clusters ToF returns inside the walking path into obstacles. */
    fun tofObstacles(sweep: TofSweep): List<TofObstacle> {
        val inPath = sweep.points.filter { p ->
            if (p.rangeM <= 0.05 || p.rangeM > config.infoM) return@filter false
            val a = Math.toRadians(p.angleDeg)
            val x = p.rangeM * sin(a)
            val y = p.rangeM * cos(a)
            y > 0.1 && abs(x) <= config.pathHalfWidthM
        }.sortedBy { it.angleDeg }
        val clusters = mutableListOf<MutableList<com.guidehub.core.TofPoint>>()
        for (p in inPath) {
            val last = clusters.lastOrNull()?.lastOrNull()
            if (last != null && abs(last.rangeM - p.rangeM) < 0.35 && p.angleDeg - last.angleDeg <= 12) clusters.last() += p
            else clusters += mutableListOf(p)
        }
        return clusters.map { c -> TofObstacle(c.minOf { it.rangeM }, c.map { it.angleDeg }.average(), c.size) }
    }

    /**
     * Fuse the latest sweep with the latest labelled detections.
     * [tofHealthy] false = ToF error: camera-only estimation with raised caution (thresholds x1.3).
     */
    fun evaluate(sweep: TofSweep?, objects: ObjectDetections?, now: Long, tofHealthy: Boolean = true): List<Hazard> {
        val out = mutableListOf<Hazard>()
        val cams = objects?.objects.orEmpty().filter { it.label in DistanceFusion.HAZARD_LABELS }
        val usedCams = mutableSetOf<CameraObject>()
        val caution = if (tofHealthy) 1.0 else 1.3

        if (sweep != null && tofHealthy) {
            for (ob in tofObstacles(sweep)) {
                val cam = cams.filter { abs(it.bearingDeg - ob.bearingDeg) <= config.labelBinDeg }.minByOrNull { abs(it.bearingDeg - ob.bearingDeg) }
                cam?.let { usedCams += it }
                val label = cam?.label ?: "obstacle"
                val zone = cam?.let { zoneOf(it) } ?: HeightZone.HEAD // the ToF beam is at head height
                val sev = severity(ob.rangeM, zone, 1.0)
                if (sev == Severity.NONE) continue
                out += Hazard(
                    type = when (label) { "person" -> HazardType.PERSON; "obstacle" -> HazardType.OBSTACLE; else -> HazardType.OBJECT },
                    label = label, rangeM = ob.rangeM, bearingDeg = ob.bearingDeg, heightZone = zone,
                    severity = sev, ts = now, rangeFromTof = true,
                )
            }
        }
        // Camera-only hazards: when ToF is unavailable, or for objects the head-height beam cannot see
        // (low objects like benches) that are roughly in the walking direction.
        for (c in cams) {
            if (c in usedCams) continue
            if (abs(c.bearingDeg) > 25) continue
            val zone = zoneOf(c)
            if (sweep != null && tofHealthy && zone != HeightZone.GROUND) continue // ToF saw the path clear
            val range = fusion.cameraRange(c) ?: continue
            val sev = severity(range, zone, caution)
            if (sev == Severity.NONE) continue
            out += Hazard(
                type = if (c.label == "person") HazardType.PERSON else HazardType.OBJECT,
                label = c.label, rangeM = range, bearingDeg = c.bearingDeg, heightZone = zone,
                severity = sev, ts = now, rangeFromTof = false,
            )
        }
        return out.sortedWith(compareByDescending<Hazard> { it.severity.level }.thenBy { it.rangeM })
    }

    fun severity(rangeM: Double, zone: HeightZone, caution: Double = 1.0): Severity {
        val base = when {
            rangeM < config.criticalM * caution -> Severity.CRITICAL
            rangeM < config.warningM * caution -> Severity.WARNING
            rangeM < config.infoM * caution -> Severity.INFO
            else -> Severity.NONE
        }
        if (base == Severity.NONE) return base
        val escalate = config.escalateHeadTorso && (zone == HeightZone.HEAD || zone == HeightZone.TORSO)
        return if (escalate) Severity.entries[(base.level + 1).coerceAtMost(Severity.CRITICAL.level)] else base
    }

    private fun zoneOf(c: CameraObject): HeightZone = when {
        c.label == "person" -> HeightZone.TORSO
        c.bottom < 0.5 -> HeightZone.HEAD           // entirely above the horizon: overhanging
        c.top > 0.55 -> HeightZone.GROUND           // entirely low in the frame
        else -> HeightZone.TORSO
    }
}
