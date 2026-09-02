package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BeautyFaceModelsV28Test {
    @Test
    fun beautyStrengths_stackAcrossEffectsOnSameNode() {
        val graph = ClipNodeGraph.default()
        val selected = graph.selectedNodeId!!
        val withBeauty = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id != selected) node else node.copy(
                    effects = listOf(
                        NodeEffect(name = BEAUTY_SKIN_BRIGHT_V28, amount = .70f),
                        NodeEffect(name = BEAUTY_PINK_LIP_V28, amount = .55f),
                        NodeEffect(name = BEAUTY_HAIR_BROW_DARK_V28, amount = .40f),
                        NodeEffect(name = BEAUTY_EYE_POP_V28, amount = .35f),
                    ),
                )
            },
        )
        val clip = TimelineClip(
            uri = "content://test/video",
            label = "Portrait",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
            nodeGraph = withBeauty,
        )

        val strengths = clip.beautyStrengthsV28()
        assertEquals(0f, strengths.skinBright, .0001f)
        assertEquals(.70f, clip.skinQualifierStrengthV38(), .0001f)
        assertEquals(.55f, strengths.pinkLip, .0001f)
        assertEquals(.40f, strengths.hairBrowDark, .0001f)
        assertEquals(.35f, strengths.eyePop, .0001f)
    }

    @Test
    fun faceTrack_interpolatesMovingFeatureGeometry() {
        fun geometry(left: Float): BeautyFaceGeometryV28 {
            val face = BeautyRectV28(left, .20f, left + .30f, .75f)
            return BeautyFaceGeometryV28(
                face = face,
                lips = BeautyRectV28(left + .10f, .55f, left + .20f, .62f),
                leftEye = BeautyRectV28(left + .05f, .36f, left + .12f, .42f),
                rightEye = BeautyRectV28(left + .18f, .36f, left + .25f, .42f),
                leftBrow = BeautyRectV28(left + .04f, .30f, left + .13f, .34f),
                rightBrow = BeautyRectV28(left + .17f, .30f, left + .26f, .34f),
                hair = BeautyRectV28(left - .02f, .04f, left + .32f, .34f),
            )
        }
        val track = BeautyFaceTrackV28(
            sourceUri = "content://test/video",
            analyzedStartUs = 0L,
            analyzedEndUs = 1_000_000L,
            samples = listOf(
                BeautyFaceSampleV28(0L, geometry(.10f)),
                BeautyFaceSampleV28(1_000_000L, geometry(.30f)),
            ),
        )

        val middle = track.geometryAt(500_000L)
        assertNotNull(middle)
        assertEquals(.20f, middle!!.face.left, .0001f)
        assertEquals(.50f, middle.face.right, .0001f)
    }
}
