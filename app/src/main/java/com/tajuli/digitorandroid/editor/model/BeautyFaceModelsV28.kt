package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs

/** Internal effect ids intentionally stay out of the normal 50-effect creator catalog. */
const val BEAUTY_SKIN_BRIGHT_V28 = "__digitor_beauty_skin_bright_v28__"
const val BEAUTY_SKIN_SMOOTH_V28 = "__digitor_beauty_skin_smooth_v28__"
const val BEAUTY_PINK_LIP_V28 = "__digitor_beauty_pink_lip_v28__"
const val BEAUTY_HAIR_BROW_DARK_V28 = "__digitor_beauty_hair_brow_dark_v28__"
const val BEAUTY_EYE_POP_V28 = "__digitor_beauty_eye_pop_v28__"

data class BeautyStrengthsV28(
    val skinBright: Float = 0f,
    val skinSmooth: Float = 0f,
    val pinkLip: Float = 0f,
    val hairBrowDark: Float = 0f,
    val eyePop: Float = 0f,
) {
    val isIdentity: Boolean
        get() = skinBright <= 0f && skinSmooth <= 0f && pinkLip <= 0f &&
            hairBrowDark <= 0f && eyePop <= 0f
}

/**
 * Resolve stackable beauty strengths from all editable nodes. Multiple filter nodes therefore add
 * naturally: Skin Bright + Pink Lip + Hair/Brow Dark + Eye Pop can all be active together.
 */
fun TimelineClip.beautyStrengthsV28(): BeautyStrengthsV28 {
    var skinBright = 0f
    var skinSmooth = 0f
    var pinkLip = 0f
    var hairBrowDark = 0f
    var eyePop = 0f
    nodeGraph.nodes.asSequence()
        .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
        .flatMap { it.effects.asSequence() }
        .filter { it.enabled && it.amount > 0f }
        .forEach { effect ->
            when (effect.name) {
                BEAUTY_SKIN_BRIGHT_V28 -> skinBright += effect.amount
                BEAUTY_SKIN_SMOOTH_V28 -> skinSmooth += effect.amount
                BEAUTY_PINK_LIP_V28 -> pinkLip += effect.amount
                BEAUTY_HAIR_BROW_DARK_V28 -> hairBrowDark += effect.amount
                BEAUTY_EYE_POP_V28 -> eyePop += effect.amount
            }
        }
    return BeautyStrengthsV28(
        skinBright = skinBright.coerceIn(0f, 1.5f),
        skinSmooth = skinSmooth.coerceIn(0f, 1.5f),
        pinkLip = pinkLip.coerceIn(0f, 1.5f),
        hairBrowDark = hairBrowDark.coerceIn(0f, 1.5f),
        eyePop = eyePop.coerceIn(0f, 1.5f),
    )
}

/** Normalized image-space rectangle using Android top-left coordinates. */
data class BeautyRectV28(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun normalized(): BeautyRectV28 {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(l, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(t, 1f)
        return BeautyRectV28(l, t, r, b)
    }

    fun lerp(other: BeautyRectV28, t: Float): BeautyRectV28 = BeautyRectV28(
        left = left + (other.left - left) * t,
        top = top + (other.top - top) * t,
        right = right + (other.right - right) * t,
        bottom = bottom + (other.bottom - bottom) * t,
    ).normalized()
}

data class BeautyFaceGeometryV28(
    val face: BeautyRectV28,
    val lips: BeautyRectV28,
    val leftEye: BeautyRectV28,
    val rightEye: BeautyRectV28,
    val leftBrow: BeautyRectV28,
    val rightBrow: BeautyRectV28,
    val hair: BeautyRectV28,
) {
    fun lerp(other: BeautyFaceGeometryV28, t: Float): BeautyFaceGeometryV28 = BeautyFaceGeometryV28(
        face = face.lerp(other.face, t),
        lips = lips.lerp(other.lips, t),
        leftEye = leftEye.lerp(other.leftEye, t),
        rightEye = rightEye.lerp(other.rightEye, t),
        leftBrow = leftBrow.lerp(other.leftBrow, t),
        rightBrow = rightBrow.lerp(other.rightBrow, t),
        hair = hair.lerp(other.hair, t),
    )
}

data class BeautyFaceSampleV28(
    val sourceTimeUs: Long,
    val geometry: BeautyFaceGeometryV28?,
)

data class BeautyFaceTrackV28(
    val sourceUri: String,
    val analyzedStartUs: Long,
    val analyzedEndUs: Long,
    val samples: List<BeautyFaceSampleV28>,
    val version: Int = 1,
) {
    fun covers(startUs: Long, endUs: Long): Boolean =
        sourceUri.isNotBlank() && analyzedStartUs <= startUs && analyzedEndUs >= endUs

    /**
     * Interpolates geometry between sampled video frames. Explicit no-face samples are respected so
     * a face leaving the frame does not leave a stale beauty mask behind.
     */
    fun geometryAt(sourceTimeUs: Long): BeautyFaceGeometryV28? {
        val ordered = samples.sortedBy { it.sourceTimeUs }
        if (ordered.isEmpty()) return null
        if (sourceTimeUs <= ordered.first().sourceTimeUs) return ordered.first().geometry
        if (sourceTimeUs >= ordered.last().sourceTimeUs) return ordered.last().geometry

        val rightIndex = ordered.indexOfFirst { it.sourceTimeUs >= sourceTimeUs }.coerceAtLeast(1)
        val leftSample = ordered[rightIndex - 1]
        val rightSample = ordered[rightIndex]
        if (sourceTimeUs == rightSample.sourceTimeUs) return rightSample.geometry

        val leftGeometry = leftSample.geometry
        val rightGeometry = rightSample.geometry
        if (leftGeometry == null || rightGeometry == null) {
            return if (abs(sourceTimeUs - leftSample.sourceTimeUs) <= abs(rightSample.sourceTimeUs - sourceTimeUs)) {
                leftGeometry
            } else {
                rightGeometry
            }
        }
        val span = (rightSample.sourceTimeUs - leftSample.sourceTimeUs).coerceAtLeast(1L)
        val t = ((sourceTimeUs - leftSample.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
        return leftGeometry.lerp(rightGeometry, t)
    }

    fun mergedWith(other: BeautyFaceTrackV28): BeautyFaceTrackV28 {
        if (sourceUri != other.sourceUri) return other
        val byTime = linkedMapOf<Long, BeautyFaceSampleV28>()
        samples.sortedBy { it.sourceTimeUs }.forEach { byTime[it.sourceTimeUs] = it }
        other.samples.sortedBy { it.sourceTimeUs }.forEach { byTime[it.sourceTimeUs] = it }
        return copy(
            analyzedStartUs = minOf(analyzedStartUs, other.analyzedStartUs),
            analyzedEndUs = maxOf(analyzedEndUs, other.analyzedEndUs),
            samples = byTime.values.sortedBy { it.sourceTimeUs },
            version = maxOf(version, other.version),
        )
    }
}
