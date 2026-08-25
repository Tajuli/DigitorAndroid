package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Keeps preview/export effect ordering identical: geometry first, then the resolved color graph,
 * then one graph-aware spatial FX compositor.
 *
 * The spatial stage keeps parallel node branches independent and mixes them at Mix nodes instead
 * of flattening editable nodes into a serial effect list.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    /** Legacy single-source ExoPlayer preview path retained for V5 fallback. */
    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    /**
     * True multilayer preview path. CompositionPlayer feeds item-local timestamps to each video
     * sequence, so geometry/color/spatial animation must use the same timestamp mapping as export.
     * Preview-specific LUT sizing is preserved for responsiveness.
     */
    fun compositionPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.compositionPreviewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }
}
