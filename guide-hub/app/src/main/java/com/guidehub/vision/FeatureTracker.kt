package com.guidehub.vision

import com.guidehub.nav.Pt
import com.guidehub.nav.VoResult
import com.guidehub.nav.VoSolver
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Sparse optical flow (Lucas-Kanade) between consecutive frames, RANSAC homography to reject
 * outliers (moving people, noise), then [VoSolver] for yaw and metric forward progress.
 */
class FeatureTracker(intrinsics: CameraIntrinsics) {
    private var solver = VoSolver(intrinsics, intrinsics.cameraHeightM)
    private var base = intrinsics
    private var prevGray: Mat? = null
    private var prevPts: MatOfPoint2f? = null
    private var prevTof: Double? = null

    fun reset() {
        prevGray?.release(); prevGray = null
        prevPts?.release(); prevPts = null
        prevTof = null
    }

    fun track(gray: Mat, tofForwardM: Double?): VoResult? {
        if (gray.cols() != base.width || gray.rows() != base.height) {
            base = base.scaledTo(gray.cols(), gray.rows())
            solver = VoSolver(base, base.cameraHeightM)
            reset()
        }
        val pg = prevGray
        var pp = prevPts
        if (pg == null) {
            prevGray = gray.clone()
            prevPts = detect(gray)
            prevTof = tofForwardM
            return null
        }
        if (pp == null || pp.total() < 40) { pp?.release(); pp = detect(pg) }
        if (pp.total() < 8) {
            pg.release(); prevGray = gray.clone(); prevPts = detect(gray); prevTof = tofForwardM
            return VoResult(0.0, 0.0, 0.0, 0)
        }
        val next = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        Video.calcOpticalFlowPyrLK(pg, gray, pp, next, status, err)
        val a = pp.toArray()
        val b = next.toArray()
        val st = status.toArray()
        val prevOk = ArrayList<Point>()
        val curOk = ArrayList<Point>()
        for (i in st.indices) if (st[i].toInt() == 1) { prevOk += a[i]; curOk += b[i] }

        var prevIn: List<Point> = prevOk
        var curIn: List<Point> = curOk
        if (prevOk.size >= 8) {
            val mask = Mat()
            val p1 = MatOfPoint2f(*prevOk.toTypedArray())
            val p2 = MatOfPoint2f(*curOk.toTypedArray())
            val h = Calib3d.findHomography(p1, p2, Calib3d.RANSAC, 3.0, mask)
            if (!h.empty() && mask.rows() == prevOk.size) {
                val m = ByteArray(mask.rows()).also { mask.get(0, 0, it) }
                prevIn = prevOk.filterIndexed { i, _ -> m[i].toInt() != 0 }
                curIn = curOk.filterIndexed { i, _ -> m[i].toInt() != 0 }
            }
            h.release(); mask.release(); p1.release(); p2.release()
        }
        val result = solver.solve(prevIn.map { Pt(it.x, it.y) }, curIn.map { Pt(it.x, it.y) }, prevTof, tofForwardM)

        pg.release()
        pp.release()
        status.release(); err.release()
        prevGray = gray.clone()
        prevPts = if (curOk.size >= 40) MatOfPoint2f(*curOk.toTypedArray()).also { next.release() } else { next.release(); detect(gray) }
        prevTof = tofForwardM
        return result
    }

    private fun detect(gray: Mat): MatOfPoint2f {
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(gray, corners, 150, 0.01, 10.0)
        val out = MatOfPoint2f(*corners.toArray().map { Point(it.x, it.y) }.toTypedArray())
        corners.release()
        return out
    }
}

/** Low-light / motion-blur flags (mean brightness, variance of Laplacian). */
object FrameQualityAnalyzer {
    fun analyze(gray: Mat): Pair<Double, Double> {
        val mean = Core.mean(gray).`val`[0]
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val m = MatOfDouble()
        val sd = MatOfDouble()
        Core.meanStdDev(lap, m, sd)
        val variance = sd.toArray()[0].let { it * it }
        lap.release(); m.release(); sd.release()
        return mean to variance
    }

    const val LOW_LIGHT_MEAN = 40.0
    const val BLUR_LAPLACIAN_VAR = 60.0
}
