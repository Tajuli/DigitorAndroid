package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdaptiveNoiseReducer
import com.tajuli.digitorandroid.editor.model.AudioMix
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixIdentityTest {
    @Test
    fun defaultAudioMixIsUnity() {
        val mix = AudioMix()
        assertEquals(1f, mix.volume, 0f)
        assertEquals(0L, mix.fadeInUs)
        assertEquals(0L, mix.fadeOutUs)
        assertEquals(0f, mix.noiseReduction, 0f)
    }

    @Test
    fun adaptiveNoiseReductionSuppressesSteadyNoiseAndOpensForSpeech() {
        val reducer = AdaptiveNoiseReducer(1f)
        var gain = 1f

        // About one second of low-level room noise should settle well below unity.
        repeat(48_000) {
            gain = reducer.processFrame(.006f, 48_000)
        }
        assert(gain < .35f)

        // Speech should open the gate quickly without a hard discontinuity.
        val beforeSpeech = gain
        repeat(480) {
            gain = reducer.processFrame(.45f, 48_000)
        }
        assert(gain > beforeSpeech)
        assert(gain > .85f)

        // Release should be gradual rather than snapping back to the noise floor.
        val firstQuietFrame = reducer.processFrame(.006f, 48_000)
        assert(firstQuietFrame > .75f)
    }

    @Test
    fun adaptiveNoiseFloorCanTrackSustainedBackground() {
        val reducer = AdaptiveNoiseReducer(.75f)
        val initialFloor = reducer.currentNoiseFloorForTest()
        repeat(96_000) {
            reducer.processFrame(.015f, 48_000)
        }
        assert(reducer.currentNoiseFloorForTest() > initialFloor)
    }
}
