package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val PERSON_SEMANTIC_MODEL_ASSET_V54 = "selfie_multiclass_256x256.tflite"

internal data class PersonRegionV55(
    val bounds: RectF,
    val gate: Bitmap,
)

/**
 * Lightweight person localizer for PP-MattingV2 ROI selection.
 *
 * SelfieMulticlass is NOT used as the final cutout. We keep only its largest plausible connected
 * person component, use that component to localize the subject, and build a deliberately generous
 * dilated gate. The gate is only a background veto after PP-MattingV2: PP-MattingV2 still supplies
 * all final edge/alpha detail inside the allowed human envelope.
 *
 * The Symphony Z60 / T606 UNISOC family is kept on the CPU delegate for this lightweight localizer.
 * On those devices a MediaPipe GPU delegate can be created successfully and then die inside the
 * vendor driver during the first segment() call, which is process-fatal and cannot be caught by the
 * Kotlin fallback below. PP-MattingV2 itself remains on ncnn Vulkan; only ROI localization is CPU.
 */
internal class FastPersonSemanticSegmenterV54(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var segmenter: ImageSegmenter
    var usingGpuDelegate: Boolean
        private set

    init {
        if (isUnsafeUnisocGpuDelegateDevice()) {
            segmenter = createSegmenter(appContext, Delegate.CPU)
            usingGpuDelegate = false
        } else {
            val gpu = runCatching { createSegmenter(appContext, Delegate.GPU) }
            if (gpu.isSuccess) {
                segmenter = gpu.getOrThrow()
                usingGpuDelegate = true
            } else {
                segmenter = createSegmenter(appContext, Delegate.CPU)
                usingGpuDelegate = false
            }
        }
    }

    /** Returns one localized human region in source-bitmap coordinates, or null when not confident. */
    fun detectPersonRegion(bitmap: Bitmap): PersonRegionV55? {
        val result = runCatching { segmenter.segment(BitmapImageBuilder(bitmap).build()) }
            .getOrElse { error ->
                if (!usingGpuDelegate) throw error
                runCatching { segmenter.close() }
                segmenter = createSegmenter(appContext, Delegate.CPU)
                usingGpuDelegate = false
                segmenter.segment(BitmapImageBuilder(bitmap).build())
            }

        val mpMask = result.categoryMask().orElse(null) ?: return null
        val width = mpMask.width.coerceAtLeast(1)
        val height = mpMask.height.coerceAtLeast(1)
        val count = width * height
        val categories = ByteBufferExtractor.extract(mpMask)
        categories.rewind()
        if (categories.remaining() < count) return null

        val labels = ByteArray(count)
        categories.get(labels)
        val visited = BooleanArray(count)
        val componentQueue = IntArray(count)

        val frameCx = (width - 1) * .5f
        val frameCy = (height - 1) * .5f
        val frameDiag = sqrt((width * width + height * height).toFloat()).coerceAtLeast(1f)
        val minComponentArea = max(32, (count * .004f).toInt())

        var bestScore = -1f
        var bestArea = 0
        var bestLeft = 0
        var bestTop = 0
        var bestRight = 0
        var bestBottom = 0
        var bestPixels = IntArray(0)

        for (start in 0 until count) {
            if (visited[start]) continue
            visited[start] = true
            if ((labels[start].toInt() and 0xFF) == 0) continue

            var head = 0
            var tail = 0
            componentQueue[tail++] = start
            var area = 0
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val index = componentQueue[head++]
                val y = index / width
                val x = index - y * width
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) tail = enqueuePerson(index - 1, labels, visited, componentQueue, tail)
                if (x + 1 < width) tail = enqueuePerson(index + 1, labels, visited, componentQueue, tail)
                if (y > 0) tail = enqueuePerson(index - width, labels, visited, componentQueue, tail)
                if (y + 1 < height) tail = enqueuePerson(index + width, labels, visited, componentQueue, tail)
            }

            if (area < minComponentArea || maxX < minX || maxY < minY) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < width * .05f || boxH < height * .10f) continue

            val cx = sumX.toFloat() / area.toFloat()
            val cy = sumY.toFloat() / area.toFloat()
            val centerDistance = sqrt((cx - frameCx) * (cx - frameCx) + (cy - frameCy) * (cy - frameCy))
            val centerBonus = 1f + .30f * (1f - (centerDistance / frameDiag).coerceIn(0f, 1f))
            val verticalBonus = 1f + .12f * (boxH.toFloat() / height.toFloat()).coerceIn(0f, 1f)
            val score = area.toFloat() * centerBonus * verticalBonus

            if (score > bestScore) {
                bestScore = score
                bestArea = area
                bestLeft = minX
                bestTop = minY
                bestRight = maxX + 1
                bestBottom = maxY + 1
                bestPixels = componentQueue.copyOf(tail)
            }
        }

        if (bestScore <= 0f || bestArea < minComponentArea || bestPixels.isEmpty()) return null

        val smallGate = buildDilatedGate(width, height, bestPixels)
        val fullGate = if (smallGate.width == bitmap.width && smallGate.height == bitmap.height) {
            smallGate.copy(Bitmap.Config.ARGB_8888, false) ?: smallGate
        } else {
            Bitmap.createScaledBitmap(smallGate, bitmap.width, bitmap.height, true)
        }
        if (fullGate !== smallGate && !smallGate.isRecycled) smallGate.recycle()

        val scaleX = bitmap.width.toFloat() / width.toFloat()
        val scaleY = bitmap.height.toFloat() / height.toFloat()
        return PersonRegionV55(
            bounds = RectF(
                bestLeft * scaleX,
                bestTop * scaleY,
                bestRight * scaleX,
                bestBottom * scaleY,
            ),
            gate = fullGate,
        )
    }

    private fun buildDilatedGate(width: Int, height: Int, component: IntArray): Bitmap {
        val count = width * height
        val radius = max(5, min(width, height) / 32)
        val innerRadius = max(1, radius - 2)
        val distance = IntArray(count) { Int.MAX_VALUE }
        val queue = IntArray(count)
        var head = 0
        var tail = 0

        for (index in component) {
            if (index !in 0 until count || distance[index] == 0) continue
            distance[index] = 0
            queue[tail++] = index
        }

        while (head < tail) {
            val index = queue[head++]
            val d = distance[index]
            if (d >= radius) continue
            val y = index / width
            val x = index - y * width
            val nextDistance = d + 1

            fun offer(next: Int) {
                if (distance[next] <= nextDistance) return
                distance[next] = nextDistance
                queue[tail++] = next
            }

            if (x > 0) offer(index - 1)
            if (x + 1 < width) offer(index + 1)
            if (y > 0) offer(index - width)
            if (y + 1 < height) offer(index + width)
            if (x > 0 && y > 0) offer(index - width - 1)
            if (x + 1 < width && y > 0) offer(index - width + 1)
            if (x > 0 && y + 1 < height) offer(index + width - 1)
            if (x + 1 < width && y + 1 < height) offer(index + width + 1)
        }

        val pixels = IntArray(count)
        for (i in 0 until count) {
            val d = distance[i]
            val value = when {
                d <= innerRadius -> 255
                d == innerRadius + 1 -> 176
                d <= radius -> 88
                else -> 0
            }
            pixels[i] = Color.argb(255, value, value, value)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun enqueuePerson(
        index: Int,
        labels: ByteArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (visited[index]) return tail
        visited[index] = true
        if ((labels[index].toInt() and 0xFF) == 0) return tail
        queue[tail] = index
        return tail + 1
    }

    override fun close() {
        segmenter.close()
    }

    private companion object {
        fun isUnsafeUnisocGpuDelegateDevice(): Boolean {
            val model = Build.MODEL.orEmpty().lowercase()
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            val hardware = Build.HARDWARE.orEmpty().lowercase()
            val board = Build.BOARD.orEmpty().lowercase()
            val device = Build.DEVICE.orEmpty().lowercase()
            val product = Build.PRODUCT.orEmpty().lowercase()
            val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
            val combined = listOf(model, manufacturer, hardware, board, device, product, fingerprint).joinToString(" ")

            return model.contains("z60") ||
                combined.contains("t606") ||
                combined.contains("unisoc") ||
                combined.contains("spreadtrum") ||
                hardware.startsWith("ums") ||
                hardware.startsWith("sp") && manufacturer.contains("symphony")
        }

        fun createSegmenter(context: Context, delegate: Delegate): ImageSegmenter {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(PERSON_SEMANTIC_MODEL_ASSET_V54)
                .setDelegate(delegate)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            return ImageSegmenter.createFromOptions(context, options)
        }
    }
}
