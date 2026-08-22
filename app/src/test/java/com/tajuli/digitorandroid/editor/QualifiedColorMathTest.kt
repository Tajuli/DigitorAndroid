package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifiedColorMathTest {
    private fun qualifiedGreenNode(): ColorNode = ColorNode(
        kind = NodeKind.SERIAL,
        label = "01",
        position = NodePosition(100f, 100f),
        corrections = NodeCorrections(exposure = 1f),
        advancedColor = AdvancedColorGrade(
            qualifier = HslQualifier(
                enabled = true,
                hueCenterDegrees = 120f,
                hueWidthDegrees = 40f,
                saturationMin = .30f,
                saturationMax = 1f,
                luminanceMin = .05f,
                luminanceMax = .80f,
                softness = 0f,
            ),
        ),
    )

    @Test fun correctionDoesNotLeakOutsideQualifierRange() {
        val node = qualifiedGreenNode()
        val source = floatArrayOf(.40f, .10f, .10f) // red, outside green key
        val out = QualifiedColorMath.applyNode(node, source[0], source[1], source[2])

        assertEquals(source[0], out[0], .0001f)
        assertEquals(source[1], out[1], .0001f)
        assertEquals(source[2], out[2], .0001f)
    }

    @Test fun correctionAppliesInsideQualifierRange() {
        val node = qualifiedGreenNode()
        val source = floatArrayOf(.10f, .40f, .10f) // green, inside key
        val out = QualifiedColorMath.applyNode(node, source[0], source[1], source[2])

        assertTrue(out[1] > source[1] + .20f)
        assertTrue(out[0] > source[0])
        assertTrue(out[2] > source[2])
    }
}
