package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class CutoutPreviewRoutingV70Test {
    private fun clip(
        mode: CutoutModeV43,
        chromaPicked: Boolean = false,
    ) = TimelineClip(
        id = "clip",
        uri = "content://test/preview-cutout",
        label = "preview-cutout",
        timelineStartUs = 0L,
        sourceOutUs = 1_000_000L,
        cutoutV43 = ClipCutoutV43(
            mode = mode,
            chromaKeyColorPickedV71 = chromaPicked,
        ),
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

    @Test
    fun pendingChromaDoesNotApplyBeforeScreenColorIsPicked() {
        val pending = clip(CutoutModeV43.CHROMA_KEY, chromaPicked = false)
        val exportEffects = SharedVideoPipeline.compositedExportEffectsFor(pending)
        assertFalse(exportEffects.any { it is CutoutEffectV43 })

        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(pending),
                ),
            ),
        )
        PreviewProjectRegistry.update(project)
        try {
            assertEquals(
                CutoutModeV43.NONE,
                PreviewProjectRegistry.clip(pending.id)?.resolvedCutoutV43()?.mode,
            )
        } finally {
            PreviewProjectRegistry.clear()
        }
    }

    @Test
    fun pickedChromaAppliesToPreviewAndExport() {
        val picked = clip(CutoutModeV43.CHROMA_KEY, chromaPicked = true)
        val exportEffects = SharedVideoPipeline.compositedExportEffectsFor(picked)
        assertTrue(exportEffects.any { it is CutoutEffectV43 })

        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(picked),
                ),
            ),
        )
        PreviewProjectRegistry.update(project)
        try {
            assertEquals(
                CutoutModeV43.CHROMA_KEY,
                PreviewProjectRegistry.clip(picked.id)?.resolvedCutoutV43()?.mode,
            )
        } finally {
            PreviewProjectRegistry.clear()
        }
    }
}
