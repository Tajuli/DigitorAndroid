package com.tajuli.digitorandroid.editor.model

/**
 * A template is one editable timeline effect that orchestrates several existing visual
 * effects and camera moves across multiple timed phases. It is NOT a list of isolated
 * one-effect shortcuts. A template is persisted as a single NodeEffect in saved projects.
 */
data class CompositeEffectLayerV2(val effectName: String, val amount: Float)

data class CompositeTemplateStageV2(
    /** Exclusive end fraction of the effect timeline bar, except the final stage. */
    val end: Float,
    val motionStyle: Int,
    val motionAmount: Float,
    val layers: List<CompositeEffectLayerV2>,
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
    val elapsedSeconds: Float = 0f,
    val layers: List<CompositeEffectLayerV2> = emptyList(),
) {
    val active: Boolean get() = style != 0 && amount > 0f
}

/** Original Digitor multi-effect edit recipes; never depends on external video templates. */
object CompositeTemplateCatalogV2 {
    const val VIDEO = "Video Effect"
    const val IMAGE = "Image Effect"

    private fun fx(name: String, amount: Float) = CompositeEffectLayerV2(name, amount)
    private fun stage(end: Float, motion: Int, motionAmount: Float, vararg layers: CompositeEffectLayerV2) =
        CompositeTemplateStageV2(end, motion, motionAmount, layers.toList())
    private fun t(
        name: String,
        category: String,
        description: String,
        vararg stages: CompositeTemplateStageV2,
    ) = CompositeEffectTemplateV2(name, category, description, stages.toList())

    val presets: List<CompositeEffectTemplateV2> = listOf(
        t("Fashion Velocity", VIDEO, "Whip + chromatic blur, cinematic hold, flash finish",
            stage(.23f, 2, .85f, fx("Zoom Blur", .75f), fx("RGB Split", .48f), fx("Motion Blur", .62f)),
            stage(.72f, 1, .42f, fx("HDR Pop", .55f), fx("Dreamy", .27f), fx("Vignette", .23f)),
            stage(1f, 4, .76f, fx("Glow", .55f), fx("Chromatic", .38f), fx("Sharpen", .24f))),
        t("Beat Drop Cinema", VIDEO, "Slow focus builds to a shake, zoom blur and beat flash",
            stage(.37f, 1, .31f, fx("Soft Focus", .55f), fx("Black Mist", .27f), fx("Glow", .20f)),
            stage(.72f, 3, .73f, fx("Zoom Blur", .68f), fx("RGB Split", .32f), fx("HDR Pop", .31f)),
            stage(1f, 4, .86f, fx("Film Burn", .48f), fx("Sharpen", .41f), fx("Ghost Trail", .19f))),
        t("Dreamy Slowmo", VIDEO, "Smooth camera drift, bloom, soft-focus glow and gentle echo",
            stage(.38f, 11, .28f, fx("Dreamy", .68f), fx("Soft Focus", .41f), fx("Black Mist", .21f)),
            stage(.77f, 5, .47f, fx("Glow", .52f), fx("Ghost Trail", .34f), fx("Sepia Fade", .20f)),
            stage(1f, 12, .40f, fx("Dreamy", .34f), fx("Sharpen", .34f), fx("Vignette", .19f))),
        t("Neon Glitch Rush", VIDEO, "RGB channel drift, glitch bursts, scanlines and zoom echo",
            stage(.26f, 2, .70f, fx("Digital Glitch", .62f), fx("RGB Split", .55f), fx("Zoom Blur", .42f)),
            stage(.68f, 5, .56f, fx("Ghost Trail", .62f), fx("Chromatic", .62f), fx("Glow", .43f)),
            stage(1f, 3, .61f, fx("VHS", .52f), fx("Jitter", .42f), fx("Flash", .48f))),
        t("Color Reveal Story", VIDEO, "Monochrome opening, soft zoom reveal, then vivid flare",
            stage(.42f, 6, .88f, fx("Old Film", .33f), fx("Vignette", .44f), fx("Soft Focus", .24f)),
            stage(.78f, 1, .41f, fx("HDR Pop", .62f), fx("Glow", .37f), fx("Sharpen", .26f)),
            stage(1f, 4, .51f, fx("Light Leak", .36f), fx("Chromatic", .25f), fx("Glow", .39f))),
        t("Split Screen Beat", VIDEO, "Four-panel burst followed by a zoom and flicker echo",
            stage(.43f, 7, .81f, fx("RGB Split", .29f), fx("Glow", .24f), fx("Sharpen", .41f)),
            stage(.76f, 2, .61f, fx("Zoom Blur", .67f), fx("Digital Glitch", .27f), fx("Ghost Trail", .30f)),
            stage(1f, 4, .61f, fx("Strobe", .39f), fx("Chromatic", .38f), fx("HDR Pop", .26f))),
        t("Retro Music Video", VIDEO, "Vintage look layered over rhythmic camera and flash",
            stage(.34f, 11, .27f, fx("Old Film", .76f), fx("VHS", .38f), fx("Vignette", .22f)),
            stage(.76f, 3, .43f, fx("Retro Noise", .55f), fx("Light Leak", .32f), fx("Ghost Trail", .22f)),
            stage(1f, 4, .47f, fx("Film Burn", .46f), fx("Glow", .26f), fx("Scan Lines", .24f))),
        t("Cinematic Photo Story", IMAGE, "Slow Ken Burns, focus bloom and a warm photographic finish",
            stage(.34f, 11, .63f, fx("Soft Focus", .55f), fx("Dreamy", .37f), fx("Vignette", .26f)),
            stage(.73f, 13, .35f, fx("HDR Pop", .42f), fx("Glow", .25f), fx("Sepia Fade", .33f)),
            stage(1f, 12, .40f, fx("Light Leak", .33f), fx("Sharpen", .26f), fx("Black Mist", .24f))),
        t("Vintage Memory Reel", IMAGE, "A moving photograph with analog dust, warmth and film burn",
            stage(.33f, 11, .45f, fx("Old Film", .69f), fx("Film Grain", .39f), fx("Vignette", .32f)),
            stage(.77f, 14, .29f, fx("Super 8", .45f), fx("Sepia Fade", .38f), fx("Soft Focus", .26f)),
            stage(1f, 16, .46f, fx("Film Burn", .47f), fx("Retro Noise", .26f), fx("Glow", .25f))),
        t("Portrait Dream Glow", IMAGE, "Floating portrait, luminous soft focus and glowing reveal",
            stage(.39f, 13, .36f, fx("Dreamy", .61f), fx("Soft Focus", .40f), fx("Black Mist", .29f)),
            stage(.78f, 12, .50f, fx("Glow", .63f), fx("HDR Pop", .23f), fx("Vignette", .18f)),
            stage(1f, 16, .32f, fx("Light Leak", .44f), fx("Sharpen", .29f), fx("Glow", .25f))),
        t("Photo Collage Rush", IMAGE, "Animated tiled reveal, pan zoom and vivid glitch highlight",
            stage(.45f, 17, .81f, fx("RGB Split", .34f), fx("HDR Pop", .35f), fx("Sharpen", .36f)),
            stage(.78f, 15, .53f, fx("Zoom Blur", .49f), fx("Glow", .29f), fx("Chromatic", .33f)),
            stage(1f, 16, .61f, fx("Digital Glitch", .33f), fx("Flash", .30f), fx("Sharpen", .23f))),
        t("Golden Hour Motion", IMAGE, "Gentle pan, warm film softness and light leak finale",
            stage(.39f, 11, .44f, fx("Dreamy", .40f), fx("Sepia Fade", .60f), fx("Soft Focus", .28f)),
            stage(.76f, 13, .30f, fx("Light Leak", .52f), fx("Glow", .30f), fx("Vignette", .23f)),
            stage(1f, 12, .30f, fx("Fade Film", .40f), fx("HDR Pop", .25f), fx("Sharpen", .22f))),
        t("Photo Beat Flash", IMAGE, "Beat-synced photo push, chromatic offset and strobe finale",
            stage(.35f, 15, .56f, fx("Zoom Blur", .44f), fx("Chromatic", .35f), fx("Glow", .27f)),
            stage(.76f, 12, .60f, fx("RGB Split", .46f), fx("HDR Pop", .49f), fx("Ghost Trail", .24f)),
            stage(1f, 16, .73f, fx("Flash", .53f), fx("Sharpen", .29f), fx("Glow", .43f))),
        t("Film Frame Journey", IMAGE, "Travel-photo parallax, analog flicker and a vignette fade",
            stage(.33f, 14, .29f, fx("90s Cam", .52f), fx("Film Grain", .32f), fx("Vignette", .44f)),
            stage(.72f, 13, .42f, fx("Vintage Lens", .44f), fx("Light Leak", .22f), fx("Soft Focus", .19f)),
            stage(1f, 11, .39f, fx("Fade Film", .55f), fx("Old Film", .25f), fx("Glow", .20f))),
    )

    private val byName = presets.associateBy { it.name.lowercase() }
    fun find(name: String): CompositeEffectTemplateV2? = byName[name.lowercase()]
    fun forCategory(category: String): List<CompositeEffectTemplateV2> =
        presets.filter { it.category == category }

    /** Uses the user's existing V26 effect bar, including trims and source-time retiming. */
    fun atSourceTime(
        effects: List<NodeEffect>,
        clip: TimelineClip,
        sourceTimeUs: Long,
    ): CompositeTemplateFrameV2 {
        val chosen = effects.lastOrNull { effect ->
            find(effect.name)?.let { template ->
                template.category == (if (clip.isImageV21) IMAGE else VIDEO) &&
                    effect.activeAtSourceTimeV26(clip, sourceTimeUs)
            } == true
        } ?: return CompositeTemplateFrameV2()
        val preset = find(chosen.name) ?: return CompositeTemplateFrameV2()
        val start = chosen.resolvedSourceStartUsV26(clip)
        val end = chosen.resolvedSourceEndUsV26(clip)
        val length = (end - start).coerceAtLeast(1L)
        val elapsed = (sourceTimeUs - start).coerceAtLeast(0L)
        val overall = (elapsed.toDouble() / length).toFloat().coerceIn(0f, 1f)
        val index = preset.stages.indexOfFirst { overall < it.end }.let {
            if (it >= 0) it else preset.stages.lastIndex
        }
        val stage = preset.stages[index]
        val stageStart = if (index == 0) 0f else preset.stages[index - 1].end
        val stageFraction = (stage.end - stageStart).coerceAtLeast(.001f)
        return CompositeTemplateFrameV2(
            style = stage.motionStyle,
            amount = (chosen.amount * stage.motionAmount).coerceIn(0f, 1f),
            progress = ((overall - stageStart) / stageFraction).coerceIn(0f, 1f),
            elapsedSeconds = (elapsed / 1_000_000f - stageStart * length / 1_000_000f).coerceAtLeast(0f),
            layers = stage.layers.map { it.copy(amount = (it.amount * chosen.amount).coerceIn(0f, 1f)) },
        )
    }
}
