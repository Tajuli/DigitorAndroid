package com.tajuli.digitorandroid.editor.model

/**
 * Shared layout contract for generated Auto CC text.
 *
 * Preview constrains the caption container to this fraction of the visible frame. Export uses the
 * same explicit line wrapping so a caption that is two/three lines in preview stays two/three lines
 * in the rendered file instead of growing beyond the frame edges.
 */
const val AUTO_CAPTION_SAFE_WIDTH_FRACTION_V85 = 0.82f
const val AUTO_CAPTION_LINE_CODEPOINTS_V85 = 24
private const val AUTO_CAPTION_ID_PREFIX_V85 = "auto-cc-v77-"

fun TextOverlayClip.isAutoCaptionV85(): Boolean = id.startsWith(AUTO_CAPTION_ID_PREFIX_V85)

/**
 * Greedy Unicode-safe word wrapping for Auto CC. Existing manual newlines are preserved. Very long
 * unbroken tokens are split by Unicode code point so Bangla/emoji surrogate pairs are not cut in the
 * middle. Text is never truncated: longer edits naturally become 2, 3, or more centered lines.
 */
fun autoCaptionWrappedTextV85(
    raw: String,
    maxCodePoints: Int = AUTO_CAPTION_LINE_CODEPOINTS_V85,
): String {
    require(maxCodePoints > 0) { "maxCodePoints must be positive" }
    val normalized = raw.trim()
    if (normalized.isEmpty()) return normalized

    return normalized.split('\n').joinToString("\n") { paragraph ->
        wrapParagraphV85(paragraph, maxCodePoints).joinToString("\n")
    }
}

private fun wrapParagraphV85(paragraph: String, maxCodePoints: Int): List<String> {
    val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var current = ""

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            lines += current
            current = ""
        }
    }

    words.forEach { word ->
        val pieces = splitTokenV85(word, maxCodePoints)
        pieces.forEachIndexed { index, piece ->
            val candidate = if (current.isEmpty()) piece else "$current $piece"
            if (candidate.codePointLengthV85() <= maxCodePoints) {
                current = candidate
            } else {
                flushCurrent()
                current = piece
            }

            // A split token has no real whitespace between its chunks. Finish every full chunk so
            // the following chunk starts on the next line instead of being joined with a space.
            if (pieces.size > 1 && index < pieces.lastIndex) flushCurrent()
        }
    }
    flushCurrent()
    return lines
}

private fun splitTokenV85(token: String, maxCodePoints: Int): List<String> {
    if (token.codePointLengthV85() <= maxCodePoints) return listOf(token)
    val result = mutableListOf<String>()
    var start = 0
    while (start < token.length) {
        var end = start
        var count = 0
        while (end < token.length && count < maxCodePoints) {
            end = token.offsetByCodePoints(end, 1)
            count++
        }
        result += token.substring(start, end)
        start = end
    }
    return result
}

private fun String.codePointLengthV85(): Int = codePointCount(0, length)
