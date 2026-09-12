package com.tajuli.digitorandroid.editor.model

/**
 * Runtime Whisper language selection.
 *
 * V82 intentionally uses a data class instead of a fixed enum so the editor can expose every
 * language reported by the pinned whisper.cpp runtime without duplicating its language table in
 * Kotlin. AUTO remains the default for international projects.
 */
data class AutoCaptionLanguageV80(
    val label: String,
    val whisperCode: String,
) {
    companion object {
        val AUTO = AutoCaptionLanguageV80("Auto Detect", "auto")
        val ENGLISH = AutoCaptionLanguageV80("English", "en")
        val BENGALI = AutoCaptionLanguageV80("Bengali", "bn")
    }
}

data class AutoCaptionSegmentV80(
    val startUs: Long,
    val endUs: Long,
    val text: String,
) {
    fun normalized(): AutoCaptionSegmentV80? {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return null
        val start = startUs.coerceAtLeast(0L)
        val end = endUs.coerceAtLeast(start + 1L)
        return copy(startUs = start, endUs = end, text = clean)
    }
}

/**
 * Makes Whisper output safe for the Resolve-style single caption lane. Audio tracks may overlap,
 * while one V-track lane may not. We preserve the earlier caption and trim the next caption's left
 * edge rather than silently moving spoken words to a different point in time.
 */
fun normalizeAutoCaptionSegmentsV80(
    segments: List<AutoCaptionSegmentV80>,
    minimumVisibleUs: Long = 180_000L,
): List<AutoCaptionSegmentV80> {
    val sorted = segments.mapNotNull { it.normalized() }.sortedWith(
        compareBy<AutoCaptionSegmentV80> { it.startUs }.thenBy { it.endUs },
    )
    if (sorted.isEmpty()) return emptyList()

    val result = mutableListOf<AutoCaptionSegmentV80>()
    for (segment in sorted) {
        val previous = result.lastOrNull()
        val adjustedStart = if (previous == null) segment.startUs else maxOf(segment.startUs, previous.endUs)
        val adjustedEnd = maxOf(segment.endUs, adjustedStart + 1L)
        if (adjustedEnd - adjustedStart < minimumVisibleUs) continue
        result += segment.copy(startUs = adjustedStart, endUs = adjustedEnd)
    }
    return result
}
