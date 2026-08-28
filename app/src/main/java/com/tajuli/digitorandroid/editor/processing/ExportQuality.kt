package com.tajuli.digitorandroid.editor.processing

import kotlin.math.roundToInt

/** User-facing H.264 export quality presets.
 *
 * Quality changes encoder bitrate only; project resolution, frame rate, grading, effects and
 * compositor semantics remain unchanged. This keeps preview/export render-stage geometry stable
 * while giving the user a predictable quality/file-size tradeoff.
 */
enum class ExportQuality(
    val label: String,
    private val bitsPerPixelPerFrame: Double,
) {
    HIGH("High", 0.26),
    MEDIUM("Medium", 0.13),
    LOW("Low", 0.065),
    ;

    fun videoBitrate(width: Int, height: Int, frameRate: Int): Int {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val safeFps = frameRate.coerceIn(1, 120)
        val requested = safeWidth.toDouble() * safeHeight.toDouble() * safeFps * bitsPerPixelPerFrame
        return requested
            .coerceIn(MIN_VIDEO_BITRATE.toDouble(), MAX_VIDEO_BITRATE.toDouble())
            .roundToInt()
    }

    companion object {
        const val MIN_VIDEO_BITRATE = 750_000
        const val MAX_VIDEO_BITRATE = 80_000_000
    }
}
