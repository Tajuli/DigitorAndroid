package com.tajuli.digitorandroid.editor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBeatPulseV1Test {
    @Test fun silenceNeverGeneratesFakeBeats() {
        val waveform = AudioWaveform(2_000_000L, FloatArray(100))
        assertTrue(detectHeavyOnsetsV1(waveform).isEmpty())
    }

    @Test fun detectsOnlyProminentSeparatedOnsets() {
        val peaks = FloatArray(100) { .12f }
        peaks[25] = .95f
        peaks[55] = .88f
        peaks[85] = 1f
        // Very close echo and soft ticks must not be interpreted as extra heavy beats.
        peaks[28] = .80f
        peaks[65] = .25f
        val beats = detectHeavyOnsetsV1(AudioWaveform(2_000_000L, peaks))
        assertEquals(listOf(500_000L, 1_100_000L, 1_700_000L), beats.toList())
    }

    @Test fun sustainedLoudAudioWithoutOnsetsDoesNotShake() {
        val peaks = FloatArray(150) { .75f }
        assertTrue(detectHeavyOnsetsV1(AudioWaveform(3_000_000L, peaks)).isEmpty())
    }
}
