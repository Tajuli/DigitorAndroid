package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import com.tajuli.digitorandroid.editor.model.visibleVideoSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun overlapFlattensToOneVisibleVideoStream() {
        val top = clip("top-v2", startUs = 750_000L, durationUs = 1_000_000L)
        val bottom = clip("bottom-v1", startUs = 0L, durationUs = 3_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        val segments = project.visibleVideoSegments()
        assertEquals(3, segments.size)
        assertEquals(bottom.id, segments[0].clip.id)
        assertEquals(0L, segments[0].timelineStartUs)
        assertEquals(750_000L, segments[0].timelineEndUs)
        assertEquals(top.id, segments[1].clip.id)
        assertEquals(750_000L, segments[1].timelineStartUs)
        assertEquals(1_750_000L, segments[1].timelineEndUs)
        assertEquals(bottom.id, segments[2].clip.id)
        assertEquals(1_750_000L, segments[2].timelineStartUs)
        assertEquals(3_000_000L, segments[2].timelineEndUs)
    }

    @Test fun flattenedFragmentsPreserveCorrectSourceTimes() {
        val bottom = TimelineClip(
            uri = "bottom",
            label = "bottom",
            timelineStartUs = 0L,
            sourceInUs = 2_000_000L,
            sourceOutUs = 5_000_000L,
        )
        val top = clip("top", startUs = 1_000_000L, durationUs = 1_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        val fragments = project.visibleVideoSegments().map { it.asTimelineClip() }
        assertEquals(3, fragments.size)
        assertEquals(2_000_000L, fragments[0].sourceInUs)
        assertEquals(3_000_000L, fragments[0].sourceOutUs)
        assertEquals(4_000_000L, fragments[2].sourceInUs)
        assertEquals(5_000_000L, fragments[2].sourceOutUs)
        assertTrue(fragments.all { it.durationUs > 0L })
    }

    @Test fun adjacentBoundariesForSameVisibleClipAreMerged() {
        val top = clip("top", startUs = 1_000_000L, durationUs = 1_000_000L)
        val hiddenA = clip("hidden-a", startUs = 0L, durationUs = 1_500_000L)
        val hiddenB = clip("hidden-b", startUs = 1_500_000L, durationUs = 1_500_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V3", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(hiddenA, hiddenB)),
            ),
        )

        val segments = project.visibleVideoSegments()
        assertTrue(segments.zipWithNext().none { (a, b) ->
            a.clip.id == b.clip.id && a.timelineEndUs == b.timelineStartUs
        })
    }
}
