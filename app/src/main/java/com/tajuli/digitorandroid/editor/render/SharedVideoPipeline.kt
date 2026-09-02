package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V37 separates LOOKS from BEAUTY at the render-contract level:
 *  - LOOKS are deterministic full-frame RGB transforms in [CreatorLookEffectV37]. They never depend
 *    on face boxes, skin masks, ML segmentation, or pixel location.
 *  - BEAUTY is the only semantic/spatial face/skin/hair stage in [BeautyFaceEffectV36].
 *
 * Two preview contracts intentionally remain:
 *  - [compositedPreviewEffectsFor] is the production realtime graph. It keeps look/beauty stages
 *    resident so a filter marker/intensity change is visible on the next submitted frame.
 *  - [compositedExactPreviewEffectsFor] is the deterministic export-parity snapshot. It uses the
 *    same static topology as export so an inactive resident pass cannot create a one-LSB framebuffer
 *    rounding difference in the byte-parity harness.
 *
 * Order:
 *  1. Transform.
 *  2. Optional semantic BASE beauty.
 *  3. Camera input transform + normal Correction/Color LUT.
 *  4. Full-frame V37 creator look.
 *  5. Timed creator effects.
 *  6. Optional semantic FINISH beauty.
 *  7. Transition.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = false)?.let(::add)
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
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    /** Production zero-latency composited preview chain. */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        CreatorLookEffectV37.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
