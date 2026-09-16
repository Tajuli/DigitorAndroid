package com.tajuli.digitorandroid.editor.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptionInternationalV86Test {
    private val expectedLanguages = listOf(
        AutoCaptionLanguageV86.BANGLA,
        AutoCaptionLanguageV86.ENGLISH,
        AutoCaptionLanguageV86.HINDI,
        AutoCaptionLanguageV86.ARABIC,
        AutoCaptionLanguageV86.INDONESIAN,
        AutoCaptionLanguageV86.JAPANESE,
        AutoCaptionLanguageV86.RUSSIAN,
        AutoCaptionLanguageV86.THAI,
        AutoCaptionLanguageV86.VIETNAMESE,
        AutoCaptionLanguageV86.CHINESE,
        AutoCaptionLanguageV86.KOREAN,
        AutoCaptionLanguageV86.FRENCH,
    )

    @Test
    fun expandedReleaseHasTwelveDownloadableLanguageChoices() {
        val languages = internationalLanguageChoicesV86()
        assertEquals(expectedLanguages, languages)
        assertEquals(12, languages.size)
        assertTrue(languages.all { it.downloadable })
        assertFalse(AutoCaptionLanguageV86.AUTO_BN_EN.downloadable)
    }

    @Test
    fun registryOrderIsStableForHorizontalLanguagePicker() {
        assertEquals(expectedLanguages, internationalLanguageChoicesV86())
    }

    @Test
    fun englishPackExistsAsCompactSelectableCandidate() {
        val english = autoCaptionPackSpecV86(AutoCaptionLanguageV86.ENGLISH)
        assertEquals(AutoCaptionLanguageV86.ENGLISH, english.language)
        assertEquals("English Zipformer 20M INT8", english.displayName)
        assertTrue(english.approximateDownloadMb in 1..50)
        assertEquals(AutoCaptionModelFamilyV89.TRANSDUCER, english.family)
    }

    @Test
    fun hindiUsesStreamingZipformer2CtcShape() {
        val hindi = autoCaptionPackSpecV86(AutoCaptionLanguageV86.HINDI)
        assertEquals(AutoCaptionModelFamilyV89.ZIPFORMER2_CTC, hindi.family)
        assertEquals(setOf("model.onnx", "tokens.txt"), hindi.files.map { it.localName }.toSet())
        assertTrue(hindi.displayName.contains("Hindi"))
        assertTrue(hindi.displayName.contains("Zipformer2"))
    }

    @Test
    fun multi8LanguagesShareOnePhysicalDownload() {
        val shared = listOf(
            AutoCaptionLanguageV86.ARABIC,
            AutoCaptionLanguageV86.INDONESIAN,
            AutoCaptionLanguageV86.JAPANESE,
            AutoCaptionLanguageV86.RUSSIAN,
            AutoCaptionLanguageV86.THAI,
            AutoCaptionLanguageV86.VIETNAMESE,
        ).map(::autoCaptionPackSpecV86)

        assertEquals(1, shared.map { it.directoryName }.toSet().size)
        assertEquals(1, shared.map { it.baseUrl }.toSet().size)
        assertEquals(1, shared.map { it.files }.toSet().size)
        assertTrue(shared.all { it.family == AutoCaptionModelFamilyV89.TRANSDUCER })
        assertTrue(shared.all { it.approximateDownloadMb >= 300 })
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
    fun allPacksUseHttpsSourcesAndExpectedRuntimeFiles() {
        val specs = AUTO_CAPTION_PACKS_V86
        assertEquals(12, specs.size)
        specs.forEach { spec ->
            assertTrue(spec.baseUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.approximateDownloadMb > 0)
            assertEquals("Apache-2.0", spec.license)
            val names = spec.files.map { it.localName }.toSet()
            when (spec.family) {
                AutoCaptionModelFamilyV89.TRANSDUCER -> assertEquals(
                    setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
                    names,
                )
                AutoCaptionModelFamilyV89.ZIPFORMER2_CTC -> assertEquals(
                    setOf("model.onnx", "tokens.txt"),
                    names,
                )
            }
            assertTrue(spec.files.all { it.minimumBytes > 0L })
        }
    }
}
