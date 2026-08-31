package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.transitionPairsV22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveVideoCompositorSettingsTest {
    @Test
    fun exportVideoInputsPreserveTopToBottomTimelineOrder() {
        val v2 = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("overlay", 0L, 2_000_000L)),
        )
        val v1 = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("background", 0L, 4_000_000L)),
        )
        val a1 = TimelineTrack(name = "A1", kind = TrackKind.AUDIO)
        val project = TimelineProject(tracks = listOf(v2, v1, a1))

        assertEquals(listOf("V2", "V1"), resolveCompositionVideoTracks(project).map { it.name })
    }

    @Test
    fun shorterOverlayIsInactiveWhileBackgroundContinues() {
        val overlay = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("overlay", 0L, 2_000_000L)),
        )
        val background = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("background", 0L, 4_000_000L)),
        )

        assertEquals("overlay", overlay.activeVideoClipAt(1_000_000L)?.label)
        assertEquals("background", background.activeVideoClipAt(1_000_000L)?.label)
        assertNull(overlay.activeVideoClipAt(3_000_000L))
        assertEquals("background", background.activeVideoClipAt(3_000_000L)?.label)
    }

    @Test
    fun crossDissolveGhostFadesOverIncomingClip() {
        val outgoing = clip("out", 0L, 2_000_000L)
        val incoming = clip("in", 2_000_000L, 4_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CROSS_DISSOLVE,
                durationUsV22 = 1_000_000L,
            ),
        )
        val track = TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(outgoing, incoming))
        val pair = track.transitionPairsV22().single()
        val ghost = outgoing.copy(
            id = transitionGhostIdV22(pair),
            timelineStartUs = pair.startUs,
            sourceInUs = 1_000_000L,
            sourceOutUs = 2_000_000L,
            transition = incoming.transition,
        )
        val settings = ResolveVideoCompositorSettings(
            outputWidth = 1920,
            outputHeight = 1080,
            videoTracks = listOf(track),
            inputsV22 = listOf(
                ResolveCompositorInputV22.TransitionGhostInput(pair, ghost),
                ResolveCompositorInputV22.TrackInput(track),
            ),
        )

        assertEquals(1f, settings.resolveOverlayState(0, 2_000_000L)!!.alphaScale, .0001f)
        assertEquals(.5f, settings.resolveOverlayState(0, 2_500_000L)!!.alphaScale, .02f)
        assertTrue(settings.resolveOverlayState(0, 2_990_000L)!!.alphaScale < .01f)
        assertEquals(1f, settings.resolveOverlayState(1, 2_500_000L)!!.alphaScale, .0001f)
    }

    @Test
    fun pushLeftMovesIncomingFromRightAndGhostToLeft() {
        val outgoing = clip("out", 0L, 2_000_000L)
        val incoming = clip("in", 2_000_000L, 4_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.PUSH_LEFT,
                durationUsV22 = 1_000_000L,
            ),
        )
        val track = TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(outgoing, incoming))
        val pair = track.transitionPairsV22().single()
        val ghost = outgoing.copy(
            id = transitionGhostIdV22(pair),
            timelineStartUs = pair.startUs,
            sourceInUs = 1_000_000L,
            sourceOutUs = 2_000_000L,
            transition = incoming.transition,
        )
        val settings = ResolveVideoCompositorSettings(
            outputWidth = 1920,
            outputHeight = 1080,
            videoTracks = listOf(track),
            inputsV22 = listOf(
                ResolveCompositorInputV22.TransitionGhostInput(pair, ghost),
                ResolveCompositorInputV22.TrackInput(track),
            ),
        )

        assertTrue(settings.resolveOverlayState(1, 2_000_000L)!!.backgroundX > 1.9f)
        assertTrue(settings.resolveOverlayState(0, 2_900_000L)!!.backgroundX < -1.8f)
    }

    private fun clip(label: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        uri = "content://test/$label",
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
