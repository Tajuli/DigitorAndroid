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
import kotlin.math.max
import kotlin.math.roundToInt

private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 1280

internal data class PersonCutoutAnalysisResultV47(
    val analyzedFrames: Int,
    val attemptedFrames: Int,
    val backendSummary: String,
    val error: String? = null,
)

internal class GpuPersonCutoutAnalyzerV47(
    context: Context,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val segmenter = GpuPersonCutoutSegmenterV47(appContext)
    private val decoder = GpuSequentialCutoutDecoderV47(appContext)

    fun analyze(
        clip: TimelineClip,
        onProgress: (Float) -> Unit,
    ): PersonCutoutAnalysisResultV47 {
        preparePersonCutoutGenerationV47(appContext, clip)
        val settings = clip.resolvedCutoutV43()
        val policy = PersonCutoutAnalysisPolicyV46.forQuality(settings.analysisQualityV47)
        var attempted = 0
        var analyzed = 0
        var error: String? = null

        try {
            decoder.decode(
                clip = clip,
                policy = policy,
                onFrame = { bitmap, sourceTimeUs, progress ->
                    attempted++
                    val stored = runCatching {
                        segmenter.segmentAndStore(clip, bitmap, sourceTimeUs)
                    }.getOrElse { throwable ->
                        error = throwable.message ?: throwable.javaClass.simpleName
                        false
                    }
                    if (stored) analyzed++
                    onProgress(progress)
                    error == null
                },
            )
            if (error == null && analyzed > 0) {
                segmenter.flushWrites()
                markPersonCutoutGenerationV47Ready(appContext, clip)
            }
        } catch (throwable: Throwable) {
            error = throwable.message ?: throwable.javaClass.simpleName
        }

        return PersonCutoutAnalysisResultV47(
            analyzedFrames = analyzed,
            attemptedFrames = attempted,
            backendSummary = segmenter.backendSummary(),
            error = error,
        )
    }

    override fun close() {
        runCatching { decoder.close() }
        runCatching { segmenter.close() }
    }
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
        append(" · Hair/hijab/body safety headroom")
        append(" · Outward-fast/inward-slow bbox motion tracking")
        append(" · Final ROI clamp")
        append(" · Fresh neural matte every analyzed frame")
        append(" · Hair "); append(if (hair.usingGpuDelegate) "GPU" else "CPU fallback")
        append(" · Temporal refine "); append(if (gpuTemporal != null) "GPU" else "CPU fallback")
        append(" · CPU scheduler")
    }

    fun segmentAndStore(clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not convert decoded frame to ARGB_8888 for Pro Cutout")
        }
        try {
            val settings = clip.resolvedCutoutV43()
            val quality = settings.analysisQualityV47
            val hairMask = hairMaskForFrame(source, sourceTimeUs, quality)
            val neuralMatte = roiMatte.infer(source, sourceTimeUs)
            val hairRefined = try {
                refineWithHairV44(neuralMatte, hairMask, settings.hairDetailV44)
            } finally {
                neuralMatte.recycle()
            }

            val temporal = try {
                stabilizeTemporal(source, hairRefined, sourceTimeUs, settings.temporalStabilityV44)
            } finally {
                hairRefined.recycle()
            }

            val clamped = try {
                roiMatte.clampToActiveRoi(temporal)
            } finally {
                temporal.recycle()
            }
            return try {
                matteWriter.write(clip, sourceTimeUs, clamped)
                true
            } finally {
                clamped.recycle()
            }
        } finally {
            if (source !== bitmap && !source.isRecycled) source.recycle()
        }
    }

    fun flushWrites() {
        matteWriter.flush()
    }

    private fun hairMaskForFrame(
        source: Bitmap,
        sourceTimeUs: Long,
        quality: CutoutAnalysisQualityV47,
    ): Bitmap? {
        val cached = cachedHairMask
        val maxAgeUs = when (quality) {
            CutoutAnalysisQualityV47.LOW -> 1_000_000L
            CutoutAnalysisQualityV47.MEDIUM -> 600_000L
            CutoutAnalysisQualityV47.HIGH -> 350_000L
        }
        if (
            cached != null && !cached.isRecycled && cachedHairQuality == quality &&
            cachedHairTimeUs != Long.MIN_VALUE && sourceTimeUs >= cachedHairTimeUs &&
            sourceTimeUs - cachedHairTimeUs <= maxAgeUs
        ) return cached

        val fresh = runCatching { hair.segment(source) }.getOrNull() ?: return cached
        if (cached != null && cached !== fresh && !cached.isRecycled) cached.recycle()
        cachedHairMask = fresh
        cachedHairTimeUs = sourceTimeUs
        cachedHairQuality = quality
        return fresh
    }

    private fun stabilizeTemporal(
        source: Bitmap,
        matte: Bitmap,
        sourceTimeUs: Long,
        amount: Float,
    ): Bitmap {
        val gpu = gpuTemporal
        if (gpu != null) {
            val result = runCatching { gpu.stabilize(source, matte, sourceTimeUs, amount) }.getOrNull()
            if (result != null) return result
            runCatching { gpu.close() }
            gpuTemporal = null
        }
        return cpuTemporal.stabilize(source, matte, sourceTimeUs, amount)
    }

    override fun close() {
        cachedHairMask?.let { if (!it.isRecycled) it.recycle() }
        cachedHairMask = null
        runCatching { gpuTemporal?.close() }
        runCatching { cpuTemporal.close() }
        runCatching { hair.close() }
        runCatching { roiMatte.close() }
        runCatching { portraitMatte.close() }
        runCatching { matteWriter.close() }
    }
}

private class GpuSequentialCutoutDecoderV47(
    private val context: Context,
) : AutoCloseable {
    fun decode(
        clip: TimelineClip,
        policy: PersonCutoutAnalysisPolicyV46,
        onFrame: (Bitmap, Long, Float) -> Boolean,
    ) {
        val uri = Uri.parse(clip.uri)
        if (uri.scheme == "content" || uri.scheme == "file") decodeVideoOrImage(uri, clip, policy, onFrame)
        else {
            val bitmap = decodeImage(uri) ?: error("Could not decode Pro Cutout source")
            try { onFrame(bitmap, clip.sourceInUs, 1f) } finally { bitmap.recycle() }
        }
    }

    private fun decodeVideoOrImage(
        uri: Uri,
        clip: TimelineClip,
        policy: PersonCutoutAnalysisPolicyV46,
        onFrame: (Bitmap, Long, Float) -> Boolean,
    ) {
        val videoResult = runCatching {
            AsyncCutoutInferenceWorkerV48(context).use { worker ->
                worker.decode(uri, clip, policy) { bitmap, sourceTimeUs, progress ->
                    val normalized = normalizeForAnalysis(bitmap)
                    try { onFrame(normalized, sourceTimeUs, progress) }
                    finally { if (normalized !== bitmap && !normalized.isRecycled) normalized.recycle() }
                }
            }
        }
        if (videoResult.isSuccess) return

        val bitmap = decodeImage(uri) ?: throw videoResult.exceptionOrNull() ?: error("Could not decode Pro Cutout source")
        try { onFrame(bitmap, clip.sourceInUs, 1f) } finally { bitmap.recycle() }
    }

    private fun normalizeForAnalysis(raw: Bitmap): Bitmap {
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

    private fun ensureArgb(bitmap: Bitmap): Bitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else {
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

    override fun close() = Unit
}
