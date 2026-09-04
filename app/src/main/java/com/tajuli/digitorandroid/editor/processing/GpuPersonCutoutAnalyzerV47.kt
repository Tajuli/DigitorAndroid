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
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MODNET_MODEL_ASSET_V47 = "modnet_v44.tflite"
private const val MODNET_SIZE_V47 = 512
private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 720

/**
 * V48 throughput revision of the adaptive GPU-first analyzer.
 *
 * LOW: 4 fps. MEDIUM: 12 fps. HIGH: every decoded frame. Hardware decode overlaps a bounded
 * GPU-inference worker. All LiteRT/MediaPipe/EGL objects for video are created, executed and closed
 * on that same inference thread to preserve GPU delegate/context affinity. PNG persistence runs on a
 * separate bounded worker so compression no longer serializes every GPU inference.
 */
class GpuPersonCutoutAnalyzerV47(private val context: Context) {
    fun analyzeAndStore(
        clip: TimelineClip,
        prioritySourceUs: Long? = null,
        onAnchorStored: ((completedAnchors: Int) -> Unit)? = null,
        onBackendResolved: ((backend: String) -> Unit)? = null,
    ): PersonCutoutMaskTrackV43 {
        preparePersonCutoutGenerationV47(context, clip)
        val completed = if (clip.isImageV21) {
            analyzeImage(clip, onAnchorStored, onBackendResolved)
        } else {
            analyzeVideo(clip, prioritySourceUs, onAnchorStored, onBackendResolved)
        }
        check(completed > 0) { "Could not generate any V48 portrait matte" }
        markPersonCutoutGenerationV47Ready(context, clip)
        return PersonCutoutMaskStoreV43.index(context, clip)
    }

    private fun analyzeImage(
        clip: TimelineClip,
        onAnchorStored: ((Int) -> Unit)?,
        onBackendResolved: ((String) -> Unit)?,
    ): Int {
        val bitmap = decodeImage(Uri.parse(clip.uri)) ?: error("Could not decode image for Pro Cutout")
        try {
            GpuPersonCutoutSegmenterV47(context).use { segmenter ->
                onBackendResolved?.invoke(segmenter.backendSummary())
                check(segmenter.segmentAndStore(clip, bitmap, clip.sourceInUs)) {
                    "Portrait matting returned no alpha"
                }
                onAnchorStored?.invoke(1)
                segmenter.awaitPendingStores()
            }
        } finally {
            bitmap.recycle()
        }
        return 1
    }

    private fun analyzeVideo(
        clip: TimelineClip,
        prioritySourceUs: Long?,
        onAnchorStored: ((Int) -> Unit)?,
        onBackendResolved: ((String) -> Unit)?,
    ): Int {
        val start = clip.sourceInUs.coerceAtLeast(0L)
        val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
        val settings = clip.resolvedCutoutV43()
        val quality = settings.analysisQualityV47
        val cadence = personCutoutCadenceV47(quality)
        val targetTimes = personCutoutTargetTimesV47(start, end, quality)

        // Important: lazy initialization happens on the inference worker, not this producer thread.
        // MediaPipe GPU delegates and EGL contexts therefore keep create/run/close thread affinity.
        val segmenterLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            GpuPersonCutoutSegmenterV47(context).also { onBackendResolved?.invoke(it.backendSummary()) }
        }
        val segmenterClosed = AtomicBoolean(false)
        val worker = AsyncCutoutInferenceWorkerV48(
            process = { sourceUs, bitmap -> segmenterLazy.value.segmentAndStore(clip, bitmap, sourceUs) },
            onCompleted = onAnchorStored,
        )

        fun closeSegmenterOnWorker() {
            if (segmenterClosed.compareAndSet(false, true) && segmenterLazy.isInitialized()) {
                val segmenter = segmenterLazy.value
                try {
                    segmenter.awaitPendingStores()
                } finally {
                    segmenter.close()
                }
            }
        }

        try {
            // LOW/MEDIUM retain one playhead-priority frame, but it is enqueued to the same GPU
            // worker rather than executing the delegate on the producer thread. HIGH uses only true
            // decoded source-frame timestamps.
            val priority = if (quality == CutoutAnalysisQualityV47.HIGH) {
                null
            } else {
                prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
            }
            if (priority != null) {
                val frame = decodeSinglePriorityFrame(clip, priority)
                if (frame != null) {
                    try {
                        worker.enqueueCopy(priority, frame)
                    } finally {
                        frame.recycle()
                    }
                }
            }

            // Producer: MediaCodec + OES + GL scale. Consumer: LiteRT GPU + MediaPipe GPU + GL flow.
            // The two-frame bound keeps decode and inference overlapped without ballooning memory.
            val sequentialResult = runCatching {
                GpuSequentialCutoutDecoderV47(context, PERSON_ANALYSIS_LONG_EDGE_V47).decodeTargets(
                    uri = Uri.parse(clip.uri),
                    startUs = start,
                    endUs = end,
                    targetTimesUs = targetTimes,
                    emitEveryFrame = cadence.everyDecodedFrame,
                ) { sourceUs, bitmap ->
                    worker.enqueueCopy(sourceUs, bitmap)
                }
            }

            if (sequentialResult.isFailure || sequentialResult.getOrDefault(0) <= 0) {
                if (quality == CutoutAnalysisQualityV47.HIGH) {
                    val cause = sequentialResult.exceptionOrNull()
                    error(
                        "High quality requires every-frame MediaCodec GPU decode on this device" +
                            (cause?.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    )
                }

                // LOW/MEDIUM reliability fallback for unusual vendor codecs. Frames still enter the
                // same GPU inference worker, so model/flow thread affinity and pipelined persistence remain.
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(clip.uri))
                    for (sourceUs in targetTimes) {
                        val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                        try {
                            worker.enqueueCopy(sourceUs, frame)
                        } finally {
                            frame.recycle()
                        }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }

            val completed = worker.awaitIdle()
            worker.runAfterPending { closeSegmenterOnWorker() }
            return completed
        } finally {
            if (!segmenterClosed.get()) {
                runCatching { worker.runAfterPending { closeSegmenterOnWorker() } }
            }
            runCatching { worker.close() }
        }
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
    private var gpuTemporal = runCatching { GpuSpatialFlowTemporalMatteStabilizerV47() }.getOrNull()
    private val cpuTemporal = SpatialFlowTemporalMatteStabilizerV45()
    private val matteWriter = AsyncPersonCutoutMaskWriterV48(appContext)

    private var cachedHairMask: Bitmap? = null
    private var cachedHairTimeUs: Long = Long.MIN_VALUE
    private var cachedHairQuality: CutoutAnalysisQualityV47? = null

    fun backendSummary(): String = buildString {
        append("MODNet "); append(modnet.backendLabel)
        append(" · Hair "); append(if (hair.usingGpuDelegate) "GPU" else "CPU fallback")
        append(" · Flow "); append(if (gpuTemporal != null) "GPU" else "CPU fallback")
        append(" · decode/inference overlapped")
    }

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
            val hairMask = hairMaskForFrame(
                source = source,
                sourceTimeUs = sourceTimeUs,
                quality = settings.analysisQualityV47,
                strength = settings.hairDetailV44,
            )
            val stabilized = try {
                stabilizeWithGpuOrFallback(
                    source = source,
                    modnetMatte = modnetMatte,
                    hairMask = hairMask,
                    sourceTimeUs = sourceTimeUs,
                    hairStrength = settings.hairDetailV44,
                    temporalStrength = settings.temporalStabilityV44,
                )
            } finally {
                modnetMatte.recycle()
            }

            // Ownership transfers to the async writer; PNG compression no longer blocks model work.
            matteWriter.enqueue(clip.uri, sourceTimeUs, stabilized)
            return true
        } finally {
            if (source !== bitmap && !source.isRecycled) source.recycle()
        }
    }

    private fun hairMaskForFrame(
        source: Bitmap,
        sourceTimeUs: Long,
        quality: CutoutAnalysisQualityV47,
        strength: Float,
    ): Bitmap? {
        if (strength <= .001f) {
            cachedHairMask?.recycle()
            cachedHairMask = null
            cachedHairTimeUs = Long.MIN_VALUE
            cachedHairQuality = quality
            return null
        }

        val old = cachedHairMask
        val intervalUs = hairSemanticRefreshIntervalUsV48(quality)
        val monotonic = sourceTimeUs > cachedHairTimeUs
        val shouldRefresh =
            old == null || cachedHairQuality != quality || !monotonic ||
                sourceTimeUs - cachedHairTimeUs >= intervalUs

        if (!shouldRefresh) return old

        val fresh = runCatching { hair.segmentSoftMask(source) }.getOrNull()
        if (fresh != null) {
            if (old != null && old !== fresh && !old.isRecycled) old.recycle()
            cachedHairMask = fresh
            cachedHairTimeUs = sourceTimeUs
            cachedHairQuality = quality
            return fresh
        }
        return old
    }

    private fun stabilizeWithGpuOrFallback(
        source: Bitmap,
        modnetMatte: Bitmap,
        hairMask: Bitmap?,
        sourceTimeUs: Long,
        hairStrength: Float,
        temporalStrength: Float,
    ): Bitmap {
        val gpu = gpuTemporal
        if (gpu != null) {
            val result = runCatching {
                gpu.stabilize(
                    source = source,
                    currentMatte = modnetMatte,
                    hairMask = hairMask,
                    sourceTimeUs = sourceTimeUs,
                    hairStrength = hairStrength,
                    temporalStrength = temporalStrength,
                )
            }
            result.getOrNull()?.let { return it }
            runCatching { gpu.close() }
            gpuTemporal = null
        }

        // Compatibility fallback only; normal V48 devices stay on the GL path above.
        val fused = fuseHairCpu(modnetMatte, hairMask, hairStrength)
        return try {
            cpuTemporal.stabilize(source, fused, sourceTimeUs, temporalStrength)
        } finally {
            fused.recycle()
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

    fun awaitPendingStores() {
        matteWriter.awaitIdle()
    }

    override fun close() {
        runCatching { matteWriter.close() }
        cachedHairMask?.let { if (!it.isRecycled) it.recycle() }
        cachedHairMask = null
        runCatching { gpuTemporal?.close() }
        gpuTemporal = null
        cpuTemporal.close()
        runCatching { hair.close() }
        runCatching { modnet.close() }
    }
}

/** LiteRT CompiledModel requests a true GPU-only compile first, then hybrid, then CPU fallback. */
private class GpuModNetPortraitMatteV47(context: Context) : AutoCloseable {
    private val model: CompiledModel
    val backendLabel: String
    private val inputBuffers: List<com.google.ai.edge.litert.TensorBuffer>
    private val outputBuffers: List<com.google.ai.edge.litert.TensorBuffer>

    private val plane = MODNET_SIZE_V47 * MODNET_SIZE_V47
    private val inputPixels = IntArray(plane)
    private val inputTensor = FloatArray(plane * 3)
    private val alphaPixels = IntArray(plane)
    private val inputSquare = Bitmap.createBitmap(MODNET_SIZE_V47, MODNET_SIZE_V47, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODNET_SIZE_V47, MODNET_SIZE_V47, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        val app = context.applicationContext
        val gpuOnly = runCatching {
            CompiledModel.create(
                app.assets,
                MODNET_MODEL_ASSET_V47,
                CompiledModel.Options(Accelerator.GPU),
            )
        }
        if (gpuOnly.isSuccess) {
            model = gpuOnly.getOrThrow()
            backendLabel = "GPU-only"
        } else {
            val hybrid = runCatching {
                CompiledModel.create(
                    app.assets,
                    MODNET_MODEL_ASSET_V47,
                    CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
                )
            }
            if (hybrid.isSuccess) {
                model = hybrid.getOrThrow()
                backendLabel = "GPU/hybrid"
            } else {
                model = CompiledModel.create(
                    app.assets,
                    MODNET_MODEL_ASSET_V47,
                    CompiledModel.Options(Accelerator.CPU).apply {
                        cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
                    },
                )
                backendLabel = "CPU fallback"
            }
        }
        inputBuffers = model.createInputBuffers()
        outputBuffers = model.createOutputBuffers()
        check(inputBuffers.isNotEmpty() && outputBuffers.isNotEmpty()) {
            "MODNet did not expose input/output buffers"
        }
    }

    fun infer(source: Bitmap): Bitmap {
        val prepared = letterboxGeometry(source)
        inputSquare.eraseColor(Color.BLACK)
        inputCanvas.drawBitmap(
            source,
            null,
            Rect(prepared.left, prepared.top, prepared.left + prepared.contentWidth, prepared.top + prepared.contentHeight),
            filterPaint,
        )
        inputSquare.getPixels(
            inputPixels,
            0,
            MODNET_SIZE_V47,
            0,
            0,
            MODNET_SIZE_V47,
            MODNET_SIZE_V47,
        )

        // Current LiteRT Kotlin still exposes a host FloatArray boundary, but these buffers and the
        // letterbox bitmap are reused instead of allocated for every frame.
        for (i in 0 until plane) {
            val pixel = inputPixels[i]
            inputTensor[i] = Color.red(pixel) / 127.5f - 1f
            inputTensor[plane + i] = Color.green(pixel) / 127.5f - 1f
            inputTensor[plane * 2 + i] = Color.blue(pixel) / 127.5f - 1f
        }
        inputBuffers[0].writeFloat(inputTensor)
        model.run(inputBuffers, outputBuffers)
        val alpha = outputBuffers[0].readFloat()
        check(alpha.size >= plane) { "MODNet alpha output was ${alpha.size}; expected at least $plane values" }

        for (i in 0 until plane) {
            val v = (alpha[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, MODNET_SIZE_V47, 0, 0, MODNET_SIZE_V47, MODNET_SIZE_V47)

        // Draw straight from the valid letterbox region to source resolution; no per-frame cropped
        // intermediate Bitmap or createScaledBitmap allocation remains.
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            alphaSquare,
            Rect(prepared.left, prepared.top, prepared.left + prepared.contentWidth, prepared.top + prepared.contentHeight),
            Rect(0, 0, source.width, source.height),
            filterPaint,
        )
        return output
    }

    private fun letterboxGeometry(source: Bitmap): LetterboxV47 {
        val scale = min(
            MODNET_SIZE_V47 / source.width.toFloat(),
            MODNET_SIZE_V47 / source.height.toFloat(),
        )
        val width = (source.width * scale).roundToInt().coerceIn(1, MODNET_SIZE_V47)
        val height = (source.height * scale).roundToInt().coerceIn(1, MODNET_SIZE_V47)
        return LetterboxV47(
            left = (MODNET_SIZE_V47 - width) / 2,
            top = (MODNET_SIZE_V47 - height) / 2,
            contentWidth = width,
            contentHeight = height,
        )
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { model.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }

    private data class LetterboxV47(
        val left: Int,
        val top: Int,
        val contentWidth: Int,
        val contentHeight: Int,
    )
}
