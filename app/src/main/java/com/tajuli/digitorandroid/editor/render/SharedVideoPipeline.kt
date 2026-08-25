package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Keeps preview/export effect ordering identical: geometry first, then color.
 * Preview geometry is playhead-clock anchored inside ClipTransformEffect; export geometry remains
 * deterministic from the media item's presentation timestamps.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
    }
}
