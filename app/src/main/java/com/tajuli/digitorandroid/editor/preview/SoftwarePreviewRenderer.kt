package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Native still-image TimelineClip items also intentionally use this path. The realtime GPU transport
 * is MediaExtractor/MediaCodec based and therefore has no encoded video track to open for a JPEG or
 * PNG. Static images are decoded with BitmapFactory, passed through the same shared color LUT, and
 * then displayed by GpuPreviewSurface while keeping the clip itself timeline-native.
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
 * The fallback uses the same 33^3 cube density as exact GPU preview/export and tetrahedral sampling
 * instead of nearest-cell lookup. The old 17^3 nearest path visibly posterized smooth Log gradients
 * and could make an 8-bit source look more banded than it really was. No artificial dither/noise is
 * added here, so the fallback does not deliberately diverge from the shared color transform.
 *
 * Static grades reuse one flattened 33^3 cube across frames. Animated correction/color grades key
 * the cache by source timestamp, preserving frame-accurate animation while avoiding expensive cube
 * regeneration during ordinary fallback playback.
 *
 * The whole fallback decode is guarded by PreviewExportCoordinator. Export takes the exclusive side
 * of that barrier and releases the cached retriever before Transformer starts, so the software
 * preview can never compete with the export decoder/encoder on fragile codec stacks.
 */
internal object SoftwarePreviewRenderer {
    private const val FALLBACK_LUT_SIZE = 33

    private data class RetrieverSession(
        val uri: String,
        val retriever: MediaMetadataRetriever,
        val width: Int,
        val height: Int,
    )

    private data class LutKey(
        val clipId: String,
        val nodeGraphHash: Int,
        val legacyGradeHash: Int,
        val inputProfileHash: Int,
        val animationRevision: Long,
        val animatedSourceUs: Long,
    )

    private data class CachedLut(
        val key: LutKey,
        val size: Int,
        val colors: IntArray,
    )

    // Access is serialized by PreviewExportCoordinator.previewDecodeGate.
    private var cachedSession: RetrieverSession? = null
    private var cachedLut: CachedLut? = null

    fun render(
        context: Context,
        clip: TimelineClip,
        sourceTimeUs: Long,
        maxLongEdge: Int = 640,
        closestSyncOnly: Boolean = false,
    ): Bitmap? = PreviewExportCoordinator.withSoftwarePreviewDecode {
        val safeLongEdge = maxLongEdge.coerceAtLeast(240)
        val safeSourceUs = sourceTimeUs.coerceIn(
            clip.sourceInUs,
            clip.sourceOutUs.coerceAtLeast(clip.sourceInUs),
        )

        if (clip.isImageV21) {
            return@withSoftwarePreviewDecode renderStillImage(
                context = context,
                clip = clip,
                sourceTimeUs = safeSourceUs,
                maxLongEdge = safeLongEdge,
            )
        }

        val session = sessionFor(context, clip) ?: return@withSoftwarePreviewDecode null
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

        val working = mutableArgb8888(scaled) ?: return@withSoftwarePreviewDecode null
        applyCubeTetrahedral(working, lutFor(clip, safeSourceUs))
        working
    }

    private fun renderStillImage(
        context: Context,
        clip: TimelineClip,
        sourceTimeUs: Long,
        maxLongEdge: Int,
    ): Bitmap? {
        val uri = runCatching { Uri.parse(clip.uri) }.getOrNull() ?: return null
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxLongEdge * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
        }.getOrNull() ?: return null

        val scaled = scaleDown(decoded, maxLongEdge)
        if (scaled !== decoded) decoded.recycle()
        val working = mutableArgb8888(scaled) ?: return null
        applyCubeTetrahedral(working, lutFor(clip, sourceTimeUs))
        return working
    }

    private fun mutableArgb8888(bitmap: Bitmap): Bitmap? {
        if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) return bitmap
        val copied = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (copied == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        if (copied !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return copied
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

    private fun lutFor(clip: TimelineClip, sourceTimeUs: Long): CachedLut {
        val key = LutKey(
            clipId = clip.id,
            nodeGraphHash = clip.nodeGraph.hashCode(),
            legacyGradeHash = clip.colorGrade.hashCode(),
            inputProfileHash = clip.inputColorProfileV1?.hashCode() ?: 0,
            animationRevision = clip.nodeAnimations.revision,
            animatedSourceUs = if (clip.nodeAnimations.hasColorAnimation) sourceTimeUs else Long.MIN_VALUE,
        )
        cachedLut?.takeIf { it.key == key }?.let { return it }

        val cube = SharedColorPipeline.buildCubeAtSourceTime(
            clip = clip,
            size = FALLBACK_LUT_SIZE,
            sourceTimeUs = sourceTimeUs,
        )
        val size = cube.size
        val flat = IntArray(size * size * size)
        for (r in 0 until size) {
            for (g in 0 until size) {
                for (b in 0 until size) {
                    flat[b + size * (g + size * r)] = cube[r][g][b]
                }
            }
        }
        return CachedLut(key, size, flat).also { cachedLut = it }
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

    /**
     * Four-corner tetrahedral 3D-LUT interpolation. Compared with nearest lookup this keeps smooth
     * Log ramps continuous between cube cells while using half the corner reads of trilinear (4 vs
     * 8), which matters on the low-end devices most likely to be running this emergency path.
     */
    private fun applyCubeTetrahedral(
        bitmap: Bitmap,
        lut: CachedLut,
    ) {
        val size = lut.size
        if (size < 2) return
        val last = size - 1
        val flat = lut.colors

        fun colorAt(r: Int, g: Int, b: Int): Int = flat[b + size * (g + size * r)]

        fun blend(c0: Int, c1: Int, c2: Int, c3: Int, w0: Float, w1: Float, w2: Float, w3: Float): Int {
            fun channel(shift: Int): Int {
                val value =
                    ((c0 ushr shift) and 0xFF) * w0 +
                        ((c1 ushr shift) and 0xFF) * w1 +
                        ((c2 ushr shift) and 0xFF) * w2 +
                        ((c3 ushr shift) and 0xFF) * w3
                return (value + .5f).toInt().coerceIn(0, 255)
            }
            return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scale = last / 255f

        for (index in pixels.indices) {
            val input = pixels[index]
            val alpha = input ushr 24 and 0xFF
            val rs = ((input ushr 16) and 0xFF) * scale
            val gs = ((input ushr 8) and 0xFF) * scale
            val bs = (input and 0xFF) * scale

            val r0 = rs.toInt().coerceIn(0, last)
            val g0 = gs.toInt().coerceIn(0, last)
            val b0 = bs.toInt().coerceIn(0, last)
            val r1 = (r0 + 1).coerceAtMost(last)
            val g1 = (g0 + 1).coerceAtMost(last)
            val b1 = (b0 + 1).coerceAtMost(last)
            val fr = if (r0 == last) 0f else rs - r0
            val fg = if (g0 == last) 0f else gs - g0
            val fb = if (b0 == last) 0f else bs - b0

            val c000 = colorAt(r0, g0, b0)
            val rgb = if (fr >= fg) {
                if (fg >= fb) {
                    blend(
                        c000,
                        colorAt(r1, g0, b0),
                        colorAt(r1, g1, b0),
                        colorAt(r1, g1, b1),
                        1f - fr,
                        fr - fg,
                        fg - fb,
                        fb,
                    )
                } else if (fr >= fb) {
                    blend(
                        c000,
                        colorAt(r1, g0, b0),
                        colorAt(r1, g0, b1),
                        colorAt(r1, g1, b1),
                        1f - fr,
                        fr - fb,
                        fb - fg,
                        fg,
                    )
                } else {
                    blend(
                        c000,
                        colorAt(r0, g0, b1),
                        colorAt(r1, g0, b1),
                        colorAt(r1, g1, b1),
                        1f - fb,
                        fb - fr,
                        fr - fg,
                        fg,
                    )
                }
            } else {
                if (fb >= fg) {
                    blend(
                        c000,
                        colorAt(r0, g0, b1),
                        colorAt(r0, g1, b1),
                        colorAt(r1, g1, b1),
                        1f - fb,
                        fb - fg,
                        fg - fr,
                        fr,
                    )
                } else if (fb >= fr) {
                    blend(
                        c000,
                        colorAt(r0, g1, b0),
                        colorAt(r0, g1, b1),
                        colorAt(r1, g1, b1),
                        1f - fg,
                        fg - fb,
                        fb - fr,
                        fr,
                    )
                } else {
                    blend(
                        c000,
                        colorAt(r0, g1, b0),
                        colorAt(r1, g1, b0),
                        colorAt(r1, g1, b1),
                        1f - fg,
                        fg - fr,
                        fr - fb,
                        fb,
                    )
                }
            }
            pixels[index] = (alpha shl 24) or rgb
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
