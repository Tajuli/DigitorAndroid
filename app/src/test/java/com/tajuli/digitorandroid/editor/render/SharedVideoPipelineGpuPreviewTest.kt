package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedVideoPipelineGpuPreviewTest {
    @Test
    fun compositedPreviewEffects_buildForDefaultClip() {
        val clip = TimelineClip(
            uri = "content://clip",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )

        assertTrue(SharedVideoPipeline.compositedPreviewEffectsFor(clip).isNotEmpty())
    }
}
