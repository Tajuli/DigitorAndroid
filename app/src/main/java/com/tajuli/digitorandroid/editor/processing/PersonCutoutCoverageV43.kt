package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.max

/**
 * Verifies that the URI-level semantic matte cache actually covers this clip's trimmed source span.
 * A different clip may point at the same source URI but use a completely different in/out range, so
 * "there is at least one cached mask" is not enough to safely skip analysis.
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

    // Quality analyzer targets roughly 5 anchors/sec, capped at 300. The binary SelfieSegmenter is
    // much faster than the old multiclass model, so motion can be sampled densely enough to reduce
    // mask interpolation lag while still keeping long clips bounded. Allow roughly two missed
    // samples before declaring the cache incomplete, with a 450 ms floor for VFR/decoder misses.
    val expectedCount = (((durationUs * 5L) / 1_000_000L).toInt() + 2).coerceIn(8, 300)
    val expectedGapUs = durationUs / (expectedCount - 1).coerceAtLeast(1)
    val maxGapUs = max(450_000L, expectedGapUs * 2L)

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
