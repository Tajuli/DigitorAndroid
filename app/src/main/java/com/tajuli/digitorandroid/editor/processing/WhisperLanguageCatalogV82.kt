package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80
import java.util.Locale

/**
 * Language order supported by the official OpenAI multilingual Whisper tokenizer used by Tencent's
 * ncnn Whisper graphs. Keeping the catalog in Kotlin avoids loading any legacy ggml runtime merely
 * to populate the picker.
 */
private val NCNN_WHISPER_LANGUAGE_CODES_V87 = listOf(
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv",
    "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
    "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr",
    "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
    "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu",
    "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
    "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su",
)

private val LANGUAGE_LABEL_OVERRIDES_V87 = mapOf(
    "bn" to "Bengali",
    "zh" to "Chinese",
    "jw" to "Javanese",
    "tl" to "Tagalog",
    "yi" to "Yiddish",
)

internal fun supportedAutoCaptionLanguagesV82(): List<AutoCaptionLanguageV80> {
    val languages = NCNN_WHISPER_LANGUAGE_CODES_V87.map { code ->
        val label = LANGUAGE_LABEL_OVERRIDES_V87[code]
            ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?: code.uppercase(Locale.ENGLISH)
        AutoCaptionLanguageV80(label = label, whisperCode = code)
    }.distinctBy { it.whisperCode }
        .sortedBy { it.label.lowercase(Locale.ENGLISH) }

    return listOf(AutoCaptionLanguageV80.AUTO) + languages
}
