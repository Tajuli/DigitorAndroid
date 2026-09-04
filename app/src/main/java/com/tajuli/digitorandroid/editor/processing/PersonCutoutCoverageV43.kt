package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43

/**
 * V47/V48 adaptive matte-cache coverage contract.
 *
 * A normal successful generation requires the ready marker. High/every-frame analysis has one
 * additional recovery path for vendor codecs that fail only while draining the final EOS: when the
 * exact pending generation signature still matches and the produced mattes already satisfy the High
 * gap/end coverage contract, those mattes are safe to promote instead of forcing a full re-analysis.
 */
fun hasPersonCutoutCoverageV43(context: Context, clip: TimelineClip): Boolean {
    val quality = clip.resolvedCutoutV43().analysisQualityV47
    val readyGeneration = hasPersonCutoutGenerationV47Marker(context, clip)
    val recoverableHighGeneration =
        quality == CutoutAnalysisQualityV47.HIGH &&
            hasPersonCutoutGenerationV47PendingMarker(context, clip)
    if (!readyGeneration && !recoverableHighGeneration) return false

    val frames = PersonCutoutMaskStoreV43.index(context, clip).frames
        .filter { it.file.isFile }
        .sortedBy { it.sourceTimeUs }
    if (frames.isEmpty()) return false
    if (clip.isImageV21) return readyGeneration

    val startUs = clip.sourceInUs.coerceAtLeast(0L)
    val endUs = clip.sourceOutUs.coerceAtLeast(startUs + 1L) - 1L
    val maxGapUs = personCutoutMaxGapUsV47(quality)

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
