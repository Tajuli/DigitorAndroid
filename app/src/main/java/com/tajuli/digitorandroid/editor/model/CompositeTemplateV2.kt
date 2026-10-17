package com.tajuli.digitorandroid.editor.model

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** A single durable FX timeline bar drives the same sequential look on video or a still photo. */
data class CompositeEffectLayerV2(val effectName: String, val amount: Float)

data class CompositeTemplateStageV2(
    val label: String,
    val startSeconds: Int,
    val endSeconds: Int,
)

data class CompositeEffectTemplateV2(
    val name: String,
    val category: String,
    val description: String,
    val stages: List<CompositeTemplateStageV2>,
)

data class CompositeTemplateFrameV2(
    val style: Int = 0,
    val amount: Float = 0f,
    val progress: Float = 0f,
    /** Elapsed in OUTPUT seconds, not original source seconds (works on retimed clips). */
    val elapsedSeconds: Float = 0f,
    val zoomScale: Float = 1f,
    val blurReveal: Float = 0f,
    val waveProgress: Float = 0f,
    val waveStrength: Float = 0f,
    val shakeStrength: Float = 0f,
    val rippleCenterX: Float = .5f,
    val rippleCenterY: Float = .5f,
    val layers: List<CompositeEffectLayerV2> = emptyList(),
) {
    val active: Boolean get() = style != 0 && amount > 0f
}

/**
 * One template per media type, intentionally. The old single-effect and multi-card
 * galleries are removed from these categories; all ordinary creator-effect families
 * outside these two template categories remain available.
 *
 * Timeline:
 *  0..2 sec   strong blur progressively clears (only once)
 *  2..7 sec   clean frame + one top-left -> bottom-right travelling wave
 *  7..12 sec  clean frame + two distinct shake impulses
 *  12..17 ... alternate the wave and shake sections in 5-second windows.
 * A very slow, seamless zoom-in/out oscillation continues across ALL sections.
 */
object CompositeTemplateCatalogV2 {
    const val VIDEO = "Video Effect"
    const val IMAGE = "Image Effect"
    const val VIDEO_NAME = "Signature Motion"
    const val IMAGE_NAME = "Signature Photo Motion"
    const val WAVE_SECONDS = 5f
    const val OPENING_SECONDS = 2f
    const val ZOOM_PERIOD_SECONDS = 16f

    private val sequence = listOf(
        CompositeTemplateStageV2("Heavy blur → clean", 0, 2),
        CompositeTemplateStageV2("Clean + corner-to-corner wave", 2, 7),
        CompositeTemplateStageV2("Clean + two shake pulses", 7, 12),
        CompositeTemplateStageV2("Then repeat wave / double-shake every 5s", 12, 17),
    )

    val presets: List<CompositeEffectTemplateV2> = listOf(
        CompositeEffectTemplateV2(
            VIDEO_NAME, VIDEO,
            "2s blur reveal · 5s diagonal wave · 5s double shake · repeating beats · continuous slow zoom",
            sequence,
        ),
        CompositeEffectTemplateV2(
            IMAGE_NAME, IMAGE,
            "Animated photo: 2s blur reveal · 5s diagonal wave · 5s double shake · continuous slow zoom",
            sequence,
        ),
    )
    private val byName = presets.associateBy { it.name.lowercase() }
    fun find(name: String): CompositeEffectTemplateV2? = byName[name.lowercase()]
    fun forCategory(category: String): List<CompositeEffectTemplateV2> =
        presets.filter { it.category == category }

    /** Animation evaluated in output time so speed curves preserve the two-second reveal. */
    private fun atRemoteMotionTimeV1(
        effect: NodeEffect,
        clip: TimelineClip,
        sourceTimeUs: Long,
        beatPulse: Float,
    ): CompositeTemplateFrameV2 {
        val recipe = CreatorEffectCatalogV25.find(effect.name)?.remoteMotion
            ?: return CompositeTemplateFrameV2()
        val start = effect.resolvedSourceStartUsV26(clip)
        val elapsedUs = (clip.outputTimeForSource(sourceTimeUs) -
            clip.outputTimeForSource(start)).coerceAtLeast(0L)
        val seconds = elapsedUs / 1_000_000f
        val amount = effect.amount.coerceIn(0f, 1f)
        val revealT = (seconds / recipe.blurSeconds).coerceIn(0f, 1f)
        val revealEase = revealT * revealT * (3f - 2f * revealT)
        val blur = (1f - revealEase) * recipe.blurStrength * amount
        // Small continuous push / pull with no abrupt jumps at cycle boundaries.
        val zoomCycle = sin(2.0 * PI * seconds / recipe.zoomPeriodSeconds)
        val zoom = 1f + amount * (0.024f +
            recipe.zoomStrength * ((1f + zoomCycle.toFloat()) * .5f))
        var waveProgress = 0f
        var waveStrength = 0f
        var cx = .5f
        var cy = .5f
        if (seconds >= recipe.blurSeconds) {
            val afterReveal = seconds - recipe.blurSeconds
            val interval = recipe.rippleIntervalSeconds
            val slot = floor(afterReveal / interval).toInt()
            val inSlot = afterReveal - slot * interval
            // A rare, soft point-origin expanding circular wave; most frames stay clean.
            val waveAge = inSlot - .70f
            if (waveAge in 0f..1.35f) {
                val phase = (waveAge / 1.35f).coerceIn(0f, 1f)
                waveProgress = phase * 1.25f
                waveStrength = (sin(PI * phase).toFloat().coerceAtLeast(0f) *
                    recipe.rippleStrength * amount)
            }
            cx = when (slot % 3) { 0 -> .33f; 1 -> .62f; else -> .47f }
            cy = when (slot % 3) { 0 -> .38f; 1 -> .57f; else -> .68f }
        }
        return CompositeTemplateFrameV2(
            style = 20,
            amount = amount,
            elapsedSeconds = seconds,
            zoomScale = zoom,
            blurReveal = blur,
            waveProgress = waveProgress,
            waveStrength = waveStrength,
            shakeStrength = recipe.shakeStrength * beatPulse.coerceIn(0f, 1f) * amount,
            rippleCenterX = cx,
            rippleCenterY = cy,
            layers = if (blur > .001f) listOf(
                CompositeEffectLayerV2("Blur", blur * .28f)
            ) else emptyList(),
        )
    }

    /** Pure frame schedule shared by GPU preview/export and the CPU rendering fallback. */
    fun atSourceTime(
        effects: List<NodeEffect>,
        clip: TimelineClip,
        sourceTimeUs: Long,
        beatPulse: Float = 0f,
    ): CompositeTemplateFrameV2 {
        val remote = effects.lastOrNull {
            CreatorEffectCatalogV25.find(it.name)?.remoteMotion != null &&
                it.activeAtSourceTimeV26(clip, sourceTimeUs)
        }
        if (remote != null) return atRemoteMotionTimeV1(
            remote, clip, sourceTimeUs, beatPulse
        )
        val chosen = effects.lastOrNull { effect ->
            val template = find(effect.name)
            template != null &&
                template.category == (if (clip.isImageV21) IMAGE else VIDEO) &&
                effect.activeAtSourceTimeV26(clip, sourceTimeUs)
        } ?: return CompositeTemplateFrameV2()

        val startSourceUs = chosen.resolvedSourceStartUsV26(clip)
        // Use the displayed timeline's clock so a curve-retimed clip still has a 2s
        // opening and 5s sections. The bound stays anchored in SOURCE time for trims.
        val elapsedUs = (clip.outputTimeForSource(sourceTimeUs) -
            clip.outputTimeForSource(startSourceUs)).coerceAtLeast(0L)
        val seconds = elapsedUs / 1_000_000f
        val a = chosen.amount.coerceIn(0f, 1f)
        val opening = (seconds / OPENING_SECONDS).coerceIn(0f, 1f)
        val easedOpening = opening * opening * (3f - 2f * opening)
        val blur = (1f - easedOpening) * a

        // One gentle zoom cycle every 16 seconds; 1.04..1.13 at full intensity.
        val zoomCycle = sin(2.0 * PI * seconds / ZOOM_PERIOD_SECONDS - PI / 2.0)
        val zoom = 1f + a * (.04f + .09f * ((1f + zoomCycle.toFloat()) * .5f))

        var waveProgress = 0f
        var waveStrength = 0f
        var shakeStrength = 0f
        var progress = opening
        if (seconds >= OPENING_SECONDS) {
            val afterIntro = seconds - OPENING_SECONDS
            val slot = floor(afterIntro / WAVE_SECONDS).toInt()
            val inSlot = afterIntro - slot * WAVE_SECONDS
            progress = (inSlot / WAVE_SECONDS).coerceIn(0f, 1f)
            if (slot % 2 == 0) {
                // One sweeping front in the 5-second clean section (0.6s..3.8s).
                val waveT = ((inSlot - .6f) / 3.2f).coerceIn(0f, 1f)
                val fadeIn = (inSlot / .6f).coerceIn(0f, 1f)
                val fadeOut = ((WAVE_SECONDS - inSlot) / 1.2f).coerceIn(0f, 1f)
                waveProgress = waveT
                waveStrength = if (inSlot in .6f..3.8f) a * fadeIn * fadeOut else 0f
            } else {
                // Exactly two tight separate hits, at 1.45s and 3.45s of this slot.
                fun impulse(center: Float): Float {
                    val offset = (inSlot - center) / .17f
                    return exp((-offset * offset).toDouble()).toFloat()
                }
                shakeStrength = (a * (impulse(1.45f) + impulse(3.45f))).coerceIn(0f, 1f)
            }
        }

        return CompositeTemplateFrameV2(
            style = if (clip.isImageV21) 19 else 18,
            amount = a,
            progress = progress,
            elapsedSeconds = seconds,
            zoomScale = zoom,
            blurReveal = blur,
            waveProgress = waveProgress,
            waveStrength = waveStrength,
            shakeStrength = shakeStrength,
            // Existing creator shader also contributes its normal soft focus
            // during the opening; it decays completely before the clean section.
            layers = if (blur > 0.0001f) listOf(
                CompositeEffectLayerV2("Blur", blur * .86f),
                CompositeEffectLayerV2("Soft Focus", blur * .38f),
            ) else emptyList(),
        )
    }
}
