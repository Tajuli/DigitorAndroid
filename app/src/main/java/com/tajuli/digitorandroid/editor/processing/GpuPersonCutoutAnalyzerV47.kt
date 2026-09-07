package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Decode a materially denser analysis proxy before person cropping. PP-MattingV2 still receives a
// fixed 384 tensor after the verified crop.
private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 1280

/**
 * Per-frame PP-MattingV2 Pro Cutout analyzer with V66 durable checkpoint/resume.
 *
 * Completed PNG mattes are durable frame checkpoints. If the exact analysis signature is still
 * pending after Pause/process death/native crash, Low/Medium skip already-covered target times and
 * High restarts at the first gap after the durable contiguous prefix. Changing any analysis-time
 * setting invalidates the signature and beginPersonCutoutGenerationV66 clears the old mattes.
 */
class GpuPersonCutoutAnalyzerV47(private val context: Context) {
    fun analyzeAndStore(
        clip: TimelineClip,
        prioritySourceUs: Long? = null,
        onAnchorStored: ((completedAnchors: Int) -> Unit)? = null,
        onBackendResolved: ((backend: String) -> Unit)? = null,
    ): PersonCutoutMaskTrackV43 {
        val generation = beginPersonCutoutGenerationV66(context, clip)
        CutoutAnalysisRuntimeV66.throwIfPauseRequested()

        val newlyCompleted = if (clip.isImageV21) {
            analyzeImage(clip, generation, onAnchorStored, onBackendResolved)
        } else {
            analyzeVideo(clip, generation, prioritySourceUs, onAnchorStored, onBackendResolved)
        }
        val total = generation.savedFrames + newlyCompleted
        check(total > 0) { "Could not generate any per-frame PP-MattingV2 portrait matte" }
        CutoutAnalysisRuntimeV66.throwIfPauseRequested()
        markPersonCutoutGenerationV47Ready(context, clip)
        return PersonCutoutMaskStoreV43.index(context, clip)
    }

    private fun analyzeImage(
        clip: TimelineClip,
        generation: PersonCutoutGenerationStartV66,
        onAnchorStored: ((Int) -> Unit)?,
        onBackendResolved: ((String) -> Unit)?,
    ): Int {
        if (generation.resumed && generation.durableTimesUs.any { abs(it - clip.sourceInUs) <= 1_000L }) {
            onAnchorStored?.invoke(generation.savedFrames)
            return 0
        }
        CutoutAnalysisRuntimeV66.throwIfPauseRequested()
        val bitmap = decodeImage(Uri.parse(clip.uri)) ?: error("Could not decode image for Pro Cutout")
        try {
            GpuPersonCutoutSegmenterV47(context).use { segmenter ->
                onBackendResolved?.invoke(segmenter.backendSummary())
                check(segmenter.segmentAndStore(clip, bitmap, clip.sourceInUs)) {
                    "Portrait matting returned no alpha"
                }
                segmenter.awaitPendingStores()
                onAnchorStored?.invoke(generation.savedFrames + 1)
            }
        } finally {
            bitmap.recycle()
        }
        return 1
    }

    private fun analyzeVideo(
        clip: TimelineClip,
        generation: PersonCutoutGenerationStartV66,
        prioritySourceUs: Long?,
        onAnchorStored: ((Int) -> Unit)?,
        onBackendResolved: ((String) -> Unit)?,
    ): Int {
        val start = clip.sourceInUs.coerceAtLeast(0L)
        val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
        val settings = clip.resolvedCutoutV43()
        val quality = settings.analysisQualityV47
        val cadence = personCutoutCadenceV47(quality)
        val allTargetTimes = personCutoutTargetTimesV47(start, end, quality)
        val existing = generation.durableTimesUs

        val targetTimes = if (cadence.everyDecodedFrame) {
            emptyList()
        } else if (generation.resumed) {
            allTargetTimes.filterNot { target -> existingCoversTargetV66(existing, target, quality) }
        } else {
            allTargetTimes
        }
        val decodeStartUs = if (cadence.everyDecodedFrame && generation.resumed) {
            highResumeStartUsV66(existing, start, end)
        } else if (!cadence.everyDecodedFrame && generation.resumed) {
            targetTimes.firstOrNull()?.let { target ->
                (target - resumeLeadUsV66(quality)).coerceAtLeast(start)
            } ?: end
        } else {
            start
        }

        if (decodeStartUs >= end && (cadence.everyDecodedFrame || targetTimes.isEmpty())) {
            onAnchorStored?.invoke(generation.savedFrames)
            return 0
        }

        val segmenterLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            GpuPersonCutoutSegmenterV47(context).also {
                onBackendResolved?.invoke(it.backendSummary())
            }
        }
        val segmenterClosed = AtomicBoolean(false)
        val worker = AsyncCutoutInferenceWorkerV48(
            process = { sourceUs, bitmap -> segmenterLazy.value.segmentAndStore(clip, bitmap, sourceUs) },
            onCompleted = { newCount -> onAnchorStored?.invoke(generation.savedFrames + newCount) },
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
            // Priority/playhead analysis is useful on a fresh Low/Medium pass, but a resume should
            // fill the first durable gap chronologically rather than create another out-of-order island.
            val priority = if (
                generation.resumed || quality == CutoutAnalysisQualityV47.HIGH
            ) {
                null
            } else {
                prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
            }
            if (priority != null) {
                CutoutAnalysisRuntimeV66.throwIfPauseRequested()
                decodeSinglePriorityFrame(clip, priority)?.let { frame ->
                    worker.enqueueOwned(priority, frame)
                }
            }

            CutoutAnalysisRuntimeV66.throwIfPauseRequested()
            val sequentialResult = runCatching {
                GpuSequentialCutoutDecoderV47(context, PERSON_ANALYSIS_LONG_EDGE_V47).decodeTargets(
                    uri = Uri.parse(clip.uri),
                    startUs = decodeStartUs,
                    endUs = end,
                    targetTimesUs = targetTimes,
                    emitEveryFrame = cadence.everyDecodedFrame,
                ) { sourceUs, bitmap ->
                    CutoutAnalysisRuntimeV66.throwIfPauseRequested()
                    worker.enqueueOwned(sourceUs, bitmap)
                }
            }

            val sequentialCause = sequentialResult.exceptionOrNull()
            if (sequentialCause is CutoutAnalysisPausedV66) throw sequentialCause

            if (sequentialResult.isFailure || sequentialResult.getOrDefault(0) <= 0) {
                if (quality == CutoutAnalysisQualityV47.HIGH) {
                    val cause = sequentialCause
                    error(
                        "High quality requires every-frame MediaCodec GPU decode on this device" +
                            (cause?.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    )
                }

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(clip.uri))
                    for (sourceUs in targetTimes) {
                        CutoutAnalysisRuntimeV66.throwIfPauseRequested()
                        val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                        worker.enqueueOwned(sourceUs, frame)
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }

            val completed = worker.awaitIdle()
            worker.runAfterPending { closeSegmenterOnWorker() }
            CutoutAnalysisRuntimeV66.throwIfPauseRequested()
            return completed
        } finally {
            if (!segmenterClosed.get()) {
                runCatching { worker.runAfterPending { closeSegmenterOnWorker() } }
            }
            runCatching { worker.close() }
        }
    }

    private fun existingCoversTargetV66(
        existing: List<Long>,
        targetUs: Long,
        quality: CutoutAnalysisQualityV47,
    ): Boolean {
        if (existing.isEmpty()) return false
        val tolerance = when (quality) {
            CutoutAnalysisQualityV47.LOW -> 70_000L
            CutoutAnalysisQualityV47.MEDIUM -> 40_000L
            CutoutAnalysisQualityV47.HIGH -> 8_000L
        }
        return existing.any { abs(it - targetUs) <= tolerance }
    }

    private fun resumeLeadUsV66(quality: CutoutAnalysisQualityV47): Long = when (quality) {
        CutoutAnalysisQualityV47.LOW -> 80_000L
        CutoutAnalysisQualityV47.MEDIUM -> 50_000L
        CutoutAnalysisQualityV47.HIGH -> 0L
    }

    /**
     * High-mode writes arrive sequentially but the writer pipeline can leave the final couple of
     * PNGs out of order during an abrupt process death. Find the first meaningful gap instead of
     * blindly trusting max(timestamp), then decode again from just after the last contiguous frame.
     */
    private fun highResumeStartUsV66(existing: List<Long>, startUs: Long, endUs: Long): Long {
        val times = existing.filter { it >= startUs && it < endUs }.distinct().sorted()
        if (times.isEmpty()) return startUs
        val deltas = times.zipWithNext { a, b -> b - a }
            .filter { it in 1L..200_000L }
            .sorted()
        val typical = deltas.getOrNull(deltas.size / 2) ?: 33_333L
        val gapThreshold = max(25_000L, (typical * 18L) / 10L)
        if (times.first() > startUs + gapThreshold) return startUs
        var last = times.first()
        for (time in times.drop(1)) {
            if (time - last > gapThreshold) return (last + 1L).coerceAtMost(endUs)
            last = time
        }
        return (last + 1L).coerceAtMost(endUs)
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
    private val portraitMatte = PpMattingV2PortraitMatteV50(appContext)
    private val roiMatte = PersonRoiMatteV57(appContext, portraitMatte)
    private val hair = BeautyHairSegmenterV29(appContext)
    private var gpuTemporal = runCatching { GpuSpatialFlowTemporalMatteStabilizerV47() }.getOrNull()
    private val cpuTemporal = SpatialFlowTemporalMatteStabilizerV45()
    private val matteWriter = AsyncPersonCutoutMaskWriterV48(appContext)

    private var cachedHairMask: Bitmap? = null
    private var cachedHairTimeUs: Long = Long.MIN_VALUE
    private var cachedHairQuality: CutoutAnalysisQualityV47? = null

    fun backendSummary(): String = buildString {
        append(portraitMatte.backendLabel)
        append(" · Motion-safe tight person ROI (").append(roiMatte.detectorBackendLabel).append(")")
        append(" · Crop before PP-MattingV2 384 resize")
        append(" · In-box alpha PP-MattingV2 RAW")
        append(" · Final ROI clamp")
        append(" · Durable per-frame checkpoint + Resume")
        if (useInterruptionSafeSerialCutoutV66()) {
            append(" · UNISOC interruption-safe serial GL→Vulkan")
        }
        append(" · Hair "); append(if (hair.usingGpuDelegate) "GPU" else "CPU fallback")
        append(" · Temporal refine "); append(if (gpuTemporal != null) "GPU" else "CPU fallback")
        append(" · CPU scheduler")
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
            val quality = settings.analysisQualityV47
            val hairMask = hairMaskForFrame(
                source = source,
                sourceTimeUs = sourceTimeUs,
                quality = quality,
                strength = settings.hairDetailV44,
            )

            val baseMatte = roiMatte.infer(source, sourceTimeUs)
            val stabilized = try {
                stabilizeWithGpuOrFallback(
                    source = source,
                    baseMatte = baseMatte,
                    hairMask = hairMask,
                    sourceTimeUs = sourceTimeUs,
                    hairStrength = settings.hairDetailV44,
                    temporalStrength = settings.temporalStabilityV44,
                )
            } finally {
                baseMatte.recycle()
            }

            val finalMatte = try {
                roiMatte.clampToActiveRoi(stabilized)
            } finally {
                stabilized.recycle()
            }
            matteWriter.enqueue(clip.uri, sourceTimeUs, finalMatte)
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
        baseMatte: Bitmap,
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
                    currentMatte = baseMatte,
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

        val fused = fuseHairCpu(baseMatte, hairMask, hairStrength)
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
                val v = (fused * 255f + .5f).toInt().coerceIn(0, 255)
                out[i] = Color.argb(255, v, v, v)
            }
            return Bitmap.createBitmap(out, base.width, base.height, Bitmap.Config.ARGB_8888)
        } finally {
            scaledHair.recycle()
        }
    }

    fun awaitPendingStores() = matteWriter.awaitIdle()

    override fun close() {
        // Drain then actually shut down all long-lived worker/state objects. The previous code only
        // awaited the matte writer, leaving its executor thread alive after every Analyze/Resume.
        runCatching { matteWriter.close() }
        cachedHairMask?.recycle()
        cachedHairMask = null
        runCatching { hair.close() }
        runCatching { roiMatte.close() }
        runCatching { portraitMatte.close() }
        runCatching { gpuTemporal?.close() }
        gpuTemporal = null
        runCatching { cpuTemporal.close() }
    }
}
