package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.QualifierFinesseKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifierSoftnessTest {
    private fun node(
        qualifier: HslQualifier,
        corrections: NodeCorrections = NodeCorrections(),
        vararg metadata: NodeEffect,
    ) = ColorNode(
        kind = NodeKind.SERIAL,
        label = "01",
        position = NodePosition(0f, 0f),
        corrections = corrections,
        advancedColor = AdvancedColorGrade(qualifier = qualifier),
        effects = metadata.toList(),
    )

    @Test fun saturationLowSoftFeathersOutsideHardRange() {
        val node = node(
            HslQualifier(
                enabled = true,
                hueWidthDegrees = 360f,
                saturationMin = .50f,
                saturationMax = 1f,
                luminanceMin = 0f,
                luminanceMax = 1f,
                softness = 0f,
            ),
            NodeCorrections(),
            NodeEffect(name = QualifierFinesseKeys.SAT_LOW_SOFT, amount = .20f),
            NodeEffect(name = QualifierFinesseKeys.SAT_HIGH_SOFT, amount = 0f),
        )

        val atHardEdge = hslToRgb(120f, .50f, .50f)
        val halfway = hslToRgb(120f, .40f, .50f)
        val atOuterEdge = hslToRgb(120f, .30f, .50f)

        assertEquals(1f, mask(node, atHardEdge), .0001f)
        assertEquals(.5f, mask(node, halfway), .02f)
        assertEquals(0f, mask(node, atOuterEdge), .0001f)
    }

    @Test fun hueSoftFeathersOutsideWidthNotInsideIt() {
        val node = node(
            HslQualifier(
                enabled = true,
                hueCenterDegrees = 120f,
                hueWidthDegrees = 60f,
                saturationMin = 0f,
                saturationMax = 1f,
                luminanceMin = 0f,
                luminanceMax = 1f,
                softness = .10f, // 18 degrees of feather on each side.
            ),
            NodeCorrections(),
            NodeEffect(name = QualifierFinesseKeys.HUE_SYMMETRY, amount = .5f),
        )

        val atHardEdge = hslToRgb(150f, 1f, .50f) // 30 degrees from center.
        val halfway = hslToRgb(159f, 1f, .50f) // 9 of the 18 feather degrees.
        val atOuterEdge = hslToRgb(168f, 1f, .50f)

        assertEquals(1f, mask(node, atHardEdge), .0001f)
        assertEquals(.5f, mask(node, halfway), .02f)
        assertEquals(0f, mask(node, atOuterEdge), .0001f)
    }

    @Test fun nodeGradeStrengthFollowsFeatherMatte() {
        val qualifier = HslQualifier(
            enabled = true,
            hueWidthDegrees = 360f,
            saturationMin = .50f,
            saturationMax = 1f,
            luminanceMin = 0f,
            luminanceMax = 1f,
            softness = 0f,
        )
        val node = node(
            qualifier,
            NodeCorrections(exposure = 1f),
            NodeEffect(name = QualifierFinesseKeys.SAT_LOW_SOFT, amount = .20f),
        )
        val source = hslToRgb(120f, .40f, .35f) // halfway through the low-soft feather.
        val matte = mask(node, source)
        assertEquals(.5f, matte, .02f)

        val output = QualifiedColorMath.applyNode(node, source[0], source[1], source[2])
        val fullNode = node.copy(
            advancedColor = node.advancedColor.copy(qualifier = qualifier.copy(enabled = false)),
        )
        val full = AdvancedColorMath.applyNode(fullNode, source[0], source[1], source[2])

        for (channel in 0..2) {
            val expected = source[channel] + (full[channel] - source[channel]) * matte
            assertEquals(expected, output[channel], .002f)
        }
        assertTrue(output[1] > source[1])
        assertTrue(output[1] < full[1])
    }

    private fun mask(node: ColorNode, rgb: FloatArray): Float =
        QualifiedColorMath.qualifierMask(node, rgb[0], rgb[1], rgb[2])

    private fun hslToRgb(hueDegrees: Float, saturation: Float, luminance: Float): FloatArray {
        val h = ((hueDegrees % 360f) + 360f) % 360f / 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = luminance.coerceIn(0f, 1f)
        if (s <= 0f) return floatArrayOf(l, l, l)
        val q = if (l < .5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < .5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return floatArrayOf(hue(h + 1f / 3f), hue(h), hue(h - 1f / 3f))
    }
}
