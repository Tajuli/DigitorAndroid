package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapCutTransitionUiV23Test {
    @Test
    fun contiguousVideoClipsExposeCutTargets() {
        val first = clip("a", 0L, 1_000_000L)
        val second = clip("b", 1_000_000L, 2_000_000L)
        val third = clip("c", 2_000_000L, 3_000_000L)
        val track = TimelineTrack(
            id = "v1",
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(third, first, second),
        )

        val cuts = track.capCutTransitionCutsV23()

        assertEquals(listOf(1_000_000L, 2_000_000L), cuts.map { it.cutUs })
        assertEquals(listOf("b", "c"), cuts.map { it.incoming.id })
    }

    @Test
    fun gapsAudioAndMutedTracksDoNotExposeCutTargets() {
        val first = clip("a", 0L, 1_000_000L)
        val secondWithGap = clip("b", 1_200_000L, 2_000_000L)

        assertTrue(
            TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, secondWithGap))
                .capCutTransitionCutsV23()
                .isEmpty(),
        )
        assertTrue(
            TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(first, first.copy(id = "b", timelineStartUs = 1_000_000L)))
                .capCutTransitionCutsV23()
                .isEmpty(),
        )
        assertTrue(
            TimelineTrack(name = "V2", kind = TrackKind.VIDEO, muted = true, clips = listOf(first, first.copy(id = "b", timelineStartUs = 1_000_000L)))
                .capCutTransitionCutsV23()
                .isEmpty(),
        )
    }

    private fun clip(id: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        id = id,
        uri = "content://test/$id",
        label = id,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
