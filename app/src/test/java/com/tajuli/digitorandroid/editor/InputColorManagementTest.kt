package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.InputColorProfile
import com.tajuli.digitorandroid.editor.model.InputColorTransform
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedInputColorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputColorManagementTest {
    @Test
    fun legacyClipDefaultsToRec709() {
        val clip = TimelineClip(
            uri = "content://test",
            label = "Legacy",
            timelineStartUs = 0L,
            sourceOutUs = 1_000_000L,
        )
        assertEquals(InputColorProfile.REC709, clip.resolvedInputColorProfile())
    }

    @Test
    fun rec709ProfileIsIdentity() {
        val rgb = InputColorTransform.toWorkingRec709(InputColorProfile.REC709, .2f, .4f, .8f)
        assertEquals(.2f, rgb[0], 0f)
        assertEquals(.4f, rgb[1], 0f)
        assertEquals(.8f, rgb[2], 0f)
    }

    @Test
    fun slog3GrayNormalizesNearRec709MiddleGray() {
        val slog3Gray = 420f / 1023f
        val rgb = InputColorTransform.toWorkingRec709(
            InputColorProfile.SONY_SLOG3_SGAMUT3_CINE,
            slog3Gray,
            slog3Gray,
            slog3Gray,
        )
        // 18% linear gray encoded into Rec.709 is approximately 0.409.
        rgb.forEach { channel -> assertTrue(channel in .38f..44f / 100f) }
    }

    @Test
    fun cameraProfilesProduceFiniteDisplayRange() {
        InputColorProfile.entries.filterNot { it == InputColorProfile.REC709 }.forEach { profile ->
            val rgb = InputColorTransform.toWorkingRec709(profile, .25f, .5f, .75f)
            rgb.forEach { channel -> assertTrue("$profile produced $channel", channel in 0f..1f) }
        }
    }
}
