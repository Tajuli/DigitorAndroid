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
    fun cinematicDarkReferenceHasMeasuredGlobalMidtoneLiftAndWhiteRolloff() {
        val kernel = CINEMATIC_DARK_REFERENCE_V37
        val gray20 = kernel.mapRgb(.20f, .20f, .20f).average().toFloat()
        val gray50 = kernel.mapRgb(.50f, .50f, .50f).average().toFloat()
        val gray80 = kernel.mapRgb(.80f, .80f, .80f).average().toFloat()
        val white = kernel.mapRgb(1f, 1f, 1f)

        assertTrue("20% neutral must not be crushed", gray20 >= .20f)
        assertTrue("50% neutral must receive the measured strong lift", gray50 > .56f)
        assertTrue("80% neutral must still lift", gray80 > .84f)
        assertTrue("reference white must roll off below clipping", white.maxOrNull()!! < .99f)
    }

    @Test
    fun cinematicDarkReferenceNeutralRampIsMonotonic() {
        val kernel = CINEMATIC_DARK_REFERENCE_V37
        var previous = kernel.mapRgb(0f, 0f, 0f).average().toFloat()
        for (step in 1..100) {
            val v = step / 100f
            val current = kernel.mapRgb(v, v, v).average().toFloat()
            assertTrue("neutral response reversed near $v: $current < $previous", current + 1e-5f >= previous)
            previous = current
        }
    }

    @Test
    fun cinematicDarkReferenceMapsSkinLikeAndColorPixelsGlobally() {
        val kernel = CINEMATIC_DARK_REFERENCE_V37
        val skinLike = kernel.mapRgb(.72f, .52f, .45f)
        val wallLike = kernel.mapRgb(.90f, .90f, .90f)
        val blueObject = kernel.mapRgb(.10f, .20f, .30f)

        // These are RGB-only probes: the kernel has no concept of where the pixel came from.
        assertTrue(skinLike[0] > .75f && skinLike[1] > .58f)
        assertTrue(wallLike.average() > .90)
        assertTrue(blueObject[2] > blueObject[1] && blueObject[1] > blueObject[0])
        listOf(skinLike, wallLike, blueObject).flatMap { it.toList() }.forEach { channel ->
            assertTrue(channel in 0f..1f)
        }
    }
}
