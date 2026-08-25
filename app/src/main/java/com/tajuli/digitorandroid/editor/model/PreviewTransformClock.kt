package com.tajuli.digitorandroid.editor.model

import java.util.concurrent.atomic.AtomicReference

/**
 * Lock-free bridge between the editor playhead and the Media3 video-effect thread.
 *
 * ExoPlayer can restart the effect timestamp origin after a seek or after video effects are
 * rebuilt. Transform keyframes are stored in clip-local timeline time, so preview must not depend
 * on Media3's timestamp origin alone. The editor updates this clock whenever it resolves the
 * visible clip at the current playhead. ClipTransformEffect uses the revision as a fresh anchor
 * and advances smoothly from Media3 frame timestamps between editor clock updates.
 */
object PreviewTransformClock {
    data class Snapshot(
        val clipId: String?,
        val localUs: Long,
        val revision: Long,
    )

    private val state = AtomicReference(Snapshot(null, 0L, 0L))

    fun update(clip: TimelineClip?, timelineUs: Long) {
        val clipId = clip?.id
        val localUs = if (clip == null) {
            0L
        } else {
            ClipTransform.clipLocalTimeUs(
                clipStartUs = clip.timelineStartUs,
                clipDurationUs = clip.durationUs,
                timelineUs = timelineUs,
            )
        }

        while (true) {
            val current = state.get()
            if (current.clipId == clipId && current.localUs == localUs) return
            val next = Snapshot(clipId, localUs, current.revision + 1L)
            if (state.compareAndSet(current, next)) return
        }
    }

    fun snapshotFor(clipId: String): Snapshot? =
        state.get().takeIf { it.clipId == clipId }

    fun clear() {
        while (true) {
            val current = state.get()
            if (current.clipId == null && current.localUs == 0L) return
            if (state.compareAndSet(current, Snapshot(null, 0L, current.revision + 1L))) return
        }
    }
}
