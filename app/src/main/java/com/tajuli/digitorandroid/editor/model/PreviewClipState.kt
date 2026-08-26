package com.tajuli.digitorandroid.editor.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe bridge from immutable Compose timeline state to long-lived GPU preview stages.
 *
 * Final-output preview keeps MediaCodec decoders and the Media3 VideoGraph alive while ordinary
 * transform/color/effect parameters change. The newest clip snapshot is published here so preview
 * shader/compositor callbacks can read current values without rebuilding the decoder graph.
 *
 * Transformer export never reads this bridge; export remains fully snapshot/deterministic.
 */
object PreviewClipState {
    private val clips = ConcurrentHashMap<String, TimelineClip>()

    fun update(clip: TimelineClip) {
        clips[clip.id] = clip
    }

    fun updateAll(values: Iterable<TimelineClip>) {
        values.forEach(::update)
    }

    fun snapshot(clipId: String): TimelineClip? = clips[clipId]

    fun remove(clipId: String) {
        clips.remove(clipId)
    }

    fun clear() {
        clips.clear()
    }
}
