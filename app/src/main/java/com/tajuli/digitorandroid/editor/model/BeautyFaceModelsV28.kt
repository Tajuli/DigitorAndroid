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
 * Resolve the Skin Bright amount independently for V38's global color qualifier.
 *
 * Skin Bright is no longer a spatial face-mask operation. The renderer may use face geometry only
 * to AUTO-PICK representative skin chroma, then applies the qualifier anywhere the same color occurs
 * in the frame. Keeping the amount separate prevents the legacy V36 BASE shader from painting an
 * ellipse/semantic mask over the face while preserving old direct-effect and V36 marker projects.
 */
fun TimelineClip.skinQualifierStrengthV38(): Float {
    var skinBright = 0f

    nodeGraph.nodes.asSequence()
        .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
        .flatMap { it.effects.asSequence() }
        .filter { it.enabled && it.amount > 0f }
        .forEach { effect ->
            val filterId = effect.creatorFilterPresetIdV36()
            if (filterId != null) {
                val preset = creatorFilterPresetV36(filterId)
                if (preset?.group == CreatorFilterGroupV36.BEAUTY) {
                    skinBright += (preset.beautyWeights[BEAUTY_SKIN_BRIGHT_V28] ?: 0f) * effect.amount
                }
            } else if (effect.name == BEAUTY_SKIN_BRIGHT_V28) {
                skinBright += effect.amount
            }
        }

    return skinBright.coerceIn(0f, 1.5f)
}

/**
 * Resolve spatial beauty strengths from both legacy direct beauty effects and V36 filter markers.
 *
 * V38 deliberately removes Skin Bright from this spatial contract. Skin Smooth, lips, eyes and hair
 * may remain semantic/spatial, but brightness is handled by the global color qualifier instead. This
 * is what removes the visible "bright face layer" boundary while keeping saved projects compatible.
 */
fun TimelineClip.beautyStrengthsV28(): BeautyStrengthsV28 {
    var skinSmooth = 0f
    var pinkLip = 0f
    var hairBrowDark = 0f
    var eyePop = 0f

    fun add(name: String, amount: Float) {
        when (name) {
            // BEAUTY_SKIN_BRIGHT_V28 intentionally routes to skinQualifierStrengthV38().
            BEAUTY_SKIN_SMOOTH_V28 -> skinSmooth += amount
            BEAUTY_PINK_LIP_V28 -> pinkLip += amount
            BEAUTY_HAIR_BROW_DARK_V28 -> hairBrowDark += amount
            BEAUTY_EYE_POP_V28 -> eyePop += amount
        }
    }

    nodeGraph.nodes.asSequence()
        .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
        .flatMap { it.effects.asSequence() }
        .filter { it.enabled && it.amount > 0f }
        .forEach { effect ->
            val filterId = effect.creatorFilterPresetIdV36()
            if (filterId != null) {
                val preset = creatorFilterPresetV36(filterId)
                if (preset?.group == CreatorFilterGroupV36.BEAUTY) {
                    preset.beautyWeights.forEach { (name, weight) ->
                        add(name, weight * effect.amount)
                    }
                }
            } else {
                add(effect.name, effect.amount)
            }
        }

    return BeautyStrengthsV28(
        skinBright = 0f,
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
