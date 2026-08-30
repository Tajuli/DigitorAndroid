package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedColorIdentityTest {
    @Test
    fun defaultTwoPointCurveIsIdentity() {
        val curve = Curve5()
        listOf(0f, .1f, .25f, .5f, .75f, .9f, 1f).forEach { value ->
            assertEquals(value, curve.valueAt(value), 0.000001f)
        }
    }

    @Test
    fun untouchedNodeDoesNotChangePixel() {
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "neutral",
            position = NodePosition(0f, 0f),
        )
        val samples = listOf(
            floatArrayOf(.08f, .22f, .91f),
            floatArrayOf(.25f, .50f, .75f),
            floatArrayOf(.92f, .68f, .11f),
        )

        samples.forEach { input ->
            val output = AdvancedColorMath.applyNode(node, input[0], input[1], input[2])
            assertEquals(input[0], output[0], 0.000001f)
            assertEquals(input[1], output[1], 0.000001f)
            assertEquals(input[2], output[2], 0.000001f)
        }
    }

    @Test
    fun colorBoostFavorsLowSaturationColors() {
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "boost",
            position = NodePosition(0f, 0f),
            corrections = NodeCorrections(colorBoost = 100f),
        )
        val low = floatArrayOf(.55f, .50f, .45f)
        val high = floatArrayOf(.80f, .30f, .10f)
        val lowOut = AdvancedColorMath.applyNode(node, low[0], low[1], low[2])
        val highOut = AdvancedColorMath.applyNode(node, high[0], high[1], high[2])

        fun chroma(rgb: FloatArray): Float =
            (rgb.maxOrNull() ?: 0f) - (rgb.minOrNull() ?: 0f)

        val lowRatio = chroma(lowOut) / chroma(low)
        val highRatio = chroma(highOut) / chroma(high)
        assertTrue(lowRatio > 1f)
        assertTrue(lowRatio > highRatio)
    }

    @Test
    fun colorBoostDoesNotTintNeutralPixels() {
        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "boost-neutral",
            position = NodePosition(0f, 0f),
            corrections = NodeCorrections(colorBoost = 100f),
        )
        val output = AdvancedColorMath.applyNode(node, .42f, .42f, .42f)
        assertEquals(.42f, output[0], 0.000001f)
        assertEquals(.42f, output[1], 0.000001f)
        assertEquals(.42f, output[2], 0.000001f)
    }
}
