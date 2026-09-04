package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MODNET_MODEL_ASSET_V44 = "modnet_v44.tflite"
private const val MODNET_SIZE_V44 = 512
private const val PERSON_ANALYSIS_LONG_EDGE_V44 = 720
private const val TEMPORAL_RESET_GAP_US_V44 = 1_200_000L

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

/** V46 cache is separate so old 160-anchor V45 mattes cannot be mistaken for the denser policy. */
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
                "Could not encode V46 portrait alpha matte"
            }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not install V46 portrait alpha matte" }
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
        File(File(context.filesDir, "person_cutout_masks_v46_modnet_hair_spatialflow_512_320"), cacheKey(sourceUri))

    private fun cacheKey(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceUri.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
}

/**
 * MODNet portrait matting engine. GPU+CPU is requested so LiteRT can use the GPU where supported
 * and retain a CPU partition/fallback on devices with incomplete GPU op coverage.
 */
private class ModNetPortraitMatteV44(context: Context) : AutoCloseable {
    private val model: CompiledModel
    private val inputBuffers: List<com.google.ai.edge.litert.TensorBuffer>
    private val outputBuffers: List<com.google.ai.edge.litert.TensorBuffer>

    init {
        val app = context.applicationContext
        val accelerated = runCatching {
            // Keep GPU options at LiteRT's validated defaults. This remains source-compatible with
            // the stable 2.1.5 runtime while still allowing GPU execution with CPU partition/fallback.
            CompiledModel.create(
                app.assets,
                MODNET_MODEL_ASSET_V44,
                CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
            )
        }
        model = accelerated.getOrElse {
            CompiledModel.create(
                app.assets,
                MODNET_MODEL_ASSET_V44,
                CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                },
            )
        }
        inputBuffers = model.createInputBuffers()
        outputBuffers = model.createOutputBuffers()
        check(inputBuffers.isNotEmpty() && outputBuffers.isNotEmpty()) {
            "MODNet did not expose input/output buffers"
        }
    }

    fun infer(source: Bitmap): Bitmap {
        val prepared = letterbox(source)
        try {
            val inputPixels = IntArray(MODNET_SIZE_V44 * MODNET_SIZE_V44)
            prepared.bitmap.getPixels(
                inputPixels,
                0,
                MODNET_SIZE_V44,
                0,
                0,
                MODNET_SIZE_V44,
                MODNET_SIZE_V44,
            )
            val plane = MODNET_SIZE_V44 * MODNET_SIZE_V44
            val tensor = FloatArray(plane * 3)
            for (i in 0 until plane) {
                val pixel = inputPixels[i]
                tensor[i] = Color.red(pixel) / 127.5f - 1f
                tensor[plane + i] = Color.green(pixel) / 127.5f - 1f
                tensor[plane * 2 + i] = Color.blue(pixel) / 127.5f - 1f
            }

            inputBuffers[0].writeFloat(tensor)
            model.run(inputBuffers, outputBuffers)
            val alpha = outputBuffers[0].readFloat()
            check(alpha.size >= plane) {
                "MODNet alpha output was ${alpha.size}; expected at least $plane values"
            }

            val alphaPixels = IntArray(plane)
            for (i in 0 until plane) {
                val value = (alpha[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                alphaPixels[i] = Color.argb(255, value, value, value)
            }
            val square = Bitmap.createBitmap(MODNET_SIZE_V44, MODNET_SIZE_V44, Bitmap.Config.ARGB_8888)
            square.setPixels(alphaPixels, 0, MODNET_SIZE_V44, 0, 0, MODNET_SIZE_V44, MODNET_SIZE_V44)
            try {
                val cropped = Bitmap.createBitmap(
                    square,
                    prepared.left,
                    prepared.top,
                    prepared.contentWidth,
                    prepared.contentHeight,
                )
                return try {
                    Bitmap.createScaledBitmap(cropped, source.width, source.height, true)
                } finally {
                    cropped.recycle()
                }
            } finally {
                square.recycle()
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun letterbox(source: Bitmap): Letterbox {
        val scale = min(
            MODNET_SIZE_V44 / source.width.toFloat(),
            MODNET_SIZE_V44 / source.height.toFloat(),
        )
        val width = (source.width * scale).roundToInt().coerceIn(1, MODNET_SIZE_V44)
        val height = (source.height * scale).roundToInt().coerceIn(1, MODNET_SIZE_V44)
        val left = (MODNET_SIZE_V44 - width) / 2
        val top = (MODNET_SIZE_V44 - height) / 2
        val bitmap = Bitmap.createBitmap(MODNET_SIZE_V44, MODNET_SIZE_V44, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        Canvas(bitmap).drawBitmap(
            source,
            null,
            Rect(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return Letterbox(bitmap, left, top, width, height)
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { model.close() }
    }

    private data class Letterbox(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
        val contentWidth: Int,
        val contentHeight: Int,
    )
}

/**
 * Legacy V44 centroid stabilizer retained only for source compatibility while V45 uses the local
 * spatial-flow implementation in SpatialFlowTemporalMatteV45.kt.
 */
private class TemporalMatteStabilizerV44 {
    private var previous: Bitmap? = null
    private var previousTimeUs: Long = Long.MIN_VALUE

    fun stabilize(current: Bitmap, sourceTimeUs: Long, strength: Float): Bitmap {
        val old = previous
        if (
            old == null || old.width != current.width || old.height != current.height ||
            sourceTimeUs <= previousTimeUs || sourceTimeUs - previousTimeUs > TEMPORAL_RESET_GAP_US_V44
        ) {
            replacePrevious(current, sourceTimeUs)
            return current.copy(Bitmap.Config.ARGB_8888, false)
        }

        val currentPixels = IntArray(current.width * current.height)
        val previousPixels = IntArray(old.width * old.height)
        current.getPixels(currentPixels, 0, current.width, 0, 0, current.width, current.height)
        old.getPixels(previousPixels, 0, old.width, 0, 0, old.width, old.height)

        val nowCentroid = centroid(currentPixels, current.width, current.height)
        val oldCentroid = centroid(previousPixels, old.width, old.height)
        val dx = (nowCentroid.first - oldCentroid.first).roundToInt()
            .coerceIn(-current.width / 12, current.width / 12)
        val dy = (nowCentroid.second - oldCentroid.second).roundToInt()
            .coerceIn(-current.height / 12, current.height / 12)
        val s = strength.coerceIn(0f, .92f)
        val out = IntArray(currentPixels.size)

        for (y in 0 until current.height) {
            val py = (y - dy).coerceIn(0, current.height - 1)
            for (x in 0 until current.width) {
                val px = (x - dx).coerceIn(0, current.width - 1)
                val i = y * current.width + x
                val p = py * current.width + px
                val curr = Color.red(currentPixels[i]) / 255f
                val prev = Color.red(previousPixels[p]) / 255f
                val uncertainty = (1f - abs(curr * 2f - 1f)).coerceIn(0f, 1f)
                val agreement = (1f - abs(curr - prev) * 2.2f).coerceIn(0f, 1f)
                val blend = s * .72f * uncertainty * agreement
                val alpha = (curr * (1f - blend) + prev * blend).coerceIn(0f, 1f)
                val v = (alpha * 255f).roundToInt().coerceIn(0, 255)
                out[i] = Color.argb(255, v, v, v)
            }
        }

        val result = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, current.width, 0, 0, current.width, current.height)
        replacePrevious(result, sourceTimeUs)
        return result
    }

    private fun centroid(pixels: IntArray, width: Int, height: Int): Pair<Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var weight = 0.0
        val step = max(1, min(width, height) / 128)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val a = Color.red(pixels[y * width + x]) / 255.0
                if (a > .2) {
                    val w = a * a
                    sumX += x * w
                    sumY += y * w
                    weight += w
                }
                x += step
            }
            y += step
        }
        return if (weight <= 1e-6) width / 2f to height / 2f
        else (sumX / weight).toFloat() to (sumY / weight).toFloat()
    }

    private fun replacePrevious(bitmap: Bitmap, sourceTimeUs: Long) {
        previous?.recycle()
        previous = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        previousTimeUs = sourceTimeUs
    }

    fun close() {
        previous?.recycle()
        previous = null
    }
}

/**
 * Historical class name kept so the UI bridge does not churn. V46 implementation is MODNet alpha
 * matting + MediaPipe hair fusion + local spatial-flow temporal stabilization.
 */
class PersonCutoutSegmenterV43(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modnet = ModNetPortraitMatteV44(appContext)
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
            val modnetMatte = modnet.infer(source)
            val fused = try {
                fuseHair(clip, source, sourceTimeUs, modnetMatte, settings.hairDetailV44)
            } finally {
                modnetMatte.recycle()
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
        }
        val hairFile = BeautyHairMaskStoreV29.index(appContext, clip).nearest(sourceTimeUs)?.file
            ?: return matte.copy(Bitmap.Config.ARGB_8888, false)
        val hairBitmap = BitmapFactory.decodeFile(hairFile.absolutePath)
            ?: return matte.copy(Bitmap.Config.ARGB_8888, false)
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
                // Preserve MODNet as authority. HairSegmenter may only restore detail in/near the
                // uncertain portrait boundary; it cannot create a full-opacity foreground island.
                val hairContribution = h * s * (.28f + .72f * uncertain)
                val fused = max(a, min(1f, a + (1f - a) * hairContribution))
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
        runCatching { modnet.close() }
    }
}

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
