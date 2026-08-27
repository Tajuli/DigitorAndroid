package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class StableGpuExportCompositionBuilderTest {

    @Test
    fun resolveCompositorKeepsSingleTrackOpacity() {
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
        val compositor = ResolveVideoCompositorSettings(
            outputWidth = 1280,
            outputHeight = 720,
            videoTracks = listOf(track),
        )

        val state = compositor.resolveOverlayState(0, 500_000L)
        assertNotNull(state)
        assertEquals(0.37f, state!!.alphaScale, 0f)
    }

    @Test
    fun resolveCompositorUsesProjectResolutionForSingleTrack() {
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
        val compositor = ResolveVideoCompositorSettings(
            outputWidth = 1080,
            outputHeight = 1920,
            videoTracks = listOf(track),
        )
        val outputSize = compositor.getOutputSize(emptyList())

        assertEquals(1080, outputSize.width)
        assertEquals(1920, outputSize.height)
    }

    @Test
    fun singleVideoTrackGetsInvisibleConcurrentSentinel() {
        val clip = TimelineClip(
            id = "clip-1",
            uri = "content://test/source",
            label = "source",
            timelineStartUs = 250_000L,
            sourceInUs = 500_000L,
            sourceOutUs = 2_500_000L,
            opacity = 0.61f,
            linkGroupId = "linked",
        )
        val track = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip),
        )
        val project = TimelineProject(tracks = listOf(track))

        val prepared = withSingleLayerCompositorSentinel(project)
        val videoTracks = resolveCompositionVideoTracks(prepared)

        assertEquals(2, videoTracks.size)
        assertEquals(track, videoTracks[0])
        val sentinel = videoTracks[1]
        assertEquals(1, sentinel.clips.size)
        assertEquals(clip.uri, sentinel.clips.single().uri)
        assertEquals(clip.timelineStartUs, sentinel.clips.single().timelineStartUs)
        assertEquals(clip.sourceInUs, sentinel.clips.single().sourceInUs)
        assertEquals(clip.sourceOutUs, sentinel.clips.single().sourceOutUs)
        assertEquals(0f, sentinel.clips.single().opacity, 0f)
        assertEquals(null, sentinel.clips.single().linkGroupId)
    }

    @Test
    fun multiVideoTrackProjectIsNotRewritten() {
        val first = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(
                TimelineClip(
                    uri = "content://test/one",
                    label = "one",
                    timelineStartUs = 0L,
                    sourceOutUs = 1_000_000L,
                ),
            ),
        )
        val second = TimelineTrack(
            id = "v2",
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(
                TimelineClip(
                    uri = "content://test/two",
                    label = "two",
                    timelineStartUs = 0L,
                    sourceOutUs = 1_000_000L,
                ),
            ),
        )
        val project = TimelineProject(tracks = listOf(first, second))

        assertSame(project, withSingleLayerCompositorSentinel(project))
    }
}
