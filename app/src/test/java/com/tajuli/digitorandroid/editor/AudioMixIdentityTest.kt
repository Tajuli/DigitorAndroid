package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AudioMix
import com.tajuli.digitorandroid.editor.model.audioNoiseReductionGain
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
    fun noiseReductionLeavesLoudSpeechAndSuppressesLowLevelNoise() {
        assertEquals(1f, audioNoiseReductionGain(.5f, 1f), 0.0001f)
        val lowNoiseGain = audioNoiseReductionGain(.005f, 1f)
        assert(lowNoiseGain < .2f)
    }
}
