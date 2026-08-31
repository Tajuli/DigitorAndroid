package com.tajuli.digitorandroid.editor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWaveformTest {
    @Test
    fun peakBetween_respects_trimmed_source_window() {
        val waveform = AudioWaveform(
            durationUs = 4_000_000L,
            peaks = floatArrayOf(.1f, .2f, .9f, .3f),
        )

        assertEquals(.2f, waveform.peakBetween(1_000_000L, 2_000_000L), 0.0001f)
        assertEquals(.9f, waveform.peakBetween(1_500_000L, 3_200_000L), 0.0001f)
    }

    @Test
    fun peakAt_clamps_to_source_bounds() {
        val waveform = AudioWaveform(
            durationUs = 2_000_000L,
            peaks = floatArrayOf(.25f, .75f),
        )

        assertEquals(.25f, waveform.peakAt(-100L), 0.0001f)
        assertEquals(.75f, waveform.peakAt(9_000_000L), 0.0001f)
    }

    @Test
    fun accumulator_compacts_long_sources_without_losing_peaks() {
        val accumulator = AudioWaveformRepository.PeakAccumulator(initialBucketUs = 1L)
        accumulator.add(1L, .2f)
        accumulator.add(20_000L, .9f)

        val peaks = accumulator.finish(20_001L)

        assertTrue(peaks.size <= 8192)
        assertTrue(peaks.any { it >= .9f })
        assertTrue(accumulator.bucketUs > 1L)
    }
}
