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

private const val FACE_SKIN_MODEL_ASSET_V31 = "selfie_multiclass_256x256.tflite"
private const val FACE_SKIN_CLASS_V31 = 3
private const val FACE_SKIN_MASK_LONG_EDGE_V31 = 192

/** One cached semantic face-skin mask from MediaPipe SelfieMulticlass. */
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
 * Semantic face-skin cache. The multiclass model explicitly separates face skin from hair,
 * clothing and accessories, avoiding the pink-hijab/skin-color false positives of the old YCbCr
 * heuristic. Stored masks are softly feathered before GPU upload so the beauty edge is invisible.
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
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode semantic face-skin mask" }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not install semantic face-skin mask" }
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
        File(File(context.filesDir, "beauty_face_skin_masks_v31"), cacheKey(sourceUri))

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
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context.applicationContext, options)
    }

    fun segmentAndStore(context: Context, clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val result = segmenter.segment(BitmapImageBuilder(bitmap).build())
        val mpMask = result.categoryMask().orElse(null) ?: return false
        val width = mpMask.width.coerceAtLeast(1)
        val height = mpMask.height.coerceAtLeast(1)
        val categories = ByteBufferExtractor.extract(mpMask)
        categories.rewind()
        if (categories.remaining() < width * height) return false

        val binary = IntArray(width * height)
        for (index in binary.indices) {
            val category = categories.get().toInt() and 0xFF
            binary[index] = if (category == FACE_SKIN_CLASS_V31) 255 else 0
        }
        // Two small box-blur passes soften class boundaries without bleeding far into hair/clothes.
        val softened = blurMask(blurMask(binary, width, height), width, height)
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
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until width) continue
                        sum += source[yy * width + xx]
                        count++
                    }
                }
                out[y * width + x] = if (count == 0) source[y * width + x] else sum / count
            }
        }
        return out
    }

    override fun close() {
        segmenter.close()
    }
}
