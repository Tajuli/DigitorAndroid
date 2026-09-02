package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.ColorGraphEvaluator
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.InputColorProfile
import com.tajuli.digitorandroid.editor.model.InputColorTransform
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedInputColorProfile

/**
 * Single source of truth for GPU color processing.
 *
 * V41 restores true node-local creator LOOK execution. A LOOK marker is evaluated inside the
 * Serial/Parallel node that owns it, immediately after that node's manual correction, and before
 * downstream nodes or a Parallel Mixer. This means node order and branch topology are now real
 * processing semantics instead of UI-only metadata.
 *
 * Camera input transforms still run before the graph. BEAUTY remains outside this 3D color LUT
 * because semantic smoothing/lips/eyes/hair require spatial processing.
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
            CreatorLookNodeTransformV41.apply(node, r, g, b)
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
