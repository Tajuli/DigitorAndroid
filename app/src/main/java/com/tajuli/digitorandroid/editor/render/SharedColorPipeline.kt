package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ColorGraphEvaluator
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Single source of truth for GPU color processing.
 *
 * Correction/Color node snapshots can now be keyframed. The LUT effect is timestamp-aware and is
 * shared by preview and export. Static grades still upload only once; animated grades rebuild the
 * cube for the current source timestamp and update the same GL texture.
 */
@UnstableApi
object SharedColorPipeline {
    private const val EXPORT_LUT_SIZE = 33
    private const val PREVIEW_LUT_SIZE = 17
    private const val QUALIFIER_PREVIEW_LUT_SIZE = 25

    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        addSpatialQualifierEffects(clip)
        add(AnimatedNodeColorLut(clip, EXPORT_LUT_SIZE, preview = false))
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        val hasQualifier = clip.nodeGraph.nodes.any { it.advancedColor.qualifier.enabled }
        addSpatialQualifierEffects(clip)
        val size = if (hasQualifier || clip.nodeAnimations.hasColorAnimation) {
            QUALIFIER_PREVIEW_LUT_SIZE
        } else {
            PREVIEW_LUT_SIZE
        }
        add(AnimatedNodeColorLut(clip, size, preview = true))
    }

    private fun MutableList<Effect>.addSpatialQualifierEffects(clip: TimelineClip) {
        clip.nodeGraph.nodes.forEach { node ->
            // The spatial pre-filter has immutable shader parameters today. If the qualifier itself
            // is animated, do not leave a stale static mask in front of the correctly animated LUT.
            if (!clip.nodeAnimations.qualifierIsAnimated(node.id)) {
                QualifierSpatialFeatherEffect.fromNode(node)?.let(::add)
            }
        }
    }

    internal fun buildCube(clip: TimelineClip): Array<Array<IntArray>> =
        buildCubeAtSourceTime(clip, EXPORT_LUT_SIZE, clip.sourceInUs)

    internal fun buildCubeAtSourceTime(
        clip: TimelineClip,
        size: Int,
        sourceTimeUs: Long,
    ): Array<Array<IntArray>> {
        val last = (size - 1).toFloat()
        val evaluatedGraph = clip.nodeAnimations.evaluateGraph(clip.nodeGraph, sourceTimeUs)
        val graphPlan = ColorGraphEvaluator.compile(evaluatedGraph)
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
