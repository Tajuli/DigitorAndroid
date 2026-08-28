package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CreatorTransitionTest {
    @Test
    fun transitionMetadataSurvivesProjectCopy() {
        val clip = TimelineClip(
            uri = "file:///clip.mp4",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 4_000_000L,
            transition = ClipTransition(500_000L, 700_000L),
        )
        val project = TimelineProject(tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))))
        val copied = project.copy()
        assertEquals(500_000L, copied.tracks.first().clips.first().transition.fadeInUs)
        assertEquals(700_000L, copied.tracks.first().clips.first().transition.fadeOutUs)
    }
}
