package com.guidehub.vision

import com.guidehub.audio.HazardAnnouncer
import com.guidehub.core.CameraObject
import com.guidehub.core.Hazard
import com.guidehub.core.HazardType
import com.guidehub.core.HeightZone
import com.guidehub.core.ObjectDetections
import com.guidehub.core.Severity
import com.guidehub.nav.Sweeps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardEngineTest {
    private val k = CameraIntrinsics.DEFAULT_640x480
    private val engine = HazardEngine(DistanceFusion(k), HazardConfig(escalateHeadTorso = false))
    private val escalating = HazardEngine(DistanceFusion(k), HazardConfig(escalateHeadTorso = true))

    @Test fun severityBands() {
        assertEquals(Severity.CRITICAL, engine.severity(0.9, HeightZone.GROUND))
        assertEquals(Severity.WARNING, engine.severity(1.5, HeightZone.GROUND))
        assertEquals(Severity.INFO, engine.severity(3.0, HeightZone.GROUND))
        assertEquals(Severity.NONE, engine.severity(3.6, HeightZone.GROUND))
    }

    @Test fun headAndTorsoEscalateOneLevel() {
        assertEquals(Severity.WARNING, escalating.severity(3.0, HeightZone.HEAD))
        assertEquals(Severity.CRITICAL, escalating.severity(1.5, HeightZone.TORSO))
        assertEquals(Severity.INFO, escalating.severity(3.0, HeightZone.GROUND))
        assertEquals(Severity.NONE, escalating.severity(5.0, HeightZone.HEAD))
    }

    @Test fun wallsAreNotHazardsButObstaclesInPathAre() {
        assertTrue(engine.evaluate(Sweeps.corridor(2.4, 0.0), null, 0).isEmpty())
        val hz = engine.evaluate(Sweeps.corridor(2.4, 0.0, obstacles = listOf(Triple(1.45, 0.0, 0.25))), null, 0)
        assertEquals(1, hz.size)
        assertEquals("obstacle", hz[0].label)
        assertEquals(1.2, hz[0].rangeM, 0.05)
        assertEquals(Severity.WARNING, hz[0].severity)
        assertTrue(hz[0].rangeFromTof)
    }

    @Test fun cameraLabelAttachesToTofRange() {
        val sweep = Sweeps.corridor(2.4, 0.0, obstacles = listOf(Triple(1.45, 0.2, 0.25)))
        val person = CameraObject("person", 0.9f, 8.0, 300.0, 480, 0.2, 0.9)
        val hz = engine.evaluate(sweep, ObjectDetections(listOf(person), 0), 0)
        assertEquals("person", hz.single().label)
        assertEquals(HazardType.PERSON, hz.single().type)
        assertEquals(HeightZone.TORSO, hz.single().heightZone)
        assertTrue(hz.single().rangeM < 1.3) // ToF range wins over the camera estimate
    }

    @Test fun cameraOnlyWhenTofUnhealthyRaisesCaution() {
        // person box 440 px tall -> 1.65 * 530 / 440 = 1.99 m
        val person = CameraObject("person", 0.9f, 0.0, 440.0, 480, 0.04, 0.96)
        val hz = engine.evaluate(null, ObjectDetections(listOf(person), 0), 0, tofHealthy = false)
        assertEquals(1, hz.size)
        assertEquals(1.99, hz[0].rangeM, 0.05)
        assertTrue(!hz[0].rangeFromTof)
        // 1.99 m is past the 2.0 m * 1.3 caution threshold -> WARNING rather than INFO.
        assertEquals(Severity.WARNING, hz[0].severity)
    }

    @Test fun lowGroundObjectsComeFromCameraEvenWithTof() {
        val bench = CameraObject("bench", 0.8f, 3.0, 200.0, 480, 0.7, 0.95)
        val hz = engine.evaluate(Sweeps.corridor(2.4, 0.0), ObjectDetections(listOf(bench), 0), 0)
        assertEquals(HeightZone.GROUND, hz.single().heightZone)
        assertEquals(0.45 * 530 / 200.0, hz.single().rangeM, 0.01)
    }

    @Test fun distanceFusionSizeHeuristic() {
        val f = DistanceFusion(k)
        assertEquals(1.65 * 530 / 265.0, f.cameraRange(CameraObject("person", 1f, 0.0, 265.0, 480, 0.0, 1.0))!!, 1e-6)
        // Half-resolution frame: focal length scales with it.
        assertEquals(1.65 * 265 / 132.5, f.cameraRange(CameraObject("person", 1f, 0.0, 132.5, 240, 0.0, 1.0))!!, 1e-6)
        assertEquals(null, f.cameraRange(CameraObject("unicorn", 1f, 0.0, 100.0, 480, 0.0, 1.0)))
    }
}

class HazardAnnouncerTest {
    private fun h(label: String, r: Double, sev: Severity, b: Double = 0.0) = Hazard(HazardType.PERSON, label, r, b, HeightZone.TORSO, sev, 0)

    @Test fun cooldownAndPersistence() {
        val a = HazardAnnouncer(cooldownMs = 3_000)
        assertEquals(1, a.update(listOf(h("person", 1.8, Severity.WARNING)), 0).size)
        // Same hazard persists at the same severity: silent, even after the cooldown.
        assertEquals(0, a.update(listOf(h("person", 1.7, Severity.WARNING)), 1_000).size)
        assertEquals(0, a.update(listOf(h("person", 1.6, Severity.WARNING)), 5_000).size)
        // Severity rises: speak immediately.
        assertEquals(1, a.update(listOf(h("person", 0.9, Severity.CRITICAL)), 5_100).size)
        // Gone, then back within the cooldown: silent; after it: spoken.
        a.update(emptyList(), 5_200)
        assertEquals(0, a.update(listOf(h("person", 0.9, Severity.CRITICAL)), 6_000).size)
        a.update(emptyList(), 6_100)
        assertEquals(1, a.update(listOf(h("person", 0.9, Severity.CRITICAL)), 9_000).size)
    }

    @Test fun unlabelledAndInfoAreNotSpoken() {
        val a = HazardAnnouncer()
        assertEquals(0, a.update(listOf(h("obstacle", 0.5, Severity.CRITICAL)), 0).size)
        assertEquals(0, a.update(listOf(h("chair", 3.0, Severity.INFO)), 0).size)
    }

    @Test fun phrases() {
        assertEquals("Person ahead, one metre.", HazardAnnouncer.phrase(h("person", 1.1, Severity.CRITICAL)))
        assertEquals("Chair slightly left, 1.5 metres.", HazardAnnouncer.phrase(h("chair", 1.4, Severity.WARNING, -20.0)))
        assertEquals("Pole on your right, very close.", HazardAnnouncer.phrase(h("pole", 0.5, Severity.CRITICAL, 50.0)))
    }
}
