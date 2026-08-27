package com.tajuli.digitorandroid.editor.preview

import android.os.Handler
import android.os.Looper

/**
 * Re-submits the last visible paused frame after a lifecycle/Surface/export hand-off.
 *
 * A structurally equal project copy is intentional: PreviewProjectRegistry's StateFlow will not
 * emit an equal snapshot again, but DavinciFramePreviewEngine compares the request snapshot by
 * identity for paused redraws. This therefore forces seekAndRender at the current visible frame
 * without perturbing editor state or the render/export parity graph.
 */
private val previewRefreshHandler = Handler(Looper.getMainLooper())

internal fun DavinciFramePreviewEngine.scheduleCurrentFrameRefresh(delayMs: Long = 120L) {
    val engine = this
    previewRefreshHandler.postDelayed(
        {
            val project = PreviewProjectRegistry.project() ?: return@postDelayed
            val timelineUs = engine.frame.value?.timelineUs
                ?.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
                ?: 0L
            engine.submit(project.copy(), timelineUs, false)
        },
        delayMs.coerceAtLeast(0L),
    )
}
