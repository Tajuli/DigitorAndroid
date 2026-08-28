package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.audioSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixSelectionTest {
    @Test
    fun linkedVideoSelectionFindsAudioClip() {
        val group = "g"
        val video = TimelineClip(uri = "file:///x.mp4", label = "v", timelineStartUs = 0L, sourceOutUs = 1_000_000L, linkGroupId = group)
        val audio = TimelineClip(uri = "file:///x.mp4", label = "a", timelineStartUs = 0L, sourceOutUs = 1_000_000L, linkGroupId = group)
        val project = TimelineProject(tracks = listOf(
            TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(video)),
            TimelineTrack(name = "A1", kind = TrackKind.AUDIO, clips = listOf(audio)),
        ))
        assertEquals(audio.id, project.audioSelection(video.id, setOf(video.id, audio.id)).single().id)
    }
}
