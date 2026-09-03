package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max

/**
 * V44 matte-cache coverage contract. Keep this aligned with PersonCutoutAnalyzerV43: MODNet targets
 * roughly 4 anchors/sec and caps long clips at 160. Missing one or two random decoder samples is not
 * a reason to re-run a heavy portrait-matting pass because the renderer interpolates neighbours.
 */
fun hasPersonCutoutCoverageV43(context: Context, clip: TimelineClip): Boolean {
    val frames = PersonCutoutMaskStoreV43.index(context, clip).frames
        .filter { it.file.isFile }
        .sortedBy { it.sourceTimeUs }
    if (frames.isEmpty()) return false
    if (clip.isImageV21) return true

    val startUs = clip.sourceInUs.coerceAtLeast(0L)
    val endUs = clip.sourceOutUs.coerceAtLeast(startUs + 1L) - 1L
    val durationUs = (endUs - startUs + 1L).coerceAtLeast(1L)

    val expectedCount = (((durationUs * PERSON_COVERAGE_ANCHORS_PER_SECOND_V44) / 1_000_000L).toInt() + 2)
        .coerceIn(PERSON_COVERAGE_MIN_ANCHORS_V44, PERSON_COVERAGE_MAX_ANCHORS_V44)
    val expectedGapUs = durationUs / (expectedCount - 1).coerceAtLeast(1)

    // Allow roughly 2.4 nominal anchor intervals for VFR/long-GOP decoder misses. Short footage
    // still gets a firm 700 ms floor; long clips naturally receive a larger tolerance once capped.
    val maxGapUs = max(
        PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V44,
        (expectedGapUs * 12L) / 5L + 50_000L,
    )

    val relevant = frames.filter {
        it.sourceTimeUs >= startUs - maxGapUs && it.sourceTimeUs <= endUs + maxGapUs
    }
    if (relevant.isEmpty()) return false

    val first = relevant.first().sourceTimeUs
    val last = relevant.last().sourceTimeUs
    if (first > startUs + maxGapUs || last < endUs - maxGapUs) return false

    return relevant.zipWithNext().none { (a, b) ->
        val overlapsTrim = b.sourceTimeUs >= startUs && a.sourceTimeUs <= endUs
        overlapsTrim && b.sourceTimeUs - a.sourceTimeUs > maxGapUs
    }
}

private const val PERSON_COVERAGE_ANCHORS_PER_SECOND_V44 = 4L
private const val PERSON_COVERAGE_MIN_ANCHORS_V44 = 12
private const val PERSON_COVERAGE_MAX_ANCHORS_V44 = 160
private const val PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V44 = 700_000L
