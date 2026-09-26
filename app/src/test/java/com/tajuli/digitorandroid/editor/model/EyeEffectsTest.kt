package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.*
import org.junit.Test

class EyeEffectsTest {
    private val clip = TimelineClip(uri = "test://eyes", label = "Eyes", timelineStartUs = 0, sourceOutUs = 1_000_000)
    private fun pose(x: Float = .3f, id: Int = 1, open: Float = 1f): EyePose {
        val eye = TrackedEye(x, .4f, .04f, 0f, open)
        return EyePose(eye, eye.copy(x = x+.3f), id)
    }
    @Test fun interpolatesMotionAndBlinkInSourceTime() {
        val track = EyeTrack(clip.uri, 0, 100_000, listOf(EyeSample(0, pose()), EyeSample(40_000, pose(.32f, open=0f))))
        assertEquals(.31f, track.at(20_000)!!.left.x, .0001f)
        assertEquals(.5f, track.at(20_000)!!.left.open, .0001f)
        assertNull(track.at(-1))
        assertNull(track.at(100_001))
    }
    @Test fun neverInterpolatesAcrossMissingFaceIdentityChangeOrSceneCut() {
        fun track(end: EyePose?, gap: Long = 40_000) = EyeTrack(clip.uri, 0, 300_000,
            listOf(EyeSample(0, pose()), EyeSample(gap, end)))
        assertNull(track(null).at(20_000))
        assertNull(track(pose(id=2)).at(20_000))
        assertNull(track(pose(.8f)).at(20_000))
        assertNull(track(pose(), 200_000).at(100_000))
    }
    @Test fun timedEffectsRespectDisableZeroTrimAndAmount() {
        val fire = NodeEffect(name="Fire Eyes", amount=.4f, sourceStartUsV26=200_000, sourceEndUsV26=700_000)
        assertEquals(0f, resolveEyeEffects(listOf(fire), clip, 100_000)[0], 0f)
        assertEquals(.4f, resolveEyeEffects(listOf(fire), clip, 300_000)[0], 0f)
        assertEquals(0f, resolveEyeEffects(listOf(fire), clip, 700_000)[0], 0f)
        assertTrue(resolveEyeEffects(listOf(fire.copy(enabled=false)), clip, 300_000).all { it == 0f })
        assertTrue(resolveEyeEffects(listOf(fire.copy(amount=0f)), clip, 300_000).all { it == 0f })
        assertTrue(resolveCreatorEffectsV25(listOf(fire)).isIdentity)
    }
    @Test fun rollInterpolationTakesShortestArc() {
        val a=TrackedEye(.3f,.4f,.04f,3.1f,1f)
        val b=a.copy(roll=-3.1f)
        assertTrue(kotlin.math.abs(a.interpolate(b,.5f).roll)>3f)
    }
}
