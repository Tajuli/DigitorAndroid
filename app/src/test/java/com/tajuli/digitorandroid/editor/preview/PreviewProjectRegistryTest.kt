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
        val none = projectWithEffects(emptyList())
        PreviewProjectRegistry.update(none)

        val blur = projectWithEffects(listOf(NodeEffect(id = "fx", name = "Blur", amount = .2f)))
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(blur).reason,
        )

        val strongerBlur = projectWithEffects(listOf(NodeEffect(id = "fx", name = "Blur", amount = 1f)))
        assertEquals(
            PreviewInvalidationReason.PARAMETER_CHANGE,
            PreviewProjectRegistry.update(strongerBlur).reason,
        )

        val rgbSplit = projectWithEffects(listOf(NodeEffect(id = "rgb", name = "RGB Split")))
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(rgbSplit).reason,
        )
        assertEquals(
            PreviewInvalidationReason.EFFECT_STRUCTURE_CHANGE,
            PreviewProjectRegistry.update(projectWithEffects(emptyList())).reason,
        )
    }

    @Test
    fun equalComposeResubmissionDoesNotInventAProjectRevision() {
        val project = projectWithEffects(listOf(NodeEffect(id = "fx", name = "Blur")))
        val first = PreviewProjectRegistry.update(project)
        val duplicate = PreviewProjectRegistry.update(project.copy())

        assertSame(first, duplicate)
        assertEquals(first.revision, duplicate.revision)
    }

    private fun projectWithEffects(effects: List<NodeEffect>): TimelineProject {
        val graph = com.tajuli.digitorandroid.editor.model.ClipNodeGraph.default()
        val selected = graph.selectedNodeId
        val clip = TimelineClip(
            id = "clip",
            uri = "content://preview/test",
            label = "test",
            timelineStartUs = 0L,
            sourceOutUs = 30_000_000L,
            nodeGraph = graph.copy(
                nodes = graph.nodes.map { node ->
                    if (node.id == selected) node.copy(effects = effects) else node
                },
            ),
        )
        return TimelineProject(
            tracks = listOf(TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )
    }
}
