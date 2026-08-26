package com.tajuli.digitorandroid.editor.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe bridge from Compose editor state to long-lived Media3 preview effects.
 *
 * Preview effects are intentionally created once for a source clip and remain attached to the
 * ExoPlayer pipeline. Parameter edits publish the newest immutable [TimelineClip] snapshot here so
 * transform/color/spatial GPU stages can read current values without tearing down the decoder.
 */
object PreviewClipState {
    private val clips = ConcurrentHashMap<String, TimelineClip>()

    fun update(clip: TimelineClip) {
        clips[clip.id] = clip
    }

    fun snapshot(clipId: String): TimelineClip? = clips[clipId]

    fun remove(clipId: String) {
        clips.remove(clipId)
    }

    fun clear() {
        clips.clear()
    }
}
