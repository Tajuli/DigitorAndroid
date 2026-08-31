package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineVisualMediaV21
import com.tajuli.digitorandroid.editor.render.coalescePreviewClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineImageClipV21Test {
    @Test
    fun imageIsANativeFiveSecondTimelineClipWithVideoNodeGraph() {
        val image = TimelineClip(
            uri = "content://photo/1",
            label = "Photo",
            timelineStartUs = 2_000_000L,
            sourceOutUs = 5_000_000L,
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
            sourceMimeTypeV21 = "image/png",
        )

        assertTrue(image.isImageV21)
        assertEquals(5_000_000L, image.durationUs)
        assertEquals(7_000_000L, image.timelineEndUs)
        assertTrue(image.nodeGraph.nodes.any { it.kind == NodeKind.SERIAL })
        assertEquals("image/png", image.sourceMimeTypeV21)
    }

    @Test
    fun legacyVideoClipRemainsVideo() {
        val legacy = TimelineClip(
            uri = "content://video/1",
            label = "Video",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
        )
        assertFalse(legacy.isImageV21)
    }

    @Test
    fun adjacentStillImagesAreNeverCoalescedAsContinuousVideoSource() {
        val first = TimelineClip(
            uri = "content://photo/1",
            label = "Photo",
            timelineStartUs = 0L,
            sourceOutUs = 5_000_000L,
            visualMediaV21 = TimelineVisualMediaV21.IMAGE,
            sourceMimeTypeV21 = "image/jpeg",
        )
        val second = first.copy(
            id = "second",
            timelineStartUs = 5_000_000L,
            sourceInUs = 5_000_000L,
            sourceOutUs = 10_000_000L,
        )

        assertEquals(2, coalescePreviewClips(listOf(first, second)).size)
    }
}
