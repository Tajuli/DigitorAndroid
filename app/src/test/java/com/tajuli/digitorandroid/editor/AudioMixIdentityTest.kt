package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AdaptiveNoiseReducer
import com.tajuli.digitorandroid.editor.model.AudioMix
import com.tajuli.digitorandroid.editor.model.ClipAudioDspV78
import com.tajuli.digitorandroid.editor.model.VoiceStyleV78
import com.tajuli.digitorandroid.editor.processing.preferredNativeAudioOutputChannelsV77
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixIdentityTest {
    @Test
    fun defaultAudioMixIsUnity() {
        val mix = AudioMix()
        assertEquals(1f, mix.volume, 0f)
        assertEquals(0L, mix.fadeInUs)
        assertEquals(0L, mix.fadeOutUs)
        assertEquals(0f, mix.noiseReduction, 0f)
        assertEquals(0f, mix.voiceEnhance, 0f)
        assertEquals(0f, mix.vocalFocus, 0f)
        assertEquals(0f, mix.bassDb, 0f)
        assertEquals(0f, mix.midDb, 0f)
        assertEquals(0f, mix.trebleDb, 0f)
        assertEquals(VoiceStyleV78.NORMAL, mix.resolvedVoiceStyleV78)
        assertTrue(!mix.needsDspV78)
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
        // Give the smoothed gain about 20 ms to open; the 7 ms attack intentionally avoids
        // a hard jump while still reaching near-unity well within a normal syllable.
        repeat(960) {
            gain = reducer.processFrame(.45f, 48_000)
        }
        assert(gain > beforeSpeech)
        assert(gain > .90f)

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

    @Test
    fun strongNoiseReductionUsesDoubleAttenuationCurve() {
        val reducer = AdaptiveNoiseReducer(1f)
        var gain = 1f
        repeat(48_000) {
            gain = reducer.processFrame(.006f, 48_000)
        }

        // V78 squares the previous linear floor gain, approximately doubling attenuation in dB
        // while keeping the UI range at 0..100%.
        assertTrue(gain < .08f)
    }

    @Test
    fun vocalFocusNarrowsStereoWithoutDestroyingCenter() {
        val dsp = ClipAudioDspV78(
            mix = AudioMix(vocalFocus = 1f),
            sampleRate = 48_000,
            channelCount = 2,
        )
        val frame = floatArrayOf(.8f, .2f)
        val beforeWidth = kotlin.math.abs(frame[0] - frame[1])
        val beforeCenter = (frame[0] + frame[1]) * .5f

        dsp.processFrame(frame)

        val afterWidth = kotlin.math.abs(frame[0] - frame[1])
        val afterCenter = (frame[0] + frame[1]) * .5f
        assertTrue(afterWidth < beforeWidth * .2f)
        assertEquals(beforeCenter, afterCenter, .02f)
    }

    @Test
    fun advancedMixNormalizationClampsUserControls() {
        val mix = AudioMix(
            voiceEnhance = 2f,
            vocalFocus = -1f,
            bassDb = 30f,
            midDb = -30f,
            trebleDb = 24f,
            voiceStyleV78 = VoiceStyleV78.BRIGHT,
        ).normalizedFor(1_000_000L)

        assertEquals(1f, mix.voiceEnhance, 0f)
        assertEquals(0f, mix.vocalFocus, 0f)
        assertEquals(12f, mix.bassDb, 0f)
        assertEquals(-12f, mix.midDb, 0f)
        assertEquals(12f, mix.trebleDb, 0f)
        assertEquals(VoiceStyleV78.BRIGHT, mix.resolvedVoiceStyleV78)
        assertTrue(mix.needsDspV78)
    }

    @Test
    fun nativeAudioKeepsAllMonoSourcesMono() {
        assertEquals(1, preferredNativeAudioOutputChannelsV77(listOf(1, 1, 1)))
    }

    @Test
    fun nativeAudioUsesStereoForStereoOrUnknownSources() {
        assertEquals(2, preferredNativeAudioOutputChannelsV77(listOf(1, 2)))
        assertEquals(2, preferredNativeAudioOutputChannelsV77(listOf(1, null)))
        assertEquals(2, preferredNativeAudioOutputChannelsV77(emptyList()))
    }
}
