package com.guidehub.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TfDetector

data class RawDetection(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

interface ObjectDetector {
    val available: Boolean
    fun detect(bitmap: Bitmap): List<RawDetection>
    fun close() {}
}

object NoopObjectDetector : ObjectDetector {
    override val available = false
    override fun detect(bitmap: Bitmap) = emptyList<RawDetection>()
}

/**
 * TFLite Task Vision detector (EfficientDet-Lite0 / SSD MobileNet with metadata, COCO labels).
 * The model is not bundled; place it at assets/models/detector.tflite (see docs/models.md).
 */
class TfliteObjectDetector private constructor(private val detector: TfDetector) : ObjectDetector {
    override val available = true

    override fun detect(bitmap: Bitmap): List<RawDetection> =
        detector.detect(TensorImage.fromBitmap(bitmap)).mapNotNull { d ->
            val cat = d.categories.maxByOrNull { it.score } ?: return@mapNotNull null
            val b = d.boundingBox
            RawDetection(cat.label.lowercase(), cat.score, b.left, b.top, b.right, b.bottom)
        }

    override fun close() = detector.close()

    companion object {
        const val ASSET = "models/detector.tflite"

        fun createOrNoop(context: Context): ObjectDetector {
            val exists = runCatching { context.assets.open(ASSET).close(); true }.getOrDefault(false)
            if (!exists) return NoopObjectDetector
            return runCatching {
                val opts = TfDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                    .setMaxResults(8)
                    .setScoreThreshold(0.45f)
                    .build()
                TfliteObjectDetector(TfDetector.createFromFileAndOptions(context, ASSET, opts))
            }.getOrElse { NoopObjectDetector }
        }
    }
}
