package com.guidehub.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.guidehub.core.CameraObject
import com.guidehub.core.Clock
import com.guidehub.core.DataSource
import com.guidehub.core.EventBus
import com.guidehub.core.FrameQuality
import com.guidehub.core.LogEvent
import com.guidehub.core.ObjectDetections
import com.guidehub.core.VoDelta
import com.guidehub.link.Frame
import com.guidehub.link.FrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Latest frame + overlays for the debug screen (updated at most ~2 Hz). */
data class DebugFrame(
    val bitmap: Bitmap,
    val markers: List<MarkerObservation>,
    val objects: List<RawDetection>,
    val brightness: Double,
    val sharpness: Double,
    val ts: Long,
)

/**
 * Phone-side vision on headband frames (FR-5/FR-6): ArUco/QR markers, LK visual odometry,
 * low-light/blur quality, and throttled object detection. Results go on the event bus in the same
 * models the Pi would send over BLE. Frames are written to disk only if [frameDir] returns a folder
 * (explicit debug recording).
 */
class FrameProcessor(
    private val scope: CoroutineScope,
    private val source: FrameSource,
    private val bus: EventBus,
    private val clock: Clock,
    intrinsics: CameraIntrinsics,
    private val markerDetector: MarkerDetector,
    private val objectDetector: ObjectDetector,
    private val tofForwardM: () -> Double?,
    private val objectFps: () -> Int,
    private val frameDir: () -> File?,
) {
    private val tracker = FeatureTracker(intrinsics)
    private val _debug = MutableStateFlow<DebugFrame?>(null)
    val debugFrame: StateFlow<DebugFrame?> = _debug.asStateFlow()
    private var job: Job? = null
    private var lastObjectTs = 0L
    private var lastDebugTs = 0L
    private var lastObjects: List<RawDetection> = emptyList()
    private var lastFrameTs = 0L
    var processedFrames = 0L; private set
    @Volatile var lastProcessingMs = 0L; private set

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            source.frames.conflate().collect { f ->
                try { process(f) } catch (e: Exception) {
                    bus.emit(LogEvent("vision", "Frame ${f.header.seq} failed: ${e.message}", clock.now()))
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; tracker.reset() }

    private fun process(f: Frame) {
        val t0 = clock.now()
        val now = t0
        if (lastFrameTs != 0L && now - lastFrameTs > 1_000) tracker.reset() // gap: do not integrate across it
        lastFrameTs = now
        frameDir()?.let { dir -> dir.mkdirs(); File(dir, "%08d.jpg".format(f.header.seq)).writeBytes(f.jpeg) }

        val buf = MatOfByte(*f.jpeg)
        val gray: Mat = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_GRAYSCALE)
        buf.release()
        if (gray.empty()) { gray.release(); return }
        try {
            val markers = markerDetector.detect(gray, now)
            markers.forEach { bus.emit(it.detection) }

            tracker.track(gray, tofForwardM())?.let { vo ->
                bus.emit(VoDelta(vo.dYawDeg, vo.dForwardM, vo.quality, now, DataSource.PHONE))
            }

            val (brightness, sharpness) = FrameQualityAnalyzer.analyze(gray)
            if (processedFrames % 5 == 0L) {
                bus.emit(FrameQuality(brightness < FrameQualityAnalyzer.LOW_LIGHT_MEAN, sharpness < FrameQualityAnalyzer.BLUR_LAPLACIAN_VAR, brightness, sharpness, now))
            }

            var bitmap: Bitmap? = null
            val fps = objectFps().coerceIn(1, 15)
            if (objectDetector.available && now - lastObjectTs >= 1_000 / fps) {
                lastObjectTs = now
                bitmap = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)
                if (bitmap != null) {
                    val raw = objectDetector.detect(bitmap)
                    lastObjects = raw
                    bus.emit(ObjectDetections(raw.map { toCameraObject(it, bitmap.width, bitmap.height, markerDetectorIntrinsics(gray)) }, now))
                }
            }
            if (now - lastDebugTs >= 500) {
                lastDebugTs = now
                val bmp = bitmap ?: BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)
                if (bmp != null) _debug.value = DebugFrame(bmp, markers, lastObjects, brightness, sharpness, now)
            }
            processedFrames++
        } finally {
            gray.release()
            lastProcessingMs = clock.now() - t0
        }
    }

    private var cachedIntrinsics = intrinsics
    private fun markerDetectorIntrinsics(gray: Mat): CameraIntrinsics {
        if (cachedIntrinsics.width != gray.cols() || cachedIntrinsics.height != gray.rows()) cachedIntrinsics = cachedIntrinsics.scaledTo(gray.cols(), gray.rows())
        return cachedIntrinsics
    }

    private fun toCameraObject(d: RawDetection, w: Int, h: Int, k: CameraIntrinsics): CameraObject {
        val cx = (d.left + d.right) / 2.0 * (k.width.toDouble() / w)
        return CameraObject(
            label = d.label,
            score = d.score,
            bearingDeg = k.bearingOfColumn(cx),
            boxHeightPx = (d.bottom - d.top).toDouble() * (k.height.toDouble() / h),
            frameHeightPx = k.height,
            top = d.top / h.toDouble(),
            bottom = d.bottom / h.toDouble(),
        )
    }
}
