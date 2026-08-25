package com.tajuli.digitorandroid.editor.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe bridge between the editor playhead, Compose parameter panels, and Media3 effects.
 *
 * ExoPlayer can restart the effect timestamp origin after a seek or after video effects are
 * rebuilt. Keyframes are evaluated from the real clip-local editor playhead instead of trusting
 * Media3's timestamp origin alone. The StateFlow also lets Correction/Color/Effects panels update
 * their displayed animated value while playback or scrubbing moves through keyframes.
 */
object PreviewTransformClock {
    data class Snapshot(
        val clipId: String?,
        val localUs: Long,
        val revision: Long,
    )

    private val mutableState = MutableStateFlow(Snapshot(null, 0L, 0L))
    val flow: StateFlow<Snapshot> = mutableState.asStateFlow()

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
        val current = mutableState.value
        if (current.clipId == clipId && current.localUs == localUs) return
        mutableState.value = Snapshot(clipId, localUs, current.revision + 1L)
    }

    fun snapshotFor(clipId: String): Snapshot? =
        mutableState.value.takeIf { it.clipId == clipId }

    fun clear() {
        val current = mutableState.value
        if (current.clipId == null && current.localUs == 0L) return
        mutableState.value = Snapshot(null, 0L, current.revision + 1L)
    }
}
