package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Single source of truth for GPU color processing.
 *
 * Export keeps the full 33^3 LUT. The interactive preview uses a smaller 17^3 LUT so UI edits
 * don't spend several times more CPU work than needed before the GPU can display the next frame.
 * Both paths still use the exact same AdvancedColorMath reference transform.
 */
@UnstableApi
object SharedColorPipeline {
    private const val EXPORT_LUT_SIZE = 33
    private const val PREVIEW_LUT_SIZE = 17

    fun effectsFor(clip: TimelineClip): List<Effect> = listOf(
        SingleColorLut.createFromCube(buildCube(clip, EXPORT_LUT_SIZE)),
    )

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = listOf(
        SingleColorLut.createFromCube(buildCube(clip, PREVIEW_LUT_SIZE)),
    )

    internal fun buildCube(clip: TimelineClip): Array<Array<IntArray>> =
        buildCube(clip, EXPORT_LUT_SIZE)

    private fun buildCube(clip: TimelineClip, size: Int): Array<Array<IntArray>> {
        val last = (size - 1).toFloat()
        return Array(size) { rIndex ->
            Array(size) { gIndex ->
                IntArray(size) { bIndex ->
                    val rgb = AdvancedColorMath.applyClip(
                        clip = clip,
                        r = rIndex / last,
                        g = gIndex / last,
                        b = bIndex / last,
                    )
                    val r = (rgb[0] * 255f + .5f).toInt().coerceIn(0, 255)
                    val g = (rgb[1] * 255f + .5f).toInt().coerceIn(0, 255)
                    val b = (rgb[2] * 255f + .5f).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
