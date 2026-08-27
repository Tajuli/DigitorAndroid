package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DavinciFramePreviewEngineTest {
    @Test
    fun activeLayersRenderBottomToTop() {
        val top = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("top", 0L, 2_000_000L)),
        )
        val bottom = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("bottom", 0L, 4_000_000L)),
        )
        val project = TimelineProject(tracks = listOf(top, bottom))

        assertEquals(
            listOf("bottom", "top"),
            activeVideoLayersAt(project, 1_000_000L).map { it.label },
        )
    }

    @Test
    fun endedOverlayDropsOutAtPlayhead() {
        val top = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("overlay", 0L, 1_000_000L)),
        )
        val bottom = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("background", 0L, 4_000_000L)),
        )
        val project = TimelineProject(tracks = listOf(top, bottom))

        assertEquals(
            listOf("background"),
            activeVideoLayersAt(project, 2_000_000L).map { it.label },
        )
    }

    @Test
    fun mutedTrackIsNotEvaluated() {
        val muted = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            muted = true,
            clips = listOf(clip("muted", 0L, 2_000_000L)),
        )
        val visible = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("visible", 0L, 2_000_000L)),
        )
        val project = TimelineProject(tracks = listOf(muted, visible))

        assertEquals(listOf("visible"), activeVideoLayersAt(project, 500_000L).map { it.label })
    }

    @Test
    fun trimmedClipMapsDecoderPtsToTimelinePtsExactly() {
        val trimmed = TimelineClip(
            id = "trimmed",
            uri = "content://test/trimmed",
            label = "trimmed",
            timelineStartUs = 2_000_000L,
            sourceInUs = 5_000_000L,
            sourceOutUs = 8_000_000L,
        )

        assertEquals(5_500_000L, timelineToSourceUs(trimmed, 2_500_000L))
        assertEquals(3_500_000L, sourceToTimelineUs(trimmed, 6_500_000L))
    }

    private fun clip(label: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        uri = "content://test/$label",
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
