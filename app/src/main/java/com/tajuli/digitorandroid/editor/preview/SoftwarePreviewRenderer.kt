package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
 * This path is intentionally preview-only and lower resolution. Healthy devices remain on the exact
 * GPU path; fallback exists so unsupported Surface handoffs never make footage uneditable.
 */
internal object SoftwarePreviewRenderer {
    private const val FALLBACK_LUT_SIZE = 17

    fun render(
        context: Context,
        clip: TimelineClip,
        sourceTimeUs: Long,
        maxLongEdge: Int = 720,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val decoded = try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            retriever.getFrameAtTime(
                sourceTimeUs.coerceIn(clip.sourceInUs, clip.sourceOutUs.coerceAtLeast(clip.sourceInUs)),
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        } ?: return null

        val scaled = scaleDown(decoded, maxLongEdge.coerceAtLeast(240))
        if (scaled !== decoded) decoded.recycle()

        val working = if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, true).also {
                if (it !== scaled) scaled.recycle()
            }
        }

        val cube = SharedColorPipeline.buildCubeAtSourceTime(
            clip = clip,
            size = FALLBACK_LUT_SIZE,
            sourceTimeUs = sourceTimeUs,
        )
        applyCubeNearest(working, cube)
        return working
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
