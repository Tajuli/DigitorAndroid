package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max

/**
 * V46 matte-cache coverage contract. Analyzer and readiness validation share the same target-count
 * policy: roughly 4 anchors/sec, capped at 320 for long clips. Missing one or two decoder samples is
 * tolerated because the renderer interpolates neighbouring mattes.
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

    val expectedCount = personCutoutTargetAnchorCountV46(durationUs)
    val expectedGapUs = durationUs / (expectedCount - 1).coerceAtLeast(1)

    // Allow roughly 2.4 nominal anchor intervals for VFR/long-GOP decoder misses. Short footage
    // still gets a firm 700 ms floor; long clips naturally receive a larger tolerance once capped.
    val maxGapUs = max(
        PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V46,
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

private const val PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V46 = 700_000L
