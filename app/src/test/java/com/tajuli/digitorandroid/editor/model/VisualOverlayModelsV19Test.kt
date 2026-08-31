package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualOverlayModelsV19Test {

    @Test
    fun legacyProjectWithoutVisualFieldResolvesEmpty() {
        val project = TimelineProject(visualOverlaysV19 = null)

        assertTrue(project.resolvedVisualOverlaysV19().isEmpty())
        assertEquals(0L, project.durationUs)
    }

    @Test
    fun visualOverlayExtendsProjectDuration() {
        val overlay = VisualOverlayClipV19(
            id = "visual",
            kind = VisualOverlayKindV19.SHAPE,
            label = "Shape",
            timelineStartUs = 2_000_000L,
            timelineEndUs = 5_000_000L,
            shapePreset = ShapePresetV19.RECTANGLE,
        )
        val project = TimelineProject(visualOverlaysV19 = listOf(overlay))

        assertEquals(5_000_000L, project.durationUs)
    }

    @Test
    fun normalizationClampsTransformAndTiming() {
        val normalized = VisualOverlayClipV19(
            kind = VisualOverlayKindV19.STICKER,
            label = "Sticker",
            timelineStartUs = -5L,
            timelineEndUs = 10L,
            stickerPreset = StickerPresetV19.STAR,
            positionX = 4f,
            positionY = -3f,
            scale = 9f,
            rotationDegrees = -90f,
            opacity = 2f,
        ).normalized()

        assertEquals(0L, normalized.timelineStartUs)
        assertEquals(100_000L, normalized.timelineEndUs)
        assertEquals(1f, normalized.positionX, 0f)
        assertEquals(-1f, normalized.positionY, 0f)
        assertEquals(1.5f, normalized.scale, 0f)
        assertEquals(270f, normalized.rotationDegrees, 0f)
        assertEquals(1f, normalized.opacity, 0f)
    }

    @Test
    fun slotChecksMediaTextAndVisualOccupancy() {
        val media = TimelineClip(
            id = "media",
            uri = "content://media",
            label = "Media",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
        )
        val track = TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(media))
        val text = TextOverlayClip(
            id = "text",
            text = "Title",
            timelineStartUs = 2_000_000L,
            timelineEndUs = 4_000_000L,
            videoTrackIdV3 = "v1",
        )
        val visual = VisualOverlayClipV19(
            id = "visual",
            kind = VisualOverlayKindV19.SHAPE,
            label = "Shape",
            timelineStartUs = 4_000_000L,
            timelineEndUs = 6_000_000L,
            shapePreset = ShapePresetV19.CIRCLE,
            videoTrackIdV19 = "v1",
        )
        val project = TimelineProject(
            tracks = listOf(track),
            textOverlays = listOf(text),
            visualOverlaysV19 = listOf(visual),
        )

        assertFalse(project.visualOverlaySlotAvailableV19("v1", 1_000_000L, 1_500_000L))
        assertFalse(project.visualOverlaySlotAvailableV19("v1", 3_000_000L, 3_500_000L))
        assertFalse(project.visualOverlaySlotAvailableV19("v1", 5_000_000L, 5_500_000L))
        assertTrue(project.visualOverlaySlotAvailableV19("v1", 6_000_000L, 7_000_000L))
        assertTrue(project.visualOverlaySlotAvailableV19("v1", 4_000_000L, 6_000_000L, ignoreVisualId = "visual"))
    }
}
