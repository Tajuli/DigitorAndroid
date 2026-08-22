package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.QualifierFinesseKeys
import com.tajuli.digitorandroid.editor.model.visibleEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifierFinesseTest {
    private fun nodeWith(vararg metadata: NodeEffect): ColorNode = ColorNode(
        kind = NodeKind.SERIAL,
        label = "01",
        position = NodePosition(0f, 0f),
        advancedColor = AdvancedColorGrade(
            qualifier = HslQualifier(
                enabled = true,
                hueCenterDegrees = 120f,
                hueWidthDegrees = 80f,
                saturationMin = .20f,
                saturationMax = 1f,
                luminanceMin = .05f,
                luminanceMax = .90f,
                softness = .10f,
            ),
        ),
        effects = metadata.toList(),
    )

    @Test fun internalFinesseMetadataIsHiddenFromEffectsUi() {
        val node = nodeWith(
            NodeEffect(name = QualifierFinesseKeys.CLEAN_BLACK, amount = .5f),
            NodeEffect(name = "Glow", amount = .8f),
        )
        assertEquals(listOf("Glow"), node.visibleEffects().map { it.name })
    }

    @Test fun blackClipCanRejectAWeakEdgeMatte() {
        val base = nodeWith()
        val clipped = nodeWith(NodeEffect(name = QualifierFinesseKeys.BLACK_CLIP, amount = .75f))

        // Yellow-green lies near the edge of the green hue key and therefore has a partial matte.
        val r = .55f
        val g = .70f
        val b = .05f
        val baseMask = QualifiedColorMath.qualifierMask(base, r, g, b)
        val clippedMask = QualifiedColorMath.qualifierMask(clipped, r, g, b)

        assertTrue(baseMask >= clippedMask)
    }

    @Test fun whiteClipDefaultPreservesAFullGreenKey() {
        val node = nodeWith(NodeEffect(name = QualifierFinesseKeys.WHITE_CLIP, amount = 1f))
        val mask = QualifiedColorMath.qualifierMask(node, .10f, .70f, .10f)
        assertTrue(mask > .90f)
    }
}
