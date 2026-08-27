package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableGpuExportCompositionBuilderTest {

    @Test
    fun singleVideoTrackUsesResolveCompositorAndKeepsOpacity() {
        val clip = TimelineClip(
            id = "single",
            uri = "content://test/single",
            label = "single",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
            opacity = 0.37f,
        )
        val track = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip),
        )
        val project = TimelineProject(
            width = 1280,
            height = 720,
            tracks = listOf(track),
        )

        val composition = StableGpuExportCompositionBuilder().build(project)
        val compositor = composition.videoCompositorSettings

        assertTrue(compositor is ResolveVideoCompositorSettings)
        assertEquals(0.37f, compositor.getOverlaySettings(0, 500_000L).alphaScale, 0f)
    }

    @Test
    fun singleVideoTrackCompositorUsesProjectResolution() {
        val clip = TimelineClip(
            uri = "content://test/project-size",
            label = "project-size",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        val track = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip),
        )
        val project = TimelineProject(
            width = 1080,
            height = 1920,
            tracks = listOf(track),
        )

        val compositor = StableGpuExportCompositionBuilder().build(project).videoCompositorSettings
        val outputSize = compositor.getOutputSize(emptyList())

        assertEquals(1080, outputSize.width)
        assertEquals(1920, outputSize.height)
    }

    @Test
    fun exportKeepsTrimPointsAtMicrosecondPrecision() {
        val clip = TimelineClip(
            uri = "content://test/microseconds",
            label = "microseconds",
            timelineStartUs = 0L,
            sourceInUs = 1_234_567L,
            sourceOutUs = 3_456_789L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(clip),
                ),
            ),
        )

        val composition = StableGpuExportCompositionBuilder().build(project)
        val mediaItem = composition.sequences.single().editedMediaItems.single().mediaItem
        val clipping = mediaItem.clippingConfiguration

        assertEquals(1_234_567L, clipping.startPositionUs)
        assertEquals(3_456_789L, clipping.endPositionUs)
    }
}
