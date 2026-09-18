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

    // V92 exact crop compensation. The previous heuristic could leave a thin black edge because
    // translation + rotation do not combine linearly. Solve the inverse transform of all four output
    // corners instead: the required scale is the smallest uniform zoom that keeps every corner inside
    // the source rectangle. Crop=1 guarantees cover (plus a small sampling safety margin).
    val requiredCoverScale = requiredCoverScaleV92(
        offsetX = dx,
        offsetY = dy,
        rotationDegrees = rotation,
    )
    val coverTarget = max(scaleCorrection, requiredCoverScale * COVER_SAFETY_V92)
    val finalScale = lerpV90(
        scaleCorrection,
        coverTarget,
        state.crop,
    ).coerceIn(.78f, MAX_STABILIZATION_ZOOM_V92)

    return EvaluatedStabilizationV90(dx, dy, rotation, finalScale)
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

    for (sample in samples) {
        if (sample.segmentV93 != segment) continue
        val distance = abs(sample.sourceTimeUs - sourceTimeUs)
        if (distance > radius) continue
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
