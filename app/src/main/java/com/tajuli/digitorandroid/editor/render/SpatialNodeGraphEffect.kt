package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.SpatialGraphOperation
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects

/**
 * GPU texture compositor for spatial node effects.
 *
 * The whole spatial node graph is evaluated inside one Media3 [GlEffect]. Editable nodes render to
 * off-screen textures, parallel branches keep independent textures, and Mix nodes combine them as:
 *
 *     mixed = commonBase + sum(branch - commonBase)
 *
 * This mirrors the existing color-graph mixer semantics instead of flattening spatial branches into
 * a left-to-right effect chain. The color graph still runs before this spatial graph in the shared
 * video pipeline; this class owns the spatial topology after color has been resolved.
 */
@UnstableApi
internal class SpatialNodeGraphEffect private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): SpatialNodeGraphEffect? {
            val hasSpatialFx = clip.nodeGraph.nodes.any { node ->
                if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) return@any false
                node.visibleEffects().any { it.enabled && it.amount > 0f } ||
                    clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
            }
            return if (hasSpatialFx) SpatialNodeGraphEffect(clip, preview) else null
        }
    }

    private class Program(
        private val clip: TimelineClip,
        private val preview: Boolean,
        private val useHighPrecisionColorComponents: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHighPrecisionColorComponents,
        /* texturePoolCapacity = */ 1,
    ) {
        private val plan = SpatialNodeGraphPlan.compile(clip.nodeGraph)
        private val nodeProgram: GlProgram
        private val mixProgram: GlProgram
        private val copyProgram: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private var scratchTextures = IntArray(0)
        private var scratchFbos = IntArray(0)
        private var previewRevision = Long.MIN_VALUE
        private var previewAnchorPresentationUs = 0L
        private var previewAnchorSourceUs = clip.sourceInUs

        init {
            try {
                nodeProgram = newProgram(NODE_FRAGMENT_SHADER)
                mixProgram = newProgram(MIX_FRAGMENT_SHADER)
                copyProgram = newProgram(COPY_FRAGMENT_SHADER)
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(1)
            this.inputHeight = inputHeight.coerceAtLeast(1)
            try {
                releaseScratch()
                val count = plan.maximumScratchPasses.coerceAtLeast(1)
                scratchTextures = IntArray(count)
                scratchFbos = IntArray(count)
                for (index in 0 until count) {
                    val texture = GlUtil.createTexture(
                        this.inputWidth,
                        this.inputHeight,
                        useHighPrecisionColorComponents,
                    )
                    scratchTextures[index] = texture
                    scratchFbos[index] = GlUtil.createFboForTexture(texture)
                }
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val outputFboHolder = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFboHolder, 0)
                val media3OutputFbo = outputFboHolder[0]
                val sourceUs = sourceTimeUs(presentationTimeUs)
                val slotTextures = IntArray(plan.operations.size) { inputTexId }
                var scratchCursor = 0

                fun nextScratch(): Pair<Int, Int> {
                    if (scratchCursor !in scratchTextures.indices) {
                        throw VideoFrameProcessingException(
                            IllegalStateException(
                                "Spatial node graph scratch pool exhausted: $scratchCursor/${scratchTextures.size}",
                            ),
                            presentationTimeUs,
                        )
                    }
                    val pair = scratchTextures[scratchCursor] to scratchFbos[scratchCursor]
                    scratchCursor++
                    return pair
                }

                plan.operations.forEach { operation ->
                    when (operation.node.kind) {
                        NodeKind.IMPORT -> slotTextures[operation.slot] = inputTexId

                        NodeKind.SERIAL, NodeKind.PARALLEL -> {
                            val input = textureForSlot(slotTextures, operation.inputSlot, operation.slot, inputTexId)
                            val evaluated = clip.nodeAnimations.evaluateNode(operation.node, sourceUs)
                            val amounts = effectAmounts(evaluated)
                            if (amounts.isIdentity) {
                                slotTextures[operation.slot] = input
                            } else {
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderNode(nodeProgram, input, amounts, evaluated.id, sourceUs)
                                slotTextures[operation.slot] = texture
                            }
                        }

                        NodeKind.MIX -> {
                            val base = textureForSlot(
                                slotTextures,
                                operation.mixerBaseSlot,
                                operation.slot,
                                inputTexId,
                            )
                            var accumulator = base
                            operation.mixerInputSlots.forEach { branchSlot ->
                                if (branchSlot !in 0 until operation.slot) return@forEach
                                val branch = slotTextures[branchSlot]
                                if (branch == base) return@forEach
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderMix(mixProgram, accumulator, branch, base)
                                accumulator = texture
                            }
                            slotTextures[operation.slot] = accumulator
                        }

                        NodeKind.OUTPUT -> {
                            slotTextures[operation.slot] = textureForSlot(
                                slotTextures,
                                operation.inputSlot,
                                operation.slot,
                                inputTexId,
                            )
                        }
                    }
                }

                val finalTexture = if (plan.outputSlot in slotTextures.indices) {
                    slotTextures[plan.outputSlot]
                } else {
                    inputTexId
                }
                GlUtil.focusFramebufferUsingCurrentContext(media3OutputFbo, inputWidth, inputHeight)
                renderCopy(copyProgram, finalTexture)
                GlUtil.checkGlError()
            } catch (error: VideoFrameProcessingException) {
                throw error
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        private fun textureForSlot(
            slots: IntArray,
            slot: Int,
            currentSlot: Int,
            sourceTexture: Int,
        ): Int = if (slot in 0 until currentSlot) slots[slot] else sourceTexture

        private fun focus(fbo: Int) {
            GlUtil.focusFramebufferUsingCurrentContext(fbo, inputWidth, inputHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun renderNode(
            program: GlProgram,
            inputTexture: Int,
            amounts: EffectAmounts,
            nodeId: String,
            sourceUs: Long,
        ) {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexture, 0)
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
            )
            program.setFloatUniform("uBlur", amounts.blur)
            program.setFloatUniform("uSharpen", amounts.sharpen)
            program.setFloatUniform("uGlow", amounts.glow)
            program.setFloatUniform("uGrain", amounts.grain)
            program.setFloatUniform("uTime", (sourceUs % 10_000_000L).toFloat() / 1_000_000f)
            program.setFloatUniform("uSeed", ((nodeId.hashCode() ushr 1) % 10_000).toFloat() / 10_000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderMix(
            program: GlProgram,
            accumulatorTexture: Int,
            branchTexture: Int,
            baseTexture: Int,
        ) {
            program.use()
            program.setSamplerTexIdUniform("uAccumulator", accumulatorTexture, 0)
            program.setSamplerTexIdUniform("uBranch", branchTexture, 1)
            program.setSamplerTexIdUniform("uBase", baseTexture, 2)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderCopy(program: GlProgram, texture: Int) {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", texture, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun effectAmounts(node: ColorNode): EffectAmounts {
            val effects = node.visibleEffects()
            fun amount(name: String): Float = effects
                .firstOrNull { it.name.equals(name, ignoreCase = true) && it.enabled }
                ?.amount
                ?.coerceIn(0f, 1f)
                ?: 0f
            return EffectAmounts(
                blur = amount("Blur"),
                sharpen = amount("Sharpen"),
                glow = amount("Glow"),
                grain = amount("Film Grain"),
            )
        }

        private fun sourceTimeUs(presentationTimeUs: Long): Long {
            val minSource = clip.sourceInUs.coerceAtLeast(0L)
            val maxSource = clip.sourceOutUs.coerceAtLeast(minSource)
            if (!preview) {
                return (clip.sourceInUs + presentationTimeUs.coerceAtLeast(0L))
                    .coerceIn(minSource, maxSource)
            }

            val snapshot = PreviewTransformClock.snapshotFor(clip.id)
            if (snapshot == null) return presentationTimeUs.coerceIn(minSource, maxSource)
            if (snapshot.revision != previewRevision) {
                previewRevision = snapshot.revision
                previewAnchorPresentationUs = presentationTimeUs
                previewAnchorSourceUs = clip.sourceInUs + snapshot.localUs
            }
            return (previewAnchorSourceUs + (presentationTimeUs - previewAnchorPresentationUs))
                .coerceIn(minSource, maxSource)
        }

        private fun newProgram(fragmentShader: String): GlProgram =
            GlProgram(VERTEX_SHADER, fragmentShader).also { program ->
                program.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }

        private fun releaseScratch() {
            scratchFbos.forEach { fbo -> if (fbo != 0) GlUtil.deleteFbo(fbo) }
            scratchTextures.forEach { texture -> if (texture != 0) GlUtil.deleteTexture(texture) }
            scratchFbos = IntArray(0)
            scratchTextures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                releaseScratch()
                nodeProgram.delete()
                mixProgram.delete()
                copyProgram.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class EffectAmounts(
            val blur: Float,
            val sharpen: Float,
            val glow: Float,
            val grain: Float,
        ) {
            val isIdentity: Boolean
                get() = blur <= 0f && sharpen <= 0f && glow <= 0f && grain <= 0f
        }

        companion object {
            private const val VERTEX_SHADER = """
                attribute vec4 aFramePosition;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aFramePosition;
                    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
                }
            """

            private const val NODE_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexelSize;
                uniform float uBlur;
                uniform float uSharpen;
                uniform float uGlow;
                uniform float uGrain;
                uniform float uTime;
                uniform float uSeed;
                varying vec2 vTexCoord;

                float hash21(vec2 p) {
                    p = fract(p * vec2(123.34 + uSeed * 17.0, 345.45 + uSeed * 31.0));
                    p += dot(p, p + 34.345 + uTime * 0.173 + uSeed * 13.7);
                    return fract(p.x * p.y);
                }

                void main() {
                    vec4 center = texture2D(uTexSampler, vTexCoord);
                    float radius = 1.0 + uBlur * 4.0 + uGlow * 2.0;
                    vec2 o = uTexelSize * radius;

                    vec3 n  = texture2D(uTexSampler, vTexCoord + vec2(0.0,  o.y)).rgb;
                    vec3 s  = texture2D(uTexSampler, vTexCoord + vec2(0.0, -o.y)).rgb;
                    vec3 e  = texture2D(uTexSampler, vTexCoord + vec2( o.x, 0.0)).rgb;
                    vec3 w  = texture2D(uTexSampler, vTexCoord + vec2(-o.x, 0.0)).rgb;
                    vec3 ne = texture2D(uTexSampler, vTexCoord + vec2( o.x,  o.y)).rgb;
                    vec3 nw = texture2D(uTexSampler, vTexCoord + vec2(-o.x,  o.y)).rgb;
                    vec3 se = texture2D(uTexSampler, vTexCoord + vec2( o.x, -o.y)).rgb;
                    vec3 sw = texture2D(uTexSampler, vTexCoord + vec2(-o.x, -o.y)).rgb;

                    vec3 blurred = (center.rgb * 4.0 + (n + s + e + w) * 2.0 + ne + nw + se + sw) / 16.0;
                    vec3 rgb = mix(center.rgb, blurred, clamp(uBlur * 0.92, 0.0, 0.92));

                    vec3 crossAverage = (n + s + e + w) * 0.25;
                    rgb += (center.rgb - crossAverage) * uSharpen * 1.45;

                    float glowMask = smoothstep(0.48, 0.88, dot(blurred, vec3(0.2126, 0.7152, 0.0722)));
                    rgb += blurred * glowMask * uGlow * 0.72;

                    float grain = (hash21(vTexCoord * vec2(1920.0, 1080.0)) - 0.5) * 2.0;
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    float grainWeight = 0.55 + 0.45 * (1.0 - abs(luma * 2.0 - 1.0));
                    rgb += vec3(grain * uGrain * 0.10 * grainWeight);

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), center.a);
                }
            """

            private const val MIX_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uAccumulator;
                uniform sampler2D uBranch;
                uniform sampler2D uBase;
                varying vec2 vTexCoord;

                void main() {
                    vec4 accumulator = texture2D(uAccumulator, vTexCoord);
                    vec4 branch = texture2D(uBranch, vTexCoord);
                    vec4 base = texture2D(uBase, vTexCoord);
                    vec3 rgb = accumulator.rgb + (branch.rgb - base.rgb);
                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), accumulator.a);
                }
            """

            private const val COPY_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uTexSampler, vTexCoord);
                }
            """
        }
    }
}
