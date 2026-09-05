package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PpMattingV2PortraitMatteV56Test {
    @Test
    fun inputIsRgbNchwNormalizedToMinusOneThroughOne() {
        val pixels = intArrayOf(
            0xff000000.toInt(),
            0xffffffff.toInt(),
            0xffff8000.toInt(),
        )
        val actual = FloatArray(9)

        fillPpMattingV2InputV56(pixels, actual)

        assertPlane(floatArrayOf(-1f, 1f, 1f), actual, 0)
        assertPlane(floatArrayOf(-1f, 1f, 128f / 127.5f - 1f), actual, 3)
        assertPlane(floatArrayOf(-1f, 1f, -1f), actual, 6)
    }

    @Test
    fun undersizedDestinationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            fillPpMattingV2InputV56(intArrayOf(0xff000000.toInt()), FloatArray(2))
        }
    }

    private fun assertPlane(expected: FloatArray, actual: FloatArray, offset: Int) {
        expected.forEachIndexed { index, value ->
            assertEquals(value, actual[offset + index], 0.00001f)
        }
    }
}
