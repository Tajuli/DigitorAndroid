package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorLookProfilesV37Test {
    @Test
    fun lastEnabledLookWinsAndBeautyDoesNotBecomeALook() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id != host.id) node else node.copy(
                        effects = listOf(
                            NodeEffect(name = creatorFilterMarkerNameV36("warm_film"), amount = .7f),
                            NodeEffect(name = creatorFilterMarkerNameV36("skin_bright"), amount = .9f),
                            NodeEffect(name = creatorFilterMarkerNameV36("moody_cinema"), amount = .8f),
                        ),
                    )
                },
            ),
        )

        val active = requireNotNull(clip.activeCreatorLookV37())
        assertEquals("moody_cinema", active.preset.id)
        assertEquals(.8f, active.intensity, .0001f)
        assertEquals(CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE, active.kernel)
    }

    @Test
    fun beautyOnlyClipHasNoCreatorLook() {
        val graph = ClipNodeGraph.default()
        val host = graph.nodes.first { it.kind == NodeKind.SERIAL }
        val clip = TimelineClip(
            uri = "file:///tmp/source.mp4",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id != host.id) node else node.copy(
                        effects = listOf(
                            NodeEffect(name = creatorFilterMarkerNameV36("skin_bright"), amount = 1f),
                        ),
                    )
                },
            ),
        )

        assertNull(clip.activeCreatorLookV37())
    }

    @Test
    fun cinematicDarkReferenceHasMeasuredMidtoneLiftAndHighlightRolloff() {
        val kernel = CINEMATIC_DARK_REFERENCE_V37

        val y20 = kernel.toneLuma(.20f)
        val y50 = kernel.toneLuma(.50f)
        val y80 = kernel.toneLuma(.80f)
        val y95 = kernel.toneLuma(.95f)
        val y100 = kernel.toneLuma(1f)

        assertTrue("20% tone should not be crushed", y20 >= .20f)
        assertTrue("50% tone should receive the strongest visible lift", y50 > .56f)
        assertTrue("80% tone should still lift", y80 > .82f)
        assertTrue("95% tone must remain below clipping", y95 < .99f)
        assertTrue("white endpoint must roll off below one", y100 < .99f)
    }

    @Test
    fun cinematicDarkReferenceToneCurveIsMonotonic() {
        val kernel = CINEMATIC_DARK_REFERENCE_V37
        var previous = kernel.toneLuma(0f)
        for (step in 1..100) {
            val y = step / 100f
            val current = kernel.toneLuma(y)
            assertTrue("tone curve reversed near $y: $current < $previous", current + 1e-5f >= previous)
            previous = current
        }
    }
}
