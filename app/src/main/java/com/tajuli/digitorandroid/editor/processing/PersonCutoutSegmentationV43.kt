package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val PERSON_ANALYSIS_LONG_EDGE_V44 = 720
private const val PERSON_CUTOUT_CACHE_DIR_V50 = "person_cutout_masks_v50_ppmattingv2_hair_spatialflow_512"

data class PersonCutoutMaskFrameV43(
    val sourceTimeUs: Long,
    val file: File,
)

data class PersonCutoutMaskTrackV43(
    val sourceUri: String,
    val frames: List<PersonCutoutMaskFrameV43>,
) {
    fun nearest(sourceTimeUs: Long): PersonCutoutMaskFrameV43? {
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

/** V50 PP-MattingV2 cache. Old MODNet generations are intentionally not discovered here. */
object PersonCutoutMaskStoreV43 {
    private val indexCache = ConcurrentHashMap<String, PersonCutoutMaskTrackV43>()

    fun index(context: Context, clip: TimelineClip): PersonCutoutMaskTrackV43 {
        val key = cacheKey(clip.uri)
        return indexCache[key] ?: loadIndex(context, clip.uri).also { indexCache[key] = it }
    }

    fun hasAny(context: Context, clip: TimelineClip): Boolean =
        index(context, clip).frames.any { it.file.isFile }

    fun save(context: Context, sourceUri: String, sourceTimeUs: Long, mask: Bitmap): File {
        val dir = sourceDir(context, sourceUri).apply { mkdirs() }
        val target = File(dir, "$sourceTimeUs.png")
        val temp = File(dir, "$sourceTimeUs.png.tmp")
        temp.outputStream().buffered().use { stream ->
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not encode V50 portrait alpha matte"
            }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not install V50 portrait alpha matte" }
        indexCache.remove(cacheKey(sourceUri))
        return target
    }

    private fun loadIndex(context: Context, sourceUri: String): PersonCutoutMaskTrackV43 {
        val frames = sourceDir(context, sourceUri).listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .mapNotNull { file ->
                file.nameWithoutExtension.toLongOrNull()?.let { time -> PersonCutoutMaskFrameV43(time, file) }
            }
            .sortedBy { it.sourceTimeUs }
            .toList()
        return PersonCutoutMaskTrackV43(sourceUri, frames)
    }

    private fun sourceDir(context: Context, sourceUri: String): File =
        File(File(context.filesDir, PERSON_CUTOUT_CACHE_DIR_V50), cacheKey(sourceUri))

    private fun cacheKey(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceUri.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
}

/**
 * Historical class name retained for callers that still use the compatibility analyzer. The base
 * matte is now PP-MattingV2 only, followed by MediaPipe hair fusion and local spatial-flow temporal
 * stabilization, matching the V50 Pro Cutout direction.
 */
class PersonCutoutSegmenterV43(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val portraitMatte = PpMattingV2PortraitMatteV50(appContext)
    private val hair = BeautyHairSegmenterV29(appContext)
    private val temporal = SpatialFlowTemporalMatteStabilizerV45()

    fun segmentAndStore(context: Context, clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not convert decoded frame to ARGB_8888 for Pro Cutout")
        }
        try {
            val settings = clip.resolvedCutoutV43()
            val baseMatte = portraitMatte.infer(source)
            val fused = try {
                fuseHair(clip, source, sourceTimeUs, baseMatte, settings.hairDetailV44)
            } finally {
                baseMatte.recycle()
            }
            val stabilized = try {
                temporal.stabilize(source, fused, sourceTimeUs, settings.temporalStabilityV44)
            } finally {
                fused.recycle()
            }
            try {
                PersonCutoutMaskStoreV43.save(context.applicationContext, clip.uri, sourceTimeUs, stabilized)
            } finally {
                stabilized.recycle()
            }
            return true
        } finally {
            if (source !== bitmap && !source.isRecycled) source.recycle()
        }
    }

    private fun fuseHair(
        clip: TimelineClip,
        source: Bitmap,
        sourceTimeUs: Long,
        matte: Bitmap,
        strength: Float,
    ): Bitmap {
        val hairStored = runCatching {
            hair.segmentAndStore(appContext, clip, source, sourceTimeUs)
        }.getOrDefault(false)
        if (!hairStored || strength <= .001f) {
            return matte.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy PP-MattingV2 matte")
        }
        val hairFile = BeautyHairMaskStoreV29.index(appContext, clip).nearest(sourceTimeUs)?.file
            ?: return matte.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy PP-MattingV2 matte")
        val hairBitmap = BitmapFactory.decodeFile(hairFile.absolutePath)
            ?: return matte.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy PP-MattingV2 matte")
        val scaledHair = if (hairBitmap.width == matte.width && hairBitmap.height == matte.height) {
            hairBitmap
        } else {
            Bitmap.createScaledBitmap(hairBitmap, matte.width, matte.height, true).also { hairBitmap.recycle() }
        }
        try {
            val basePixels = IntArray(matte.width * matte.height)
            val hairPixels = IntArray(basePixels.size)
            matte.getPixels(basePixels, 0, matte.width, 0, 0, matte.width, matte.height)
            scaledHair.getPixels(hairPixels, 0, matte.width, 0, 0, matte.width, matte.height)
            val out = IntArray(basePixels.size)
            val s = strength.coerceIn(0f, 1f)
            for (i in out.indices) {
                val a = Color.red(basePixels[i]) / 255f
                val h = Color.red(hairPixels[i]) / 255f
                val uncertain = (1f - abs(a * 2f - 1f)).coerceIn(0f, 1f)
                // PP-MattingV2 remains authoritative; HairSegmenter only restores boundary detail.
                val hairContribution = h * s * (.28f + .72f * uncertain)
                val fused = max(a, (a + (1f - a) * hairContribution).coerceAtMost(1f))
                val v = (fused * 255f).roundToInt().coerceIn(0, 255)
                out[i] = Color.argb(255, v, v, v)
            }
            return Bitmap.createBitmap(matte.width, matte.height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(out, 0, matte.width, 0, 0, matte.width, matte.height)
            }
        } finally {
            scaledHair.recycle()
        }
    }

    override fun close() {
        temporal.close()
        runCatching { hair.close() }
        runCatching { portraitMatte.close() }
    }
}

/** Compatibility analyzer retained for CPU/vendor-codec fallback paths. */
class PersonCutoutAnalyzerV43(private val context: Context) {
    fun analyzeAndStore(
        clip: TimelineClip,
        prioritySourceUs: Long? = null,
        onAnchorStored: ((completedAnchors: Int) -> Unit)? = null,
    ): PersonCutoutMaskTrackV43 {
        PersonCutoutSegmenterV43(context).use { segmenter ->
            if (clip.isImageV21) analyzeImage(clip, segmenter, onAnchorStored)
            else analyzeVideo(clip, segmenter, prioritySourceUs, onAnchorStored)
        }
        return PersonCutoutMaskStoreV43.index(context, clip)
    }

    private fun analyzeImage(
        clip: TimelineClip,
        segmenter: PersonCutoutSegmenterV43,
        onAnchorStored: ((Int) -> Unit)?,
    ) {
        val bitmap = decodeImage(Uri.parse(clip.uri)) ?: error("Could not decode image for Pro Cutout")
        try {
            check(segmenter.segmentAndStore(context, clip, bitmap, clip.sourceInUs)) {
                "Portrait matting returned no alpha"
            }
            onAnchorStored?.invoke(1)
        } finally {
            bitmap.recycle()
        }
    }

    private fun analyzeVideo(
        clip: TimelineClip,
        segmenter: PersonCutoutSegmenterV43,
        prioritySourceUs: Long?,
        onAnchorStored: ((Int) -> Unit)?,
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            val start = clip.sourceInUs.coerceAtLeast(0L)
            val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
            val durationUs = (end - start).coerceAtLeast(1L)
            val count = personCutoutTargetAnchorCountV46(durationUs)
            val regularTimes = evenlySpacedTimes(start, end, count)
            val priority = prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
            val times = buildList {
                priority?.let(::add)
                addAll(regularTimes)
            }.distinct()

            var completed = 0
            var decoded = 0
            for (sourceUs in times) {
                val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                decoded++
                try {
                    if (segmenter.segmentAndStore(context, clip, frame, sourceUs)) {
                        completed++
                        onAnchorStored?.invoke(completed)
                    }
                } finally {
                    frame.recycle()
                }
            }
            check(decoded > 0) { "Could not decode any video frame for Pro Cutout" }
            check(completed > 0) { "Could not generate any portrait matte" }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun evenlySpacedTimes(start: Long, end: Long, count: Int): List<Long> {
        val last = (end - 1L).coerceAtLeast(start)
        val safeCount = count.coerceAtLeast(2)
        if (last <= start) return listOf(start)
        return (0 until safeCount).map { index ->
            if (index == safeCount - 1) last
            else start + ((last - start) * index.toLong()) / (safeCount - 1).toLong()
        }.distinct()
    }

    private fun scaledFrameAtTime(retriever: MediaMetadataRetriever, sourceUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val longEdge = max(width, height)
                val scale = if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V44) 1f
                else PERSON_ANALYSIS_LONG_EDGE_V44 / longEdge.toFloat()
                val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
                retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight,
                )?.let { return ensureArgb(it) }
            }
        }
        val raw = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val normalized = ensureArgb(raw)
        if (normalized !== raw && !raw.isRecycled) raw.recycle()
        val longEdge = max(normalized.width, normalized.height)
        if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V44) return normalized
        val scale = PERSON_ANALYSIS_LONG_EDGE_V44 / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            normalized,
            (normalized.width * scale).roundToInt().coerceAtLeast(1),
            (normalized.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== normalized) normalized.recycle() }
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not convert video frame to ARGB_8888")
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > PERSON_ANALYSIS_LONG_EDGE_V44) {
                    val scale = PERSON_ANALYSIS_LONG_EDGE_V44 / longEdge.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        } ?: return@runCatching null
        val normalized = ensureArgb(raw)
        if (normalized !== raw && !raw.isRecycled) raw.recycle()
        normalized
    }.getOrNull()
}
