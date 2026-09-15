package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionLayoutV85Test {

    @Test
    fun shortCaptionIsUnchanged() {
        val text = "ছোট একটি ক্যাপশন"
        assertEquals(text, autoCaptionWrappedTextV85(text))
    }

    @Test
    fun longBanglaCaptionWrapsWithoutLosingText() {
        val raw = "এটি একটি অনেক বড় বাংলা ক্যাপশন যেটি ফোনের স্ক্রিনের বাইরে না গিয়ে দুই বা তিন লাইনে থাকবে"
        val wrapped = autoCaptionWrappedTextV85(raw)
        val lines = wrapped.lines()

        assertTrue(lines.size >= 2)
        assertTrue(lines.all { it.codePointCount(0, it.length) <= AUTO_CAPTION_LINE_CODEPOINTS_V85 })
        assertEquals(raw.split(Regex("\\s+")).joinToString(" "), wrapped.replace('\n', ' '))
    }

    @Test
    fun longEnglishCaptionWrapsToSafeLines() {
        val raw = "This is a deliberately long auto caption that should remain centered inside the visible phone frame"
        val wrapped = autoCaptionWrappedTextV85(raw)

        assertTrue(wrapped.lines().size >= 2)
        assertTrue(wrapped.lines().all { it.codePointCount(0, it.length) <= AUTO_CAPTION_LINE_CODEPOINTS_V85 })
    }

    @Test
    fun unbrokenLongTokenIsSplitUnicodeSafely() {
        val raw = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val wrapped = autoCaptionWrappedTextV85(raw)

        assertTrue(wrapped.lines().size >= 2)
        assertTrue(wrapped.lines().all { it.codePointCount(0, it.length) <= AUTO_CAPTION_LINE_CODEPOINTS_V85 })
        assertEquals(raw, wrapped.replace("\n", ""))
    }
}
