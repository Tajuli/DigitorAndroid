package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PERSON_MEMORY_RESET_GAP_US_V51 = 1_200_000L
private const val PERSON_MEMORY_SCENE_CUT_MAD_V51 = 48f

/** Pure per-pixel decision used by the Android wrapper and JVM tests. */
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
 * V51 persistent-person memory.
 *
 * The existing V47 GPU/CPU temporal stage already performs local optical-flow warping. V51 adds a
 * second, deliberately conservative temporal memory layer after that result:
 *  1) persistent person lock: established foreground is carried through local motion and decays
 *     slowly instead of allowing unrelated background to become foreground for a few anchors;
 *  2) motion-aware hysteresis: new foreground without warped person support needs stronger evidence,
 *     while moving new limbs/hair can still enter quickly;
 *  3) confidence memory: foreground exit is slower than entry, reducing one-frame alpha collapses.
 *
 * No new ML model or network dependency is introduced. The state is derived only from the existing
 * PP-MattingV2 matte and the same local motion estimator already used by the CPU fallback.
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
    val movingFreshEvidence =
        motionEvidence * reliableFlow * smoothstepV51(.70f, .94f, current)
    val allowedFreshEntry = max(strongFreshEvidence, movingFreshEvidence)

    // Persistent person lock: a pixel with no nearby warped person support is treated as a new birth.
    // Stationary, unsupported births (chair backs, wall edges, furniture touching a shoulder) require
    // extremely strong PP-MattingV2 evidence. Motion-supported births remain permissive for hands,
    // loose cloth and hair entering newly exposed areas.
    val unsupported =
        (1f - established) * (1f - smoothstepV51(.08f, .46f, alphaSupport))
    val birthGuard = (
        unsupported * staticness * reliableFlow * (1f - allowedFreshEntry)
    ).coerceIn(0f, 1f)
    val birthGuardStrength = .76f + .22f * temporalStrength
    val birthTarget = min(current, alphaSupport + .045f)
    var refined = mixV51(current, birthTarget, birthGuard * birthGuardStrength)

    // Hysteresis: once foreground is established, it should not disappear on one weak model frame.
    // The previous alpha is already flow-warped by the local motion field. Fast motion reduces the
    // hold weight to avoid trails/ghosts, while slow/high-confidence motion keeps soft edges stable.
    val currentDrop = smoothstepV51(.10f, .56f, previous - current)
    val motionDamping = 1f - .58f * smoothstepV51(1.35f, 4.8f, motionBlocks)
    val exitHold = (
        established * reliableFlow * currentDrop * motionDamping * (.46f + .54f * temporalStrength)
    ).coerceIn(0f, .82f)
    val heldTarget = max(refined, min(previous, max(alphaSupport, previous * .94f)))
    refined = mixV51(refined, heldTarget, exitHold)

    // Confidence rises faster on supported/moving foreground than it falls. That asymmetry is the
    // hysteresis map: background needs repeated evidence to become foreground, while an established
    // subject edge survives brief alpha dips without becoming permanently sticky.
    val outputEvidence = max(refined, current * .86f)
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
 * Motion-warped persistent state applied after the existing GPU/CPU temporal stabilizer.
 *
 * State is stored as FloatArrays only for the current analysis worker and is never persisted into the
 * project. Cache generation is bumped separately so old V50 mattes are never reported as V51-ready.
 */
internal class PersistentPersonMemoryV51 : AutoCloseable {
    private var previousLuma: LumaFrameV45? = null
    private var previousAlpha: FloatArray? = null
    private var previousLock: FloatArray? = null
    private var previousConfidence: FloatArray? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var previousTimeUs = Long.MIN_VALUE

    fun refine(
        source: Bitmap,
        currentMatte: Bitmap,
        sourceTimeUs: Long,
        temporalStrength: Float,
    ): Bitmap {
        val width = currentMatte.width.coerceAtLeast(1)
        val height = currentMatte.height.coerceAtLeast(1)
        val currentPixels = IntArray(width * height)
        currentMatte.getPixels(currentPixels, 0, width, 0, 0, width, height)
        val currentAlpha = FloatArray(currentPixels.size) { index ->
            Color.red(currentPixels[index]) / 255f
        }
        val nowLuma = LumaFrameV45.fromBitmap(source)

        val oldLuma = previousLuma
        val oldAlpha = previousAlpha
        val oldLock = previousLock
        val oldConfidence = previousConfidence
        val reset =
            oldLuma == null || oldAlpha == null || oldLock == null || oldConfidence == null ||
                previousWidth != width || previousHeight != height ||
                oldAlpha.size != currentAlpha.size || oldLock.size != currentAlpha.size ||
                oldConfidence.size != currentAlpha.size ||
                oldLuma.width != nowLuma.width || oldLuma.height != nowLuma.height ||
                sourceTimeUs <= previousTimeUs ||
                sourceTimeUs - previousTimeUs > PERSON_MEMORY_RESET_GAP_US_V51 ||
                isSceneCutV51(oldLuma, nowLuma)

        if (reset || temporalStrength <= .001f) {
            seedStateV51(currentAlpha, width, height, nowLuma, sourceTimeUs)
            return currentMatte.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not copy V51 person-memory matte")
        }

        val flow = SpatialMotionFieldV45.estimate(nowLuma, oldLuma)
        val nextLock = FloatArray(currentAlpha.size)
        val nextConfidence = FloatArray(currentAlpha.size)
        val outPixels = IntArray(currentAlpha.size)

        val matteToFlowX = (nowLuma.width - 1).toFloat() / max(1, width - 1).toFloat()
        val matteToFlowY = (nowLuma.height - 1).toFloat() / max(1, height - 1).toFloat()
        val flowToMatteX = max(1, width - 1).toFloat() / max(1, nowLuma.width - 1).toFloat()
        val flowToMatteY = max(1, height - 1).toFloat() / max(1, nowLuma.height - 1).toFloat()
        val supportRadiusX = max(1f, flowToMatteX * 1.65f)
        val supportRadiusY = max(1f, flowToMatteY * 1.65f)

        for (y in 0 until height) {
            val ly = y * matteToFlowY
            for (x in 0 until width) {
                val index = y * width + x
                val lx = x * matteToFlowX
                val vector = flow.sample(lx, ly)
                val previousX = x + vector.dx * flowToMatteX
                val previousY = y + vector.dy * flowToMatteY
                val warpedPreviousAlpha = sampleFloatV51(oldAlpha, width, height, previousX, previousY)
                val warpedPreviousLock = sampleFloatV51(oldLock, width, height, previousX, previousY)
                val warpedPreviousConfidence = sampleFloatV51(oldConfidence, width, height, previousX, previousY)
                val alphaSupport = sampleSupportV51(
                    oldAlpha,
                    width,
                    height,
                    previousX,
                    previousY,
                    supportRadiusX,
                    supportRadiusY,
                )
                val lockSupport = sampleSupportV51(
                    oldLock,
                    width,
                    height,
                    previousX,
                    previousY,
                    supportRadiusX,
                    supportRadiusY,
                )
                val motionBlocks = sqrt(vector.dx * vector.dx + vector.dy * vector.dy)
                val decision = decidePersonMemorySampleV51(
                    PersonMemorySampleV51(
                        currentAlpha = currentAlpha[index],
                        previousAlpha = warpedPreviousAlpha,
                        previousLock = warpedPreviousLock,
                        previousConfidence = warpedPreviousConfidence,
                        previousAlphaSupport = alphaSupport,
                        previousLockSupport = lockSupport,
                        flowConfidence = vector.confidence,
                        motionBlocks = motionBlocks,
                        temporalStrength = temporalStrength,
                    ),
                )
                nextLock[index] = decision.nextLock
                nextConfidence[index] = decision.nextConfidence
                currentAlpha[index] = decision.alpha
                val v = (decision.alpha * 255f).roundToInt().coerceIn(0, 255)
                outPixels[index] = Color.argb(255, v, v, v)
            }
        }

        previousLuma = nowLuma
        previousAlpha = currentAlpha
        previousLock = nextLock
        previousConfidence = nextConfidence
        previousWidth = width
        previousHeight = height
        previousTimeUs = sourceTimeUs

        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun seedStateV51(
        alpha: FloatArray,
        width: Int,
        height: Int,
        luma: LumaFrameV45,
        sourceTimeUs: Long,
    ) {
        previousAlpha = alpha.copyOf()
        previousLock = FloatArray(alpha.size) { i -> smoothstepV51(.58f, .94f, alpha[i]) }
        previousConfidence = FloatArray(alpha.size) { i -> smoothstepV51(.28f, .88f, alpha[i]) }
        previousLuma = luma
        previousWidth = width
        previousHeight = height
        previousTimeUs = sourceTimeUs
    }

    override fun close() {
        previousLuma = null
        previousAlpha = null
        previousLock = null
        previousConfidence = null
        previousWidth = 0
        previousHeight = 0
        previousTimeUs = Long.MIN_VALUE
    }
}

private fun sampleSupportV51(
    values: FloatArray,
    width: Int,
    height: Int,
    x: Float,
    y: Float,
    radiusX: Float,
    radiusY: Float,
): Float {
    var support = sampleFloatV51(values, width, height, x, y)
    support = max(support, sampleFloatV51(values, width, height, x + radiusX, y))
    support = max(support, sampleFloatV51(values, width, height, x - radiusX, y))
    support = max(support, sampleFloatV51(values, width, height, x, y + radiusY))
    support = max(support, sampleFloatV51(values, width, height, x, y - radiusY))
    support = max(support, sampleFloatV51(values, width, height, x + radiusX, y + radiusY))
    support = max(support, sampleFloatV51(values, width, height, x - radiusX, y - radiusY))
    support = max(support, sampleFloatV51(values, width, height, x + radiusX, y - radiusY))
    support = max(support, sampleFloatV51(values, width, height, x - radiusX, y + radiusY))
    return support.coerceIn(0f, 1f)
}

private fun sampleFloatV51(
    values: FloatArray,
    width: Int,
    height: Int,
    x: Float,
    y: Float,
): Float {
    val fx = x.coerceIn(0f, (width - 1).toFloat())
    val fy = y.coerceIn(0f, (height - 1).toFloat())
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    val x1 = min(x0 + 1, width - 1)
    val y1 = min(y0 + 1, height - 1)
    val tx = fx - x0
    val ty = fy - y0
    val top = values[y0 * width + x0] + (values[y0 * width + x1] - values[y0 * width + x0]) * tx
    val bottom = values[y1 * width + x0] + (values[y1 * width + x1] - values[y1 * width + x0]) * tx
    return (top + (bottom - top) * ty).coerceIn(0f, 1f)
}

private fun isSceneCutV51(previous: LumaFrameV45, current: LumaFrameV45): Boolean {
    if (previous.width != current.width || previous.height != current.height) return true
    var sum = 0L
    var count = 0
    val step = 3
    var y = 0
    while (y < current.height) {
        var x = 0
        while (x < current.width) {
            sum += abs(current[x, y] - previous[x, y])
            count++
            x += step
        }
        y += step
    }
    return sum.toFloat() / count.coerceAtLeast(1).toFloat() >= PERSON_MEMORY_SCENE_CUT_MAD_V51
}

private fun smoothstepV51(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mixV51(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
