package com.tajuli.digitorandroid.editor.preview

import android.os.Handler
import android.os.Looper
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineProject

/**
 * Re-submits the editor's current paused playhead after a lifecycle/Surface/export hand-off.
 *
 * Do not use engine.frame.value as the source of truth here. That value is the last GPU output and
 * can be many seconds behind the editor cursor while a seek/effect graph rebuild is in flight. Using
 * the stale GPU timestamp can immediately overwrite a correct current-cursor submit, which in turn
 * makes GpuPreviewSurface activate the software fallback and visually hide spatial creator effects.
 *
 * A structurally equal project copy is intentional: PreviewProjectRegistry's StateFlow will not
 * emit an equal snapshot again, but DavinciFramePreviewEngine compares the request snapshot by
 * identity for paused redraws.
 */
private val previewRefreshHandler = Handler(Looper.getMainLooper())

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

internal fun DavinciFramePreviewEngine.scheduleCurrentFrameRefresh(delayMs: Long = 120L) {
    val engine = this
    previewRefreshHandler.postDelayed(
        {
            val project = PreviewProjectRegistry.project() ?: return@postDelayed
            val timelineUs = resolvePreviewRefreshTimelineUs(
                project = project,
                staleGpuTimelineUs = engine.frame.value?.timelineUs,
            )
            engine.submit(project.copy(), timelineUs, false)
        },
        delayMs.coerceAtLeast(0L),
    )
}
