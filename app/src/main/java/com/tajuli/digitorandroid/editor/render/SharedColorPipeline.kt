package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ColorGraphEvaluator
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.InputColorProfile
import com.tajuli.digitorandroid.editor.model.InputColorTransform
import com.tajuli.digitorandroid.editor.model.QualifiedColorMath
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedInputColorProfile

/**
 * Single source of truth for GPU color processing.
 *
 * Optional camera log/HDR input transforms run first, then Correction/Color node snapshots. NONE is
 * a true bypass, so flat source code values can go straight into grading and export.
 *
 * V34 keeps preview and export on the same 33^3 cube. The previous 17^3/25^3 preview cubes were
 * cheaper, but they visibly reduced smoothness in skin/highlight gradients while authoring looks.
 */
@UnstableApi
object SharedColorPipeline {
    private const val LUT_SIZE = 33

    fun effectsFor(clip: TimelineClip): List<Effect> = buildList {
        addSpatialQualifierEffects(clip)
        add(AnimatedNodeColorLut(clip, LUT_SIZE, preview = false))
    }

    fun exactPreviewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addSpatialQualifierEffects(clip)
        add(AnimatedNodeColorLut(clip, LUT_SIZE, preview = true))
    }

    fun previewEffectsFor(clip: TimelineClip): List<Effect> = buildList {
        addSpatialQualifierEffects(clip)
        add(AnimatedNodeColorLut(clip, LUT_SIZE, preview = true))
    }

    private fun MutableList<Effect>.addSpatialQualifierEffects(clip: TimelineClip) {
        // This pre-filter samples source RGB before the 3D LUT. NONE/Rec.709 use those RGB code
        // values directly, so the mask is valid. Managed Log/HDR transforms stay inside the LUT.
        val profile = clip.resolvedInputColorProfile()
        if (profile != InputColorProfile.NONE && profile != InputColorProfile.REC709) return

        clip.nodeGraph.nodes.forEach { node ->
            if (!clip.nodeAnimations.qualifierIsAnimated(node.id)) {
                QualifierSpatialFeatherEffect.fromNode(node)?.let(::add)
            }
        }
    }

    internal fun buildCube(clip: TimelineClip): Array<Array<IntArray>> =
        buildCubeAtSourceTime(clip, LUT_SIZE, clip.sourceInUs)

    internal fun buildCubeAtSourceTime(
        clip: TimelineClip,
        size: Int,
        sourceTimeUs: Long,
    ): Array<Array<IntArray>> {
        val last = (size - 1).toFloat()
        val evaluatedGraph = clip.nodeAnimations.evaluateGraph(clip.nodeGraph, sourceTimeUs)
        val graphPlan = ColorGraphEvaluator.compile(evaluatedGraph)
        val inputProfile = clip.resolvedInputColorProfile()
        val nodeTransform: (ColorNode, Float, Float, Float) -> FloatArray = { node, r, g, b ->
            QualifiedColorMath.applyNode(node, r, g, b)
        }
        return Array(size) { rIndex ->
            Array(size) { gIndex ->
                IntArray(size) { bIndex ->
                    val input = InputColorTransform.toWorkingRec709(
                        inputProfile,
                        rIndex / last,
                        gIndex / last,
                        bIndex / last,
                    )
                    val rgb = graphPlan.apply(
                        r = input[0],
                        g = input[1],
                        b = input[2],
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
