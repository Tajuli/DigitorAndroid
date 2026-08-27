package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuPreviewActiveLayersTest {
    @Test
    fun activeLayers_areReturnedBottomToTopForCompositing() {
        val top = TimelineClip(
            id = "top",
            uri = "content://top",
            label = "top",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        val bottom = top.copy(id = "bottom", uri = "content://bottom", label = "bottom")
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(name = "V2", kind = TrackKind.VIDEO, clips = listOf(top)),
                TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(bottom)),
            ),
        )

        assertEquals(listOf("bottom", "top"), activeVideoLayersAt(project, 500_000L).map { it.id })
    }
}
