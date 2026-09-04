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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MODNET_MODEL_ASSET_V47 = "modnet_v44.tflite"
private const val MODNET_SIZE_V47 = 512
private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 720

/**
 * V47 analysis entry point used by the editor. The bulk path is GPU-first:
 * MediaCodec -> OES texture -> GL scale -> LiteRT GPU MODNet -> MediaPipe GPU Hair -> GL local flow.
 * CPU remains for orchestration, model tensor packing/readback, tiny scene-cut signatures and cache
 * I/O. If a device rejects the GL/GPU path, V45 CPU flow and random-seek decode remain a fallback.
 */
class GpuPersonCutoutAnalyzerV47(private val context: Context) {
    fun analyzeAndStore(
        clip: TimelineClip,
        prioritySourceUs: Long? = null,
        onAnchorStored: ((completedAnchors: Int) -> Unit)? = null,
    ): PersonCutoutMaskTrackV43 {
        preparePersonCutoutGenerationV47(context, clip.uri)
        var completed = 0
        GpuPersonCutoutSegmenterV47(context).use { segmenter ->
            if (clip.isImageV21) {
                val bitmap = decodeImage(Uri.parse(clip.uri)) ?: error("Could not decode image for Pro Cutout")
                try {
                    check(segmenter.segmentAndStore(clip, bitmap, clip.sourceInUs)) {
                        "Portrait matting returned no alpha"
                    }
                    completed = 1
                    onAnchorStored?.invoke(completed)
                } finally {
                    bitmap.recycle()
                }
            } else {
                completed = analyzeVideo(clip, segmenter, prioritySourceUs, onAnchorStored)
            }
        }
        check(completed > 0) { "Could not generate any V47 portrait matte" }
        markPersonCutoutGenerationV47Ready(context, clip.uri)
        return PersonCutoutMaskStoreV43.index(context, clip)
    }

    private fun analyzeVideo(
        clip: TimelineClip,
        segmenter: GpuPersonCutoutSegmenterV47,
        prioritySourceUs: Long?,
        onAnchorStored: ((Int) -> Unit)?,
    ): Int {
        val start = clip.sourceInUs.coerceAtLeast(0L)
        val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
        val durationUs = (end - start).coerceAtLeast(1L)
        val count = personCutoutTargetAnchorCountV46(durationUs)
        val regularTimes = evenlySpacedTimes(start, end, count)
        var completed = 0

        // Preserve the old UX win: one requested playhead frame can become visible before the bulk
        // sequential decode reaches that timestamp. It is the only intentional random seek in V47.
        val priority = prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
        if (priority != null) {
            val frame = decodeSinglePriorityFrame(clip, priority)
            if (frame != null) {
                try {
                    if (segmenter.segmentAndStore(clip, frame, priority)) {
                        completed++
                        onAnchorStored?.invoke(completed)
                    }
                } finally {
                    frame.recycle()
                }
            }
        }

        val sequentialResult = runCatching {
            GpuSequentialCutoutDecoderV47(context, PERSON_ANALYSIS_LONG_EDGE_V47).decodeTargets(
                uri = Uri.parse(clip.uri),
                startUs = start,
                endUs = end,
                targetTimesUs = regularTimes,
            ) { sourceUs, bitmap ->
                if (segmenter.segmentAndStore(clip, bitmap, sourceUs)) {
                    completed++
                    onAnchorStored?.invoke(completed)
                }
            }
        }

        if (sequentialResult.isSuccess && sequentialResult.getOrDefault(0) > 0) return completed

        // Codec/OES behavior varies across vendor decoders. Reliability wins over speed on an
        // unsupported device: fall back to the old per-target retriever path, still using GPU-first
        // MODNet/Hair when those delegates are available.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            for (sourceUs in regularTimes) {
                val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                try {
                    if (segmenter.segmentAndStore(clip, frame, sourceUs)) {
                        completed++
                        onAnchorStored?.invoke(completed)
                    }
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
        return completed
    }

    private fun decodeSinglePriorityFrame(clip: TimelineClip, sourceUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            scaledFrameAtTime(retriever, sourceUs)
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
                val scale = if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V47) 1f
                else PERSON_ANALYSIS_LONG_EDGE_V47 / longEdge.toFloat()
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
        if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V47) return normalized
        val scale = PERSON_ANALYSIS_LONG_EDGE_V47 / longEdge.toFloat()
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
                if (longEdge > PERSON_ANALYSIS_LONG_EDGE_V47) {
                    val scale = PERSON_ANALYSIS_LONG_EDGE_V47 / longEdge.toFloat()
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

private class GpuPersonCutoutSegmenterV47(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modnet = GpuModNetPortraitMatteV47(appContext)
    private val hair = BeautyHairSegmenterV29(appContext)
    private val gpuTemporal = runCatching { GpuSpatialFlowTemporalMatteStabilizerV47() }.getOrNull()
    private val cpuTemporal = SpatialFlowTemporalMatteStabilizerV45()

    fun segmentAndStore(clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not convert decoded frame to ARGB_8888 for Pro Cutout")
        }
        try {
            val settings = clip.resolvedCutoutV43()
            val modnetMatte = modnet.infer(source)
            val hairMask = if (settings.hairDetailV44 > .001f) {
                runCatching { hair.segmentSoftMask(source) }.getOrNull()
            } else {
                null
            }
            val stabilized = try {
                val gpu = gpuTemporal
                if (gpu != null) {
                    gpu.stabilize(
                        source = source,
                        currentMatte = modnetMatte,
                        hairMask = hairMask,
                        sourceTimeUs = sourceTimeUs,
                        hairStrength = settings.hairDetailV44,
                        temporalStrength = settings.temporalStabilityV44,
                    )
                } else {
                    val fused = fuseHairCpu(modnetMatte, hairMask, settings.hairDetailV44)
                    try {
                        cpuTemporal.stabilize(source, fused, sourceTimeUs, settings.temporalStabilityV44)
                    } finally {
                        fused.recycle()
                    }
                }
            } finally {
                hairMask?.recycle()
                modnetMatte.recycle()
            }
            try {
                PersonCutoutMaskStoreV43.save(appContext, clip.uri, sourceTimeUs, stabilized)
            } finally {
                stabilized.recycle()
            }
            return true
        } finally {
            if (source !== bitmap && !source.isRecycled) source.recycle()
        }
    }

    private fun fuseHairCpu(base: Bitmap, hair: Bitmap?, strength: Float): Bitmap {
        if (hair == null || strength <= .001f) {
            return base.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy portrait matte")
        }
        val scaledHair = if (hair.width == base.width && hair.height == base.height) {
            hair.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy hair matte")
        } else {
            Bitmap.createScaledBitmap(hair, base.width, base.height, true)
        }
        try {
            val aPixels = IntArray(base.width * base.height)
            val hPixels = IntArray(aPixels.size)
            val out = IntArray(aPixels.size)
            base.getPixels(aPixels, 0, base.width, 0, 0, base.width, base.height)
            scaledHair.getPixels(hPixels, 0, base.width, 0, 0, base.width, base.height)
            val s = strength.coerceIn(0f, 1f)
            for (i in out.indices) {
                val a = Color.red(aPixels[i]) / 255f
                val h = Color.red(hPixels[i]) / 255f
                val uncertainty = (4f * a * (1f - a)).coerceIn(0f, 1f)
                val contribution = h * s * (.10f + .46f * uncertainty)
                val fused = max(a, a + (1f - a) * contribution).coerceIn(0f, 1f)
                val v = (fused * 255f).roundToInt().coerceIn(0, 255)
                out[i] = Color.argb(255, v, v, v)
            }
            return Bitmap.createBitmap(out, base.width, base.height, Bitmap.Config.ARGB_8888)
        } finally {
            scaledHair.recycle()
        }
    }

    override fun close() {
        runCatching { gpuTemporal?.close() }
        cpuTemporal.close()
        runCatching { hair.close() }
        runCatching { modnet.close() }
    }
}

/** LiteRT CompiledModel with GPU requested first; CPU is retained only as delegate fallback. */
private class GpuModNetPortraitMatteV47(context: Context) : AutoCloseable {
    private val model: CompiledModel
    private val inputBuffers: List<com.google.ai.edge.litert.TensorBuffer>
    private val outputBuffers: List<com.google.ai.edge.litert.TensorBuffer>

    init {
        val app = context.applicationContext
        val accelerated = runCatching {
            CompiledModel.create(
                app.assets,
                MODNET_MODEL_ASSET_V47,
                CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
            )
        }
        model = accelerated.getOrElse {
            CompiledModel.create(
                app.assets,
                MODNET_MODEL_ASSET_V47,
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
            val inputPixels = IntArray(MODNET_SIZE_V47 * MODNET_SIZE_V47)
            prepared.bitmap.getPixels(
                inputPixels,
                0,
                MODNET_SIZE_V47,
                0,
                0,
                MODNET_SIZE_V47,
                MODNET_SIZE_V47,
            )
            val plane = MODNET_SIZE_V47 * MODNET_SIZE_V47
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
            check(alpha.size >= plane) { "MODNet alpha output was ${alpha.size}; expected at least $plane values" }
            val alphaPixels = IntArray(plane)
            for (i in 0 until plane) {
                val v = (alpha[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                alphaPixels[i] = Color.argb(255, v, v, v)
            }
            val square = Bitmap.createBitmap(MODNET_SIZE_V47, MODNET_SIZE_V47, Bitmap.Config.ARGB_8888)
            square.setPixels(alphaPixels, 0, MODNET_SIZE_V47, 0, 0, MODNET_SIZE_V47, MODNET_SIZE_V47)
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

    private fun letterbox(source: Bitmap): LetterboxV47 {
        val scale = min(
            MODNET_SIZE_V47 / source.width.toFloat(),
            MODNET_SIZE_V47 / source.height.toFloat(),
        )
        val width = (source.width * scale).roundToInt().coerceIn(1, MODNET_SIZE_V47)
        val height = (source.height * scale).roundToInt().coerceIn(1, MODNET_SIZE_V47)
        val left = (MODNET_SIZE_V47 - width) / 2
        val top = (MODNET_SIZE_V47 - height) / 2
        val bitmap = Bitmap.createBitmap(MODNET_SIZE_V47, MODNET_SIZE_V47, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        Canvas(bitmap).drawBitmap(
            source,
            null,
            Rect(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return LetterboxV47(bitmap, left, top, width, height)
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { model.close() }
    }

    private data class LetterboxV47(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
        val contentWidth: Int,
        val contentHeight: Int,
    )
}
