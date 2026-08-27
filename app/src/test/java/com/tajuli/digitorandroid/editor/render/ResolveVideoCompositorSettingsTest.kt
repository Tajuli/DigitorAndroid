package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveVideoCompositorSettingsTest {
    @Test
    fun exportVideoInputsPreserveTopToBottomTimelineOrder() {
        val v2 = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("overlay", 0L, 2_000_000L)),
        )
        val v1 = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("background", 0L, 4_000_000L)),
        )
        val a1 = TimelineTrack(name = "A1", kind = TrackKind.AUDIO)
        val project = TimelineProject(tracks = listOf(v2, v1, a1))

        assertEquals(listOf("V2", "V1"), resolveCompositionVideoTracks(project).map { it.name })
    }

    @Test
    fun shorterOverlayIsInactiveWhileBackgroundContinues() {
        val overlay = TimelineTrack(
            name = "V2",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("overlay", 0L, 2_000_000L)),
        )
        val background = TimelineTrack(
            name = "V1",
            kind = TrackKind.VIDEO,
            clips = listOf(clip("background", 0L, 4_000_000L)),
        )

        assertEquals("overlay", overlay.activeVideoClipAt(1_000_000L)?.label)
        assertEquals("background", background.activeVideoClipAt(1_000_000L)?.label)
        assertNull(overlay.activeVideoClipAt(3_000_000L))
        assertEquals("background", background.activeVideoClipAt(3_000_000L)?.label)
    }

    @Test
    fun matchingAspectDoesNotChangeScale() {
        val scale = aspectFitScale(1920, 1080, 1920, 1080)

        assertEquals(1f, scale.first, 0.0001f)
        assertEquals(1f, scale.second, 0.0001f)
    }

    @Test
    fun portraitInputIsContainedInsideLandscapeCanvas() {
        val scale = aspectFitScale(1080, 1920, 1920, 1080)

        assertEquals(0.31640625f, scale.first, 0.0001f)
        assertEquals(1f, scale.second, 0.0001f)
    }

    @Test
    fun ultraWideInputIsContainedInsideLandscapeCanvas() {
        val scale = aspectFitScale(2560, 1080, 1920, 1080)

        assertEquals(1f, scale.first, 0.0001f)
        assertEquals(0.75f, scale.second, 0.0001f)
    }

    private fun clip(label: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        uri = "content://test/$label",
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
