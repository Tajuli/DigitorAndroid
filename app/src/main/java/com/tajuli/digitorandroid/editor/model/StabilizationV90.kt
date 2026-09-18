package com.tajuli.digitorandroid.editor.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

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
)

data class ClipStabilizationV90(
    val enabled: Boolean = true,
    val mode: StabilizationModeV90 = StabilizationModeV90.SIMILARITY,
    val strength: Float = .90f,
    val smoothRadiusUs: Long = 700_000L,
    /** 0 keeps the original framing; 1 aggressively hides stabilization borders. */
    val crop: Float = .85f,
    val analyzedWidth: Int = 0,
    val analyzedHeight: Int = 0,
    val samples: List<StabilizationPathSampleV90> = emptyList(),
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

    val raw = state.pathAtV90(sourceTimeUs)
    val desired = when (state.mode) {
        StabilizationModeV90.CAMERA_LOCK -> StabilizationPathValueV90(0f, 0f, 0f, 0f)
        StabilizationModeV90.TRANSLATION,
        StabilizationModeV90.SIMILARITY -> state.smoothedPathAtV90(sourceTimeUs)
    }

    val amount = state.strength
    val dx = ((desired.x - raw.x) * amount).coerceIn(-1.5f, 1.5f)
    val dy = ((desired.y - raw.y) * amount).coerceIn(-1.5f, 1.5f)
    val rotation = when (state.mode) {
        StabilizationModeV90.TRANSLATION -> 0f
        StabilizationModeV90.SIMILARITY,
        StabilizationModeV90.CAMERA_LOCK -> ((desired.rotation - raw.rotation) * amount).coerceIn(-45f, 45f)
    }
    val scaleCorrection = when (state.mode) {
        StabilizationModeV90.TRANSLATION -> 1f
        StabilizationModeV90.SIMILARITY,
        StabilizationModeV90.CAMERA_LOCK ->
            exp(((desired.logScale - raw.logScale) * amount).coerceIn(-.22f, .22f).toDouble()).toFloat()
    }

    // Conservative border compensation. It intentionally prefers a little extra crop to black edges.
    val rotationRad = abs(rotation) * (PI.toFloat() / 180f)
    val borderDemand = (abs(dx) * .55f + abs(dy) * .55f + rotationRad * .62f).coerceIn(0f, .45f)
    val autoZoom = 1f + borderDemand * state.crop
    val finalScale = max(1f, scaleCorrection * autoZoom).coerceIn(1f, 1.55f)

    return EvaluatedStabilizationV90(dx, dy, rotation, finalScale)
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
    var sumW = 0f
    var x = 0f
    var y = 0f
    var r = 0f
    var s = 0f

    for (sample in samples) {
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        if (distance > radius) continue
        val normalized = distance.toFloat() / radius.toFloat()
        // Smooth bell-like compact kernel, weighted by motion confidence.
        val kernel = (1f - normalized * normalized).coerceAtLeast(0f)
        val w = kernel * kernel * (.25f + .75f * sample.confidence.coerceIn(0f, 1f))
        sumW += w
        x += sample.pathX * w
        y += sample.pathY * w
        r += sample.rotationDegrees * w
        s += sample.logScale * w
    }

    if (sumW <= .0001f) return pathAtV90(sourceTimeUs)
    return StabilizationPathValueV90(x / sumW, y / sumW, r / sumW, s / sumW)
}

private fun StabilizationPathSampleV90.pathValueV90() =
    StabilizationPathValueV90(pathX, pathY, rotationDegrees, logScale)

private fun lerpV90(a: Float, b: Float, t: Float): Float = a + (b - a) * t
