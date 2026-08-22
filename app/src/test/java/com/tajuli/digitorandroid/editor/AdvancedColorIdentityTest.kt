package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import org.junit.Assert.assertEquals
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
}
