package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V36 keeps creator filters responsive by attaching persistent realtime-preview stages before a
 * filter is selected. Filter taps only mutate lightweight effect markers on an existing node, so
 * neither the decoder nor the GL graph needs to be recreated.
 *
 * Two preview contracts intentionally exist:
 *  - [compositedPreviewEffectsFor] is the production realtime graph. It keeps the BASE/look/FINISH
 *    stages resident so a new filter becomes visible on the next submitted frame.
 *  - [compositedExactPreviewEffectsFor] is the deterministic export-parity snapshot used by the
 *    byte-parity harness. It uses the same static stage topology as export. A resident neutral GPU
 *    pass can differ by one 8-bit rounding step even when it is visually a no-op, so comparing that
 *    latency-optimised graph byte-for-byte with an export that correctly omits inactive stages would
 *    test framebuffer quantisation rather than render math.
 *
 * Order:
 *  1. Transform.
 *  2. BASE skin retouch (instant color-skin fallback -> semantic refinement).
 *  3. Camera input transform + normal Correction/Color LUT.
 *  4. High-precision creator-look stack.
 *  5. Timed creator effects.
 *  6. FINISH lips/eyes/hair beauty.
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
        compositedStaticEffectsFor(clip)

    /**
     * Static snapshot used only when proving render-stage parity against export. Production realtime
     * preview uses [compositedPreviewEffectsFor] so filter stages stay resident and immediately live.
     */
    fun compositedExactPreviewEffectsFor(clip: TimelineClip): List<Effect> =
        compositedStaticEffectsFor(clip)

    private fun compositedStaticEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV36.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        CreatorLookStackEffectV36.forClip(clip, preview = false)?.let(::add)
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV36.finishForClip(clip, preview = false)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = false)?.let(::add)
    }

    /** Production zero-latency composited preview chain. */
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
