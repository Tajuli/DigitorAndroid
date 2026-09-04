package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutExportRoutingV43Test {

    private fun projectWith(mode: CutoutModeV43): TimelineProject {
        val clip = TimelineClip(
            id = "clip",
            uri = "content://test/cutout",
            label = "cutout",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
            cutoutV43 = ClipCutoutV43(mode = mode),
        )
        return TimelineProject(
            tracks = listOf(
                TimelineTrack(
                    id = "v1",
                    name = "V1",
                    kind = TrackKind.VIDEO,
                    clips = listOf(clip),
                ),
            ),
        )
    }

    @Test
    fun normalOpaqueClipCanUseDirectSingleInputExport() {
        assertTrue(canUseDirectSingleInputExport(projectWith(CutoutModeV43.NONE)))
    }

    @Test
    fun autoCutoutForcesCompositorExportSoAlphaIsFlattenedBeforeH264() {
        val project = projectWith(CutoutModeV43.PERSON)
        assertFalse(canUseDirectSingleInputExport(project))
        assertFalse(shouldUseStableSingleInputExportV17(project))
    }

    @Test
    fun chromaKeyForcesCompositorExportSoAlphaIsFlattenedBeforeH264() {
        val project = projectWith(CutoutModeV43.CHROMA_KEY)
        assertFalse(canUseDirectSingleInputExport(project))
        assertFalse(shouldUseStableSingleInputExportV17(project))
    }
}
