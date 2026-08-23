package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import com.tajuli.digitorandroid.editor.model.ColorGraphEvaluator
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Single source of truth for GPU color processing.
 *
 * Export keeps the full 33^3 LUT. Normal interactive preview uses a smaller 17^3 LUT so UI edits
 * stay responsive. Qualifier preview uses a finer 25^3 LUT because soft H/S/L mattes, especially
 * near neutral colors, otherwise quantize into visible islands/bands on flat walls and gradients.
 * Both paths compile and execute the same graph topology and Resolve-style qualified node transform.
 */
@UnstableApi
object SharedColorPipeline {
    private const val EXPORT_LUT_SIZE = 33
    private const val PREVIEW_LUT_SIZE = 17
    private const val QUALIFIER_PREVIEW_LUT_SIZE = 25

    fun effectsFor(clip: TimelineClip): List<Effect> = listOf(
        SingleColorLut.createFromCube(buildCube(clip, EXPORT_LUT_SIZE)),
    )

    fun previewEffectsFor(clip: TimelineClip): List<Effect> {
        val hasQualifier = clip.nodeGraph.nodes.any { it.advancedColor.qualifier.enabled }
        val size = if (hasQualifier) QUALIFIER_PREVIEW_LUT_SIZE else PREVIEW_LUT_SIZE
        return listOf(SingleColorLut.createFromCube(buildCube(clip, size)))
    }

    internal fun buildCube(clip: TimelineClip): Array<Array<IntArray>> =
        buildCube(clip, EXPORT_LUT_SIZE)

    private fun buildCube(clip: TimelineClip, size: Int): Array<Array<IntArray>> {
        val last = (size - 1).toFloat()
        val graphPlan = ColorGraphEvaluator.compile(clip.nodeGraph)
        val nodeTransform: (ColorNode, Float, Float, Float) -> FloatArray = { node, r, g, b ->
            QualifiedColorMath.applyNode(node, r, g, b)
        }
        return Array(size) { rIndex ->
            Array(size) { gIndex ->
                IntArray(size) { bIndex ->
                    val rgb = graphPlan.apply(
                        r = rIndex / last,
                        g = gIndex / last,
                        b = bIndex / last,
                        nodeTransform = nodeTransform,
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