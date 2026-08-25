package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.topmostVideoClipAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTransformClockTest {
    @Test
    fun previewLookupPublishesClipLocalPlayheadTime() {
        PreviewTransformClock.clear()
        val clip = TimelineClip(
            uri = "video",
            label = "video",
            timelineStartUs = 10_000_000L,
            sourceOutUs = 30_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )

        project.topmostVideoClipAt(15_000_000L)
        val first = PreviewTransformClock.snapshotFor(clip.id)!!
        assertEquals(5_000_000L, first.localUs)

        project.topmostVideoClipAt(15_000_000L)
        val same = PreviewTransformClock.snapshotFor(clip.id)!!
        assertEquals(first.revision, same.revision)

        project.topmostVideoClipAt(20_000_000L)
        val moved = PreviewTransformClock.snapshotFor(clip.id)!!
        assertEquals(10_000_000L, moved.localUs)
        assertNotEquals(first.revision, moved.revision)
    }

    @Test
    fun gapClearsPublishedPreviewClip() {
        PreviewTransformClock.clear()
        val clip = TimelineClip(
            uri = "video",
            label = "video",
            sourceOutUs = 2_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )

        project.topmostVideoClipAt(1_000_000L)
        project.topmostVideoClipAt(3_000_000L)
        assertNull(PreviewTransformClock.snapshotFor(clip.id))
    }
}
