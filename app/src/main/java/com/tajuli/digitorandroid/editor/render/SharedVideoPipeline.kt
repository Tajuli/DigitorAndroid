package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V38 keeps three different responsibilities separate:
 *  - spatial BEAUTY: smoothing/lips/eyes/hair may use semantic geometry in [BeautyFaceEffectV36]
 *  - LOOK: the full-frame creator transform is [CreatorLookEffectV37]
 *  - Skin Bright / portrait relight: [AdaptiveSkinQualifierEffectV38] auto-picks face color but then
 *    applies the response globally by COLOR, never by face position or a segmentation mask
 *
 * This removes the visible bright-face-layer boundary while preserving the resident realtime graph.
 * Preview and export use the same stage order and render math.
 *
 * Order:
 *  1. Transform.
 *  2. Optional spatial BASE beauty (Skin Smooth only after V38 routing).
 *  3. Camera input transform + normal Correction/Color LUT.
 *  4. Full-frame V37 creator look.
 *  5. V38 adaptive color qualifier / relight.
 *  6. Timed creator effects.
 *  7. Optional semantic FINISH beauty.
 *  8. Transition.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = false)?.let(::add)
        AdaptiveSkinQualifierEffectV38.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> =
        compositedStaticEffectsFor(clip)

    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> =
        compositedStaticEffectsFor(clip)

    private fun compositedStaticEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = false)?.let(::add)
        AdaptiveSkinQualifierEffectV38.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    /** Production zero-latency composited preview chain. */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = true)?.let(::add)
        AdaptiveSkinQualifierEffectV38.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = true)?.let(::add)
        AdaptiveSkinQualifierEffectV38.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
