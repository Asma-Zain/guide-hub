package com.guidehub.nav

import com.guidehub.TestMaps
import com.guidehub.core.Clock
import com.guidehub.core.MarkerDetection
import com.guidehub.core.PositionUpdate
import com.guidehub.core.TofPoint
import com.guidehub.core.TofSweep
import com.guidehub.core.VoDelta
import com.guidehub.vision.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MapValidatorTest {
    @Test fun demoMapIsValid() {
        val r = MapParser.parse(TestMaps.demoText())
        assertTrue(r.errors.toString(), r.ok)
    }

    @Test fun rejectsBrokenMaps() {
        val bad = """{"building":"X","nodes":[{"id":"A","x":0,"y":0},{"id":"A","x":1,"y":0},{"id":"R","x":5,"y":5,"type":"room"}],
            "edges":[{"from":"A","to":"Z","lengthM":-1,"headingDeg":90}]}"""
        val r = MapParser.parse(bad)
        assertFalse(r.ok)
        assertNull(r.map)
        assertTrue(r.errors.any { "Duplicate node id" in it })
        assertTrue(r.errors.any { "unknown node 'Z'" in it })
        assertTrue(r.errors.any { "positive lengthM" in it })
        assertTrue(r.errors.any { "Room node 'R' has no names" in it })
        assertFalse(MapParser.parse("{not json").ok)
    }

    @Test fun disconnectedMapRejected() {
        val m = """{"building":"X","nodes":[{"id":"A","x":0,"y":0},{"id":"B","x":1,"y":0},{"id":"C","x":9,"y":9}],
            "edges":[{"from":"A","to":"B","lengthM":1,"headingDeg":90}]}"""
        assertTrue(MapParser.parse(m).errors.any { "not connected" in it })
    }

    @Test fun markerSpacingWarnings() {
        val m = """{"building":"X","nodes":[
            {"id":"A","x":0,"y":0,"type":"junction","marker":{"id":1,"facingDeg":90}},
            {"id":"B","x":10,"y":0},{"id":"C","x":25,"y":0,"type":"junction"},
            {"id":"D","x":25,"y":5,"type":"room","names":["Lab"]}],
            "edges":[{"from":"A","to":"B","lengthM":10,"headingDeg":90},{"from":"B","to":"C","lengthM":15,"headingDeg":90},
                     {"from":"C","to":"D","lengthM":5,"headingDeg":0}]}"""
        val r = MapParser.parse(m)
        assertTrue(r.ok)
        assertTrue(r.warnings.toString(), r.warnings.any { it.startsWith("markerSpacing") && "'C'" in it })
        // A..D is one marker-free chain (B and C are pass-through nodes): 10 + 15 + 5 m.
        assertTrue(r.warnings.toString(), r.warnings.any { it.startsWith("markerSpacing: A..D is 30.0 m") })
    }

    @Test fun headingAndLengthConsistencyWarnings() {
        val m = """{"building":"X","nodes":[{"id":"A","x":0,"y":0,"marker":{"id":1,"facingDeg":0}},{"id":"B","x":10,"y":0,"marker":{"id":2,"facingDeg":0}}],
            "edges":[{"from":"A","to":"B","lengthM":5,"headingDeg":0}]}"""
        val r = MapParser.parse(m)
        assertTrue(r.warnings.any { "lengthM" in it })
        assertTrue(r.warnings.any { "headingDeg" in it })
    }
}

class RouterTest {
    private val g = TestMaps.demoGraph()
    private val router = Router(g)

    @Test fun shortestPathAndSteps() {
        val r = router.route("ENT", "R204")!!
        assertEquals(listOf("ENT", "N1", "N2", "N3", "R204"), r.nodes)
        assertEquals(26.0, r.totalM, 1e-9)
        assertEquals(listOf(TurnType.START, TurnType.LEFT, TurnType.ARRIVE), r.steps.map { it.turnType })
        assertEquals(23.0, r.steps[0].distanceM, 1e-9)
        assertEquals("N3", r.steps[0].nextNode)
        assertEquals("notice board on your right", r.steps[0].landmark)
        assertEquals(3.0, r.steps[1].distanceM, 1e-9)
    }

    @Test fun reverseDirectionTurnsAreMirrored() {
        val r = router.route("R204", "R101")!!
        assertEquals(listOf("R204", "N3", "N2", "N1", "R101"), r.nodes)
        // Walk south out of the lab, turn right (west), then left (south) into the library.
        assertEquals(listOf(TurnType.START, TurnType.RIGHT, TurnType.LEFT, TurnType.ARRIVE), r.steps.map { it.turnType })
    }

    @Test fun sameNodeAndUnknown() {
        assertEquals(emptyList<DirectedEdge>(), router.shortestPath("N2", "N2"))
        assertNull(router.shortestPath("N2", "NOPE"))
    }

    @Test fun aStarMatchesExhaustiveDijkstraOnAllPairs() {
        val ids = g.nodes.keys.toList()
        for (a in ids) for (b in ids) {
            val astar = router.shortestPath(a, b)!!.sumOf { it.lengthM }
            assertEquals("$a->$b", dijkstra(a)[b]!!, astar, 1e-9)
        }
    }

    private fun dijkstra(src: String): Map<String, Double> {
        val dist = g.nodes.keys.associateWith { Double.POSITIVE_INFINITY }.toMutableMap()
        dist[src] = 0.0
        val todo = g.nodes.keys.toMutableSet()
        while (todo.isNotEmpty()) {
            val u = todo.minBy { dist[it]!! }
            todo -= u
            g.neighbours(u).forEach { e -> dist[e.to] = minOf(dist[e.to]!!, dist[u]!! + e.lengthM) }
        }
        return dist
    }

    @Test fun turnClassification() {
        assertEquals(TurnType.STRAIGHT, Turns.classify(10.0))
        assertEquals(TurnType.SLIGHT_RIGHT, Turns.classify(40.0))
        assertEquals(TurnType.LEFT, Turns.classify(-90.0))
        assertEquals(TurnType.LEFT, Turns.classify(270.0))
        assertEquals(TurnType.U_TURN, Turns.classify(180.0))
    }
}

/** Synthetic corridor sweeps with known yaw and lateral offsets. */
object Sweeps {
    /**
     * Head at lateral offset [lateral] (+ right of centre) in a corridor of [width], head yaw [yawDeg]
     * relative to the corridor axis (+ right). Optional obstacles as (forward, lateral, radius).
     */
    fun corridor(
        width: Double, yawDeg: Double, lateral: Double = 0.0, range: IntProgression = -60..60 step 5,
        obstacles: List<Triple<Double, Double, Double>> = emptyList(), noise: Double = 0.0, seed: Int = 1, maxRange: Double = 4.0,
    ): TofSweep {
        val rnd = Random(seed)
        val pts = range.map { a ->
            val th = Math.toRadians(yawDeg + a) // ray direction in corridor frame (x right, y forward)
            val dx = sin(th)
            val dy = cos(th)
            var best = Double.POSITIVE_INFINITY
            for (wallX in listOf(-width / 2, width / 2)) {
                if (abs(dx) > 1e-9) { val t = (wallX - lateral) / dx; if (t > 0) best = minOf(best, t) }
            }
            for ((fy, fx, r) in obstacles) {
                val ox = lateral - fx; val oy = -fy
                val b = ox * dx + oy * dy
                val c = ox * ox + oy * oy - r * r
                val disc = b * b - c
                if (disc >= 0) { val t = -b - sqrt(disc); if (t > 0) best = minOf(best, t) }
            }
            val rr = if (best > maxRange) 0.0 else best + if (noise > 0) rnd.nextDouble(-noise, noise) else 0.0
            TofPoint(a.toDouble(), rr)
        }
        return TofSweep(pts, 0)
    }
}

class WallHeadingEstimatorTest {
    private val est = WallHeadingEstimator()

    @Test fun recoversYawOffsetWithinTenDegrees() {
        for (yaw in listOf(-25.0, -10.0, 0.0, 5.0, 15.0, 30.0)) {
            val e = est.estimate(Sweeps.corridor(2.4, yaw, noise = 0.015), 2.4)
            assertNotNull("yaw $yaw", e)
            assertEquals("yaw $yaw", yaw, e!!.yawOffsetDeg, 3.0)
            // Beyond ~20 deg the far-side wall leaves the 4 m sensor range for most of the sweep.
            if (kotlin.math.abs(yaw) <= 15) assertEquals("yaw $yaw", 2, e.wallsSeen)
        }
    }

    @Test fun lateralOffsetFromBothWalls() {
        val e = est.estimate(Sweeps.corridor(2.4, 0.0, lateral = 0.4), 2.4)!!
        assertEquals(0.4, e.lateralOffsetM!!, 0.05)
        assertEquals(1.6, e.leftDistM!!, 0.05)
        assertEquals(0.8, e.rightDistM!!, 0.05)
    }

    @Test fun oneWallFallback() {
        // Only the right half of the sweep is available (left side out of range / occluded).
        val e = est.estimate(Sweeps.corridor(2.4, 10.0, lateral = 0.3, range = 0..60 step 5), 2.4)!!
        assertEquals(1, e.wallsSeen)
        assertEquals(10.0, e.yawOffsetDeg, 3.0)
        assertFalse(e.fullCoverage)
        assertEquals(0.3, e.lateralOffsetM!!, 0.1)
    }

    @Test fun rejectsSweepDominatedByPeople() {
        val crowd = listOf(Triple(0.7, -0.3, 0.25), Triple(0.9, 0.3, 0.25), Triple(0.6, 0.0, 0.25), Triple(1.0, -0.7, 0.3), Triple(1.0, 0.75, 0.3))
        assertNull(est.estimate(Sweeps.corridor(2.4, 0.0, obstacles = crowd), 2.4))
    }

    @Test fun ignoresSingleObstacle() {
        val e = est.estimate(Sweeps.corridor(2.4, 12.0, obstacles = listOf(Triple(2.0, 0.0, 0.25)), noise = 0.01), 2.4)!!
        assertEquals(12.0, e.yawOffsetDeg, 3.0)
    }
}

class VoSolverTest {
    private val k = CameraIntrinsics.DEFAULT_640x480
    private val solver = VoSolver(k, cameraHeightM = 1.6)

    /** Random scene: ground points (y = +h) and wall/far points; camera x right, y down, z forward. */
    private fun scene(seed: Int = 3): List<DoubleArray> {
        val r = Random(seed)
        val ground = List(60) { doubleArrayOf(r.nextDouble(-1.2, 1.2), 1.6, r.nextDouble(1.5, 7.0)) }
        val walls = List(60) { doubleArrayOf(if (r.nextBoolean()) -1.2 else 1.2, r.nextDouble(-1.0, 1.0), r.nextDouble(2.0, 9.0)) }
        return ground + walls
    }

    private fun project(p: DoubleArray) = Pt(k.cx + k.fx * p[0] / p[2], k.cy + k.fy * p[1] / p[2])

    /** Move forward by [t] then yaw right by [yawDeg]. */
    private fun move(p: DoubleArray, t: Double, yawDeg: Double): DoubleArray {
        val x = p[0]; val z = p[2] - t
        val a = Math.toRadians(yawDeg)
        return doubleArrayOf(x * cos(a) - z * sin(a), p[1], x * sin(a) + z * cos(a))
    }

    private fun run(t: Double, yaw: Double): VoResult {
        val pts = scene()
        val prev = pts.map(::project)
        val cur = pts.map { project(move(it, t, yaw)) }
        return solver.solve(prev, cur)
    }

    @Test fun pureRotation() {
        val r = run(0.0, 8.0)
        assertEquals(8.0, r.dYawDeg, 0.5)
        assertEquals(0.0, r.dForwardM, 0.05)
    }

    @Test fun pureForwardWithMetricScaleFromGround() {
        val r = run(0.3, 0.0)
        assertEquals(0.0, r.dYawDeg, 0.5)
        assertEquals(0.3, r.dForwardM, 0.05)
        assertTrue(r.quality > 0.5)
    }

    @Test fun walkAndTurn() {
        val r = run(0.15, -4.0)
        assertEquals(-4.0, r.dYawDeg, 0.7)
        assertEquals(0.15, r.dForwardM, 0.06)
    }

    @Test fun tofScaleUsedWhenGroundUnavailable() {
        val walls = scene().filter { it[1] != 1.6 }
        val prev = walls.map(::project)
        val cur = walls.map { project(move(it, 0.4, 0.0)) }
        val r = solver.solve(prev, cur, tofForwardPrevM = 3.0, tofForwardCurM = 2.6)
        assertEquals(0.4, r.dForwardM, 0.02)
    }

    @Test fun tooFewFeatures() {
        val r = solver.solve(listOf(Pt(1.0, 1.0)), listOf(Pt(2.0, 2.0)))
        assertEquals(0.0, r.quality, 0.0)
    }
}

class PositionEstimatorTest {
    private val g = TestMaps.demoGraph()
    private var now = 0L
    private val clock = Clock { now }

    private fun fixAt(est: PositionEstimator, ux: Double, uy: Double, yaw: Double, markerNode: String) {
        val n = g.nodes.getValue(markerNode)
        val m = n.marker!!
        val range = Geo.dist(ux, uy, n.x, n.y)
        val bearing = Geo.norm180(Geo.bearing(ux, uy, n.x, n.y) - yaw)
        est.onMarker(MarkerDetection(m.id, range, bearing, Geo.norm180(m.facingDeg - yaw), now))
    }

    @Test fun markerFixGivesAbsolutePoseOnGraph() {
        val est = PositionEstimator(g, clock)
        fixAt(est, -2.0, 0.3, 95.0, "N1")
        val p = est.snapshot()!!
        assertEquals("ENT", p.edgeFrom); assertEquals("N1", p.edgeTo)
        assertEquals(1.0, p.s, 0.01)
        assertEquals(-0.3, p.lateralOffsetM, 0.01) // north of an eastbound corridor = left
        assertEquals(95.0, p.yawDeg, 0.01)
        assertEquals(1.0, p.confidence, 0.0)
    }

    @Test fun voAdvancesAlongRouteAndConfidenceDecays() {
        val est = PositionEstimator(g, clock)
        val route = Router(g).route("ENT", "R204")!!
        fixAt(est, -3.0, 0.0, 90.0, "N1")
        est.setRoute(route)
        repeat(50) { now += 100; est.onVo(VoDelta(0.0, 0.1, 0.9, now)) } // 5 m
        val p = est.snapshot()!!
        assertEquals("N1", p.edgeFrom); assertEquals("N2", p.edgeTo)
        assertEquals(2.0, p.s, 1e-6)
        assertEquals(1.0 - 5.0 / 30.0, p.confidence, 1e-6)
    }

    @Test fun markerCorrectsDrift() {
        val est = PositionEstimator(g, clock)
        val route = Router(g).route("ENT", "R204")!!
        fixAt(est, -3.0, 0.0, 90.0, "N1")
        est.setRoute(route)
        // VO over-reports by 20 %: after walking 8 m true, estimate is 9.6 m.
        repeat(80) { now += 100; est.onVo(VoDelta(0.0, 0.12, 0.9, now)) }
        val before = est.position()!!
        assertEquals(6.6, before.first, 0.01)
        // True position x = 5, sees marker N2 ahead.
        fixAt(est, 5.0, 0.0, 90.0, "N2")
        val after = est.position()!!
        assertEquals(5.0, after.first, 0.01)
        assertEquals(1.6, est.lastCorrectionM!!, 0.01)
    }

    @Test fun wallFitCorrectsYawAndRejectsOtherCorridor() {
        val est = PositionEstimator(g, clock)
        fixAt(est, -2.0, 0.0, 90.0, "N1")
        repeat(10) { now += 100; est.onVo(VoDelta(1.0, 0.0, 0.9, now)) } // VO says yaw drifted to 100
        assertEquals(100.0, est.yaw!!, 1e-6)
        val wall = WallHeadingEstimator().estimate(Sweeps.corridor(2.4, 3.0), 2.4)!!
        repeat(5) { est.onWall(wall) }
        assertEquals(93.0, est.yaw!!, 1.5)
        // A fit implying a 60-degree jump belongs to another corridor: ignored.
        est.onWall(wall.copy(yawOffsetDeg = 63.0))
        assertEquals(93.0, est.yaw!!, 1.5)
    }

    @Test fun lookingSidewaysDoesNotCountAsProgress() {
        val est = PositionEstimator(g, clock)
        fixAt(est, -3.0, 0.0, 90.0, "N1")
        now += 100; est.onVo(VoDelta(-85.0, 0.0, 0.9, now)) // head turned to look north
        now += 100; est.onVo(VoDelta(0.0, 0.5, 0.9, now))
        assertEquals(0.0, est.s, 1e-6)
    }

    @Test fun needleSmootherHoldsDuringFastHeadTurns() {
        val s = NeedleSmoother(alpha = 1.0)
        assertEquals(10.0, s.update(10.0, 90.0, 0), 1e-9)
        assertEquals(20.0, s.update(20.0, 91.0, 100), 1e-9)
        // 30 degrees in 100 ms = 300 deg/s: hold.
        assertEquals(20.0, s.update(-60.0, 121.0, 200), 1e-9)
        assertEquals(-60.0, s.update(-60.0, 122.0, 300), 1e-9)
    }

    @Test fun headingSources() {
        val est = PositionEstimator(g, clock)
        assertTrue(HeadbandVisionHeading(est).enabled)
        assertFalse(HeadbandImuHeading().enabled)
        assertNull(HeadbandImuHeading().headingDeg())
    }
}

class NavigationControllerTest {
    private val g = TestMaps.demoGraph()

    private fun pos(from: String, to: String, s: Double, yaw: Double): PositionUpdate {
        val e = g.edge(from, to)!!
        val (x, y) = g.pointOn(e, s)
        return PositionUpdate(from, to, s, x, y, 0.0, yaw, 1.0, 1.0, 0.0, null, 0)
    }

    @Test fun turnByTurnAnnouncementsAndArrival() {
        val nav = NavigationController(g)
        nav.start(Router(g).route("ENT", "R204")!!)
        assertEquals("Walk straight ahead 23 metres, then turn left.", nav.initialInstruction(90.0))
        val said = mutableListOf<String>()
        var arrived = false
        val track = listOf("ENT" to "N1", "N1" to "N2", "N2" to "N3", "N3" to "R204")
        for ((a, b) in track) {
            val e = g.edge(a, b)!!
            var s = 0.0
            while (s <= e.lengthM && !arrived) {
                val gd = nav.update(pos(a, b, s, e.headingDeg))
                said += gd.announcements
                arrived = gd.arrived
                assertFalse(gd.deviated)
                s += 0.25
            }
        }
        assertTrue(arrived)
        assertTrue(said.toString(), said.contains("Water cooler on your left."))
        assertTrue(said.toString(), said.any { it.startsWith("Turn left in 6 metres") && "notice board" in it })
        assertTrue(said.toString(), said.contains("Turn left now."))
        assertTrue(said.toString(), said.any { it.startsWith("You have arrived at Lab 204") })
        assertEquals(1, said.count { it == "Turn left now." })
    }

    @Test fun needlePointsAlongCorridorThenIntoTheTurn() {
        val nav = NavigationController(g)
        nav.start(Router(g).route("ENT", "R204")!!)
        // Head turned 20 degrees right of the corridor: needle should point ~20 degrees left.
        val mid = nav.update(pos("N1", "N2", 5.0, 110.0))
        assertEquals(-20.0, mid.relativeBearingDeg!!, 1.0)
        // 1 m before the junction, facing east: target is the lab to the north-ish (left).
        val nearTurn = nav.update(pos("N2", "N3", 9.0, 90.0))
        assertTrue(nearTurn.relativeBearingDeg!! < -60)
    }

    @Test fun offRouteIsDeviation() {
        val nav = NavigationController(g)
        nav.start(Router(g).route("ENT", "R204")!!)
        assertTrue(nav.update(pos("N3", "R240", 1.0, 180.0)).deviated)
    }
}
