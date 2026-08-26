package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuPreviewCompositionTest {
    @Test
    fun previewSize_preservesAspectAndCapsLongEdge() {
        val project = TimelineProject(width = 1920, height = 1080)
        assertEquals(720 to 404, resolvePreviewOutputSize(project, 720))
    }

    @Test
    fun previewSize_keepsSmallProjectsEven() {
        val project = TimelineProject(width = 641, height = 359)
        assertEquals(640 to 358, resolvePreviewOutputSize(project, 720))
    }

    @Test
    fun resolveTracks_preservesVisibleVideoTrackOrder() {
        val clip = TimelineClip(
            uri = "content://clip",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(clip)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip.copy(id = "other"))),
                TimelineTrack(name = "A1", kind = TrackKind.AUDIO),
            ),
        )

        assertEquals(listOf("V2", "V1"), resolveCompositionVideoTracks(project).map { it.name })
    }
}
