package com.tajuli.digitorandroid.editor.model

import kotlin.math.abs

/**
 * Dedicated V100 body-effect domain.
 *
 * CreatorEffectGraph remains responsible for generic full-frame effects. BodyEffectGraphV100 reads
 * only this compact vector, so subject-aware FX can evolve independently around pose/matte/face
 * tracking without growing the generic creator shader again.
 */
data class BodyEffectValuesV100(
    val clone: Float = 0f,
    val fireEyes: Float = 0f,
    val bodyElectric: Float = 0f,
    val bodyAura: Float = 0f,
    val electricEyes: Float = 0f,
    val laserEyes: Float = 0f,
    val stroke: Float = 0f,
    val bodyFire: Float = 0f,
) {
    val isIdentity: Boolean
        get() = clone <= 0f && fireEyes <= 0f && bodyElectric <= 0f &&
            bodyAura <= 0f && electricEyes <= 0f && laserEyes <= 0f &&
            stroke <= 0f && bodyFire <= 0f
}

data class BodyEffectRequirementsV100(
    val face: Boolean,
    val pose: Boolean,
    val matte: Boolean,
)

fun BodyEffectValuesV100.requirementsV100(): BodyEffectRequirementsV100 =
    BodyEffectRequirementsV100(
        face = fireEyes > .001f || electricEyes > .001f || laserEyes > .001f,
        pose = bodyElectric > .001f,
        matte = clone > .001f || bodyElectric > .001f || bodyAura > .001f ||
            stroke > .001f || bodyFire > .001f,
    )

private fun CreatorEffectVectorV25.bodyValuesV100(): BodyEffectValuesV100 =
    BodyEffectValuesV100(
        clone = clone,
        fireEyes = fireEyes,
        bodyElectric = bodyElectric,
        bodyAura = bodyAura,
        electricEyes = electricEyes,
        laserEyes = laserEyes,
        stroke = stroke,
        bodyFire = bodyFire,
    )

fun resolveBodyEffectsV100(effects: List<NodeEffect>): BodyEffectValuesV100 =
    resolveCreatorEffectsV25(effects).bodyValuesV100()

fun resolveTimedBodyEffectsV100(
    effects: List<NodeEffect>,
    clip: TimelineClip,
    sourceTimeUs: Long,
): BodyEffectValuesV100 =
    resolveTimedCreatorEffectsV26(effects, clip, sourceTimeUs).bodyValuesV100()

/** Normalized image-space landmark. MediaPipe uses top-left origin for x/y. */
data class BodyLandmarkV100(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    fun lerp(other: BodyLandmarkV100, t: Float): BodyLandmarkV100 =
        BodyLandmarkV100(
            x = x + (other.x - x) * t,
            y = y + (other.y - y) * t,
            z = z + (other.z - z) * t,
        )
}

data class BodyPoseSampleV100(
    val sourceTimeUs: Long,
    val landmarks: List<BodyLandmarkV100>?,
)

data class BodyPoseTrackV100(
    val sourceUri: String,
    val analyzedStartUs: Long,
    val analyzedEndUs: Long,
    val samples: List<BodyPoseSampleV100>,
    val version: Int = 1,
) {
    fun covers(startUs: Long, endUs: Long): Boolean =
        sourceUri.isNotBlank() && analyzedStartUs <= startUs && analyzedEndUs >= endUs

    fun landmarksAt(sourceTimeUs: Long): List<BodyLandmarkV100>? {
        val ordered = samples.sortedBy { it.sourceTimeUs }
        if (ordered.isEmpty()) return null
        if (sourceTimeUs <= ordered.first().sourceTimeUs) return ordered.first().landmarks
        if (sourceTimeUs >= ordered.last().sourceTimeUs) return ordered.last().landmarks

        val rightIndex = ordered.indexOfFirst { it.sourceTimeUs >= sourceTimeUs }.coerceAtLeast(1)
        val left = ordered[rightIndex - 1]
        val right = ordered[rightIndex]
        if (sourceTimeUs == right.sourceTimeUs) return right.landmarks

        val a = left.landmarks
        val b = right.landmarks
        if (a == null || b == null || a.size != b.size) {
            return if (abs(sourceTimeUs - left.sourceTimeUs) <= abs(right.sourceTimeUs - sourceTimeUs)) a else b
        }

        val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
        val t = ((sourceTimeUs - left.sourceTimeUs).toDouble() / span.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
        return a.indices.map { index -> a[index].lerp(b[index], t) }
    }

    fun detectedRatio(): Float {
        if (samples.isEmpty()) return 0f
        return samples.count { it.landmarks != null }.toFloat() / samples.size.toFloat()
    }
}

/** MediaPipe Pose Landmarker 33-point indices used by BodyEffectGraphV100. */
object BodyPoseLandmarkIndexV100 {
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
}
