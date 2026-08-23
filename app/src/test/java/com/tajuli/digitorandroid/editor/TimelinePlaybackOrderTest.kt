package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePlaybackOrderTest {
    private fun clip(label: String, startUs: Long = 0L, durationUs: Long = 2_000_000L) = TimelineClip(
        uri = label,
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = durationUs,
    )

    @Test fun firstVideoTrackIsTopmostWhenClipsOverlap() {
        val top = clip("top-v2")
        val bottom = clip("bottom-v1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        assertEquals(top.id, project.topmostVideoClipAt(1_000_000L)?.id)
    }

    @Test fun mutedTopTrackFallsThroughToLowerVideoTrack() {
        val top = clip("top-v2")
        val bottom = clip("bottom-v1")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top), muted = true),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        assertEquals(bottom.id, project.topmostVideoClipAt(1_000_000L)?.id)
    }

    @Test fun higherTrackWinsOnlyDuringItsOwnActiveRange() {
        val top = clip("top-v2", startUs = 750_000L, durationUs = 1_000_000L)
        val bottom = clip("bottom-v1", startUs = 0L, durationUs = 3_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        assertEquals(bottom.id, project.topmostVideoClipAt(500_000L)?.id)
        assertEquals(top.id, project.topmostVideoClipAt(1_000_000L)?.id)
        assertEquals(bottom.id, project.topmostVideoClipAt(2_000_000L)?.id)
    }
}
