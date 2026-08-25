package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeAnimations
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAnimationModelsTest {
    private fun node(exposure: Float = 0f): ColorNode = ColorNode(
        id = "node-1",
        kind = NodeKind.SERIAL,
        label = "01",
        position = NodePosition(0f, 0f),
        corrections = NodeCorrections(exposure = exposure),
    )

    private fun capture(
        animations: NodeAnimations,
        value: ColorNode,
        domain: NodeAnimationDomain,
        timeUs: Long,
    ) {
        animations.toggle(value, domain, timeUs)
        animations.upsertIfAnimated(value, domain, timeUs)
    }

    @Test
    fun correctionInterpolatesBetweenTwoSnapshotKeys() {
        val animations = NodeAnimations()
        capture(animations, node(exposure = 1.2f), NodeAnimationDomain.CORRECTION, 5_000_000L)
        capture(animations, node(exposure = -.8f), NodeAnimationDomain.CORRECTION, 20_000_000L)

        val mid = animations.evaluateNode(node(), 12_500_000L)
        assertEquals(.2f, mid.corrections.exposure, .0001f)
    }

    @Test
    fun deletingSpecificCorrectionKeyLeavesOtherKeys() {
        val animations = NodeAnimations()
        capture(animations, node(.5f), NodeAnimationDomain.CORRECTION, 5_000_000L)
        capture(animations, node(1.5f), NodeAnimationDomain.CORRECTION, 10_000_000L)
        capture(animations, node(2f), NodeAnimationDomain.CORRECTION, 20_000_000L)

        animations.remove("node-1", NodeAnimationDomain.CORRECTION, 10_000_000L)

        assertTrue(animations.hasKeyframeAt("node-1", NodeAnimationDomain.CORRECTION, 5_000_000L))
        assertFalse(animations.hasKeyframeAt("node-1", NodeAnimationDomain.CORRECTION, 10_000_000L))
        assertTrue(animations.hasKeyframeAt("node-1", NodeAnimationDomain.CORRECTION, 20_000_000L))
        assertEquals(2, animations.keyframeTimes("node-1", NodeAnimationDomain.CORRECTION).size)
    }

    @Test
    fun colorWheelValuesInterpolate() {
        val animations = NodeAnimations()
        val a = node().let { n ->
            n.copy(advancedColor = n.advancedColor.copy(
                primary = n.advancedColor.primary.copy(
                    gain = n.advancedColor.primary.gain.copy(red = -.4f, luma = -.2f),
                ),
            ))
        }
        val b = node().let { n ->
            n.copy(advancedColor = n.advancedColor.copy(
                primary = n.advancedColor.primary.copy(
                    gain = n.advancedColor.primary.gain.copy(red = .4f, luma = .2f),
                ),
            ))
        }
        capture(animations, a, NodeAnimationDomain.COLOR, 0L)
        capture(animations, b, NodeAnimationDomain.COLOR, 10_000_000L)

        val mid = animations.evaluateNode(node(), 5_000_000L).advancedColor.primary.gain
        assertEquals(0f, mid.red, .0001f)
        assertEquals(0f, mid.luma, .0001f)
    }

    @Test
    fun effectAmountInterpolatesAndCanBeDeletedIndividually() {
        val animations = NodeAnimations()
        val base = node()
        val low = base.copy(effects = listOf(NodeEffect(id = "fx-blur", name = "Blur", amount = .2f)))
        val high = base.copy(effects = listOf(NodeEffect(id = "fx-blur", name = "Blur", amount = 1f)))
        capture(animations, low, NodeAnimationDomain.EFFECTS, 2_000_000L)
        capture(animations, high, NodeAnimationDomain.EFFECTS, 6_000_000L)

        val mid = animations.evaluateNode(base, 4_000_000L).effects.single()
        assertEquals(.6f, mid.amount, .0001f)

        animations.remove("node-1", NodeAnimationDomain.EFFECTS, 2_000_000L)
        assertFalse(animations.hasKeyframeAt("node-1", NodeAnimationDomain.EFFECTS, 2_000_000L))
        assertTrue(animations.hasKeyframeAt("node-1", NodeAnimationDomain.EFFECTS, 6_000_000L))
    }
}
