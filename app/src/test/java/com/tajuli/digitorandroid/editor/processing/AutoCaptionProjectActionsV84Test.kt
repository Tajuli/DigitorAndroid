package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionProjectActionsV84Test {

    private fun projectWithCaptions(): TimelineProject {
        val v1 = TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO)
        val manual = TextOverlayClip(
            id = "manual-v1",
            text = "Manual V1 title",
            timelineStartUs = 0L,
            timelineEndUs = 1_000_000L,
            videoTrackIdV3 = v1.id,
        )
        return TimelineProject(tracks = listOf(v1), textOverlays = listOf(manual))
            .withAutoCaptionsV77(
                listOf(
                    AutoCaptionDraftV77("প্রথম ক্যাপশন", 0L, 900_000L),
                    AutoCaptionDraftV77("দ্বিতীয় ক্যাপশন", 1_000_000L, 1_900_000L),
                ),
            )
    }

    @Test
    fun generatedCaptionTextCanBeEditedWithoutChangingIdentityOrTiming() {
        val source = projectWithCaptions()
        val before = source.autoCaptionOverlaysV84().first()

        val edited = source.updateAutoCaptionTextV84(before.id, "সংশোধিত ক্যাপশন")
        val after = edited.autoCaptionOverlaysV84().first { it.id == before.id }

        assertEquals("সংশোধিত ক্যাপশন", after.text)
        assertEquals(before.id, after.id)
        assertEquals(before.timelineStartUs, after.timelineStartUs)
        assertEquals(before.timelineEndUs, after.timelineEndUs)
        assertEquals(before.videoTrackIdV3, after.videoTrackIdV3)
        assertTrue(edited.textOverlays.any { it.id == "manual-v1" && it.text == "Manual V1 title" })
    }

    @Test
    fun deletingOneCaptionKeepsCcTrackAndOtherCaptions() {
        val source = projectWithCaptions()
        val captions = source.autoCaptionOverlaysV84()
        val ccTrackId = source.autoCaptionTrackIdV84()
        assertNotNull(ccTrackId)

        val next = source.deleteAutoCaptionTextV84(captions.first().id)

        assertEquals(1, next.autoCaptionOverlaysV84().size)
        assertFalse(next.textOverlays.any { it.id == captions.first().id })
        assertEquals(ccTrackId, next.autoCaptionTrackIdV84())
        assertTrue(next.textOverlays.any { it.id == "manual-v1" })
    }

    @Test
    fun deletingCcTrackRemovesItsCaptionsButPreservesManualV1Text() {
        val source = projectWithCaptions()
        val ccTrackId = source.autoCaptionTrackIdV84()!!
        val manualOnCc = TextOverlayClip(
            id = "manual-cc",
            text = "Manual text placed on CC",
            timelineStartUs = 2_000_000L,
            timelineEndUs = 3_000_000L,
            videoTrackIdV3 = ccTrackId,
        )
        val withManualCc = source.copy(textOverlays = source.textOverlays + manualOnCc)

        val next = withManualCc.deleteAutoCaptionTrackV84()

        assertNull(next.autoCaptionTrackIdV84())
        assertEquals(0, next.autoCaptionCountV77())
        assertTrue(next.textOverlays.any { it.id == "manual-v1" && it.videoTrackIdV3 == "v1" })
        assertFalse(next.textOverlays.any { it.id == "manual-cc" })
        assertTrue(next.tracks.any { it.id == "v1" && it.name == "V1" })
    }
}
