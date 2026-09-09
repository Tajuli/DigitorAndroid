package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import kotlin.math.max
import kotlin.math.min

private enum class PreviewColorPickerTarget {
    QUALIFIER,
    CHROMA_KEY,
}

private object PreviewColorPickerRoute {
    @Volatile
    var target: PreviewColorPickerTarget = PreviewColorPickerTarget.QUALIFIER
}

/** Starts preview sampling with the next picked color routed to the selected clip's Chroma Key. */
internal fun startChromaKeyColorPicker(vm: EditorViewModelV4) {
    PreviewColorPickerRoute.target = PreviewColorPickerTarget.CHROMA_KEY
    vm.setQualifierPickerActive(true)
}

/** Cancels a pending Chroma Key pick and restores the preview picker to its qualifier default. */
internal fun cancelChromaKeyColorPicker(vm: EditorViewModelV4) {
    if (PreviewColorPickerRoute.target == PreviewColorPickerTarget.CHROMA_KEY) {
        PreviewColorPickerRoute.target = PreviewColorPickerTarget.QUALIFIER
        vm.setQualifierPickerActive(false)
    }
}

/**
 * Applies an RGB sample taken from the already-visible preview.
 *
 * Chroma Key reuses the same preview-surface sampler as the HSL qualifier. The picker intentionally
 * does not open a MediaMetadataRetriever/MediaCodec: sampling the visible preview avoids a second
 * decoder, long-GOP seeks and vendor-codec contention while keeping the interaction instant.
 */
internal fun applyQualifierPickedColor(
    vm: EditorViewModelV4,
    red: Float,
    green: Float,
    blue: Float,
) {
    if (PreviewColorPickerRoute.target == PreviewColorPickerTarget.CHROMA_KEY) {
        PreviewColorPickerRoute.target = PreviewColorPickerTarget.QUALIFIER
        val state = vm.state.value
        val clip = state.project.clip(state.selectedClipId)
        if (clip != null) {
            val settings = clip.resolvedCutoutV43()
            vm.setSelectedCutoutV43(
                settings.copy(
                    mode = CutoutModeV43.CHROMA_KEY,
                    keyRed = red.coerceIn(0f, 1f),
                    keyGreen = green.coerceIn(0f, 1f),
                    keyBlue = blue.coerceIn(0f, 1f),
                ),
                status = "Chroma key color picked from preview",
                coalesce = false,
            )
        }
        vm.setQualifierPickerActive(false)
        return
    }

    val hsl = qualifierRgbToHsl(red, green, blue)
    val hue = hsl[0] * 360f
    val sat = hsl[1]
    val lum = hsl[2]

    vm.setQualifier("hue", hue)
    vm.setQualifier("width", 34f)
    vm.setQualifier("satmin", (sat - .18f).coerceIn(0f, 1f))
    vm.setQualifier("satmax", (sat + .18f).coerceIn(0f, 1f))
    vm.setQualifier("lummin", (lum - .18f).coerceIn(0f, 1f))
    vm.setQualifier("lummax", (lum + .18f).coerceIn(0f, 1f))
    vm.setQualifier("softness", .12f)
    vm.setQualifierEnabled(true)
    vm.setQualifierPickerActive(false)
}

private fun qualifierRgbToHsl(r0: Float, g0: Float, b0: Float): FloatArray {
    val r = r0.coerceIn(0f, 1f)
    val g = g0.coerceIn(0f, 1f)
    val b = b0.coerceIn(0f, 1f)
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) * .5f
    if (mx == mn) return floatArrayOf(0f, 0f, l)

    val d = mx - mn
    val s = if (l > .5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
        g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    return floatArrayOf(h, s, l)
}
