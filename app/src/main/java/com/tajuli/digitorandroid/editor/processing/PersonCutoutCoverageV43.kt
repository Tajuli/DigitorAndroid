package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max

/**
 * Verifies that the URI-level semantic matte cache actually covers this clip's trimmed source span.
 * A different clip may point at the same source URI but use a completely different in/out range, so
 * "there is at least one cached mask" is not enough to safely skip analysis.
 *
 * Keep this contract aligned with PersonCutoutAnalyzerV43. The quality pass currently targets
 * roughly 2 semantic anchors/sec and caps long clips at 120 anchors. The renderer interpolates the
 * surrounding mattes, so validation must not demand the old 5 fps / 300-anchor density.
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

    val expectedCount = (((durationUs * PERSON_COVERAGE_ANCHORS_PER_SECOND_V43) / 1_000_000L).toInt() + 2)
        .coerceIn(PERSON_COVERAGE_MIN_ANCHORS_V43, PERSON_COVERAGE_MAX_ANCHORS_V43)
    val expectedGapUs = durationUs / (expectedCount - 1).coerceAtLeast(1)

    // MediaMetadataRetriever can miss an occasional VFR/compressed frame. Allow about two nominal
    // anchor intervals before declaring a hole, and never use less than 1.25 s. This still catches
    // genuinely partial trim coverage while accepting the bounded 2 fps matte track that the GPU
    // renderer is designed to interpolate.
    val maxGapUs = max(PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V43, expectedGapUs * 2L + 50_000L)

    val relevant = frames.filter {
        it.sourceTimeUs >= startUs - maxGapUs && it.sourceTimeUs <= endUs + maxGapUs
    }
    if (relevant.isEmpty()) return false

    val first = relevant.first().sourceTimeUs
    val last = relevant.last().sourceTimeUs
    if (first > startUs + maxGapUs || last < endUs - maxGapUs) return false

    // Include only gaps that overlap the requested trim. Large holes elsewhere in the same source
    // file should not force this clip to re-analyze.
    return relevant.zipWithNext().none { (a, b) ->
        val overlapsTrim = b.sourceTimeUs >= startUs && a.sourceTimeUs <= endUs
        overlapsTrim && b.sourceTimeUs - a.sourceTimeUs > maxGapUs
    }
}

private const val PERSON_COVERAGE_ANCHORS_PER_SECOND_V43 = 2L
private const val PERSON_COVERAGE_MIN_ANCHORS_V43 = 8
private const val PERSON_COVERAGE_MAX_ANCHORS_V43 = 120
private const val PERSON_COVERAGE_MIN_GAP_TOLERANCE_US_V43 = 1_250_000L
