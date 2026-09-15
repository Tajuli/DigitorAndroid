package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySafeAutoCaptionEngineV81Test {

    @Test
    fun sixtySecondClipBecomesEightBalancedChunksWithoutMutatingOriginal() {
        val audioTrack = TimelineTrack(
            id = "a1",
            name = "A1",
            kind = TrackKind.AUDIO,
            clips = listOf(
                TimelineClip(
                    id = "speech",
                    uri = "content://speech",
                    label = "speech",
                    timelineStartUs = 2_000_000L,
                    sourceInUs = 5_000_000L,
                    sourceOutUs = 65_000_000L,
                ),
            ),
        )
        val project = TimelineProject(tracks = listOf(audioTrack))

        val safe = project.withMemorySafeAccurateAudioChunksV81("a1")
        val chunks = safe.track("a1")!!.sortedClips()

        assertEquals(8, chunks.size)
        assertEquals(1, project.track("a1")!!.clips.size)
        assertTrue(chunks.all { it.durationUs <= 8_000_000L })
        assertEquals(60_000_000L, chunks.sumOf { it.durationUs })
        assertEquals(2_000_000L, chunks.first().timelineStartUs)
        assertEquals(62_000_000L, chunks.last().timelineEndUs)
        assertEquals(5_000_000L, chunks.first().sourceInUs)
        assertEquals(65_000_000L, chunks.last().sourceOutUs)

        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.timelineEndUs, right.timelineStartUs)
            assertEquals(left.sourceOutUs, right.sourceInUs)
        }
    }

    @Test
    fun justOverEightSecondsAvoidsTinyTail() {
        val track = TimelineTrack(
            id = "a1",
            name = "A1",
            kind = TrackKind.AUDIO,
            clips = listOf(
                TimelineClip(
                    id = "speech",
                    uri = "file:///speech.mp4",
                    label = "speech",
                    timelineStartUs = 0L,
                    sourceOutUs = 9_000_000L,
                ),
            ),
        )
        val safe = TimelineProject(tracks = listOf(track))
            .withMemorySafeAccurateAudioChunksV81("a1")
        val chunks = safe.track("a1")!!.sortedClips()

        assertEquals(2, chunks.size)
        assertEquals(4_500_000L, chunks[0].durationUs)
        assertEquals(4_500_000L, chunks[1].durationUs)
    }

    @Test
    fun shortClipIsLeftUntouched() {
        val clip = TimelineClip(
            id = "speech",
            uri = "file:///speech.mp4",
            label = "speech",
            timelineStartUs = 700_000L,
            sourceInUs = 1_000_000L,
            sourceOutUs = 7_000_000L,
        )
        val track = TimelineTrack(id = "a1", name = "A1", kind = TrackKind.AUDIO, clips = listOf(clip))
        val project = TimelineProject(tracks = listOf(track))

        val safe = project.withMemorySafeAccurateAudioChunksV81("a1")

        assertEquals(project, safe)
    }
}
