package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.render.autoCaptionExportTextureCountV80
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionEngineV77Test {

    @Test
    fun generatedCaptionsUseDedicatedCcTrackAndPreserveManualText() {
        val manual = TextOverlayClip(
            text = "Manual title",
            timelineStartUs = 0L,
            timelineEndUs = 2_000_000L,
        )
        val source = TimelineProject(textOverlays = listOf(manual))
        val next = source.withAutoCaptionsV77(
            listOf(
                AutoCaptionDraftV77("Hello", 500_000L, 1_200_000L),
                AutoCaptionDraftV77("world", 1_200_000L, 2_000_000L),
            ),
        )

        val ccTrack = next.tracks.first { it.name == "CC" && it.kind == TrackKind.VIDEO }
        assertEquals(3, next.textOverlays.size)
        assertTrue(next.textOverlays.any { it.id == manual.id })
        assertTrue(next.textOverlays.filterNot { it.id == manual.id }.all { it.videoTrackIdV3 == ccTrack.id })
        assertEquals(2, next.autoCaptionCountV77())
    }

    @Test
    fun regenerateReplacesOnlyOldAutoCaptions() {
        val manual = TextOverlayClip(
            text = "Keep me",
            timelineStartUs = 0L,
            timelineEndUs = 1_000_000L,
        )
        val first = TimelineProject(textOverlays = listOf(manual)).withAutoCaptionsV77(
            listOf(AutoCaptionDraftV77("old", 0L, 900_000L)),
        )
        val second = first.withAutoCaptionsV77(
            listOf(AutoCaptionDraftV77("new", 1_000_000L, 1_900_000L)),
        )

        assertEquals(1, second.autoCaptionCountV77())
        assertTrue(second.textOverlays.any { it.id == manual.id })
        assertTrue(second.textOverlays.any { it.text == "new" })
        assertTrue(second.textOverlays.none { it.text == "old" })
        assertEquals(1, second.tracks.count { it.name == "CC" })
    }

    @Test
    fun clearRemovesGeneratedCaptionsOnly() {
        val manual = TextOverlayClip(
            text = "Manual",
            timelineStartUs = 0L,
            timelineEndUs = 1_000_000L,
        )
        val withCc = TimelineProject(textOverlays = listOf(manual)).withAutoCaptionsV77(
            listOf(AutoCaptionDraftV77("caption", 0L, 800_000L)),
        )
        val cleared = withCc.clearAutoCaptionsV77()

        assertEquals(0, cleared.autoCaptionCountV77())
        assertEquals(listOf(manual), cleared.textOverlays)
    }

    @Test
    fun manyGeneratedCaptionsUseOneExportTexture() {
        val captions = (0 until 21).map { index ->
            val start = index * 1_000_000L
            AutoCaptionDraftV77(
                text = "caption $index",
                timelineStartUs = start,
                timelineEndUs = start + 900_000L,
            )
        }
        val project = TimelineProject().withAutoCaptionsV77(captions)

        assertEquals(21, project.autoCaptionCountV77())
        assertEquals(1, autoCaptionExportTextureCountV80(project.textOverlays))
    }
}
