package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.render.SharedColorPipeline
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Emergency software preview used when a device/codec can decode a clip but the MediaCodec ->
 * Media3 GPU graph never produces its first frame.
 *
 * Camera Log clips are decoded as visible code values first, so an untouched S-Log/C-Log clip stays
 * flat instead of black. The same shared color LUT is then sampled on CPU, which means Input Color,
 * primary/log wheels, curves and node color changes still show in the fallback preview.
 *
 * The retriever is intentionally reused across adjacent playback frames. Reopening the media source
 * for every frame made camera Log fallback playback visibly stutter even when the device could
 * decode the stream. Android 8.1+ also decodes directly near the requested preview size instead of
 * decoding a full camera frame and scaling it afterwards.
 *
 * For a newly requested seek we can first ask for the closest sync/key frame. That path avoids a
 * potentially long GOP walk and gives the viewer something useful almost immediately. The caller
 * can then request OPTION_CLOSEST for the exact target and replace the placeholder when it arrives.
 *
 * The whole fallback decode is guarded by PreviewExportCoordinator. Export takes the exclusive side
 * of that barrier and releases the cached retriever before Transformer starts, so the software
 * preview can never compete with the export decoder/encoder on fragile codec stacks.
 */
internal object SoftwarePreviewRenderer {
    private const val FALLBACK_LUT_SIZE = 17

    private data class RetrieverSession(
        val uri: String,
        val retriever: MediaMetadataRetriever,
        val width: Int,
        val height: Int,
    )

    // Access is serialized by PreviewExportCoordinator.previewDecodeGate.
    private var cachedSession: RetrieverSession? = null

    fun render(
        context: Context,
        clip: TimelineClip,
        sourceTimeUs: Long,
        maxLongEdge: Int = 640,
        closestSyncOnly: Boolean = false,
    ): Bitmap? = PreviewExportCoordinator.withSoftwarePreviewDecode {
        val session = sessionFor(context, clip) ?: return@withSoftwarePreviewDecode null
        val safeSourceUs = sourceTimeUs.coerceIn(
            clip.sourceInUs,
            clip.sourceOutUs.coerceAtLeast(clip.sourceInUs),
        )
        val safeLongEdge = maxLongEdge.coerceAtLeast(240)
        val option = if (closestSyncOnly) {
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        } else {
            MediaMetadataRetriever.OPTION_CLOSEST
        }
        val decoded = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                session.width > 1 && session.height > 1
            ) {
                val target = fittedSize(session.width, session.height, safeLongEdge)
                session.retriever.getScaledFrameAtTime(
                    safeSourceUs,
                    option,
                    target.first,
                    target.second,
                )
            } else {
                session.retriever.getFrameAtTime(
                    safeSourceUs,
                    option,
                )
            }
        } catch (_: Throwable) {
            // A vendor retriever can become poisoned after one decoder error. Drop it so the next
            // frame gets a clean software session rather than stuttering forever on the same state.
            releaseCachedDecoderLocked()
            null
        } ?: return@withSoftwarePreviewDecode null

        val scaled = scaleDown(decoded, safeLongEdge)
        if (scaled !== decoded) decoded.recycle()

        val working = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            val copied = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: run {
                if (!scaled.isRecycled) scaled.recycle()
                return@withSoftwarePreviewDecode null
            }
            if (copied !== scaled && !scaled.isRecycled) scaled.recycle()
            copied
        }

        val cube = SharedColorPipeline.buildCubeAtSourceTime(
            clip = clip,
            size = FALLBACK_LUT_SIZE,
            sourceTimeUs = safeSourceUs,
        )
        applyCubeNearest(working, cube)
        working
    }

    /** Called only while PreviewExportCoordinator owns the software-decode gate. */
    internal fun releaseCachedDecoderForExport() {
        releaseCachedDecoderLocked()
    }

    private fun sessionFor(context: Context, clip: TimelineClip): RetrieverSession? {
        cachedSession?.takeIf { it.uri == clip.uri }?.let { return it }
        releaseCachedDecoderLocked()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1
            RetrieverSession(
                uri = clip.uri,
                retriever = retriever,
                width = width,
                height = height,
            ).also { cachedSession = it }
        } catch (_: Throwable) {
            runCatching { retriever.release() }
            null
        }
    }

    private fun releaseCachedDecoderLocked() {
        val previous = cachedSession
        cachedSession = null
        previous?.let { runCatching { it.retriever.release() } }
    }

    private fun fittedSize(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longest = max(width, height).coerceAtLeast(1)
        if (longest <= maxLongEdge) return width.coerceAtLeast(2) to height.coerceAtLeast(2)
        val scale = maxLongEdge.toFloat() / longest.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(2) to
            (height * scale).roundToInt().coerceAtLeast(2)
    }

    private fun scaleDown(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxLongEdge) return bitmap
        val scale = maxLongEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun applyCubeNearest(
        bitmap: Bitmap,
        cube: Array<Array<IntArray>>,
    ) {
        if (cube.isEmpty()) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val last = cube.size - 1

        for (index in pixels.indices) {
            val color = pixels[index]
            val a = color ushr 24 and 0xFF
            val r = color ushr 16 and 0xFF
            val g = color ushr 8 and 0xFF
            val b = color and 0xFF
            val ri = ((r / 255f) * last).roundToInt().coerceIn(0, last)
            val gi = ((g / 255f) * last).roundToInt().coerceIn(0, last)
            val bi = ((b / 255f) * last).roundToInt().coerceIn(0, last)
            pixels[index] = (a shl 24) or (cube[ri][gi][bi] and 0x00FFFFFF)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
