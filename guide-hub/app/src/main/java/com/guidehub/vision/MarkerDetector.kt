package com.guidehub.vision

import com.guidehub.core.DataSource
import com.guidehub.core.MarkerDetection
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.QRCodeDetector
import kotlin.math.atan2
import kotlin.math.sqrt

/** A detected marker with its image corners (for the debug overlay). */
data class MarkerObservation(val detection: MarkerDetection, val corners: List<Pair<Double, Double>>, val kind: String)

/**
 * ArUco / AprilTag detection with pose from solvePnP (IPPE_SQUARE) using the calibrated intrinsics.
 * QR codes are a fallback: payload "GH:<id>" (e.g. GH:12) maps to the same marker ids.
 */
class MarkerDetector(
    private var intrinsics: CameraIntrinsics,
    dictionaryName: String = "DICT_APRILTAG_36h11",
    private val markerSizeM: (Int) -> Double = { 0.15 },
    private val enableQr: Boolean = true,
) {
    private val detector = ArucoDetector(Objdetect.getPredefinedDictionary(dictionaryId(dictionaryName)), DetectorParameters())
    private val qr = QRCodeDetector()

    fun detect(gray: Mat, frameTs: Long): List<MarkerObservation> {
        if (gray.cols() != intrinsics.width || gray.rows() != intrinsics.height) intrinsics = intrinsics.scaledTo(gray.cols(), gray.rows())
        val out = mutableListOf<MarkerObservation>()
        val corners = mutableListOf<Mat>()
        val ids = Mat()
        detector.detectMarkers(gray, corners, ids)
        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)[0].toInt()
            val pts = FloatArray(8)
            corners[i].get(0, 0, pts)
            val c = (0 until 4).map { pts[2 * it].toDouble() to pts[2 * it + 1].toDouble() }
            pose(id, c, frameTs)?.let { out += MarkerObservation(it, c, "aruco") }
        }
        corners.forEach { it.release() }
        ids.release()

        if (enableQr && out.isEmpty()) {
            val points = Mat()
            val text = runCatching { qr.detectAndDecode(gray, points) }.getOrNull()
            val id = text?.removePrefix("GH:")?.toIntOrNull()
            if (id != null && text.startsWith("GH:") && points.total() >= 4) {
                val p = FloatArray(8)
                points.reshape(2, 4).get(0, 0, p)
                val c = (0 until 4).map { p[2 * it].toDouble() to p[2 * it + 1].toDouble() }
                pose(id, c, frameTs)?.let { out += MarkerObservation(it, c, "qr") }
            }
            points.release()
        }
        return out
    }

    /** Corners in order TL, TR, BR, BL. */
    private fun pose(id: Int, c: List<Pair<Double, Double>>, ts: Long): MarkerDetection? {
        val s = markerSizeM(id) / 2
        val obj = MatOfPoint3f(Point3(-s, s, 0.0), Point3(s, s, 0.0), Point3(s, -s, 0.0), Point3(-s, -s, 0.0))
        val img = MatOfPoint2f(*c.map { Point(it.first, it.second) }.toTypedArray())
        val k = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, intrinsics.fx, 0.0, intrinsics.cx, 0.0, intrinsics.fy, intrinsics.cy, 0.0, 0.0, 1.0)
        }
        val dist = MatOfDouble(*intrinsics.distortion.toDoubleArray())
        val rvec = Mat()
        val tvec = Mat()
        try {
            if (!Calib3d.solvePnP(obj, img, k, dist, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)) return null
            val t = DoubleArray(3) { tvec.get(it, 0)[0] }
            val r = Mat()
            Calib3d.Rodrigues(rvec, r)
            // Marker normal (+z of the marker frame) in camera coordinates; viewing direction is its opposite.
            val nx = r.get(0, 2)[0]
            val nz = r.get(2, 2)[0]
            r.release()
            val range = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
            val bearing = Math.toDegrees(atan2(t[0], t[2]))
            val markerYaw = Math.toDegrees(atan2(-nx, -nz))
            return MarkerDetection(id, range, bearing, markerYaw, ts, DataSource.PHONE)
        } finally {
            obj.release(); img.release(); k.release(); dist.release(); rvec.release(); tvec.release()
        }
    }

    companion object {
        fun dictionaryId(name: String): Int = when (name) {
            "DICT_4X4_50" -> Objdetect.DICT_4X4_50
            "DICT_4X4_100" -> Objdetect.DICT_4X4_100
            "DICT_5X5_50" -> Objdetect.DICT_5X5_50
            "DICT_6X6_50" -> Objdetect.DICT_6X6_50
            "DICT_APRILTAG_16h5" -> Objdetect.DICT_APRILTAG_16h5
            "DICT_APRILTAG_25h9" -> Objdetect.DICT_APRILTAG_25h9
            "DICT_APRILTAG_36h10" -> Objdetect.DICT_APRILTAG_36h10
            else -> Objdetect.DICT_APRILTAG_36h11
        }
    }
}
