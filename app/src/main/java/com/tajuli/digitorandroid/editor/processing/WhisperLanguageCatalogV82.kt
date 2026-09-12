package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80

/**
 * Reads the supported language table directly from the pinned whisper.cpp runtime.
 * This keeps the Android UI in sync with the actual model/runtime instead of maintaining a second
 * hard-coded list in Kotlin.
 */
internal object WhisperLanguageNativeV82 {
    init {
        System.loadLibrary("digitor_whisper_jni")
    }

    external fun supportedLanguages(): Array<String>
}

internal fun supportedAutoCaptionLanguagesV82(): List<AutoCaptionLanguageV80> {
    val nativeLanguages = runCatching { WhisperLanguageNativeV82.supportedLanguages().toList() }
        .getOrDefault(emptyList())
        .mapNotNull { encoded ->
            val separator = encoded.indexOf('\t')
            if (separator <= 0) return@mapNotNull null
            val code = encoded.substring(0, separator).trim()
            val rawName = encoded.substring(separator + 1).trim()
            if (code.isBlank() || rawName.isBlank()) return@mapNotNull null
            val label = rawName.replaceFirstChar { first ->
                if (first.isLowerCase()) first.titlecase() else first.toString()
            }
            AutoCaptionLanguageV80(label = label, whisperCode = code)
        }
        .filterNot { it.whisperCode == "auto" }
        .distinctBy { it.whisperCode }
        .sortedBy { it.label.lowercase() }

    // If the native library cannot be queried, keep the editor usable with a minimal safe fallback.
    // Normal Auto Caption transcription would also be unavailable if the JNI library itself failed.
    val resolved = if (nativeLanguages.isNotEmpty()) {
        nativeLanguages
    } else {
        listOf(AutoCaptionLanguageV80.ENGLISH, AutoCaptionLanguageV80.BENGALI)
    }
    return listOf(AutoCaptionLanguageV80.AUTO) + resolved
}
