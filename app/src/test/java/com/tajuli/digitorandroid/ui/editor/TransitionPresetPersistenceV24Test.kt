package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionPresetV24
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPresetPersistenceV24Test {
    @Test
    fun selectedPresetIsStoredOnIncomingClip() {
        val first = clip("a", 0L, 2_000_000L)
        val second = clip("b", 2_000_000L, 4_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second)),
            ),
        )

        val updated = project.withTransitionForCutV23(
            incomingClipId = "b",
            style = TransitionStyleV22.WHIP,
            durationUs = 650_000L,
            presetIdV24 = TransitionPresetV24.RGB_GLITCH,
        )!!

        val transition = updated.clip("b")!!.transition
        assertEquals(TransitionStyleV22.WHIP, transition.resolvedStyleV22)
        assertEquals(TransitionPresetV24.RGB_GLITCH, transition.presetIdV24)
        assertEquals(650_000L, transition.resolvedDurationUsV22)
    }

    @Test
    fun removingTransitionClearsPresetId() {
        val first = clip("a", 0L, 2_000_000L)
        val second = clip("b", 2_000_000L, 4_000_000L)
        val project = TimelineProject(
            tracks = listOf(
                TimelineTrack(id = "v1", name = "V1", kind = TrackKind.VIDEO, clips = listOf(first, second)),
            ),
        )
        val withPreset = project.withTransitionForCutV23(
            "b", TransitionStyleV22.BLUR, 500_000L, TransitionPresetV24.BLUR_ZOOM,
        )!!

        val removed = withPreset.withTransitionForCutV23("b", TransitionStyleV22.NONE, 500_000L)!!

        assertEquals(null, removed.clip("b")!!.transition.presetIdV24)
    }

    private fun clip(id: String, startUs: Long, endUs: Long): TimelineClip = TimelineClip(
        id = id,
        uri = "content://test/$id",
        label = id,
        timelineStartUs = startUs,
        sourceOutUs = endUs - startUs,
    )
}
