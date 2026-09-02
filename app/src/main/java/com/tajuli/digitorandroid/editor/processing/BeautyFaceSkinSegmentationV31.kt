package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val FACE_SKIN_MODEL_ASSET_V31 = "selfie_multiclass_256x256.tflite"
private const val FACE_SKIN_CLASS_V31 = 3
private const val FACE_SKIN_MASK_LONG_EDGE_V31 = 256

/** One cached semantic face-skin confidence mask from MediaPipe SelfieMulticlass. */
data class BeautyFaceSkinMaskFrameV31(
    val sourceTimeUs: Long,
    val file: File,
)

data class BeautyFaceSkinMaskTrackV31(
    val sourceUri: String,
    val frames: List<BeautyFaceSkinMaskFrameV31>,
) {
    fun nearest(sourceTimeUs: Long): BeautyFaceSkinMaskFrameV31? {
        if (frames.isEmpty()) return null
        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = frames[mid].sourceTimeUs
            when {
                value < sourceTimeUs -> low = mid + 1
                value > sourceTimeUs -> high = mid - 1
                else -> return frames[mid]
            }
        }
        val right = frames.getOrNull(low)
        val left = frames.getOrNull(low - 1)
        return when {
            left == null -> right
            right == null -> left
            abs(sourceTimeUs - left.sourceTimeUs) <= abs(right.sourceTimeUs - sourceTimeUs) -> left
            else -> right
        }
    }
}

/**
 * Semantic face-skin confidence cache. V34 keeps the full 256-pixel model output instead of
 * shrinking it to 192, improving cheeks, jaw, nose and head-cover boundaries without inventing
 * resolution that the model does not provide.
 */
object BeautyFaceSkinMaskStoreV31 {
    private val indexCache = ConcurrentHashMap<String, BeautyFaceSkinMaskTrackV31>()

    fun index(context: Context, clip: TimelineClip): BeautyFaceSkinMaskTrackV31 {
        val key = cacheKey(clip.uri)
        return indexCache[key] ?: loadIndex(context, clip.uri).also { indexCache[key] = it }
    }

    fun hasCoverage(context: Context, clip: TimelineClip): Boolean {
        val track = index(context, clip)
        if (track.frames.isEmpty()) return false
        if (clip.isImageV21) return track.frames.any { it.file.isFile }
        val first = track.frames.firstOrNull()?.sourceTimeUs ?: return false
        val last = track.frames.lastOrNull()?.sourceTimeUs ?: return false
        val requestedEnd = (clip.sourceOutUs - 1L).coerceAtLeast(clip.sourceInUs)
        return first <= clip.sourceInUs && last >= requestedEnd
    }

    fun save(context: Context, sourceUri: String, sourceTimeUs: Long, mask: Bitmap): File {
        val dir = sourceDir(context, sourceUri).apply { mkdirs() }
        val target = File(dir, "$sourceTimeUs.png")
        val temp = File(dir, "$sourceTimeUs.png.tmp")
        temp.outputStream().buffered().use { stream ->
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode semantic face-skin confidence mask" }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not install semantic face-skin confidence mask" }
        indexCache.remove(cacheKey(sourceUri))
        return target
    }

    private fun loadIndex(context: Context, sourceUri: String): BeautyFaceSkinMaskTrackV31 {
        val frames = sourceDir(context, sourceUri).listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .mapNotNull { file -> file.nameWithoutExtension.toLongOrNull()?.let { BeautyFaceSkinMaskFrameV31(it, file) } }
            .sortedBy { it.sourceTimeUs }
            .toList()
        return BeautyFaceSkinMaskTrackV31(sourceUri, frames)
    }

    private fun sourceDir(context: Context, sourceUri: String): File =
        File(File(context.filesDir, "beauty_face_skin_masks_v34_confidence_256"), cacheKey(sourceUri))

    private fun cacheKey(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceUri.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
}

/** Dedicated face-skin segmenter backed by Google's MediaPipe SelfieMulticlass model. */
class BeautyFaceSkinSegmenterV31(context: Context) : AutoCloseable {
    private val segmenter: ImageSegmenter

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(FACE_SKIN_MODEL_ASSET_V31)
            .setDelegate(Delegate.CPU)
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context.applicationContext, options)
    }

    fun segmentAndStore(context: Context, clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
        val masks = result.confidenceMasks().orElse(emptyList())
        val mpMask = masks.getOrNull(FACE_SKIN_CLASS_V31) ?: return false
        val width = mpMask.width.coerceAtLeast(1)
        val height = mpMask.height.coerceAtLeast(1)
        val confidences = ByteBufferExtractor.extract(mpMask).asFloatBuffer()
        confidences.rewind()
        if (confidences.remaining() < width * height) return false

        val alpha = IntArray(width * height)
        for (index in alpha.indices) {
            val confidence = confidences.get().coerceIn(0f, 1f)
            val x = ((confidence - .05f) / .84f).coerceIn(0f, 1f)
            val smooth = x * x * (3f - 2f * x)
            alpha[index] = (smooth * 255f).roundToInt().coerceIn(0, 255)
        }

        val softened = blurMask(alpha, width, height)
        val pixels = IntArray(width * height) { index ->
            val value = softened[index].coerceIn(0, 255)
            Color.argb(255, value, value, value)
        }
        val fullMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fullMask.setPixels(pixels, 0, width, 0, 0, width, height)
        val longEdge = max(width, height)
        val storedMask = if (longEdge <= FACE_SKIN_MASK_LONG_EDGE_V31) {
            fullMask
        } else {
            val scale = FACE_SKIN_MASK_LONG_EDGE_V31.toFloat() / longEdge.toFloat()
            Bitmap.createScaledBitmap(
                fullMask,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        try {
            BeautyFaceSkinMaskStoreV31.save(context.applicationContext, clip.uri, sourceTimeUs, storedMask)
        } finally {
            if (storedMask !== fullMask) storedMask.recycle()
            fullMask.recycle()
        }
        return true
    }

    private fun blurMask(source: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = source[y * width + x] * 4
                var weight = 4
                if (x > 0) { sum += source[y * width + x - 1]; weight++ }
                if (x + 1 < width) { sum += source[y * width + x + 1]; weight++ }
                if (y > 0) { sum += source[(y - 1) * width + x]; weight++ }
                if (y + 1 < height) { sum += source[(y + 1) * width + x]; weight++ }
                out[y * width + x] = sum / weight
            }
        }
        return out
    }

    override fun close() {
        segmenter.close()
    }
}
