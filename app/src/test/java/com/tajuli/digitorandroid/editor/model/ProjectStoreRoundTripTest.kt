package com.tajuli.digitorandroid.editor.model

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectStoreRoundTripTest {
    @Test
    fun creatorMetadataRoundTripsThroughGson() {
        val clip = TimelineClip(
            uri = "file:///clip.mp4",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 2_000_000L,
            transition = ClipTransition(250_000L, 300_000L),
            audioMix = AudioMix(.6f, 100_000L, 150_000L),
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))),
            textOverlays = listOf(TextOverlayClip(text = "Hello", timelineStartUs = 0L, timelineEndUs = 1_000_000L)),
        )
        val gson = GsonBuilder().create()
        val decoded = gson.fromJson(gson.toJson(project), TimelineProject::class.java)
        assertEquals("Hello", decoded.textOverlays.single().text)
        assertEquals(.6f, decoded.tracks.single().clips.single().audioMix.volume, 0f)
        assertEquals(250_000L, decoded.tracks.single().clips.single().transition.fadeInUs)
    }
}
