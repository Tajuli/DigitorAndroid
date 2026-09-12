package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuDecoderFallbackV74Test {
    @Test
    fun detectsUnisocCodecName() {
        assertTrue(
            shouldPreferSoftwareAvcDecoderV74(
                decoderNames = listOf("c2.unisoc.avc.decoder"),
                deviceHints = emptyList(),
            ),
        )
    }

    @Test
    fun detectsSprdCodecAlias() {
        assertTrue(
            shouldPreferSoftwareAvcDecoderV74(
                decoderNames = listOf("OMX.sprd.h264.decoder"),
                deviceHints = emptyList(),
            ),
        )
    }

    @Test
    fun detectsUnisocHardwareHint() {
        assertTrue(
            shouldPreferSoftwareAvcDecoderV74(
                decoderNames = listOf("c2.vendor.avc.decoder"),
                deviceHints = listOf("ums9230_hulk"),
            ),
        )
    }

    @Test
    fun normalCodecDoesNotForceSoftware() {
        assertFalse(
            shouldPreferSoftwareAvcDecoderV74(
                decoderNames = listOf("c2.qti.avc.decoder"),
                deviceHints = listOf("qcom", "sm8250"),
            ),
        )
    }
}
