package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRefreshTimelineTest {

    @After
    fun resetClock() {
        PreviewTransformClock.clear()
    }

    @Test
    fun editorPlayheadWinsOverStaleGpuFrame() {
        val clip = TimelineClip(
            id = "clip",
            uri = "content://test/video",
            label = "video",
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 63_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(clip),
                ),
            ),
        )

        PreviewTransformClock.update(clip, 0L)

        assertEquals(
            0L,
            resolvePreviewRefreshTimelineUs(
                project = project,
                staleGpuTimelineUs = 26_000_000L,
            ),
        )
    }

    @Test
    fun activeClipLocalPlayheadResolvesToTimelinePosition() {
        val clip = TimelineClip(
            id = "clip",
            uri = "content://test/video",
            label = "video",
            timelineStartUs = 5_000_000L,
            sourceInUs = 2_000_000L,
            sourceOutUs = 22_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(clip),
                ),
            ),
        )

        PreviewTransformClock.update(clip, 8_000_000L)

        assertEquals(
            8_000_000L,
            resolvePreviewRefreshTimelineUs(
                project = project,
                staleGpuTimelineUs = 19_000_000L,
            ),
        )
    }
}
