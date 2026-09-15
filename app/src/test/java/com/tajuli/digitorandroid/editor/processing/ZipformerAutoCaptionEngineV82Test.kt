package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerAutoCaptionEngineV82Test {

    @Test
    fun longClipIsSplitIntoBalancedRecognitionWindowsWithoutMutatingProject() {
        val originalClip = TimelineClip(
            id = "clip",
            uri = "file:///tmp/audio.mp4",
            label = "speech",
            timelineStartUs = 2_000_000L,
            sourceInUs = 1_000_000L,
            sourceOutUs = 61_000_000L,
        )
        val track = TimelineTrack(
            id = "a1",
            name = "A1",
            kind = TrackKind.AUDIO,
            clips = listOf(originalClip),
        )
        val project = TimelineProject(tracks = listOf(track))

        val tuned = project.withZipformerAccurateChunksV82("a1")
        val chunks = tuned.track("a1")!!.sortedClips()

        assertNotSame(project, tuned)
        assertEquals(10, chunks.size)
        assertTrue(chunks.all { it.durationUs <= 6_500_000L })
        assertEquals(originalClip.sourceInUs, chunks.first().sourceInUs)
        assertEquals(originalClip.sourceOutUs, chunks.last().sourceOutUs)
        assertEquals(originalClip.timelineStartUs, chunks.first().timelineStartUs)
        assertEquals(originalClip, project.track("a1")!!.clips.single())

        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.sourceOutUs, right.sourceInUs)
            assertEquals(left.timelineEndUs, right.timelineStartUs)
        }
    }

    @Test
    fun shortClipIsLeftUntouched() {
        val clip = TimelineClip(
            id = "clip",
            uri = "file:///tmp/audio.mp4",
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 5_000_000L,
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(id = "a1", name = "A1", kind = TrackKind.AUDIO, clips = listOf(clip))),
        )

        val tuned = project.withZipformerAccurateChunksV82("a1")

        assertEquals(project, tuned)
    }
}
