package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionInternationalV86Test {
    @Test
    fun firstInternationalReleaseHasFiveDownloadableLanguagePacks() {
        val languages = internationalLanguageChoicesV86()
        assertEquals(
            listOf(
                AutoCaptionLanguageV86.BANGLA,
                AutoCaptionLanguageV86.ENGLISH,
                AutoCaptionLanguageV86.CHINESE,
                AutoCaptionLanguageV86.KOREAN,
                AutoCaptionLanguageV86.FRENCH,
            ),
            languages,
        )
        assertTrue(languages.all { it.downloadable })
        assertFalse(AutoCaptionLanguageV86.AUTO_BN_EN.downloadable)
    }

    @Test
    fun autoModeRequiresBothBanglaAndEnglish() {
        assertFalse(autoLanguageAvailableV86(emptySet()))
        assertFalse(autoLanguageAvailableV86(setOf(AutoCaptionLanguageV86.BANGLA)))
        assertFalse(autoLanguageAvailableV86(setOf(AutoCaptionLanguageV86.ENGLISH)))
        assertTrue(
            autoLanguageAvailableV86(
                setOf(AutoCaptionLanguageV86.BANGLA, AutoCaptionLanguageV86.ENGLISH),
            ),
        )
    }

    @Test
    fun allPacksUsePinnedHttpsSourcesAndStandardLocalFiles() {
        val specs = AUTO_CAPTION_PACKS_V86
        assertEquals(5, specs.size)
        specs.forEach { spec ->
            assertTrue(spec.baseUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.approximateDownloadMb > 0)
            assertEquals("Apache-2.0", spec.license)
            assertEquals(
                setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
                spec.files.map { it.localName }.toSet(),
            )
            assertTrue(spec.files.all { it.minimumBytes > 0L })
        }
    }
}
