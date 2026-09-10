package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.chromaKeyCanApplyV71
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43

/**
 * Shared video processing stages used by both preview and export.
 *
 * V41 moves creator LOOK execution into [SharedColorPipeline], where each marker runs inside the
 * Serial/Parallel node that owns it. There is deliberately no second clip-level LOOK pass after the
 * node graph, preventing the old outside-node/double-application behavior.
 *
 * Spatial BEAUTY remains separate because smoothing/lips/eyes/hair need neighbouring pixels or
 * semantic geometry. V39's adaptive skin qualifier also remains a post-color spatial refinement.
 * V45 applies the portrait/chroma alpha matte after creator/beauty processing. V46 then performs a
 * source-RGB-guided fabric/cloth realism pass on PERSON mattes only before transition/compositing.
 * The same order is used by preview and export.
 *
 * Order:
 *  1. Transform.
 *  2. Optional spatial BASE beauty.
 *  3. Camera input transform + node graph, including V41 node-local creator LOOKS.
 *  4. V39 adaptive color qualifier / beauty refinement.
 *  5. Timed creator effects.
 *  6. Optional semantic FINISH beauty.
 *  7. V45 Pro Cutout / Chroma Key alpha matte.
 *  8. V46 fabric-aware RGB-guided portrait-edge refinement (PERSON only).
 *  9. Transition.
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
        if (clip.resolvedCutoutV43().chromaKeyCanApplyV71()) {
            CutoutEffectV43.forClip(clip, preview = false)?.let(::add)
        }
        FabricAwareCutoutRefineV46.forClip(clip, preview = false)?.let(::add)
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
        if (clip.resolvedCutoutV43().chromaKeyCanApplyV71()) {
            CutoutEffectV43.forClip(clip, preview = false)?.let(::add)
        }
        FabricAwareCutoutRefineV46.forClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    /** Production zero-latency composited preview chain. */
    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        add(residentPreviewCutoutEffect(clip))
        FabricAwareCutoutRefineV46.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV36.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        AdaptiveSkinQualifierEffectV39.forClip(clip, preview = true)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = true)?.let(::add)
        add(residentPreviewCutoutEffect(clip))
        FabricAwareCutoutRefineV46.forClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    /**
     * Realtime preview graphs are long-lived. If a session was created while Cutout mode was NONE,
     * omitting the effect here meant switching to CHROMA_KEY only updated project state; export was
     * correct, but the already-built preview graph had no alpha stage to execute.
     *
     * Keep one no-op-capable Cutout shader resident in preview from the start. The shader already
     * resolves the latest clip from PreviewProjectRegistry on every frame, so mode/key/sliders become
     * visible immediately without rebuilding MediaCodec/GL state. A PERSON snapshot is used only to
     * force construction when the stored mode is NONE; if a synthetic transition-ghost id cannot be
     * resolved from the registry, missing person mattes intentionally pass through fully opaque.
     */
    private fun residentPreviewCutoutEffect(clip: TimelineClip): Effect =
        CutoutEffectV43.forClip(clip, preview = true)
            ?: CutoutEffectV43.forClip(
                clip.copy(
                    cutoutV43 = clip.resolvedCutoutV43().copy(mode = CutoutModeV43.PERSON),
                ),
                preview = true,
            )!!
}
