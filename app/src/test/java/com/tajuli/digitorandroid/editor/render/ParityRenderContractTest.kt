package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import com.tajuli.digitorandroid.editor.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Test

class ParityRenderContractTest {

    @Test
    fun sourceTime_isIdenticalCompositionMappingForTrimmedOffsetClip() {
        val clip = TimelineClip(
            uri = "content://test/timestamp",
            label = "timestamp",
            timelineStartUs = 3_000_000L,
            sourceInUs = 7_000_000L,
            sourceOutUs = 11_000_000L,
        )

        assertEquals(7_000_000L, ParityRenderContract.sourceTimeUs(clip, 3_000_000L))
        assertEquals(7_500_000L, ParityRenderContract.sourceTimeUs(clip, 3_500_000L))
        assertEquals(10_999_999L, ParityRenderContract.sourceTimeUs(clip, 6_999_999L))
    }

    @Test
    fun sourceTime_clampsOutsideVisibleClipRange() {
        val clip = TimelineClip(
            uri = "content://test/clamp",
            label = "clamp",
            timelineStartUs = 2_000_000L,
            sourceInUs = 5_000_000L,
            sourceOutUs = 8_000_000L,
        )

        assertEquals(5_000_000L, ParityRenderContract.sourceTimeUs(clip, 0L))
        assertEquals(8_000_000L, ParityRenderContract.sourceTimeUs(clip, 99_000_000L))
    }

    @Test
    fun graphOutputColor_matchesTransformerSrgbAndGamma22Rule() {
        val srgb = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .build()
        val gamma22 = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_GAMMA_2_2)
            .build()

        val srgbFormat = Format.Builder().setColorInfo(srgb).build()
        val gammaFormat = Format.Builder().setColorInfo(gamma22).build()

        assertEquals(ColorInfo.SDR_BT709_LIMITED, ParityRenderContract.videoGraphOutputColor(srgbFormat))
        assertEquals(ColorInfo.SDR_BT709_LIMITED, ParityRenderContract.videoGraphOutputColor(gammaFormat))
    }

    @Test
    fun graphOutputColor_preservesNormalBt709Limited() {
        val format = Format.Builder().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
        assertEquals(ColorInfo.SDR_BT709_LIMITED, ParityRenderContract.videoGraphOutputColor(format))
    }

    @Test
    fun decoderOutputFormat_matchesTransformerQuarterTurnNormalization() {
        val input = Format.Builder()
            .setWidth(1920)
            .setHeight(1080)
            .setRotationDegrees(90)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()

        val output = ParityRenderContract.decoderOutputFormat(input)

        assertEquals(1080, output.width)
        assertEquals(1920, output.height)
        assertEquals(0, output.rotationDegrees)
    }
}
