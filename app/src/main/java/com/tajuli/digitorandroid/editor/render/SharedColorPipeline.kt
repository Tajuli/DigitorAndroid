package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import com.tajuli.digitorandroid.editor.model.AdvancedColorMath
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Single source of truth for GPU color processing.
 *
 * Both ExoPlayer preview and Media3 Transformer export must call [effectsFor] with the same clip.
 * The LUT is generated from the same per-pixel reference math used by the CPU fallback.
 */
@UnstableApi
object SharedColorPipeline {
    private const val LUT_SIZE = 33

    fun effectsFor(clip: TimelineClip): List<Effect> = listOf(
        SingleColorLut.createFromCube(buildCube(clip)),
    )

    internal fun buildCube(clip: TimelineClip): Array<Array<IntArray>> {
        val last = (LUT_SIZE - 1).toFloat()
        return Array(LUT_SIZE) { rIndex ->
            Array(LUT_SIZE) { gIndex ->
                IntArray(LUT_SIZE) { bIndex ->
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
