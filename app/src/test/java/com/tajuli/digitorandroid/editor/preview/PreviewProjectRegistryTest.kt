package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.NodeEffect
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewProjectRegistryTest {
    @After
    fun tearDown() = PreviewProjectRegistry.clear()

    @Test
    fun effectMembershipAndAmountHaveDistinctRealtimeInvalidations() {
        val none = projectWithEffects()
        PreviewProjectRegistry.update(none)

        val blur = none.withEffects(listOf(NodeEffect(id = "fx", name = "Blur", amount = .2f)))
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(blur).reason,
        )

        val strongerBlur = blur.withEffects(listOf(NodeEffect(id = "fx", name = "Blur", amount = 1f)))
        assertEquals(
            PreviewInvalidationReason.PARAMETER_CHANGE,
            PreviewProjectRegistry.update(strongerBlur).reason,
        )

        val rgbSplit = strongerBlur.withEffects(listOf(NodeEffect(id = "rgb", name = "RGB Split")))
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(rgbSplit).reason,
        )
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(rgbSplit.withEffects(emptyList())).reason,
        )
    }

    @Test
    fun equalComposeResubmissionDoesNotInventAProjectRevision() {
        val project = projectWithEffects().withEffects(listOf(NodeEffect(id = "fx", name = "Blur")))
        val first = PreviewProjectRegistry.update(project)
        val duplicate = PreviewProjectRegistry.update(project.copy())

        assertSame(first, duplicate)
        assertEquals(first.revision, duplicate.revision)
    }

    private fun projectWithEffects(): TimelineProject {
        val graph = com.tajuli.digitorandroid.editor.model.ClipNodeGraph.default()
        val clip = TimelineClip(
            id = "clip",
            uri = "content://preview/test",
            label = "test",
            timelineStartUs = 0L,
            sourceOutUs = 30_000_000L,
            nodeGraph = graph,
        )
        return TimelineProject(
            tracks = listOf(TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )
    }

    private fun TimelineProject.withEffects(effects: List<NodeEffect>): TimelineProject {
        val track = tracks.single()
        val clip = track.clips.single()
        val selected = clip.nodeGraph.selectedNodeId
        return copy(
            tracks = listOf(
                track.copy(
                    clips = listOf(
                        clip.copy(
                            nodeGraph = clip.nodeGraph.copy(
                                nodes = clip.nodeGraph.nodes.map { node ->
                                    if (node.id == selected) node.copy(effects = effects) else node
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
