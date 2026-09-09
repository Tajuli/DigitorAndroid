package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class CutoutPreviewRoutingV70Test {
    private fun clip(mode: CutoutModeV43) = TimelineClip(
        id = "clip",
        uri = "content://test/preview-cutout",
        label = "preview-cutout",
        timelineStartUs = 0L,
        sourceOutUs = 1_000_000L,
        cutoutV43 = ClipCutoutV43(mode = mode),
    )

    @Test
    fun realtimePreviewKeepsCutoutStageResidentBeforeChromaIsEnabled() {
        val effects = SharedVideoPipeline.compositedPreviewEffectsFor(clip(CutoutModeV43.NONE))
        assertTrue(effects.any { it is CutoutEffectV43 })
    }

    @Test
    fun exportStillSkipsCutoutStageWhenModeIsNone() {
        val effects = SharedVideoPipeline.compositedExportEffectsFor(clip(CutoutModeV43.NONE))
        assertFalse(effects.any { it is CutoutEffectV43 })
    }
}
