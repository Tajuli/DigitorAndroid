package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineModelsTest {
    @Test fun defaultProjectStartsWithV1AndA1Only() {
        val project = TimelineProject()
        assertEquals(listOf("V1", "A1"), project.tracks.map { it.name })
        assertEquals(listOf(TrackKind.VIDEO, TrackKind.AUDIO), project.tracks.map { it.kind })
    }

    @Test fun projectDurationUsesFurthestTrackEnd() {
        val a = TimelineClip(uri = "a", label = "a", timelineStartUs = 0, sourceOutUs = 2_000_000)
        val b = TimelineClip(uri = "b", label = "b", timelineStartUs = 3_000_000, sourceOutUs = 2_000_000)
        val p = TimelineProject(tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(a, b))))
        assertEquals(5_000_000, p.durationUs)
        assertTrue(p.validate().isEmpty())
    }
}
