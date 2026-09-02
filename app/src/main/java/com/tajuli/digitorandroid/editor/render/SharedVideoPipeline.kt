package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Shared video processing stages used by both preview and export.
 *
 * V35 orders portrait/color/look work deliberately:
 *
 *  1. BASE semantic skin retouch.
 *  2. Camera input transform + ordinary Correction/Color graph.
 *  3. High-precision creator looks, one final serial look node per GPU pass.
 *  4. Timed creator effects.
 *  5. FINISH lips/eyes/hair cosmetics.
 *
 * This keeps skin smoothing before stylisation, keeps creator looks out of the 8-bit grading LUT,
 * and guarantees realtime preview/export use the same V35 look shader.
 */
@UnstableApi
object SharedVideoPipeline {
    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forExport(clip)?.let(::add)
        BeautyFaceEffectV34.baseForClip(clip, preview = false)?.let(::add)
        addAll(SharedColorPipeline.effectsFor(clip))
        addAll(CreatorLookEffectV35.effectsForClip(clip, preview = false))
        CreatorEffectGraphV25.forClip(clip, preview = false)?.let(::add)
        BeautyFaceEffectV34.finishForClip(clip, preview = false)?.let(::add)
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
        BeautyFaceEffectV34.baseForClip(clip, preview = preview)?.let(::add)
        addAll(
            if (preview) SharedColorPipeline.exactPreviewEffectsFor(clip)
            else SharedColorPipeline.effectsFor(clip),
        )
        addAll(CreatorLookEffectV35.effectsForClip(clip, preview = preview))
        CreatorEffectGraphV25.forClip(clip, preview = preview)?.let(::add)
        BeautyFaceEffectV34.finishForClip(clip, preview = preview)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = preview)?.let(::add)
    }

    fun compositedPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        BeautyFaceEffectV34.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        addAll(CreatorLookEffectV35.effectsForClip(clip, preview = true))
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV34.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        ClipTransformEffect.forPreview(clip)?.let(::add)
        BeautyFaceEffectV34.baseForClip(clip, preview = true)?.let(::add)
        addAll(SharedColorPipeline.previewEffectsFor(clip))
        addAll(CreatorLookEffectV35.effectsForClip(clip, preview = true))
        CreatorEffectGraphV25.forClip(clip, preview = true)?.let(::add)
        BeautyFaceEffectV34.finishForClip(clip, preview = true)?.let(::add)
        TransitionVisualEffectV22.forClip(clip, preview = true)?.let(::add)
    }
}
