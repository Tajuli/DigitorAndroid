package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.ClipTransition
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.model.transitionPairsV22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorTransitionTest {
    @Test
    fun transitionMetadataSurvivesProjectCopy() {
        val clip = TimelineClip(
            uri = "file:///clip.mp4",
            label = "clip",
            timelineStartUs = 0L,
            sourceOutUs = 4_000_000L,
            transition = ClipTransition(
                fadeInUs = 500_000L,
                fadeOutUs = 700_000L,
                styleV22 = TransitionStyleV22.WHIP,
                durationUsV22 = 900_000L,
            ),
        )
        val project = TimelineProject(tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(clip))))
        val copied = project.copy()
        val copiedTransition = copied.tracks.first().clips.first().transition
        assertEquals(500_000L, copiedTransition.fadeInUs)
        assertEquals(700_000L, copiedTransition.fadeOutUs)
        assertEquals(TransitionStyleV22.WHIP, copiedTransition.resolvedStyleV22)
        assertEquals(900_000L, copiedTransition.resolvedDurationUsV22)
    }

    @Test
    fun allRequestedTransitionStylesArePresent() {
        val labels = TransitionStyleV22.entries.map { it.label }.toSet()
        listOf(
            "Cross Dissolve", "Smooth Cut", "Dip to Black", "Dip to White", "Fade",
            "Push Left", "Push Right", "Push Up", "Push Down", "Slide", "Zoom In", "Zoom Out",
            "Blur", "Whip", "Spin", "Flash", "Mask Wipe", "Circle Wipe", "Split", "Light Leak",
        ).forEach { label -> assertTrue("Missing transition: $label", label in labels) }
    }

    @Test
    fun cutTransitionResolvesOnlyForContiguousAdjacentClipsAndClampsDuration() {
        val first = clip("first", 0L, 2_000_000L)
        val second = clip("second", 2_000_000L, 3_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CROSS_DISSOLVE,
                durationUsV22 = 2_500_000L,
            ),
        )
        val track = TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second))
        val pair = track.transitionPairsV22().single()
        assertEquals(first.id, pair.outgoing.id)
        assertEquals(second.id, pair.incoming.id)
        // V22 caps a cut transition to half the incoming clip so the clip always retains a stable body.
        assertEquals(500_000L, pair.durationUs)
        assertFalse(second.transition.isIdentity)

        val gapped = track.copy(clips = listOf(first, second.copy(timelineStartUs = 2_100_000L)))
        assertTrue(gapped.transitionPairsV22().isEmpty())
    }

    @Test
    fun v22TransitionForcesCompositorExportRoute() {
        val first = clip("first", 0L, 2_000_000L)
        val second = clip("second", 2_000_000L, 4_000_000L).copy(
            transition = ClipTransition(
                styleV22 = TransitionStyleV22.CIRCLE_WIPE,
                durationUsV22 = 600_000L,
            ),
        )
        val project = TimelineProject(
            tracks = listOf(TimelineTrack(name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second))),
        )
        assertFalse(canUseDirectSingleInputExport(project))
    }

    private fun clip(label: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        uri = "file:///$label.mp4",
        label = label,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
