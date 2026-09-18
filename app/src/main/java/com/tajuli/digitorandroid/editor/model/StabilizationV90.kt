package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * V90 Resolve-style clip stabilization.
 *
 * Analysis stores the measured camera path in absolute source time so trims/splits can reuse it.
 * Rendering derives the correction from the raw path at frame time; changing Strength/Smooth/Crop
 * never requires re-analysis.
 */
enum class StabilizationModeV90 {
    TRANSLATION,
    SIMILARITY,
    CAMERA_LOCK,
}

data class StabilizationPathSampleV90(
    val sourceTimeUs: Long,
    val pathX: Float,
    val pathY: Float,
    val rotationDegrees: Float,
    val logScale: Float = 0f,
    val confidence: Float = 1f,
    /** V93 scene segment. Smoothing never crosses a detected hard cut. */
    val segmentV93: Int = 0,
)

data class ClipStabilizationV90(
    val enabled: Boolean = true,
    val mode: StabilizationModeV90 = StabilizationModeV90.SIMILARITY,
    val strength: Float = 1f,
    val smoothRadiusUs: Long = 900_000L,
    /** 0 keeps the original framing; 1 aggressively hides stabilization borders. */
    val crop: Float = 1f,
    val analyzedWidth: Int = 0,
    val analyzedHeight: Int = 0,
    val samples: List<StabilizationPathSampleV90> = emptyList(),
    /** 0 = legacy V90/V92 tracking; 93 = robust bidirectional/RANSAC analysis. */
    val analysisVersionV93: Int = 0,
) {
    val hasAnalysis: Boolean get() = samples.size >= 2

    fun normalized(): ClipStabilizationV90 = copy(
        strength = strength.coerceIn(0f, 1f),
        smoothRadiusUs = smoothRadiusUs.coerceIn(80_000L, 3_000_000L),
        crop = crop.coerceIn(0f, 1f),
        samples = samples
            .filter { it.sourceTimeUs >= 0L }
            .sortedBy { it.sourceTimeUs }
            .distinctBy { it.sourceTimeUs },
    )
}

data class EvaluatedStabilizationV90(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val scale: Float = 1f,
)

private data class StabilizationPathValueV90(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val logScale: Float,
)

fun ClipStabilizationV90.evaluate(sourceTimeUs: Long): EvaluatedStabilizationV90 {
    val state = normalized()
    if (!state.enabled || !state.hasAnalysis || state.strength <= 0f) return EvaluatedStabilizationV90()

    // V95 keeps V94's zero-phase smoothing, but rejects isolated correction spikes first using
    // a local median/MAD gate. A one- or two-sample tracking failure must not jerk the frame or
    // force the crop envelope into a sudden 150%+ zoom.
    val correction = when {
        state.analysisVersionV93 >= 93 && state.mode == StabilizationModeV90.TRANSLATION ->
            state.translationCorrectionV96(sourceTimeUs)
        state.analysisVersionV93 < 93 -> state.baseCorrectionV94(sourceTimeUs)
        state.mode == StabilizationModeV90.CAMERA_LOCK -> state.robustBaseCorrectionV95(sourceTimeUs)
        else -> state.filteredCorrectionV95(sourceTimeUs)
    }

    // V95 zoom envelope is driven by the same robust filtered correction used by rendering.
    // This removes V94's bug where crop probes re-read raw/base corrections and amplified an
    // otherwise filtered tracking outlier into a visible zoom spike.
    val coverTarget = if (state.analysisVersionV93 >= 93 && state.crop > 0f) {
        state.zoomEnvelopeV95(sourceTimeUs, correction)
    } else {
        max(
            correction.scaleCorrection,
            requiredCoverScaleV92(
                offsetX = correction.dx,
                offsetY = correction.dy,
                rotationDegrees = correction.rotation,
            ) * COVER_SAFETY_V92,
        )
    }
    val finalScale = lerpV90(
        correction.scaleCorrection,
        coverTarget,
        state.crop,
    ).coerceIn(.78f, MAX_STABILIZATION_ZOOM_V92)

    return EvaluatedStabilizationV90(
        offsetX = correction.dx,
        offsetY = correction.dy,
        rotationDegrees = correction.rotation,
        scale = finalScale,
    )
}


private data class StabilizationCorrectionV94(
    val dx: Float,
    val dy: Float,
    val rotation: Float,
    val scaleCorrection: Float,
)

private fun ClipStabilizationV90.baseCorrectionV94(sourceTimeUs: Long): StabilizationCorrectionV94 {
    val raw = if (analysisVersionV93 >= 93) trackingFilteredPathV94(sourceTimeUs) else pathAtV90(sourceTimeUs)
    val desired = when (mode) {
        StabilizationModeV90.CAMERA_LOCK -> StabilizationPathValueV90(0f, 0f, 0f, 0f)
        StabilizationModeV90.TRANSLATION,
        StabilizationModeV90.SIMILARITY -> smoothedPathAtV90(sourceTimeUs)
    }

    val amount = strength
    val dx = ((desired.x - raw.x) * amount).coerceIn(-1.5f, 1.5f)
    val dy = ((desired.y - raw.y) * amount).coerceIn(-1.5f, 1.5f)
    val rotation = when (mode) {
        StabilizationModeV90.TRANSLATION -> 0f
        StabilizationModeV90.SIMILARITY,
        StabilizationModeV90.CAMERA_LOCK ->
            ((desired.rotation - raw.rotation) * amount).coerceIn(-45f, 45f)
    }
    val scaleCorrection = when (mode) {
        StabilizationModeV90.TRANSLATION -> 1f
        StabilizationModeV90.SIMILARITY,
        StabilizationModeV90.CAMERA_LOCK ->
            exp(((desired.logScale - raw.logScale) * amount).coerceIn(-.22f, .22f).toDouble()).toFloat()
    }
    return StabilizationCorrectionV94(dx, dy, rotation, scaleCorrection)
}

/**
 * V96 Resolve-style Translation solver.
 *
 * Important: stabilize the camera PATH, never smooth the inverse correction. The rendered source
 * already contains the high-frequency hand shake. If the inverse correction is low-passed, that
 * shake cannot be fully cancelled and residual micro-jitter remains. Instead:
 *
 *   measured path -> robust local constant-velocity target -> exact inverse X/Y correction
 *
 * The local linear target behaves like a gimbal: stationary shots converge toward a tripod-like
 * path, while a deliberate pan becomes a smooth near-constant-velocity move. Rotation/scale remain
 * untouched in Translation mode.
 */
private fun ClipStabilizationV90.translationCorrectionV96(sourceTimeUs: Long): StabilizationCorrectionV94 {
    val raw = robustRawTranslationPathV96(sourceTimeUs)
    val target = translationGimbalTargetV96(sourceTimeUs)
    return StabilizationCorrectionV94(
        dx = ((target.x - raw.x) * strength).coerceIn(-1.5f, 1.5f),
        dy = ((target.y - raw.y) * strength).coerceIn(-1.5f, 1.5f),
        rotation = 0f,
        scaleCorrection = 1f,
    )
}

private fun ClipStabilizationV90.robustRawTranslationPathV96(sourceTimeUs: Long): StabilizationPathValueV90 {
    val center = pathAtV90(sourceTimeUs)
    val segment = segmentAtV93(sourceTimeUs)
    val window = samplesInWindowV94(sourceTimeUs, RAW_PATH_OUTLIER_RADIUS_US_V96, segment)
    if (window.size < MIN_GIMBAL_FIT_SAMPLES_V96) return center

    val xs = window.map { it.pathX }
    val ys = window.map { it.pathY }
    val medianX = medianFloatV95(xs)
    val medianY = medianFloatV95(ys)
    val gateX = robustGateV95(xs, medianX, MIN_RAW_PATH_GATE_V96)
    val gateY = robustGateV95(ys, medianY, MIN_RAW_PATH_GATE_V96)

    return center.copy(
        x = center.x.coerceIn(medianX - gateX, medianX + gateX),
        y = center.y.coerceIn(medianY - gateY, medianY + gateY),
    )
}

private fun ClipStabilizationV90.translationGimbalTargetV96(sourceTimeUs: Long): StabilizationPathValueV90 {
    val segment = segmentAtV93(sourceTimeUs)
    val radius = max(
        MIN_TRANSLATION_GIMBAL_RADIUS_US_V96,
        (smoothRadiusUs * TRANSLATION_GIMBAL_RADIUS_MULTIPLIER_V96).toLong(),
    ).coerceAtMost(MAX_TRANSLATION_GIMBAL_RADIUS_US_V96)
    val window = samplesInWindowV94(sourceTimeUs, radius, segment)
    if (window.size < MIN_GIMBAL_FIT_SAMPLES_V96) return smoothedPathAtV90(sourceTimeUs)

    val firstFitX = weightedLinearFitAtV96(window, sourceTimeUs) { it.pathX }
    val firstFitY = weightedLinearFitAtV96(window, sourceTimeUs) { it.pathY }
    if (firstFitX == null || firstFitY == null) return smoothedPathAtV90(sourceTimeUs)

    // IRLS-like second pass: reject path samples that sit far from the first constant-velocity fit.
    val residuals = window.map { sample ->
        val dt = (sample.sourceTimeUs - sourceTimeUs) / 1_000_000f
        val predictedX = firstFitX.intercept + firstFitX.slopePerSecond * dt
        val predictedY = firstFitY.intercept + firstFitY.slopePerSecond * dt
        kotlin.math.sqrt(
            (sample.pathX - predictedX) * (sample.pathX - predictedX) +
                (sample.pathY - predictedY) * (sample.pathY - predictedY),
        )
    }
    val medianResidual = medianFloatV95(residuals)
    val residualGate = max(
        MIN_GIMBAL_RESIDUAL_GATE_V96,
        medianResidual + robustGateV95(residuals, medianResidual, MIN_GIMBAL_RESIDUAL_GATE_V96),
    )
    val robustWindow = window.filterIndexed { index, _ -> residuals[index] <= residualGate }

    val fitX = weightedLinearFitAtV96(
        if (robustWindow.size >= MIN_GIMBAL_FIT_SAMPLES_V96) robustWindow else window,
        sourceTimeUs,
    ) { it.pathX } ?: firstFitX
    val fitY = weightedLinearFitAtV96(
        if (robustWindow.size >= MIN_GIMBAL_FIT_SAMPLES_V96) robustWindow else window,
        sourceTimeUs,
    ) { it.pathY } ?: firstFitY

    return StabilizationPathValueV90(
        x = fitX.intercept,
        y = fitY.intercept,
        rotation = 0f,
        logScale = 0f,
    )
}

private data class LinearFitV96(
    val intercept: Float,
    val slopePerSecond: Float,
)

private fun weightedLinearFitAtV96(
    samples: List<StabilizationPathSampleV90>,
    centerTimeUs: Long,
    value: (StabilizationPathSampleV90) -> Float,
): LinearFitV96? {
    if (samples.size < 2) return null
    var sumW = 0.0
    var sumT = 0.0
    var sumV = 0.0
    var sumTT = 0.0
    var sumTV = 0.0

    val furthestUs = samples.maxOf { abs(it.sourceTimeUs - centerTimeUs) }.coerceAtLeast(1L)
    for (sample in samples) {
        val t = (sample.sourceTimeUs - centerTimeUs).toDouble() / 1_000_000.0
        val normalizedDistance = abs(sample.sourceTimeUs - centerTimeUs).toDouble() / furthestUs.toDouble()
        val temporal = (1.0 - normalizedDistance * normalizedDistance).coerceAtLeast(0.0)
        val confidence = (.15 + .85 * sample.confidence.coerceIn(0f, 1f)).toDouble()
        val w = temporal * temporal * confidence
        if (w <= 1e-8) continue
        val v = value(sample).toDouble()
        sumW += w
        sumT += w * t
        sumV += w * v
        sumTT += w * t * t
        sumTV += w * t * v
    }
    if (sumW <= 1e-8) return null

    val denominator = sumW * sumTT - sumT * sumT
    val slope = if (abs(denominator) <= 1e-10) 0.0 else (sumW * sumTV - sumT * sumV) / denominator
    val intercept = (sumV - slope * sumT) / sumW
    return LinearFitV96(intercept.toFloat(), slope.toFloat())
}

private fun ClipStabilizationV90.filteredCorrectionV95(sourceTimeUs: Long): StabilizationCorrectionV94 {
    val segment = segmentAtV93(sourceTimeUs)
    val radius = CORRECTION_FILTER_RADIUS_US_V94
    val centerCorrection = robustBaseCorrectionV95(sourceTimeUs)
    var sumW = 1f
    var dx = centerCorrection.dx
    var dy = centerCorrection.dy
    var rotation = centerCorrection.rotation
    var logScale = ln(centerCorrection.scaleCorrection.coerceAtLeast(.01f))

    for (sample in samplesInWindowV94(sourceTimeUs, radius, segment)) {
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        if (distance == 0L) continue
        val normalized = (distance.toFloat() / radius.toFloat()).coerceIn(0f, 1f)
        val kernel = (1f - normalized * normalized).coerceAtLeast(0f)
        val weight = kernel * kernel * (.25f + .75f * sample.confidence.coerceIn(0f, 1f))
        if (weight <= .0001f) continue
        // Lookahead only needs a robust estimate of future crop demand. The exact playhead floor
        // above already uses V96 Translation, so avoid re-running the heavy gimbal regression for
        // every sparse zoom probe.
        val correction = robustBaseCorrectionV95(sample.sourceTimeUs)
        sumW += weight
        dx += correction.dx * weight
        dy += correction.dy * weight
        rotation += correction.rotation * weight
        logScale += ln(correction.scaleCorrection.coerceAtLeast(.01f)) * weight
    }
    return StabilizationCorrectionV94(
        dx = dx / sumW,
        dy = dy / sumW,
        rotation = rotation / sumW,
        scaleCorrection = exp((logScale / sumW).toDouble()).toFloat(),
    )
}

/**
 * V95 isolated-spike rejection.
 *
 * Camera tracking can occasionally emit one bad global transform even after RANSAC (motion blur,
 * foreground occlusion, repeated texture). Compare the center correction against neighbouring
 * corrections in the same scene segment. Median gives the local center; MAD estimates normal
 * variation. Only the excess beyond a robust gate is clipped, so sustained real camera motion
 * remains intact while one/two-frame impulses cannot dominate rendering or crop.
 */
private fun ClipStabilizationV90.robustBaseCorrectionV95(sourceTimeUs: Long): StabilizationCorrectionV94 {
    val center = baseCorrectionV94(sourceTimeUs)
    val segment = segmentAtV93(sourceTimeUs)
    val neighbors = samplesInWindowV94(sourceTimeUs, OUTLIER_WINDOW_RADIUS_US_V95, segment)
    if (neighbors.size < MIN_OUTLIER_WINDOW_SAMPLES_V95) return center

    val corrections = neighbors.map { baseCorrectionV94(it.sourceTimeUs) }
    val dxValues = corrections.map { it.dx }
    val dyValues = corrections.map { it.dy }
    val rotationValues = corrections.map { it.rotation }
    val logScaleValues = corrections.map { ln(it.scaleCorrection.coerceAtLeast(.01f)) }

    val medianDx = medianFloatV95(dxValues)
    val medianDy = medianFloatV95(dyValues)
    val medianRotation = medianFloatV95(rotationValues)
    val medianLogScale = medianFloatV95(logScaleValues)

    val gateDx = robustGateV95(dxValues, medianDx, MIN_TRANSLATION_GATE_V95)
    val gateDy = robustGateV95(dyValues, medianDy, MIN_TRANSLATION_GATE_V95)
    val gateRotation = robustGateV95(rotationValues, medianRotation, MIN_ROTATION_GATE_DEGREES_V95)
    val gateLogScale = robustGateV95(logScaleValues, medianLogScale, MIN_LOG_SCALE_GATE_V95)

    return StabilizationCorrectionV94(
        dx = center.dx.coerceIn(medianDx - gateDx, medianDx + gateDx),
        dy = center.dy.coerceIn(medianDy - gateDy, medianDy + gateDy),
        rotation = center.rotation.coerceIn(
            medianRotation - gateRotation,
            medianRotation + gateRotation,
        ),
        scaleCorrection = exp(
            ln(center.scaleCorrection.coerceAtLeast(.01f))
                .coerceIn(medianLogScale - gateLogScale, medianLogScale + gateLogScale)
                .toDouble(),
        ).toFloat(),
    )
}

private fun robustGateV95(values: List<Float>, median: Float, minimumGate: Float): Float {
    if (values.isEmpty()) return minimumGate
    val deviations = values.map { abs(it - median) }
    val mad = medianFloatV95(deviations)
    // 1.4826 converts MAD to a Gaussian-equivalent sigma estimate.
    val sigma = mad * MAD_TO_SIGMA_V95
    return max(minimumGate, sigma * OUTLIER_SIGMA_MULTIPLIER_V95)
}

private fun medianFloatV95(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) * .5f
    }
}

private fun ClipStabilizationV90.trackingFilteredPathV94(sourceTimeUs: Long): StabilizationPathValueV90 {
    val segment = segmentAtV93(sourceTimeUs)
    val center = pathAtV90(sourceTimeUs)
    var sumW = 1f
    var x = center.x
    var y = center.y
    var rotation = center.rotation
    var logScale = center.logScale

    for (sample in samplesInWindowV94(sourceTimeUs, TRACKING_FILTER_RADIUS_US_V94, segment)) {
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        if (distance == 0L) continue
        val normalized = (distance.toFloat() / TRACKING_FILTER_RADIUS_US_V94.toFloat()).coerceIn(0f, 1f)
        val weight = (1f - normalized) * (.20f + .80f * sample.confidence.coerceIn(0f, 1f))
        if (weight <= .0001f) continue
        sumW += weight
        x += sample.pathX * weight
        y += sample.pathY * weight
        rotation += sample.rotationDegrees * weight
        logScale += sample.logScale * weight
    }
    return StabilizationPathValueV90(x / sumW, y / sumW, rotation / sumW, logScale / sumW)
}

private fun ClipStabilizationV90.zoomEnvelopeV95(
    sourceTimeUs: Long,
    current: StabilizationCorrectionV94,
): Float {
    val segment = segmentAtV93(sourceTimeUs)
    val radius = ZOOM_ENVELOPE_RADIUS_US_V94
    val currentRequired = max(
        current.scaleCorrection,
        requiredCoverScaleV92(current.dx, current.dy, current.rotation) * COVER_SAFETY_V92,
    )
    var envelope = currentRequired

    // Build a sparse robust crop-demand curve. Each probe is already median/MAD clipped. A second
    // tiny median across neighbouring crop probes removes any remaining isolated peak without
    // repeatedly running the full zero-phase correction filter for every preview frame.
    val probes = ArrayList<Pair<Long, Float>>()
    var sampleIndex = 0
    for (sample in samplesInWindowV94(sourceTimeUs, radius, segment)) {
        if (sampleIndex++ % ZOOM_PROBE_STRIDE_V94 != 0) continue
        val correction = robustBaseCorrectionV95(sample.sourceTimeUs)
        val required = max(
            correction.scaleCorrection,
            requiredCoverScaleV92(correction.dx, correction.dy, correction.rotation) * COVER_SAFETY_V92,
        )
        probes += sample.sourceTimeUs to required
    }

    for (index in probes.indices) {
        val (probeTimeUs, _) = probes[index]
        val localStart = max(0, index - ZOOM_MEDIAN_HALF_WINDOW_V95)
        val localEnd = minOf(probes.lastIndex, index + ZOOM_MEDIAN_HALF_WINDOW_V95)
        val localRequired = ArrayList<Float>(localEnd - localStart + 1)
        for (localIndex in localStart..localEnd) {
            localRequired += probes[localIndex].second
        }
        val required = medianFloatV95(localRequired)
        val distance = abs(probeTimeUs - sourceTimeUs)
        val normalized = (distance.toFloat() / radius.toFloat()).coerceIn(0f, 1f)
        val feather = (1f - normalized * normalized).coerceAtLeast(0f)
        if (feather <= .0001f) continue

        val feathered = 1f + (required - 1f).coerceAtLeast(0f) * feather
        envelope = max(envelope, feathered)
    }
    return envelope.coerceIn(1f, MAX_STABILIZATION_ZOOM_V92)
}

private fun ClipStabilizationV90.samplesInWindowV94(
    sourceTimeUs: Long,
    radiusUs: Long,
    segment: Int,
): List<StabilizationPathSampleV90> {
    if (samples.isEmpty()) return emptyList()
    val startUs = sourceTimeUs - radiusUs
    val endUs = sourceTimeUs + radiusUs
    var lo = 0
    var hi = samples.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (samples[mid].sourceTimeUs < startUs) lo = mid + 1 else hi = mid
    }
    val out = ArrayList<StabilizationPathSampleV90>()
    var index = lo
    while (index < samples.size) {
        val sample = samples[index]
        if (sample.sourceTimeUs > endUs) break
        if (sample.segmentV93 == segment) out += sample
        index++
    }
    return out
}

/**
 * Returns the minimum uniform scale needed for a translated/rotated full-frame source to cover the
 * output canvas. Stabilization X/Y use the same normalized-device convention as ClipTransformEffect:
 * +/-1 equals half a frame, and render Y is inverted.
 */
internal fun requiredCoverScaleV92(
    offsetX: Float,
    offsetY: Float,
    rotationDegrees: Float,
): Float {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosR = cos(radians).toFloat()
    val sinR = sin(radians).toFloat()
    val tx = offsetX
    val ty = -offsetY

    var required = 1f
    val corners = arrayOf(
        -1f to -1f,
        1f to -1f,
        -1f to 1f,
        1f to 1f,
    )
    for ((x, y) in corners) {
        val shiftedX = x - tx
        val shiftedY = y - ty
        // R^-1 = transpose(R) for a pure rotation.
        val sourceX = cosR * shiftedX + sinR * shiftedY
        val sourceY = -sinR * shiftedX + cosR * shiftedY
        required = max(required, max(abs(sourceX), abs(sourceY)))
    }
    return required.coerceAtLeast(1f)
}

/** Manual transform and stabilization are composed once so preview/export/compositor stay identical. */
fun TimelineClip.evaluatedDisplayTransformV90(localUs: Long): EvaluatedClipTransform {
    val safeLocal = localUs.coerceIn(0L, durationUs.coerceAtLeast(0L))
    val manual = transform.evaluate(safeLocal)
    val stabilization = stabilizationV90?.evaluate(sourceInUs + safeLocal) ?: EvaluatedStabilizationV90()
    return EvaluatedClipTransform(
        positionX = (manual.positionX + stabilization.offsetX).coerceIn(-2f, 2f),
        positionY = (manual.positionY + stabilization.offsetY).coerceIn(-2f, 2f),
        scaleX = (manual.scaleX * stabilization.scale).coerceIn(.05f, 8f),
        scaleY = (manual.scaleY * stabilization.scale).coerceIn(.05f, 8f),
        rotationDegrees = manual.rotationDegrees + stabilization.rotationDegrees,
    )
}

private fun ClipStabilizationV90.pathAtV90(sourceTimeUs: Long): StabilizationPathValueV90 {
    val items = samples
    if (items.isEmpty()) return StabilizationPathValueV90(0f, 0f, 0f, 0f)
    if (sourceTimeUs <= items.first().sourceTimeUs) return items.first().pathValueV90()
    if (sourceTimeUs >= items.last().sourceTimeUs) return items.last().pathValueV90()

    var lo = 0
    var hi = items.lastIndex
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (items[mid].sourceTimeUs <= sourceTimeUs) lo = mid else hi = mid
    }
    val a = items[lo]
    val b = items[hi]
    if (a.segmentV93 != b.segmentV93) {
        val midpoint = a.sourceTimeUs + (b.sourceTimeUs - a.sourceTimeUs) / 2L
        return if (sourceTimeUs < midpoint) a.pathValueV90() else b.pathValueV90()
    }
    val span = (b.sourceTimeUs - a.sourceTimeUs).coerceAtLeast(1L)
    val t = ((sourceTimeUs - a.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
    return StabilizationPathValueV90(
        x = lerpV90(a.pathX, b.pathX, t),
        y = lerpV90(a.pathY, b.pathY, t),
        rotation = lerpV90(a.rotationDegrees, b.rotationDegrees, t),
        logScale = lerpV90(a.logScale, b.logScale, t),
    )
}

private fun ClipStabilizationV90.smoothedPathAtV90(sourceTimeUs: Long): StabilizationPathValueV90 {
    val radius = smoothRadiusUs.coerceAtLeast(1L)
    val segment = segmentAtV93(sourceTimeUs)
    var sumW = 0f
    var x = 0f
    var y = 0f
    var r = 0f
    var s = 0f

    for (sample in samplesInWindowV94(sourceTimeUs, radius, segment)) {
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        val normalized = distance.toFloat() / radius.toFloat()
        // Zero-phase compact kernel: strong low-pass without adding temporal lag.
        val kernel = (1f - normalized * normalized).coerceAtLeast(0f)
        val w = kernel * kernel * (.20f + .80f * sample.confidence.coerceIn(0f, 1f))
        sumW += w
        x += sample.pathX * w
        y += sample.pathY * w
        r += sample.rotationDegrees * w
        s += sample.logScale * w
    }

    if (sumW <= .0001f) return pathAtV90(sourceTimeUs)
    return StabilizationPathValueV90(x / sumW, y / sumW, r / sumW, s / sumW)
}

private fun ClipStabilizationV90.segmentAtV93(sourceTimeUs: Long): Int {
    val items = samples
    if (items.isEmpty()) return 0
    var best = items.first()
    var bestDistance = abs(best.sourceTimeUs - sourceTimeUs)
    for (index in 1 until items.size) {
        val candidate = items[index]
        val distance = abs(candidate.sourceTimeUs - sourceTimeUs)
        if (distance < bestDistance) {
            best = candidate
            bestDistance = distance
        }
    }
    return best.segmentV93
}

private fun StabilizationPathSampleV90.pathValueV90() =
    StabilizationPathValueV90(pathX, pathY, rotationDegrees, logScale)

private fun lerpV90(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private const val COVER_SAFETY_V92 = 1.015f
private const val MAX_STABILIZATION_ZOOM_V92 = 2.25f
private const val TRACKING_FILTER_RADIUS_US_V94 = 90_000L
private const val CORRECTION_FILTER_RADIUS_US_V94 = 160_000L
private const val ZOOM_ENVELOPE_RADIUS_US_V94 = 1_200_000L
private const val ZOOM_PROBE_STRIDE_V94 = 3
private const val ZOOM_MEDIAN_HALF_WINDOW_V95 = 1
private const val OUTLIER_WINDOW_RADIUS_US_V95 = 225_000L
private const val MIN_OUTLIER_WINDOW_SAMPLES_V95 = 5
private const val MAD_TO_SIGMA_V95 = 1.4826f
private const val OUTLIER_SIGMA_MULTIPLIER_V95 = 3.25f
private const val MIN_TRANSLATION_GATE_V95 = .028f
private const val MIN_ROTATION_GATE_DEGREES_V95 = .40f
private const val MIN_LOG_SCALE_GATE_V95 = .008f
private const val RAW_PATH_OUTLIER_RADIUS_US_V96 = 140_000L
private const val MIN_RAW_PATH_GATE_V96 = .030f
private const val MIN_TRANSLATION_GIMBAL_RADIUS_US_V96 = 1_500_000L
private const val MAX_TRANSLATION_GIMBAL_RADIUS_US_V96 = 3_500_000L
private const val TRANSLATION_GIMBAL_RADIUS_MULTIPLIER_V96 = 2.0f
private const val MIN_GIMBAL_FIT_SAMPLES_V96 = 7
private const val MIN_GIMBAL_RESIDUAL_GATE_V96 = .018f
