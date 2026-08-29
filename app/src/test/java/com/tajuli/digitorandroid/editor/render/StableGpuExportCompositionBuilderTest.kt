package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
