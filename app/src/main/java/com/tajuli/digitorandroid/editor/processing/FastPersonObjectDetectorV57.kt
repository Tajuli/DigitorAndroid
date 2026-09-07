package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

private const val PERSON_DETECTOR_MODEL_ASSET_V57 = "efficientdet_lite0_int8.tflite"

internal data class PersonDetectionV57(
    val bounds: RectF,
    val score: Float,
)

/**
 * Real object detector used only for person ROI localization.
 *
 * This intentionally does not use SelfieMulticlass as a detector or final matte. EfficientDet
 * returns actual object bounding boxes; PP-MattingV2 remains responsible for the alpha matte.
 * CPU is deliberate here: the ROI detector is lightweight and this avoids vendor GPU-delegate
 * instability on the Symphony Z60 / UNISOC T606 while PP-MattingV2 itself stays on ncnn Vulkan.
 */
internal class FastPersonObjectDetectorV57(context: Context) : AutoCloseable {
    private val detector = ObjectDetector.createFromOptions(
        context.applicationContext,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(PERSON_DETECTOR_MODEL_ASSET_V57)
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(5)
            .setScoreThreshold(.25f)
            .build(),
    )

    val backendLabel: String = "EfficientDet-Lite0 int8 CPU"

    fun detectPeople(bitmap: Bitmap): List<PersonDetectionV57> {
        check(!bitmap.isRecycled) { "Cannot detect a person on a recycled bitmap" }
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        return result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            if (!category.categoryName().equals("person", ignoreCase = true)) return@mapNotNull null

            val box = detection.boundingBox()
            val left = box.left.coerceIn(0f, bitmap.width.toFloat())
            val top = box.top.coerceIn(0f, bitmap.height.toFloat())
            val right = box.right.coerceIn(0f, bitmap.width.toFloat())
            val bottom = box.bottom.coerceIn(0f, bitmap.height.toFloat())
            if (right - left < 2f || bottom - top < 2f) return@mapNotNull null

            PersonDetectionV57(
                bounds = RectF(left, top, right, bottom),
                score = category.score(),
            )
        }
    }

    override fun close() {
        detector.close()
    }
}
