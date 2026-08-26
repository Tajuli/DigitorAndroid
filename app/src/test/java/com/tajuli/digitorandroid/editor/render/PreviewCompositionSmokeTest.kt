package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreviewCompositionSmokeTest {
    @Test
    fun gpuPreviewComposition_buildsForOneVideoTrack() {
        val clip = TimelineClip(
            uri = "content://clip",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
        )

        assertNotNull(Media3CompositionBuilder().buildGpuPreview(project, 720))
    }
}
