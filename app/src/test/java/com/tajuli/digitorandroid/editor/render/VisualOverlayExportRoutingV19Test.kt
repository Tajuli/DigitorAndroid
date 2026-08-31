package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ShapePresetV19
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualOverlayExportRoutingV19Test {

    @Test
    fun overlayOnlyTimelineRequiresSyntheticVideoSource() {
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO)),
            visualOverlaysV19 = listOf(shape("overlay", 0L, 5_000_000L, "v1")),
        )

        assertTrue(needsPureTextVideoSourceV18(project))
        assertFalse(canUseDirectSingleInputExport(project))
        assertEquals(5_000_000L, project.durationUs)
    }

    @Test
    fun overlayFullyCoveredBySingleVideoKeepsStableExport() {
        val project = projectWithVideoAndOverlay(2_000_000L, 6_000_000L)

        assertTrue(canUseDirectSingleInputExport(project))
        assertTrue(textOverlaysAreCoveredByRealVideoV14(project))
        assertTrue(shouldUseStableSingleInputExportV17(project))
        assertFalse(needsPureTextVideoSourceV18(project))
    }

    @Test
    fun overlayCrossingVideoEndForcesBlankFrameComposition() {
        val project = projectWithVideoAndOverlay(9_000_000L, 12_000_000L)

        assertTrue(canUseDirectSingleInputExport(project))
        assertFalse(textOverlaysAreCoveredByRealVideoV14(project))
        assertFalse(shouldUseStableSingleInputExportV17(project))
        assertFalse(needsPureTextVideoSourceV18(project))
        assertEquals(12_000_000L, project.durationUs)
    }

    private fun projectWithVideoAndOverlay(startUs: Long, endUs: Long): TimelineProject {
        val video = TimelineClip(
            id = "video",
            uri = "content://video",
            label = "Video",
            timelineStartUs = 0L,
            sourceOutUs = 10_000_000L,
        )
        return TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
                TimelineTrack(id = "v2", name = "V2", kind = TrackKind.VIDEO),
            ),
            visualOverlaysV19 = listOf(shape("overlay", startUs, endUs, "v2")),
        )
    }

    private fun shape(id: String, startUs: Long, endUs: Long, trackId: String) =
        VisualOverlayClipV19(
            id = id,
            kind = VisualOverlayKindV19.SHAPE,
            label = "Shape",
            timelineStartUs = startUs,
            timelineEndUs = endUs,
            shapePreset = ShapePresetV19.RECTANGLE,
            videoTrackIdV19 = trackId,
        )
}
