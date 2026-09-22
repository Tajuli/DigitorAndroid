package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.*
import org.junit.Test

class PortraitLensBlurV99Test {
    private val size = 128
    private fun pixels() = IntArray(size * size) { i ->
        if (i % size < size / 2) 0xffff2010.toInt()
        else if ((i % size + i / size) % 2 == 0) 0xffffffff.toInt() else 0xff000000.toInt()
    }
    private fun matte(u: Float, @Suppress("UNUSED_PARAMETER") v: Float) = if (u < .5f) 1f else 0f

    @Test fun zeroStrengthIsPixelExact() {
        val source = pixels()
        assertArrayEquals(source, PortraitLensBlurV99.apply(source, size, size, 0f, ::matte))
    }
    @Test fun opaqueSubjectIsPixelExactAndBackgroundIsDefocused() {
        val source = pixels()
        val out = PortraitLensBlurV99.apply(source, size, size, 1f, ::matte)
        for (y in 0 until size) for (x in 0 until size / 2) {
            assertEquals(source[y * size + x], out[y * size + x])
        }
        val red = (out[64 * size + 96] ushr 16) and 255
        assertTrue("Checkerboard should lose high-frequency contrast: $red", red in 60..195)
        for (i in source.indices) assertEquals(source[i] ushr 24, out[i] ushr 24)
    }
    @Test fun brightSubjectDoesNotBleedIntoUniformBackground() {
        val source = IntArray(size * size) { if (it % size < size / 2) 0xffff0000.toInt() else 0xff204060.toInt() }
        val out = PortraitLensBlurV99.apply(source, size, size, 1f, ::matte)
        for (y in 8 until size - 8) for (x in size / 2 + 1 until size / 2 + 4) {
            assertEquals("Red foreground must not contaminate background", source[y * size + x], out[y * size + x])
        }
    }
    @Test fun missingBackgroundSamplesKeepOriginalAndNeverCreateBlackHoles() {
        val source = pixels()
        assertArrayEquals(source, PortraitLensBlurV99.apply(source, size, size, 1f) { _, _ -> .8f })
    }
    @Test fun alphaAndInputArePreserved() {
        val source = pixels().map { (it and 0xffffff) or (127 shl 24) }.toIntArray()
        val before = source.copyOf()
        val out = PortraitLensBlurV99.apply(source, size, size, .8f, ::matte)
        assertArrayEquals(before, source)
        out.forEach { assertEquals(127, it ushr 24) }
    }
    @Test fun invalidStrengthIsSafeAndTinyImagesWork() {
        val source = intArrayOf(0x7f123456)
        assertArrayEquals(source, PortraitLensBlurV99.apply(source, 1, 1, Float.NaN) { _, _ -> 0f })
        assertArrayEquals(source, PortraitLensBlurV99.apply(source, 1, 1, 1f) { _, _ -> 0f })
    }
}
