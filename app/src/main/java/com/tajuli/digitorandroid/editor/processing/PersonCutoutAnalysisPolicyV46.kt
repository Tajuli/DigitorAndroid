package com.tajuli.digitorandroid.editor.processing

/**
 * Shared V46 Pro Cutout analysis cadence. Keeping analyzer and coverage on one policy prevents the
 * old class of bugs where analysis generated one cadence while the readiness validator expected
 * another.
 */
internal const val PERSON_CUTOUT_ANCHORS_PER_SECOND_V46 = 4L
internal const val PERSON_CUTOUT_MIN_ANCHORS_V46 = 12
internal const val PERSON_CUTOUT_MAX_ANCHORS_V46 = 320

internal fun personCutoutTargetAnchorCountV46(durationUs: Long): Int {
    val safeDurationUs = durationUs.coerceAtLeast(1L)
    val target = ((safeDurationUs * PERSON_CUTOUT_ANCHORS_PER_SECOND_V46) / 1_000_000L).toInt() + 2
    return target.coerceIn(PERSON_CUTOUT_MIN_ANCHORS_V46, PERSON_CUTOUT_MAX_ANCHORS_V46)
}
