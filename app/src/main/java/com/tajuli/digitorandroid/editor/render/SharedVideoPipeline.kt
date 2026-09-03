package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V41 moves creator LOOK execution into [SharedColorPipeline], where each marker runs inside the
 * Serial/Parallel node that owns it. There is deliberately no second clip-level LOOK pass after the
 * node graph, preventing the old outside-node/double-application behavior.
 *
 * Spatial BEAUTY remains separate because smoothing/lips/eyes/hair need neighbouring pixels or
 * semantic geometry. V39's adaptive skin qualifier also remains a post-color spatial refinement.
 * V43 applies the final clip cutout matte after creator/beauty processing and before transition so
 * the exact same alpha reaches the multitrack compositor in preview and export.
 *
 * Order:
 *  1. Transform.
 *  2. Optional spatial BASE beauty.
 *  3. Camera input transform + node graph, including V41 node-local creator LOOKS.
 *  4. V39 adaptive color qualifier / beauty refinement.
 *  5. Timed creator effects.
 *  6. Optional semantic FINISH beauty.
 *  7. V43 Auto Cutout / Chroma Key alpha matte.
 *  8. Transition.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        CutoutEffectV43.forClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    fun compositedExportEffectsFor(clip: TimelineClip): List<Effect> =
        compositedStaticEffectsFor(clip)

    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> =
        compositedStaticEffectsFor(clip)

    private fun compositedStaticEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        CutoutEffectV43.forClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    /** Production zero-latency composited preview chain. */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        CutoutEffectV43.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        CutoutEffectV43.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
