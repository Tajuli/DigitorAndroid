package com.tajuli.digitorandroid.editor.model

/** V80 on-device Whisper language choices exposed in the Text workspace. */
enum class AutoCaptionLanguageV80(
    val label: String,
    val whisperCode: String,
) {
    AUTO("Auto", "auto"),
    BENGALI("বাংলা", "bn"),
    ENGLISH("English", "en"),
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
