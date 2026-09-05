package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Pure per-pixel decision retained as the reference contract for V52 GPU shader tests/tuning. */
internal data class PersonMemorySampleV51(
    val currentAlpha: Float,
    val previousAlpha: Float,
    val previousLock: Float,
    val previousConfidence: Float,
    val previousAlphaSupport: Float,
    val previousLockSupport: Float,
    val flowConfidence: Float,
    val motionBlocks: Float,
    val temporalStrength: Float,
)

internal data class PersonMemoryDecisionV51(
    val alpha: Float,
    val nextLock: Float,
    val nextConfidence: Float,
)

/**
 * Reference math mirrored by GpuSpatialFlowTemporalMatteStabilizerV47's V52 shader.
 *
 * The production GPU path no longer runs a second CPU motion estimator. This function remains pure
 * and JVM-testable so chair/background birth rejection and hysteresis behavior can be regression
 * tested without an Android GPU.
 */
internal fun decidePersonMemorySampleV51(sample: PersonMemorySampleV51): PersonMemoryDecisionV51 {
    val current = sample.currentAlpha.coerceIn(0f, 1f)
    val previous = sample.previousAlpha.coerceIn(0f, 1f)
    val previousLock = sample.previousLock.coerceIn(0f, 1f)
    val previousConfidence = sample.previousConfidence.coerceIn(0f, 1f)
    val alphaSupport = sample.previousAlphaSupport.coerceIn(0f, 1f)
    val lockSupport = max(previousLock, sample.previousLockSupport.coerceIn(0f, 1f))
    val flowConfidence = sample.flowConfidence.coerceIn(0f, 1f)
    val temporalStrength = sample.temporalStrength.coerceIn(0f, .92f)
    val motionBlocks = sample.motionBlocks.coerceAtLeast(0f)

    val established = max(
        smoothstepV51(.18f, .70f, lockSupport),
        smoothstepV51(.20f, .78f, previousConfidence),
    )
    val motionEvidence = smoothstepV51(.35f, 1.85f, motionBlocks)
    val staticness = 1f - motionEvidence
    val reliableFlow = smoothstepV51(.12f, .48f, flowConfidence)
    val strongFreshEvidence = smoothstepV51(.94f, .995f, current)
    val movingFreshEvidence = motionEvidence * reliableFlow * smoothstepV51(.70f, .94f, current)
    val allowedFreshEntry = max(strongFreshEvidence, movingFreshEvidence)

    val unsupported = (1f - established) * (1f - smoothstepV51(.08f, .46f, alphaSupport))
    val birthGuard = (unsupported * staticness * reliableFlow * (1f - allowedFreshEntry)).coerceIn(0f, 1f)
    val birthGuardStrength = .76f + .22f * temporalStrength
    val birthTarget = min(current, alphaSupport + .045f)
    var refined = mixV51(current, birthTarget, birthGuard * birthGuardStrength)

    val currentDrop = smoothstepV51(.10f, .56f, previous - current)
    val motionDamping = 1f - .58f * smoothstepV51(1.35f, 4.8f, motionBlocks)
    val exitHold = (
        established * reliableFlow * currentDrop * motionDamping * (.46f + .54f * temporalStrength)
    ).coerceIn(0f, .82f)
    val heldTarget = max(refined, min(previous, max(alphaSupport, previous * .94f)))
    refined = mixV51(refined, heldTarget, exitHold)

    // Rejected raw births must not become confidence just because the base model was very certain.
    // Only already-established or motion-supported fresh foreground may add raw-current evidence.
    val confidencePermission = max(established, allowedFreshEntry)
    val outputEvidence = max(refined, current * .86f * confidencePermission)
    val riseRate = (.28f + .30f * reliableFlow + .16f * motionEvidence).coerceIn(.28f, .74f)
    val fallRate = (.075f + .11f * (1f - reliableFlow) + .10f * motionEvidence).coerceIn(.075f, .285f)
    val nextConfidence = if (outputEvidence >= previousConfidence) {
        previousConfidence + (outputEvidence - previousConfidence) * riseRate
    } else {
        previousConfidence + (outputEvidence - previousConfidence) * fallRate
    }.coerceIn(0f, 1f)

    val strongBackground = 1f - smoothstepV51(.05f, .30f, refined)
    val retainedLock = previousLock * (.995f - .025f * strongBackground)
    val lockAcquireEvidence = smoothstepV51(.64f, .92f, refined)
    val newLockPermission = max(established, max(movingFreshEvidence, strongFreshEvidence * .45f))
    val acquiredLock = lockAcquireEvidence * newLockPermission
    val nextLock = max(retainedLock, acquiredLock).coerceIn(0f, 1f)

    return PersonMemoryDecisionV51(
        alpha = refined.coerceIn(0f, 1f),
        nextLock = nextLock,
        nextConfidence = nextConfidence,
    )
}

/**
 * Compatibility shim for the historical analyzer call site.
 *
 * V52's normal GL path has already applied person-lock/hysteresis inside the shared GPU flow pass.
 * Therefore this class deliberately does no motion estimation. It only returns an owned bitmap for
 * the legacy ownership contract. CPU temporal fallback still retains its existing birth guard.
 */
internal class PersistentPersonMemoryV51 : AutoCloseable {
    fun refine(
        source: Bitmap,
        currentMatte: Bitmap,
        sourceTimeUs: Long,
        temporalStrength: Float,
    ): Bitmap {
        @Suppress("UNUSED_VARIABLE")
        val ignored = source.width + sourceTimeUs.hashCode() + temporalStrength.toBits()
        return currentMatte.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not copy V52 GPU person-memory matte")
    }

    override fun close() = Unit
}

private fun smoothstepV51(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mixV51(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
