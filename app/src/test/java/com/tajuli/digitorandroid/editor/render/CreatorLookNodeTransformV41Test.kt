package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.NodePosition
import com.tajuli.digitorandroid.editor.model.creatorFilterMarkerNameV36
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CreatorLookNodeTransformV41Test {
    @Test
    fun lookMarkerChangesOnlyOwningNodeTransform() {
        val plain = node("plain")
        val filtered = plain.copy(
            id = "filtered",
            effects = listOf(NodeEffect(name = creatorFilterMarkerNameV36("moody_cinema"), amount = 1f)),
        )
        val source = floatArrayOf(.46f, .38f, .31f)
        val a = CreatorLookNodeTransformV41.apply(plain, source[0], source[1], source[2])
        val b = CreatorLookNodeTransformV41.apply(filtered, source[0], source[1], source[2])

        assertTrue(distance(a, b) > .02f)
    }

    @Test
    fun lookOrderRelativeToDownstreamCorrectionIsReal() {
        val look = node("look").copy(
            effects = listOf(NodeEffect(name = creatorFilterMarkerNameV36("moody_cinema"), amount = 1f)),
        )
        val correction = node("correction").copy(
            corrections = NodeCorrections(exposure = .35f, contrast = 18f),
        )
        val source = floatArrayOf(.42f, .34f, .28f)

        val afterLook = CreatorLookNodeTransformV41.apply(look, source[0], source[1], source[2])
        val lookThenCorrection = CreatorLookNodeTransformV41.apply(
            correction,
            afterLook[0], afterLook[1], afterLook[2],
        )

        val afterCorrection = CreatorLookNodeTransformV41.apply(correction, source[0], source[1], source[2])
        val correctionThenLook = CreatorLookNodeTransformV41.apply(
            look,
            afterCorrection[0], afterCorrection[1], afterCorrection[2],
        )

        assertTrue(distance(lookThenCorrection, correctionThenLook) > .005f)
    }

    private fun node(id: String) = ColorNode(
        id = id,
        kind = NodeKind.SERIAL,
        label = id,
        position = NodePosition(0f, 0f),
    )

    private fun distance(a: FloatArray, b: FloatArray): Float =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
}
