package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextAnimationSpecV2
import com.tajuli.digitorandroid.editor.model.TextAnimationV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextManualAnimationV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TextTransformKeyframeV2

/**
 * CapCut-style ready-to-drop text presets built on Digitor's own text model.
 * Templates are data, not hard-coded UI branches, so more presets can be added cheaply.
 */
data class TextTemplateKeyframeV10(
    val fraction: Float,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val sizeScale: Float = 1f,
    val alpha: Float = 1f,
)

data class TextTemplatePresetV10(
    val id: String,
    val label: String,
    val category: String,
    val style: TextStyleV2,
    val bold: Boolean = true,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val sizeScale: Float = 1f,
    val entry: TextAnimationSpecV2 = TextAnimationSpecV2(),
    val exit: TextAnimationSpecV2 = TextAnimationSpecV2(),
    val manualKeyframes: List<TextTemplateKeyframeV10> = emptyList(),
) {
    fun applyTo(item: TextOverlayClip): TextOverlayClip = item.copy(
        styleV2 = style,
        bold = bold,
        positionX = positionX,
        positionY = positionY,
        sizeScale = sizeScale,
        entryAnimationV2 = entry,
        exitAnimationV2 = exit,
        manualAnimationV2 = manualAnimationFor(item.durationUs),
    )

    fun manualAnimationFor(durationUs: Long): TextManualAnimationV2? {
        if (manualKeyframes.isEmpty()) return null
        val safeDuration = durationUs.coerceAtLeast(1L)
        return TextManualAnimationV2(
            manualKeyframes.map { frame ->
                TextTransformKeyframeV2(
                    localUs = (safeDuration * frame.fraction.coerceIn(0f, 1f)).toLong(),
                    positionX = frame.positionX,
                    positionY = frame.positionY,
                    sizeScale = frame.sizeScale,
                    alpha = frame.alpha,
                )
            },
        ).normalizedFor(safeDuration)
    }
}

private fun style(
    font: TextFontV2 = TextFontV2.SANS,
    color: Long = 0xFFFFFFFFL,
    stroke: Float = 0f,
    strokeColor: Long = 0xFF000000L,
    shadow: Boolean = false,
    shadowColor: Long = 0xA0000000L,
    shadowRadius: Float = 6f,
    shadowY: Float = 3f,
    background: Boolean = false,
    backgroundColor: Long = 0xB0000000L,
    alignment: TextAlignmentV2 = TextAlignmentV2.CENTER,
) = TextStyleV2(
    font = font,
    colorArgb = color,
    strokeWidth = stroke,
    strokeArgb = strokeColor,
    shadowEnabled = shadow,
    shadowArgb = shadowColor,
    shadowRadius = shadowRadius,
    shadowDy = shadowY,
    backgroundEnabled = background,
    backgroundArgb = backgroundColor,
    alignment = alignment,
)

private fun anim(kind: TextAnimationV2, ms: Long) = TextAnimationSpecV2(kind, ms * 1_000L)

val TextTemplateCatalogV10: List<TextTemplatePresetV10> = listOf(
    TextTemplatePresetV10("clean", "Clean", "Minimal", style()),
    TextTemplatePresetV10("soft-subtitle", "Soft Subtitle", "Caption", style(background = true), positionY = .72f, sizeScale = .78f, bold = false, entry = anim(TextAnimationV2.FADE, 180), exit = anim(TextAnimationV2.FADE, 180)),
    TextTemplatePresetV10("bold-pop", "Bold Pop", "Social", style(color = 0xFFFFD54FL, stroke = 3.2f, shadow = true), sizeScale = 1.12f, entry = anim(TextAnimationV2.SLIDE_UP, 280), exit = anim(TextAnimationV2.FADE, 220)),
    TextTemplatePresetV10("creator-white", "Creator White", "Social", style(stroke = 2.2f, shadow = true), sizeScale = 1.06f, entry = anim(TextAnimationV2.SLIDE_RIGHT, 300), exit = anim(TextAnimationV2.FADE, 220)),
    TextTemplatePresetV10("neon-cyan", "Neon Cyan", "Neon", style(color = 0xFF65F5FFL, stroke = 1.4f, strokeColor = 0xFF12333CL, shadow = true, shadowColor = 0xCC32E9FFL, shadowRadius = 12f), entry = anim(TextAnimationV2.SLIDE_LEFT, 320), exit = anim(TextAnimationV2.FADE, 250)),
    TextTemplatePresetV10("neon-pink", "Neon Pink", "Neon", style(color = 0xFFFF78D1L, stroke = 1.4f, strokeColor = 0xFF3D1231L, shadow = true, shadowColor = 0xCCFF4FC3L, shadowRadius = 12f), entry = anim(TextAnimationV2.SLIDE_RIGHT, 320), exit = anim(TextAnimationV2.FADE, 250)),
    TextTemplatePresetV10("cinema-serif", "Cinema Serif", "Cinematic", style(font = TextFontV2.SERIF, color = 0xFFF4F0E8L, shadow = true, shadowRadius = 8f), bold = false, sizeScale = .92f, entry = anim(TextAnimationV2.FADE, 620), exit = anim(TextAnimationV2.FADE, 620)),
    TextTemplatePresetV10("lower-third", "Lower Third", "Caption", style(alignment = TextAlignmentV2.LEFT, background = true, backgroundColor = 0xD0141418L), positionX = -.55f, positionY = .68f, sizeScale = .76f, entry = anim(TextAnimationV2.SLIDE_RIGHT, 260), exit = anim(TextAnimationV2.SLIDE_LEFT, 220)),
    TextTemplatePresetV10("breaking", "Breaking", "Social", style(color = 0xFFFFFFFFL, background = true, backgroundColor = 0xFFE53935L), positionY = -.62f, sizeScale = .86f, entry = anim(TextAnimationV2.SLIDE_DOWN, 220), exit = anim(TextAnimationV2.FADE, 180)),
    TextTemplatePresetV10("gaming", "Gaming", "Social", style(color = 0xFFB7FF45L, stroke = 3.5f, strokeColor = 0xFF101010L, shadow = true, shadowRadius = 10f), sizeScale = 1.18f, manualKeyframes = listOf(TextTemplateKeyframeV10(0f, sizeScale = .55f, alpha = 0f), TextTemplateKeyframeV10(.12f, sizeScale = 1.28f), TextTemplateKeyframeV10(.22f, sizeScale = 1.08f), TextTemplateKeyframeV10(1f, sizeScale = 1.08f))),
    TextTemplatePresetV10("hook", "Hook", "Kinetic", style(color = 0xFFFFE45EL, stroke = 3f, shadow = true), sizeScale = 1.12f, manualKeyframes = listOf(TextTemplateKeyframeV10(0f, positionY = .35f, sizeScale = .8f, alpha = 0f), TextTemplateKeyframeV10(.12f, positionY = -.04f, sizeScale = 1.2f), TextTemplateKeyframeV10(.2f, sizeScale = 1.08f), TextTemplateKeyframeV10(1f, sizeScale = 1.08f))),
    TextTemplatePresetV10("zoom-punch", "Zoom Punch", "Kinetic", style(stroke = 2.5f, shadow = true), manualKeyframes = listOf(TextTemplateKeyframeV10(0f, sizeScale = .35f, alpha = 0f), TextTemplateKeyframeV10(.1f, sizeScale = 1.35f), TextTemplateKeyframeV10(.2f, sizeScale = 1f), TextTemplateKeyframeV10(1f))),
    TextTemplatePresetV10("rise", "Rise", "Kinetic", style(shadow = true), manualKeyframes = listOf(TextTemplateKeyframeV10(0f, positionY = .55f, alpha = 0f), TextTemplateKeyframeV10(.18f, positionY = 0f), TextTemplateKeyframeV10(1f))),
    TextTemplatePresetV10("drop", "Drop", "Kinetic", style(shadow = true), manualKeyframes = listOf(TextTemplateKeyframeV10(0f, positionY = -.55f, alpha = 0f), TextTemplateKeyframeV10(.15f, positionY = .05f), TextTemplateKeyframeV10(.22f, positionY = 0f), TextTemplateKeyframeV10(1f))),
    TextTemplatePresetV10("side-punch", "Side Punch", "Kinetic", style(color = 0xFFFFFFFFL, stroke = 2.4f), manualKeyframes = listOf(TextTemplateKeyframeV10(0f, positionX = -.85f, alpha = 0f), TextTemplateKeyframeV10(.12f, positionX = .08f), TextTemplateKeyframeV10(.19f, positionX = 0f), TextTemplateKeyframeV10(1f))),
    TextTemplatePresetV10("quote", "Quote", "Cinematic", style(font = TextFontV2.SERIF, color = 0xFFF8F1E5L, shadow = true), bold = false, positionY = .12f, sizeScale = .9f, entry = anim(TextAnimationV2.FADE, 450), exit = anim(TextAnimationV2.FADE, 450)),
    TextTemplatePresetV10("mono-tech", "Mono Tech", "Minimal", style(font = TextFontV2.MONO, color = 0xFF7DF9FFL, background = true, backgroundColor = 0xC010171BL), bold = false, sizeScale = .84f, entry = anim(TextAnimationV2.SLIDE_LEFT, 240), exit = anim(TextAnimationV2.FADE, 180)),
    TextTemplatePresetV10("retro", "Retro", "Social", style(font = TextFontV2.SERIF, color = 0xFFFFC46BL, stroke = 2.2f, strokeColor = 0xFF5A2A27L, shadow = true, shadowColor = 0xA0673E2AL), sizeScale = 1.06f, entry = anim(TextAnimationV2.SLIDE_UP, 300), exit = anim(TextAnimationV2.FADE, 260)),
    TextTemplatePresetV10("beauty", "Beauty", "Social", style(font = TextFontV2.CURSIVE, color = 0xFFFFE7F4L, shadow = true, shadowColor = 0xAAFF6EB6L, shadowRadius = 9f), bold = false, sizeScale = 1.04f, entry = anim(TextAnimationV2.FADE, 350), exit = anim(TextAnimationV2.FADE, 300)),
    TextTemplatePresetV10("food", "Food", "Social", style(color = 0xFFFFF3C4L, background = true, backgroundColor = 0xD0833D1AL), sizeScale = .92f, entry = anim(TextAnimationV2.SLIDE_UP, 240), exit = anim(TextAnimationV2.FADE, 220)),
    TextTemplatePresetV10("travel", "Travel", "Social", style(font = TextFontV2.SERIF, color = 0xFFFFFFFFL, shadow = true, shadowRadius = 10f), bold = false, positionY = -.45f, sizeScale = 1.03f, entry = anim(TextAnimationV2.FADE, 420), exit = anim(TextAnimationV2.FADE, 320)),
    TextTemplatePresetV10("sale", "Sale", "Social", style(color = 0xFFFFFFFFL, background = true, backgroundColor = 0xFFE91E63L, stroke = 1.5f), sizeScale = 1.16f, manualKeyframes = listOf(TextTemplateKeyframeV10(0f, sizeScale = .4f, alpha = 0f), TextTemplateKeyframeV10(.1f, sizeScale = 1.35f), TextTemplateKeyframeV10(.18f, sizeScale = 1.1f), TextTemplateKeyframeV10(1f, sizeScale = 1.1f))),
    TextTemplatePresetV10("tutorial", "Tutorial", "Caption", style(color = 0xFFFFFFFFL, background = true, backgroundColor = 0xD01B2733L, alignment = TextAlignmentV2.LEFT), positionX = -.42f, positionY = -.62f, sizeScale = .74f, entry = anim(TextAnimationV2.SLIDE_RIGHT, 220), exit = anim(TextAnimationV2.FADE, 180)),
    TextTemplatePresetV10("story", "Story", "Cinematic", style(font = TextFontV2.CURSIVE, color = 0xFFFFF7EDL, shadow = true), bold = false, sizeScale = 1.0f, entry = anim(TextAnimationV2.SLIDE_UP, 420), exit = anim(TextAnimationV2.FADE, 360)),
    TextTemplatePresetV10("minimal-black", "Minimal Black", "Minimal", style(color = 0xFF111111L, background = true, backgroundColor = 0xE8FFFFFFL), bold = false, sizeScale = .82f, entry = anim(TextAnimationV2.FADE, 220), exit = anim(TextAnimationV2.FADE, 220)),
    TextTemplatePresetV10("white-box", "White Box", "Caption", style(color = 0xFF101010L, background = true, backgroundColor = 0xF5FFFFFFL), positionY = .7f, sizeScale = .8f, entry = anim(TextAnimationV2.SLIDE_UP, 230), exit = anim(TextAnimationV2.FADE, 180)),
    TextTemplatePresetV10("cyan-box", "Cyan Box", "Social", style(color = 0xFF071013L, background = true, backgroundColor = 0xFF57E6E6L), sizeScale = .94f, entry = anim(TextAnimationV2.SLIDE_LEFT, 240), exit = anim(TextAnimationV2.SLIDE_RIGHT, 220)),
    TextTemplatePresetV10("purple-pop", "Purple Pop", "Social", style(color = 0xFFFFFFFFL, background = true, backgroundColor = 0xFF7C4DFFL, shadow = true), sizeScale = 1.02f, manualKeyframes = listOf(TextTemplateKeyframeV10(0f, positionX = .7f, sizeScale = .65f, alpha = 0f), TextTemplateKeyframeV10(.14f, positionX = -.05f, sizeScale = 1.12f), TextTemplateKeyframeV10(.22f, positionX = 0f, sizeScale = 1f), TextTemplateKeyframeV10(1f))),
)

val TextTemplateCategoriesV10: List<String> = listOf("All") + TextTemplateCatalogV10.map { it.category }.distinct()
