package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.CINEMATIC_DARK_REFERENCE_V37
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.CreatorFilterGroupV36
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetIdV36
import com.tajuli.digitorandroid.editor.model.creatorFilterPresetV36

/**
 * V41 executes creator LOOK markers inside the owning color node.
 *
 * The ordinary manual node grade is evaluated first. The node's selected LOOK is then applied to
 * that node output before the graph continues to the next Serial node or Parallel Mixer. This is
 * intentionally different from V37's clip-level post-LUT stage: node order and branch topology now
 * affect the result exactly as users expect from a Resolve-style node graph.
 *
 * BEAUTY markers remain metadata-owned by a node but are not evaluated here because smoothing,
 * lips/eyes/hair and similar operations require spatial/semantic GPU passes rather than a 3D LUT.
 */
internal object CreatorLookNodeTransformV41 {
    fun apply(node: ColorNode, r: Float, g: Float, b: Float): FloatArray {
        var rgb = QualifiedColorMath.applyNode(node, r, g, b)
        val look = node.effects.asSequence()
            .filter { it.enabled && it.amount > .001f }
            .mapNotNull { effect ->
                val id = effect.creatorFilterPresetIdV36() ?: return@mapNotNull null
                val preset = creatorFilterPresetV36(id) ?: return@mapNotNull null
                if (preset.group != CreatorFilterGroupV36.LOOKS) return@mapNotNull null
                Triple(preset, effect.amount.coerceIn(0f, 1f), id)
            }
            .lastOrNull()
            ?: return rgb

        val preset = look.first
        val amount = look.second
        rgb = if (look.third == "moody_cinema") {
            val mapped = CINEMATIC_DARK_REFERENCE_V37.mapRgb(rgb[0], rgb[1], rgb[2])
            floatArrayOf(
                mix(rgb[0], mapped[0], amount),
                mix(rgb[1], mapped[1], amount),
                mix(rgb[2], mapped[2], amount),
            )
        } else {
            val lookNode = ColorNode(
                kind = NodeKind.SERIAL,
                label = "V41 creator look",
                position = NodePosition(0f, 0f),
                corrections = preset.corrections.scaledV41(amount),
                advancedColor = AdvancedColorGrade(log = preset.log.scaledV41(amount)),
            )
            QualifiedColorMath.applyNode(lookNode, rgb[0], rgb[1], rgb[2])
        }
        return rgb
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}

private fun NodeCorrections.scaledV41(amount: Float): NodeCorrections = copy(
    exposure = exposure * amount,
    contrast = contrast * amount,
    saturation = saturation * amount,
    temperature = temperature * amount,
    tint = tint * amount,
    highlights = highlights * amount,
    shadows = shadows * amount,
    hue = hue * amount,
    colorBoost = colorBoost * amount,
)

private fun LogWheels.scaledV41(amount: Float): LogWheels = copy(
    shadows = shadows.scaledV41(amount),
    midtones = midtones.scaledV41(amount),
    highlights = highlights.scaledV41(amount),
    global = global.scaledV41(amount),
)

private fun ColorWheelValue.scaledV41(amount: Float): ColorWheelValue = copy(
    red = red * amount,
    green = green * amount,
    blue = blue * amount,
    luma = luma * amount,
    puckX = puckX * amount,
    puckY = puckY * amount,
)
