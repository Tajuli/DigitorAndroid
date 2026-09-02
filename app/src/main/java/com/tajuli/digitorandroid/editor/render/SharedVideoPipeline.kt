package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V36 keeps creator filters responsive by attaching persistent preview stages before a filter is
 * selected. Filter taps only mutate lightweight effect markers on an existing node, so neither the
 * decoder nor the GL graph needs to be recreated.
 *
 * Order:
 *  1. Transform.
 *  2. Persistent BASE skin retouch (instant color-skin fallback -> semantic refinement).
 *  3. Camera input transform + normal Correction/Color LUT.
 *  4. Persistent high-precision creator-look stack.
 *  5. Timed creator effects.
 *  6. Persistent FINISH lips/eyes/hair beauty.
 *  7. Transition.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorLookStackEffectV36.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
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
        BeautyFaceEffectV36.baseForClip(clip, preview = preview)?.let(::add)
        addAll(
            if (preview) SharedColorPipeline.exactPreviewEffectsFor(clip)
            else SharedColorPipeline.effectsFor(clip),
        )
        CreatorLookStackEffectV36.forClip(clip, preview = preview)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = preview)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = preview)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = preview)?.let(::add)
    }

    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookStackEffectV36.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookStackEffectV36.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
