package com.tajuli.digitorandroid.ui.editor

import kotlin.math.max
import kotlin.math.min

/**
 * Applies an RGB sample taken from the already-visible preview to the selected node qualifier.
 *
 * The picker intentionally does not open a MediaMetadataRetriever/MediaCodec. The preview surface
 * is already showing the color the user tapped, so sampling that surface avoids a second decoder,
 * long-GOP seeks and vendor-codec contention while keeping the interaction instant.
 */
internal fun applyQualifierPickedColor(
    vm: EditorViewModelV4,
    red: Float,
    green: Float,
    blue: Float,
) {
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
