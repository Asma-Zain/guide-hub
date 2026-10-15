package com.guidehub.sim

import com.guidehub.core.CameraObject
import com.guidehub.core.Clock
import com.guidehub.core.Constants
import com.guidehub.core.DataSource
import com.guidehub.core.EventBus
import com.guidehub.core.MarkerDetection
import com.guidehub.core.ObjectDetections
import com.guidehub.core.VoDelta
import com.guidehub.nav.Geo
import com.guidehub.nav.MapGraph
import com.guidehub.nav.Route
import com.guidehub.protocol.HeadbandCodec
import com.guidehub.protocol.TofSweepPacket
import com.guidehub.vision.CameraIntrinsics
import com.guidehub.vision.DistanceFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

data class SimObstacle(val id: Int, val label: String, val x: Double, val y: Double, val radius: Double, val untilTs: Long?)

/**
 * Simulated user + building. Walks the active route like a cane user would (stops for close
 * obstacles), and synthesises what the headband would report:
 *  - ToF sweeps (+-60 deg, 5 deg steps) ray-cast against corridor walls and obstacles, sent as
 *    encoded BLE packets through [MockHeadbandTransport] (so the codec + link path is exercised);
 *  - visual odometry deltas, marker detections and labelled object detections as phone-side
 *    vision results (what the frame pipeline would produce).
 */
class SimWorld(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val bus: EventBus,
    private val graphProvider: () -> MapGraph?,
    private val headband: MockHeadbandTransport,
    private val routeProvider: () -> Route?,
    private val intrinsics: CameraIntrinsics = CameraIntrinsics.DEFAULT_640x480,
    seed: Int = 7,
) {
    private val rnd = Random(seed)
    var x = 0.0; private set
    var y = 0.0; private set
    var yaw = 90.0; private set
    var speedMps = 1.2
    var noise = true
    /** Simulates "headband sees the world" (sweeps, markers); false = headband dark. */
    var sensing = true
    private val obstacles = mutableListOf<SimObstacle>()
    private var nextObstacleId = 1
    private var job: Job? = null
    private var waypointIndex = 0
    private var currentRoute: Route? = null
    private var tick = 0
    var distanceWalked = 0.0; private set
    var stoppedForObstacle = false; private set

    fun placeAt(nodeId: String, headingDeg: Double, backOffM: Double = 0.0) {
        val n = graphProvider()?.nodes?.get(nodeId) ?: return
        x = n.x - backOffM * Geo.dx(headingDeg)
        y = n.y - backOffM * Geo.dy(headingDeg)
        yaw = headingDeg
    }

    fun placeAt(px: Double, py: Double, headingDeg: Double) { x = px; y = py; yaw = headingDeg }

    fun addObstacle(label: String, ox: Double, oy: Double, radius: Double = 0.25, durationMs: Long? = null): Int {
        val id = nextObstacleId++
        synchronized(obstacles) { obstacles += SimObstacle(id, label, ox, oy, radius, durationMs?.let { clock.now() + it }) }
        return id
    }

    fun clearObstacles() = synchronized(obstacles) { obstacles.clear() }

    fun obstaclesSnapshot(): List<SimObstacle> = synchronized(obstacles) { obstacles.toList() }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                step()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun n(sigma: Double) = if (noise) rnd.nextDouble(-sigma, sigma) else 0.0

    private fun step() {
        val g = graphProvider() ?: return
        val now = clock.now()
        tick++
        synchronized(obstacles) { obstacles.removeAll { it.untilTs != null && now > it.untilTs } }

        val prevYaw = yaw
        var moved = 0.0
        val r = routeProvider()
        if (r != currentRoute) { currentRoute = r; waypointIndex = 0 }
        if (r != null) {
            val nodes = r.nodes
            // Skip waypoints we are already at.
            while (waypointIndex < nodes.size) {
                val wp = g.nodes.getValue(nodes[waypointIndex])
                if (Geo.dist(x, y, wp.x, wp.y) < 0.3) waypointIndex++ else break
            }
            if (waypointIndex < nodes.size) {
                val wp = g.nodes.getValue(nodes[waypointIndex])
                val desired = Geo.bearing(x, y, wp.x, wp.y)
                val maxTurn = 120.0 * TICK_MS / 1000.0
                val turn = Geo.norm180(desired - yaw).coerceIn(-maxTurn, maxTurn)
                yaw = Geo.norm360(yaw + turn)
                stoppedForObstacle = obstacleAhead(0.8)
                if (abs(Geo.norm180(desired - yaw)) < 30 && !stoppedForObstacle) {
                    val stepLen = minOf(speedMps * TICK_MS / 1000.0, Geo.dist(x, y, wp.x, wp.y))
                    x += stepLen * Geo.dx(yaw)
                    y += stepLen * Geo.dy(yaw)
                    moved = stepLen
                    distanceWalked += stepLen
                }
            }
        }
        // Small natural head sway.
        val sway = n(0.6)
        yaw = Geo.norm360(yaw + sway)

        if (!sensing) return

        // Visual odometry at 10 Hz (phone-side, from frames).
        bus.emit(VoDelta(Geo.norm180(yaw - prevYaw) + n(0.3), (moved * (1 + n(0.05))).coerceAtLeast(0.0), 0.85, now, DataSource.SIM))

        // ToF sweep at 2 Hz through the BLE codec.
        if (tick % 5 == 0) headband.push(Constants.HEADBAND_TOF_SWEEP, HeadbandCodec.encode(sweep(g, now)))

        // Marker sightings at 5 Hz.
        if (tick % 2 == 0) {
            for (node in g.map.nodes) {
                val m = node.marker ?: continue
                val range = Geo.dist(x, y, node.x, node.y)
                if (range > 5.0 || range < 0.3) continue
                val bearing = Geo.norm180(Geo.bearing(x, y, node.x, node.y) - yaw)
                val markerYaw = Geo.norm180(m.facingDeg - yaw)
                if (abs(bearing) > 30 || abs(markerYaw) > 60) continue
                bus.emit(MarkerDetection(m.id, range * (1 + n(0.03)), bearing + n(1.5), markerYaw + n(2.0), now, DataSource.SIM))
            }
        }

        // Labelled object detections at ~4 Hz.
        if (tick % 3 == 0) {
            val objs = obstaclesSnapshot().mapNotNull { o ->
                if (o.label == "obstacle") return@mapNotNull null
                val range = Geo.dist(x, y, o.x, o.y)
                val bearing = Geo.norm180(Geo.bearing(x, y, o.x, o.y) - yaw)
                if (range > 6 || abs(bearing) > 31) return@mapNotNull null
                val h = DistanceFusion.KNOWN_HEIGHTS_M[o.label] ?: 1.0
                val boxH = (h * intrinsics.fy / range).coerceAtMost(intrinsics.height.toDouble())
                val centre = 0.5
                CameraObject(o.label, 0.8f, bearing, boxH, intrinsics.height, centre - boxH / intrinsics.height / 2, centre + boxH / intrinsics.height / 2)
            }
            bus.emit(ObjectDetections(objs, now))
        }
    }

    private fun obstacleAhead(within: Double): Boolean = obstaclesSnapshot().any { o ->
        val d = Geo.dist(x, y, o.x, o.y) - o.radius
        val b = abs(Geo.norm180(Geo.bearing(x, y, o.x, o.y) - yaw))
        d < within && b < 35
    }

    private fun sweep(g: MapGraph, now: Long): TofSweepPacket {
        val proj = g.nearestEdge(x, y, yaw) ?: return TofSweepPacket(now and 0xFFFFFFFFL, emptyList())
        val e = proj.edge
        val a = g.nodes.getValue(e.from)
        val pts = (-60..60 step 5).map { ang ->
            val th = yaw + ang
            val dx = Geo.dx(th)
            val dy = Geo.dy(th)
            var best = Double.POSITIVE_INFINITY
            // Side walls: lines parallel to the edge at +-width/2 from its centre line.
            val nx = Geo.dy(e.headingDeg)
            val ny = -Geo.dx(e.headingDeg)
            val off = (x - a.x) * nx + (y - a.y) * ny
            val nd = dx * nx + dy * ny
            if (abs(nd) > 1e-6) for (side in listOf(-e.width / 2, e.width / 2)) {
                val t = (side - off) / nd
                if (t > 0.05) best = minOf(best, t)
            }
            // Obstacles as circles.
            for (o in obstaclesSnapshot()) {
                val ox = x - o.x
                val oy = y - o.y
                val b = ox * dx + oy * dy
                val c = ox * ox + oy * oy - o.radius * o.radius
                val disc = b * b - c
                if (disc >= 0) {
                    val t = -b - sqrt(disc)
                    if (t > 0.05) best = minOf(best, t)
                }
            }
            val mm = if (best > 4.0) 0 else ((best + n(0.015)) * 1000).roundToInt().coerceIn(1, 4000)
            ang to mm
        }
        return TofSweepPacket(now and 0xFFFFFFFFL, pts)
    }

    companion object {
        const val TICK_MS = 100L
    }
}
