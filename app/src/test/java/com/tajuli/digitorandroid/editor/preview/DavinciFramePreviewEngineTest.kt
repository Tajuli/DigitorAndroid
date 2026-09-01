package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.render.TRANSITION_GHOST_ID_PREFIX_V22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun transitionIntervalAddsOutgoingGhostAndIncomingVideo() {
        val outgoing = clip("out", 0L, 2_000_000L)
        val incoming = clip("in", 2_000_000L, 4_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CROSS_DISSOLVE,
                durationUsV22 = 1_000_000L,
            ),
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(outgoing, incoming),
                ),
            ),
        )

        val active = activeVideoLayersAt(project, 2_500_000L)

        assertEquals(2, active.size)
        assertTrue(active.first().id.startsWith(TRANSITION_GHOST_ID_PREFIX_V22))
        assertEquals("in", active.last().label)
        assertEquals(2_000_000L, active.first().timelineStartUs)
    }

    @Test
    fun transitionGhostDropsOutAfterTransitionWindow() {
        val outgoing = clip("out", 0L, 2_000_000L)
        val incoming = clip("in", 2_000_000L, 4_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CROSS_DISSOLVE,
                durationUsV22 = 500_000L,
            ),
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(outgoing, incoming),
                ),
            ),
        )

        assertEquals(listOf("in"), activeVideoLayersAt(project, 2_750_000L).map { it.label })
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
