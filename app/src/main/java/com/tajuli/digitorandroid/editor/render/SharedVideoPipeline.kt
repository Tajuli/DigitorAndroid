package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages.
 *
 * The parity compositor path is intentionally centralized so preview and export cannot silently
 * drift to different LUT resolutions or spatial shader stacks. Geometry and opacity are owned by
 * ResolveVideoCompositorSettings for both preview and export.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = false)?.let(::add)
    }

    /** Per-layer effects used before the final export compositor. */
    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> =
        compositedParityEffectsFor(clip, preview = false)

    /**
     * Pixel-parity realtime path. Preview uses the same 33^3 color LUT and the same spatial shader
     * implementation as export. The preview flag only selects live editor state/time anchoring.
     */
    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> =
        compositedParityEffectsFor(clip, preview = true)

    private fun compositedParityEffectsFor(
        clip: TimelineClip,
        preview: Boolean,
    ): List<Effect> = buildList {
        addAll(
            if (preview) {
                SharedColorPipeline.exactPreviewEffectsFor(clip)
            } else {
                SharedColorPipeline.effectsFor(clip)
            },
        )
        SpatialNodeGraphEffect.forClip(clip, preview = preview)?.let(::add)
    }

    /**
     * Lightweight legacy GPU preview path retained for fallback/experimentation. Geometry/opacity
     * are owned by the compositor while lower-resolution preview LUT/effects stay on the GL path.
     */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        SpatialNodeGraphEffect.forClip(clip, preview = true)?.let(::add)
    }
}
