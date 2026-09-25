package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineProject

/**
 * Resolves the timeline position that a forced preview refresh must re-render.
 *
 * The editor playhead is authoritative. The last GPU frame is only a fallback because it can be
 * stale while a seek or graph rebuild is in flight.
 */
internal fun resolvePreviewRefreshTimelineUs(
    project: TimelineProject,
    staleGpuTimelineUs: Long?,
): Long {
    val clock = PreviewTransformClock.flow.value
    val activeClip = project.clip(clock.clipId)
    if (activeClip != null) {
        return (activeClip.timelineStartUs + clock.localUs)
            .coerceIn(
                activeClip.timelineStartUs,
                activeClip.timelineEndUs.coerceAtLeast(activeClip.timelineStartUs),
            )
            .coerceIn(0L, project.durationUs.coerceAtLeast(0L))
    }
    return staleGpuTimelineUs
        ?.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        ?: 0L
}
