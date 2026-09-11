package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineProject
import kotlin.math.roundToInt

/** User-selectable export canvas while preserving the project aspect ratio. */
enum class ExportResolutionV72(
    val label: String,
    private val targetShortSide: Int?,
) {
    ORIGINAL("Original", null),
    P720("720p", 720),
    P1080("1080p", 1080),
    P1440("1440p", 1440),
    P2160("4K", 2160),
    ;

    fun resolve(width: Int, height: Int): Pair<Int, Int> {
        val sourceWidth = width.coerceAtLeast(2)
        val sourceHeight = height.coerceAtLeast(2)
        val shortSide = targetShortSide ?: return sourceWidth.evenV72() to sourceHeight.evenV72()
        val scale = shortSide.toDouble() / minOf(sourceWidth, sourceHeight).toDouble()
        return (sourceWidth * scale).roundToInt().evenV72() to
            (sourceHeight * scale).roundToInt().evenV72()
    }
}

/**
 * Export FPS selector. Media3 treats this as a maximum for moving-video inputs: higher-FPS sources
 * are frame-dropped to the selected rate; lower-FPS sources are not artificially duplicated.
 * Still images and the CPU fallback render at the selected rate directly.
 */
enum class ExportFrameRateV72(
    val label: String,
    private val targetFps: Int?,
) {
    ORIGINAL("Original", null),
    FPS_24("24", 24),
    FPS_25("25", 25),
    FPS_30("30", 30),
    FPS_50("50", 50),
    FPS_60("60", 60),
    ;

    fun resolve(projectFps: Int): Int = (targetFps ?: projectFps).coerceIn(1, 120)
}

data class ExportSettingsV72(
    val quality: ExportQuality = ExportQuality.HIGH,
    val resolution: ExportResolutionV72 = ExportResolutionV72.ORIGINAL,
    val frameRate: ExportFrameRateV72 = ExportFrameRateV72.ORIGINAL,
) {
    fun applyTo(project: TimelineProject): TimelineProject {
        val (width, height) = resolution.resolve(project.width, project.height)
        return project.copy(
            width = width,
            height = height,
            frameRate = frameRate.resolve(project.frameRate),
        )
    }

    fun description(project: TimelineProject): String {
        val resolved = applyTo(project)
        return "${resolved.width}×${resolved.height} · ${resolved.frameRate} fps · ${quality.label}"
    }
}

private fun Int.evenV72(): Int {
    val safe = coerceAtLeast(2)
    return if (safe % 2 == 0) safe else safe - 1
}
