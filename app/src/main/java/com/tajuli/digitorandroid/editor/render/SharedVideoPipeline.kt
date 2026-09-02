package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/** Shared video processing stages used by both preview and export. */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV33.forClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> =
        compositedParityEffectsFor(clip, preview = false)

    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> =
        compositedParityEffectsFor(clip, preview = true)

    private fun compositedParityEffectsFor(
        clip: TimelineClip,
        preview: Boolean,
    ): List<Effect> = buildList {
        addAll(
            if (preview) SharedColorPipeline.exactPreviewEffectsFor(clip)
            else SharedColorPipeline.effectsFor(clip),
        )
        CreatorEffectGraphV25.forClip(clip, preview = preview)?.let(::add)
        BeautyFaceEffectV33.forClip(clip, preview = preview)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = preview)?.let(::add)
    }

    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV33.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV33.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
