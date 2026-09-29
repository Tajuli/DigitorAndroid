package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.*
import org.junit.Test

class EyeEffectsTest {
    private val clip = TimelineClip(uri = "test://eyes", label = "Eyes", timelineStartUs = 0, sourceOutUs = 1_000_000)
    private fun pose(
        x: Float = .3f,
        id: Int = 1,
        open: Float = 1f,
        gazeX: Float = 0f,
        gazeForward: Float = 1f,
    ): EyePose {
        val eye = TrackedEye(x, .4f, .04f, 0f, open)
        return EyePose(
            eye,
            eye.copy(x = x+.3f),
            id,
            gazeX = gazeX,
            gazeForward = gazeForward,
        )
    }
    @Test fun interpolatesMotionButUsesNearestFrameBlinkState() {
        val track = EyeTrack(
            clip.uri,
            0,
            100_000,
            listOf(EyeSample(0, pose(open=1f)), EyeSample(40_000, pose(.32f, open=0f))),
        )
        assertEquals(.3095f, track.at(19_000)!!.left.x, .0001f)
        assertEquals(1f, track.at(19_000)!!.left.open, 0f)
        assertEquals(.3105f, track.at(21_000)!!.left.x, .0001f)
        assertEquals(0f, track.at(21_000)!!.left.open, 0f)
        assertNull(track.at(-1))
        assertNull(track.at(100_001))
    }
    @Test fun interpolatesSharedGazeWithoutAddingTrackingLag() {
        val track = EyeTrack(
            clip.uri,
            0,
            40_000,
            listOf(
                EyeSample(0, pose(gazeX = -1f, gazeForward = .2f)),
                EyeSample(40_000, pose(gazeX = 1f, gazeForward = .8f)),
            ),
        )
        val middle = track.at(20_000)!!
        assertEquals(0f, middle.gazeX, .0001f)
        assertEquals(.5f, middle.gazeForward, .0001f)
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
    @Test fun preservesFractionalRateAndVariableFrameTimestampsThroughATrim() {
        val samples = listOf(
            EyeSample(33_367, pose(.30f)),
            EyeSample(66_733, pose(.31f)),
            EyeSample(133_467, pose(.33f)),
            EyeSample(166_833, pose(.34f)),
        )
        val track = EyeTrack(clip.uri, 50_000, 200_000, samples)
        // A preroll frame is a valid left anchor, not a reason to shift source time.
        assertEquals(.304985f, track.at(50_000)!!.left.x, .00001f)
        for (sample in samples.drop(1)) assertEquals(sample.pose, track.at(sample.timeUs))
        assertEquals(.32f, track.at(100_100)!!.left.x, .00001f)
        assertNull(track.at(49_999))
    }
    @Test fun bridgesOnlyBriefInteriorTrackerDropouts() {
        val track = EyeTrack(
            clip.uri,
            0,
            100_000,
            listOf(
                EyeSample(0, pose(.30f)),
                EyeSample(33_333, null),
                EyeSample(66_667, pose(.32f)),
                EyeSample(100_000, pose(.33f)),
            ),
        )
        val bridged = track.at(33_333)!!
        assertEquals(.31f, bridged.left.x, .001f)
        assertEquals(0f, bridged.left.open, 0f)
        assertEquals(0f, bridged.right.open, 0f)

        val longLoss = EyeTrack(
            clip.uri,
            0,
            250_000,
            listOf(
                EyeSample(0, pose(.30f)),
                EyeSample(33_333, null),
                EyeSample(66_667, null),
                EyeSample(100_000, null),
                EyeSample(166_667, pose(.40f)),
                EyeSample(250_000, pose(.42f)),
            ),
        )
        assertNull(longLoss.at(66_667))
    }

    @Test fun requiresFreshAnalysisForTheOldNominalTimestampCache() {
        val current = EyeTrack(clip.uri, 0, clip.sourceOutUs, listOf(EyeSample(0, pose())))
        assertTrue(current.covers(clip))
        assertFalse(current.copy(version = 11).covers(clip))
        assertFalse(current.copy(version = 10).covers(clip))
        assertFalse(current.copy(version = 9).covers(clip))
        assertFalse(current.copy(version = 8).covers(clip))
        assertFalse(current.copy(version = 7).covers(clip))
        assertFalse(current.copy(version = 6).covers(clip))
        assertFalse(current.copy(version = 5).covers(clip))
        assertFalse(current.copy(version = 4).covers(clip))
        assertFalse(current.copy(version = 3).covers(clip))
        assertFalse(current.copy(version = 2).covers(clip))
    }
}
