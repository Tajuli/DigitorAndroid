package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

enum class VirtualStabilizationModeV1 {
    TRANSLATION,
    SIMILARITY,
    TRIPOD,
}

/**
 * One globally solved camera-path sample.
 *
 * raw* is the measured reference->frame camera path.
 * smooth* is the virtual camera path produced by the batch optimizer.
 * All translation values use render NDC coordinates (+Y up), so no image/render sign conversion
 * is hidden in the solver.
 */
data class VirtualCameraSampleV1(
    val sourceTimeUs: Long,
    val segment: Int = 0,
    val confidence: Float = 1f,
    val rawX: Float = 0f,
    val rawY: Float = 0f,
    val rawRotationDegrees: Float = 0f,
    val rawLogScale: Float = 0f,
    /** V2: pairwise path after sparse fixed-reference drift correction, used by Tripod only. */
    val tripodRawX: Float = 0f,
    val tripodRawY: Float = 0f,
    val tripodRawRotationDegrees: Float = 0f,
    val tripodRawLogScale: Float = 0f,
    val smoothX: Float = 0f,
    val smoothY: Float = 0f,
    val smoothRotationDegrees: Float = 0f,
    val smoothLogScale: Float = 0f,
    val lockX: Float = 0f,
    val lockY: Float = 0f,
    val lockRotationDegrees: Float = 0f,
    val lockLogScale: Float = 0f,
)

data class VirtualCameraStabilizationV1(
    val enabled: Boolean = true,
    val mode: VirtualStabilizationModeV1 = VirtualStabilizationModeV1.SIMILARITY,
    val strength: Float = 1f,
    val zoomEnabled: Boolean = true,
    val samples: List<VirtualCameraSampleV1> = emptyList(),
    val translationCoverScale: Float = 1f,
    val similarityCoverScale: Float = 1f,
    val tripodCoverScale: Float = 1f,
    val analysisVersion: Int = 1,
) {
    val hasAnalysis: Boolean get() = samples.size >= 2

    fun normalized(): VirtualCameraStabilizationV1 = copy(
        strength = strength.coerceIn(0f, 1f),
        translationCoverScale = translationCoverScale.coerceIn(1f, MAX_VIRTUAL_CAMERA_ZOOM_V1),
        similarityCoverScale = similarityCoverScale.coerceIn(1f, MAX_VIRTUAL_CAMERA_ZOOM_V1),
        tripodCoverScale = tripodCoverScale.coerceIn(1f, MAX_VIRTUAL_CAMERA_ZOOM_V1),
        samples = samples
            .filter { it.sourceTimeUs >= 0L }
            .sortedBy { it.sourceTimeUs }
            .distinctBy { it.sourceTimeUs },
    )
}

data class EvaluatedVirtualCameraV1(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val scale: Float = 1f,
)

private data class PoseV1(
    val x: Float,
    val y: Float,
    val rotationDegrees: Float,
    val logScale: Float,
)

fun VirtualCameraStabilizationV1.evaluateVirtualCameraV1(
    sourceTimeUs: Long,
): EvaluatedVirtualCameraV1 {
    val state = normalized()
    if (!state.enabled || !state.hasAnalysis || state.strength <= .0001f) {
        return EvaluatedVirtualCameraV1()
    }

    val sample = state.sampleAtV1(sourceTimeUs) ?: return EvaluatedVirtualCameraV1()
    val raw = if (
        state.mode == VirtualStabilizationModeV1.TRIPOD &&
        state.analysisVersion >= 2
    ) {
        PoseV1(
            sample.tripodRawX,
            sample.tripodRawY,
            sample.tripodRawRotationDegrees,
            sample.tripodRawLogScale,
        )
    } else {
        PoseV1(
            sample.rawX,
            sample.rawY,
            sample.rawRotationDegrees,
            sample.rawLogScale,
        )
    }
    val desired = when (state.mode) {
        VirtualStabilizationModeV1.TRANSLATION,
        VirtualStabilizationModeV1.SIMILARITY -> PoseV1(
            sample.smoothX,
            sample.smoothY,
            sample.smoothRotationDegrees,
            sample.smoothLogScale,
        )
        VirtualStabilizationModeV1.TRIPOD -> PoseV1(
            sample.lockX,
            sample.lockY,
            sample.lockRotationDegrees,
            sample.lockLogScale,
        )
    }

    // Exact relative similarity transform: desired * inverse(raw).
    val correction = relativePoseV1(raw, desired, state.mode)
    val amount = state.strength
    val rotation = correction.rotationDegrees * amount
    val scale = exp(ln(correction.scale.coerceAtLeast(.01f)) * amount)
    val tx = correction.offsetX * amount
    val ty = correction.offsetY * amount

    val cover = when (state.mode) {
        VirtualStabilizationModeV1.TRANSLATION -> state.translationCoverScale
        VirtualStabilizationModeV1.SIMILARITY -> state.similarityCoverScale
        VirtualStabilizationModeV1.TRIPOD -> state.tripodCoverScale
    }
    val autoZoom = if (state.zoomEnabled) {
        1f + (cover - 1f) * amount
    } else {
        1f
    }

    return EvaluatedVirtualCameraV1(
        offsetX = tx.coerceIn(-1.8f, 1.8f),
        // Model values are NDC (+Y up); EvaluatedClipTransform uses UI +Y down.
        offsetY = (-ty).coerceIn(-1.8f, 1.8f),
        rotationDegrees = rotation.coerceIn(-50f, 50f),
        scale = (scale * autoZoom).coerceIn(.65f, MAX_VIRTUAL_CAMERA_ZOOM_V1),
    )
}

private fun VirtualCameraStabilizationV1.sampleAtV1(sourceTimeUs: Long): VirtualCameraSampleV1? {
    val items = samples
    if (items.isEmpty()) return null
    if (sourceTimeUs <= items.first().sourceTimeUs) return items.first()
    if (sourceTimeUs >= items.last().sourceTimeUs) return items.last()

    var lo = 0
    var hi = items.lastIndex
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (items[mid].sourceTimeUs <= sourceTimeUs) lo = mid else hi = mid
    }
    val a = items[lo]
    val b = items[hi]
    if (a.segment != b.segment) {
        val midpoint = a.sourceTimeUs + (b.sourceTimeUs - a.sourceTimeUs) / 2L
        return if (sourceTimeUs < midpoint) a else b
    }
    val span = (b.sourceTimeUs - a.sourceTimeUs).coerceAtLeast(1L)
    val t = ((sourceTimeUs - a.sourceTimeUs).toDouble() / span.toDouble())
        .toFloat().coerceIn(0f, 1f)
    return VirtualCameraSampleV1(
        sourceTimeUs = sourceTimeUs,
        segment = a.segment,
        confidence = lerpV1(a.confidence, b.confidence, t),
        rawX = lerpV1(a.rawX, b.rawX, t),
        rawY = lerpV1(a.rawY, b.rawY, t),
        rawRotationDegrees = lerpV1(a.rawRotationDegrees, b.rawRotationDegrees, t),
        rawLogScale = lerpV1(a.rawLogScale, b.rawLogScale, t),
        tripodRawX = lerpV1(a.tripodRawX, b.tripodRawX, t),
        tripodRawY = lerpV1(a.tripodRawY, b.tripodRawY, t),
        tripodRawRotationDegrees = lerpV1(
            a.tripodRawRotationDegrees,
            b.tripodRawRotationDegrees,
            t,
        ),
        tripodRawLogScale = lerpV1(a.tripodRawLogScale, b.tripodRawLogScale, t),
        smoothX = lerpV1(a.smoothX, b.smoothX, t),
        smoothY = lerpV1(a.smoothY, b.smoothY, t),
        smoothRotationDegrees = lerpV1(a.smoothRotationDegrees, b.smoothRotationDegrees, t),
        smoothLogScale = lerpV1(a.smoothLogScale, b.smoothLogScale, t),
        lockX = lerpV1(a.lockX, b.lockX, t),
        lockY = lerpV1(a.lockY, b.lockY, t),
        lockRotationDegrees = lerpV1(a.lockRotationDegrees, b.lockRotationDegrees, t),
        lockLogScale = lerpV1(a.lockLogScale, b.lockLogScale, t),
    )
}

private data class RelativePoseV1(
    val offsetX: Float,
    val offsetY: Float,
    val rotationDegrees: Float,
    val scale: Float,
)

private fun relativePoseV1(
    raw: PoseV1,
    desired: PoseV1,
    mode: VirtualStabilizationModeV1,
): RelativePoseV1 {
    val rawScale = exp(raw.logScale.toDouble()).toFloat().coerceAtLeast(.01f)
    val desiredScale = if (mode == VirtualStabilizationModeV1.TRANSLATION) rawScale
    else exp(desired.logScale.toDouble()).toFloat().coerceAtLeast(.01f)
    val correctionScale = if (mode == VirtualStabilizationModeV1.TRANSLATION) 1f
    else (desiredScale / rawScale).coerceIn(.70f, 1.40f)
    val correctionRotation = if (mode == VirtualStabilizationModeV1.TRANSLATION) 0f
    else desired.rotationDegrees - raw.rotationDegrees

    val radians = Math.toRadians(correctionRotation.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val rotatedRawX = correctionScale * (c * raw.x - s * raw.y)
    val rotatedRawY = correctionScale * (s * raw.x + c * raw.y)

    return RelativePoseV1(
        offsetX = desired.x - rotatedRawX,
        offsetY = desired.y - rotatedRawY,
        rotationDegrees = correctionRotation,
        scale = correctionScale,
    )
}

fun TimelineClip.evaluatedDisplayTransformV1(localUs: Long): EvaluatedClipTransform {
    val safeLocalUs = localUs.coerceIn(0L, durationUs.coerceAtLeast(0L))
    val manual = transform.evaluate(safeLocalUs)
    val stabilization = virtualCameraStabilizationV1
        ?.evaluateVirtualCameraV1(sourceInUs + safeLocalUs)
        ?: EvaluatedVirtualCameraV1()

    return EvaluatedClipTransform(
        positionX = (manual.positionX + stabilization.offsetX).coerceIn(-2f, 2f),
        positionY = (manual.positionY + stabilization.offsetY).coerceIn(-2f, 2f),
        scaleX = (manual.scaleX * stabilization.scale).coerceIn(.05f, 8f),
        scaleY = (manual.scaleY * stabilization.scale).coerceIn(.05f, 8f),
        rotationDegrees = manual.rotationDegrees + stabilization.rotationDegrees,
    )
}

internal fun requiredVirtualCameraCoverScaleV1(
    offsetX: Float,
    offsetYNdc: Float,
    rotationDegrees: Float,
    scaleCorrection: Float,
): Float {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val invScale = 1f / scaleCorrection.coerceAtLeast(.01f)
    var required = 1f
    val corners = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
    for ((x, y) in corners) {
        val px = x - offsetX
        val py = y - offsetYNdc
        val sourceX = invScale * (c * px + s * py)
        val sourceY = invScale * (-s * px + c * py)
        required = max(required, max(abs(sourceX), abs(sourceY)))
    }
    return required.coerceIn(1f, MAX_VIRTUAL_CAMERA_ZOOM_V1)
}

private fun lerpV1(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private const val MAX_VIRTUAL_CAMERA_ZOOM_V1 = 1.65f
