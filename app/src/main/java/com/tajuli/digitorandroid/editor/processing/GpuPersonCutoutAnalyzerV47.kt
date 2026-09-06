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

private const val PERSON_ANALYSIS_LONG_EDGE_V47 = 720

/**
 * V58 PP-MattingV2 Paddle Lite/OpenCL revision of the adaptive Pro Cutout analyzer.
 *
 * LOW: 4 fps. MEDIUM: 12 fps. HIGH: every decoded frame. PP-MattingV2/STDC1 512 supplies the
 * base soft alpha through an OpenCL-only FP32 program. Decoder Bitmap ownership still transfers
 * directly, but the V58 worker is a strict barrier so OES/GL readback cannot overlap the current
 * Paddle Lite OpenCL inference on vendor GPUs that corrupt frames under cross-context pressure.
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
        check(completed > 0) { "Could not generate any V58 PP-MattingV2 portrait matte" }
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
            val priority = if (quality == CutoutAnalysisQualityV47.HIGH) {
                null
            } else {
                prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
            }
            if (priority != null) {
                decodeSinglePriorityFrame(clip, priority)?.let { frame ->
                    worker.enqueueOwned(priority, frame)
                }
            }

            // V61 verifies the decoded OES frame itself before PP-MattingV2. A corrupt
            // decoder/readback band otherwise looks like a legitimate scene change to the V60 matte guard.
            val sourceFrameGuardV61 = SequentialSourceFrameGuardV61()
            val sequentialResult = runCatching {
                GpuSequentialCutoutDecoderV47(context, PERSON_ANALYSIS_LONG_EDGE_V47).decodeTargets(
                    uri = Uri.parse(clip.uri),
                    startUs = start,
                    endUs = end,
                    targetTimesUs = targetTimes,
                    emitEveryFrame = cadence.everyDecodedFrame,
                ) { sourceUs, bitmap ->
                    // V61 catches transient OES/readback bands and independently re-decodes only
                    // that timestamp before the still-GPU PP-MattingV2 neural inference.
                    val verified = sourceFrameGuardV61.select(bitmap) {
                        decodeSinglePriorityFrame(clip, sourceUs)
                    }
                    worker.enqueueOwned(sourceUs, verified)
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

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(clip.uri))
                    for (sourceUs in targetTimes) {
                        val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                        worker.enqueueOwned(sourceUs, frame)
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

/**
 * Detects transient wide horizontal/vertical corruption in the MediaCodec/OES analysis Bitmap.
 * A flagged timestamp is re-decoded once through MediaMetadataRetriever; PP-MattingV2 inference
 * remains OpenCL GPU. Only small-band changes are considered suspicious; broad row+column changes
 * are treated as legitimate scene cuts.
 */
private class SequentialSourceFrameGuardV61 {
    private data class Profile(val rows: FloatArray, val columns: FloatArray)

    private var previous: Profile? = null
    private var scratch = IntArray(0)

    fun select(candidate: Bitmap, safeDecode: () -> Bitmap?): Bitmap {
        val candidateProfile = profile(candidate)
        val old = previous
        if (old == null || old.rows.size != candidateProfile.rows.size ||
            old.columns.size != candidateProfile.columns.size
        ) {
            previous = candidateProfile
            return candidate
        }

        if (!looksLikeTransientBand(old, candidateProfile)) {
            previous = candidateProfile
            return candidate
        }

        val safe = runCatching { safeDecode() }.getOrNull()
        if (safe != null) {
            previous = profile(safe)
            if (!candidate.isRecycled) candidate.recycle()
            return safe
        }

        // Do not promote the suspect profile to history if independent decode is unavailable.
        return candidate
    }

    private fun looksLikeTransientBand(previous: Profile, current: Profile): Boolean {
        var strongRows = 0
        var strongColumns = 0
        var maxRowDelta = 0f
        var maxColumnDelta = 0f

        for (i in current.rows.indices) {
            val delta = abs(previous.rows[i] - current.rows[i])
            if (delta >= .22f) strongRows++
            if (delta > maxRowDelta) maxRowDelta = delta
        }
        for (i in current.columns.indices) {
            val delta = abs(previous.columns[i] - current.columns[i])
            if (delta >= .22f) strongColumns++
            if (delta > maxColumnDelta) maxColumnDelta = delta
        }

        val rowFraction = strongRows / current.rows.size.coerceAtLeast(1).toFloat()
        val columnFraction = strongColumns / current.columns.size.coerceAtLeast(1).toFloat()
        if (rowFraction >= .55f && columnFraction >= .55f) return false

        val horizontalBand = maxRowDelta >= .30f && strongRows > 0 && rowFraction <= .42f
        val verticalBand = maxColumnDelta >= .30f && strongColumns > 0 && columnFraction <= .42f
        return horizontalBand || verticalBand
    }

    private fun profile(bitmap: Bitmap): Profile {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val required = width * height
        if (scratch.size < required) scratch = IntArray(required)
        bitmap.getPixels(scratch, 0, width, 0, 0, width, height)

        val rows = FloatArray(height)
        val columns = FloatArray(width)
        val xStep = (width / 48).coerceAtLeast(1)
        val yStep = (height / 48).coerceAtLeast(1)

        for (y in 0 until height) {
            var total = 0f
            var count = 0
            var x = xStep / 2
            while (x < width) {
                total += luma(scratch[y * width + x])
                count++
                x += xStep
            }
            rows[y] = total / count.coerceAtLeast(1).toFloat()
        }
        for (x in 0 until width) {
            var total = 0f
            var count = 0
            var y = yStep / 2
            while (y < height) {
                total += luma(scratch[y * width + x])
                count++
                y += yStep
            }
            columns[x] = total / count.coerceAtLeast(1).toFloat()
        }
        return Profile(rows, columns)
    }

    private fun luma(pixel: Int): Float {
        val r = ((pixel ushr 16) and 0xff) / 255f
        val g = ((pixel ushr 8) and 0xff) / 255f
        val b = (pixel and 0xff) / 255f
        return .2126f * r + .7152f * g + .0722f * b
    }
}

private class GpuPersonCutoutSegmenterV47(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val portraitMatte = PpMattingV2PortraitMatteV50(appContext)
    private val hair = BeautyHairSegmenterV29(appContext)
    private var gpuTemporal = runCatching { GpuSpatialFlowTemporalMatteStabilizerV47() }.getOrNull()
    private val cpuTemporal = SpatialFlowTemporalMatteStabilizerV45()
    private val matteWriter = AsyncPersonCutoutMaskWriterV48(appContext)

    private var cachedHairMask: Bitmap? = null
    private var cachedHairTimeUs: Long = Long.MIN_VALUE
    private var cachedHairQuality: CutoutAnalysisQualityV47? = null

    fun backendSummary(): String = buildString {
        append(portraitMatte.backendLabel)
        append(" · Hair "); append(if (hair.usingGpuDelegate) "GPU" else "CPU fallback")
        append(" · Flow "); append(if (gpuTemporal != null) "GPU" else "CPU fallback")
        append(" · serialized GPU frames")
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
            result.getOrNull()?.let { refined ->
                if (isRefinedMatteSaneV59(baseMatte, refined)) return refined

                // Some mobile GPU drivers can return a framebuffer with tile/stripe-corrupted
                // alpha without reporting a GL error. Do not persist it or feed it into temporal
                // history. Keep the current PP-MattingV2 matte authoritative for this anchor.
                if (!refined.isRecycled) refined.recycle()
                runCatching { gpu.close() }
                gpuTemporal = runCatching { GpuSpatialFlowTemporalMatteStabilizerV47() }.getOrNull()
                return baseMatte.copy(Bitmap.Config.ARGB_8888, false)
                    ?: error("Could not preserve clean PP-MattingV2 matte after GPU flow anomaly")
            }
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

    /** Reject catastrophic stripe/tile alpha changes while allowing normal edge refinement. */
    private fun isRefinedMatteSaneV59(base: Bitmap, refined: Bitmap): Boolean {
        if (base.width != refined.width || base.height != refined.height) return false
        if (base.width <= 0 || base.height <= 0) return false
        // V59's 48x48 comparison can miss a one/few-pixel horizontal stripe. Reject
        // catastrophic full-width/full-height band boundaries at native matte resolution first.
        if (hasWideBandMatteArtifactV61(refined)) return false

        val stepX = (base.width / 48).coerceAtLeast(1)
        val stepY = (base.height / 48).coerceAtLeast(1)
        var samples = 0
        var absDelta = 0f
        var leakedBackground = 0
        var lostForeground = 0
        var baseForeground = 0
        var refinedForeground = 0

        var y = stepY / 2
        while (y < base.height) {
            var x = stepX / 2
            while (x < base.width) {
                val b = Color.red(base.getPixel(x, y)) / 255f
                val r = Color.red(refined.getPixel(x, y)) / 255f
                absDelta += abs(b - r)
                if (b <= .05f && r >= .35f) leakedBackground++
                if (b >= .85f && r <= .35f) lostForeground++
                if (b >= .50f) baseForeground++
                if (r >= .50f) refinedForeground++
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return false

        val n = samples.toFloat()
        val meanAbs = absDelta / n
        val leakRate = leakedBackground / n
        val lossRate = lostForeground / n
        val baseFgRate = baseForeground / n
        val refinedFgRate = refinedForeground / n
        return meanAbs <= .13f &&
            leakRate <= .035f &&
            lossRate <= .06f &&
            refinedFgRate <= baseFgRate * 1.35f + .07f
    }


    private fun hasWideBandMatteArtifactV61(matte: Bitmap): Boolean {
        val width = matte.width
        val height = matte.height
        if (width <= 1 || height <= 1) return false
        val pixels = IntArray(width * height)
        matte.getPixels(pixels, 0, width, 0, 0, width, height)
        val rowCoverage = (width * .58f).roundToInt().coerceAtLeast(1)
        val columnCoverage = (height * .58f).roundToInt().coerceAtLeast(1)

        for (y in 1 until height) {
            val previous = (y - 1) * width
            val current = y * width
            var severe = 0
            var deltaSum = 0
            for (x in 0 until width) {
                val a = Color.red(pixels[previous + x])
                val b = Color.red(pixels[current + x])
                val delta = abs(a - b)
                deltaSum += delta
                if (delta >= 128) severe++
            }
            if (severe >= rowCoverage && deltaSum.toFloat() / width >= 56f) return true
        }
        for (x in 1 until width) {
            var severe = 0
            var deltaSum = 0
            for (y in 0 until height) {
                val a = Color.red(pixels[y * width + x - 1])
                val b = Color.red(pixels[y * width + x])
                val delta = abs(a - b)
                deltaSum += delta
                if (delta >= 128) severe++
            }
            if (severe >= columnCoverage && deltaSum.toFloat() / height >= 56f) return true
        }
        return false
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
                val value = max(a, a + (1f - a) * contribution).coerceIn(0f, 1f)
                val v = (value * 255f).roundToInt().coerceIn(0, 255)
                out[i] = Color.argb(255, v, v, v)
            }
            return Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(out, 0, base.width, 0, 0, base.width, base.height)
            }
        } finally {
            scaledHair.recycle()
        }
    }

    fun awaitPendingStores() {
        matteWriter.awaitIdle()
    }

    override fun close() {
        cachedHairMask?.let { if (!it.isRecycled) it.recycle() }
        cachedHairMask = null
        runCatching { matteWriter.close() }
        runCatching { gpuTemporal?.close() }
        gpuTemporal = null
        runCatching { cpuTemporal.close() }
        runCatching { hair.close() }
        runCatching { portraitMatte.close() }
    }
}
