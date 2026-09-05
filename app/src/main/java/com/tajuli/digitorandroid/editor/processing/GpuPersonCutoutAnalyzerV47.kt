package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 720

/**
 * V51 strict hardware/GPU revision of the adaptive Pro Cutout analyzer.
 *
 * LOW: 4 fps. MEDIUM: 12 fps. HIGH: every decoded frame. All video qualities now require the
 * MediaCodec + OES + GL decoder path; the old MediaMetadataRetriever software fallback is removed.
 * PP-MattingV2 runs through hardware-only NNAPI, MediaPipe hair requires its GPU delegate, and local
 * spatial-flow temporal refinement requires GLES. If one of those accelerators is unavailable the
 * operation fails explicitly instead of silently becoming a slow CPU job.
 *
 * Final matte readback/PNG persistence is still an intentional CPU/file-I/O boundary because the
 * existing preview/export cache contract is Bitmap/file based. Neural inference and temporal/hair
 * compute no longer have a CPU fallback in this analyzer.
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
        check(completed > 0) { "Could not generate any V51 hardware portrait matte" }
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
        val regularTargets = personCutoutTargetTimesV47(start, end, quality)

        // Preserve the playhead-priority intent without invoking MediaMetadataRetriever: insert the
        // requested time into the same MediaCodec/GL target list for LOW/MEDIUM. HIGH already emits
        // every decoded source frame and therefore needs no extra target.
        val targetTimes = if (quality == CutoutAnalysisQualityV47.HIGH || prioritySourceUs == null) {
            regularTargets
        } else {
            buildList {
                add(prioritySourceUs.coerceIn(start, (end - 1L).coerceAtLeast(start)))
                addAll(regularTargets)
            }.distinct().sorted()
        }

        // Important: lazy initialization happens on the inference worker, not this producer thread.
        // MediaPipe GPU delegates and EGL contexts therefore keep create/run/close thread affinity.
        val segmenterLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            GpuPersonCutoutSegmenterV47(context).also {
                onBackendResolved?.invoke(it.backendSummary())
            }
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
            // Producer: MediaCodec + OES + GL scale. Consumer: NNAPI PP-MattingV2 + MediaPipe GPU
            // Hair + GL temporal flow. Decoder Bitmaps are transferred directly to the bounded worker.
            val sequentialResult = runCatching {
                GpuSequentialCutoutDecoderV47(context, PERSON_ANALYSIS_LONG_EDGE_V47).decodeTargets(
                    uri = Uri.parse(clip.uri),
                    startUs = start,
                    endUs = end,
                    targetTimesUs = targetTimes,
                    emitEveryFrame = cadence.everyDecodedFrame,
                ) { sourceUs, bitmap ->
                    worker.enqueueOwned(sourceUs, bitmap)
                }
            }

            if (sequentialResult.isFailure || sequentialResult.getOrDefault(0) <= 0) {
                val cause = sequentialResult.exceptionOrNull()
                error(
                    "Pro Cutout requires MediaCodec + GPU frame decode on this device; software decode fallback is disabled" +
                        (cause?.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                )
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

    private fun ensureArgb(bitmap: Bitmap): Bitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not convert image to ARGB_8888")
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // The ONNX tensor packer and MediaPipe BitmapImageBuilder need CPU-addressable
                // pixels. This is a decode/input boundary, not neural/temporal compute.
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
    private val hair = BeautyHairSegmenterV29(appContext, requireGpu = true)
    private val gpuTemporal = GpuSpatialFlowTemporalMatteStabilizerV47()
    private val matteWriter = AsyncPersonCutoutMaskWriterV48(appContext)

    private var cachedHairMask: Bitmap? = null
    private var cachedHairTimeUs: Long = Long.MIN_VALUE
    private var cachedHairQuality: CutoutAnalysisQualityV47? = null

    fun backendSummary(): String = buildString {
        append(portraitMatte.backendLabel)
        append(" · Hair GPU-only")
        append(" · Flow GPU-only")
        append(" · MediaCodec/GL decode")
        append(" · direct frame queue")
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
            val baseMatte = portraitMatte.infer(source)
            val hairMask = hairMaskForFrame(
                source = source,
                sourceTimeUs = sourceTimeUs,
                quality = settings.analysisQualityV47,
                strength = settings.hairDetailV44,
            )
            val stabilized = try {
                stabilizeGpuOnly(
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

            // Ownership transfers to the parallel low-priority writers; PNG compression/file I/O is
            // intentionally kept behind inference so it cannot back-pressure the accelerator thread.
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

        val fresh = try {
            hair.segmentSoftMask(source)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "MediaPipe GPU hair inference failed; CPU fallback is disabled for Pro Cutout",
                error,
            )
        }
        if (fresh != null) {
            if (old != null && old !== fresh && !old.isRecycled) old.recycle()
            cachedHairMask = fresh
            cachedHairTimeUs = sourceTimeUs
            cachedHairQuality = quality
            return fresh
        }
        return old
    }

    private fun stabilizeGpuOnly(
        source: Bitmap,
        baseMatte: Bitmap,
        hairMask: Bitmap?,
        sourceTimeUs: Long,
        hairStrength: Float,
        temporalStrength: Float,
    ): Bitmap = try {
        gpuTemporal.stabilize(
            source = source,
            currentMatte = baseMatte,
            hairMask = hairMask,
            sourceTimeUs = sourceTimeUs,
            hairStrength = hairStrength,
            temporalStrength = temporalStrength,
        )
    } catch (error: Throwable) {
        throw IllegalStateException(
            "GPU temporal matte refinement failed; CPU fallback is disabled for Pro Cutout",
            error,
        )
    }

    fun awaitPendingStores() {
        matteWriter.awaitIdle()
    }

    override fun close() {
        runCatching { matteWriter.close() }
        cachedHairMask?.let { if (!it.isRecycled) it.recycle() }
        cachedHairMask = null
        runCatching { gpuTemporal.close() }
        runCatching { hair.close() }
        runCatching { portraitMatte.close() }
    }
}
