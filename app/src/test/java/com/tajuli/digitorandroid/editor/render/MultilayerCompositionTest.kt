package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MultilayerCompositionTest {
    @Test
    fun videoTrackOrderPreservesTimelineZOrderAndSkipsMutedTracks() {
        val top = TimelineTrack(
            name = "V3",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("top", startUs = 0L, endUs = 2_000_000L)),
        )
        val muted = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            muted = true,
            clips = listOf(clip("muted", startUs = 0L, endUs = 2_000_000L)),
        )
        val bottom = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("bottom", startUs = 0L, endUs = 2_000_000L)),
        )
        val audio = TimelineTrack(name = "A1", kind = TrackKind.AUDIO)
        val project = TimelineProject(tracks = listOf(top, muted, bottom, audio))

        assertEquals(listOf("V3", "V1"), compositionVideoTracks(project).map { it.name })
    }

    @Test
    fun compositionOpacityFollowsActiveClipAndClampsRange() {
        val track = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(
                clip("first", 0L, 1_000_000L, opacity = .35f),
                clip("second", 2_000_000L, 3_000_000L, opacity = 1.5f),
            ),
        )

        assertEquals(.35f, compositionOpacityAt(track, 500_000L), 0.0001f)
        assertEquals(1f, compositionOpacityAt(track, 1_500_000L), 0.0001f)
        assertEquals(1f, compositionOpacityAt(track, 2_500_000L), 0.0001f)
    }

    private fun clip(
        label: String,
        startUs: Long,
        endUs: Long,
        opacity: Float = 1f,
    ): TimelineClip = TimelineClip(
        uri = "content://test/$label",
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
        opacity = opacity,
    )
}
