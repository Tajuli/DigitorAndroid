package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun emptySentinelTrackIsAlwaysTransparent() {
        val realClip = TimelineClip(
            uri = "content://test/source",
            label = "source",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        val realTrack = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(realClip),
        )
        val sentinelTrack = TimelineTrack(
            id = "sentinel",
            name = "Compositor gap sentinel",
            kind = TrackKind.VIDEO,
            clips = emptyList(),
        )
        val compositor = ResolveVideoCompositorSettings(
            outputWidth = 1920,
            outputHeight = 1080,
            videoTracks = listOf(realTrack, sentinelTrack),
        )

        assertEquals(null, compositor.resolveOverlayState(1, 500_000L))
    }

    @Test
    fun titleFullyInsideVideoCanKeepDirectSingleInputExport() {
        val video = TimelineClip(
            id = "video",
            uri = "content://test/video",
            label = "video",
            timelineStartUs = 0L,
            sourceOutUs = 10_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
            ),
            textOverlays = listOf(
                TextOverlayClip(
                    id = "title",
                    text = "Title",
                    timelineStartUs = 2_000_000L,
                    timelineEndUs = 6_000_000L,
                    videoTrackIdV3 = "v1",
                ),
            ),
        )

        assertTrue(canUseDirectSingleInputExport(project))
        assertTrue(textOverlaysAreCoveredByRealVideoV14(project))
    }

    @Test
    fun titleAfterLastVideoForcesCompositorExport() {
        val video = TimelineClip(
            id = "video",
            uri = "content://test/video",
            label = "video",
            timelineStartUs = 0L,
            sourceOutUs = 10_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
            ),
            textOverlays = listOf(
                TextOverlayClip(
                    id = "tail-title",
                    text = "Title after video",
                    timelineStartUs = 10_000_000L,
                    timelineEndUs = 13_000_000L,
                    videoTrackIdV3 = "v1",
                ),
            ),
        )

        // This otherwise qualifies for the single-input fast path, so the coverage guard is what
        // prevents the crashing text-only tail path on affected devices.
        assertTrue(canUseDirectSingleInputExport(project))
        assertFalse(textOverlaysAreCoveredByRealVideoV14(project))
        assertEquals(13_000_000L, project.durationUs)
    }

    @Test
    fun titleCrossingVideoEndForcesCompositorExport() {
        val video = TimelineClip(
            id = "video",
            uri = "content://test/video",
            label = "video",
            timelineStartUs = 0L,
            sourceOutUs = 10_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
            ),
            textOverlays = listOf(
                TextOverlayClip(
                    id = "crossing-title",
                    text = "Crossing title",
                    timelineStartUs = 9_000_000L,
                    timelineEndUs = 12_000_000L,
                    videoTrackIdV3 = "v1",
                ),
            ),
        )

        assertTrue(canUseDirectSingleInputExport(project))
        assertFalse(textOverlaysAreCoveredByRealVideoV14(project))
    }
}
