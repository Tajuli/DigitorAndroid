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
import com.tajuli.digitorandroid.editor.model.CreatorEffectVectorV25
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolveCreatorEffectsV25
import com.tajuli.digitorandroid.editor.model.resolveTimedCreatorEffectsV26
import com.tajuli.digitorandroid.editor.model.visibleEffects
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * V25 creator-effects renderer with V26 timed effect spans.
 *
 * Keeps Digitor's Resolve-style serial/parallel node topology while expanding the old four-effect
 * shader into a compact creator library: blur/sharpen/glow/grain plus lens, RGB split, VHS lines,
 * pixelation, waves, zoom blur, ghosting, flicker, vignette and warm-film response. Preview and
 * export use the same shader and source-time evaluation for parity on the GPU path.
 */
@UnstableApi
internal class CreatorEffectGraphV25 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): CreatorEffectGraphV25? {
            val editableNodes = clip.nodeGraph.nodes.filter { node ->
                node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL
            }
            if (preview) {
                // Keep an identity-capable program alive so adding/changing an effect redraws the
                // held realtime frame without rebuilding the whole preview graph.
                return if (editableNodes.isNotEmpty()) CreatorEffectGraphV25(clip, true) else null
            }
            val hasFx = editableNodes.any { node ->
                !resolveCreatorEffectsV25(node.visibleEffects()).isIdentity ||
                    clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
            }
            return if (hasFx) CreatorEffectGraphV25(clip, false) else null
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
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                val slotTextures = IntArray(plan.operations.size) { inputTexId }
                var scratchCursor = 0

                fun nextScratch(): Pair<Int, Int> {
                    if (scratchCursor !in scratchTextures.indices) {
                        throw VideoFrameProcessingException(
                            IllegalStateException(
                                "Creator V25 scratch pool exhausted: $scratchCursor/${scratchTextures.size}",
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
                            val currentNode = if (preview) {
                                currentClip.nodeGraph.nodes.firstOrNull { node -> node.id == operation.node.id }
                                    ?: operation.node
                            } else {
                                operation.node
                            }
                            val evaluated = currentClip.nodeAnimations.evaluateNode(currentNode, sourceUs)
                            // Timing is clip-edit metadata, not an animated value. Effect keyframe
                            // snapshots may predate V26, so always merge the live base timing back by id.
                            val effectsWithTiming = evaluated.visibleEffects().map { animated ->
                                val base = currentNode.effects.firstOrNull { it.id == animated.id }
                                if (base == null) animated else animated.copy(
                                    sourceStartUsV26 = base.sourceStartUsV26,
                                    sourceEndUsV26 = base.sourceEndUsV26,
                                )
                            }
                            val vector = resolveTimedCreatorEffectsV26(effectsWithTiming, currentClip, sourceUs)
                            if (vector.isIdentity) {
                                slotTextures[operation.slot] = input
                            } else {
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderNode(nodeProgram, input, vector, evaluated.id, sourceUs)
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
            v: CreatorEffectVectorV25,
            nodeId: String,
            sourceUs: Long,
        ) {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexture, 0)
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
            )
            program.setFloatUniform("uBlur", v.blur)
            program.setFloatUniform("uSharpen", v.sharpen)
            program.setFloatUniform("uGlow", v.glow)
            program.setFloatUniform("uGrain", v.grain)
            program.setFloatUniform("uVignette", v.vignette)
            program.setFloatUniform("uRgbSplit", v.rgbSplit)
            program.setFloatUniform("uScanlines", v.scanlines)
            program.setFloatUniform("uPixelate", v.pixelate)
            program.setFloatUniform("uWave", v.wave)
            program.setFloatUniform("uLens", v.lens)
            program.setFloatUniform("uZoomBlur", v.zoomBlur)
            program.setFloatUniform("uGhost", v.ghost)
            program.setFloatUniform("uFlicker", v.flicker)
            program.setFloatUniform("uWarm", v.warm)
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
                uniform float uVignette;
                uniform float uRgbSplit;
                uniform float uScanlines;
                uniform float uPixelate;
                uniform float uWave;
                uniform float uLens;
                uniform float uZoomBlur;
                uniform float uGhost;
                uniform float uFlicker;
                uniform float uWarm;
                uniform float uTime;
                uniform float uSeed;
                varying vec2 vTexCoord;

                float hash21(vec2 p) {
                    p = fract(p * vec2(123.34 + uSeed * 17.0, 345.45 + uSeed * 31.0));
                    p += dot(p, p + 34.345 + uTime * 0.173 + uSeed * 13.7);
                    return fract(p.x * p.y);
                }

                vec2 creatorUv(vec2 uv) {
                    vec2 p = uv * 2.0 - 1.0;
                    float r2 = dot(p, p);
                    p *= 1.0 + uLens * 0.28 * r2;
                    uv = p * 0.5 + 0.5;
                    uv.x += sin(uv.y * 28.0 + uTime * 5.7) * uWave * 0.010;
                    uv.y += sin(uv.x * 24.0 - uTime * 4.1) * uWave * 0.006;
                    if (uPixelate > 0.001) {
                        float cells = mix(420.0, 30.0, clamp(uPixelate, 0.0, 1.0));
                        uv = (floor(uv * cells) + 0.5) / cells;
                    }
                    return clamp(uv, 0.001, 0.999);
                }

                void main() {
                    vec2 uv = creatorUv(vTexCoord);
                    vec4 center = texture2D(uTexSampler, uv);
                    float radius = 1.0 + uBlur * 4.0 + uGlow * 1.7;
                    vec2 o = uTexelSize * radius;

                    vec3 n  = texture2D(uTexSampler, clamp(uv + vec2(0.0,  o.y), 0.001, 0.999)).rgb;
                    vec3 s  = texture2D(uTexSampler, clamp(uv + vec2(0.0, -o.y), 0.001, 0.999)).rgb;
                    vec3 e  = texture2D(uTexSampler, clamp(uv + vec2( o.x, 0.0), 0.001, 0.999)).rgb;
                    vec3 w  = texture2D(uTexSampler, clamp(uv + vec2(-o.x, 0.0), 0.001, 0.999)).rgb;
                    vec3 ne = texture2D(uTexSampler, clamp(uv + vec2( o.x,  o.y), 0.001, 0.999)).rgb;
                    vec3 nw = texture2D(uTexSampler, clamp(uv + vec2(-o.x,  o.y), 0.001, 0.999)).rgb;
                    vec3 se = texture2D(uTexSampler, clamp(uv + vec2( o.x, -o.y), 0.001, 0.999)).rgb;
                    vec3 sw = texture2D(uTexSampler, clamp(uv + vec2(-o.x, -o.y), 0.001, 0.999)).rgb;

                    vec3 blurred = (center.rgb * 4.0 + (n + s + e + w) * 2.0 + ne + nw + se + sw) / 16.0;
                    vec3 rgb = mix(center.rgb, blurred, clamp(uBlur * 0.92, 0.0, 0.92));

                    vec3 crossAverage = (n + s + e + w) * 0.25;
                    rgb += (center.rgb - crossAverage) * uSharpen * 1.35;

                    float glowMask = smoothstep(0.44, 0.88, dot(blurred, vec3(0.2126, 0.7152, 0.0722)));
                    rgb += blurred * glowMask * uGlow * 0.68;

                    if (uZoomBlur > 0.001) {
                        vec2 d = uv - vec2(0.5);
                        vec3 zb = vec3(0.0);
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.015 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.035 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.060 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.090 * uZoomBlur, 0.001, 0.999)).rgb;
                        rgb = mix(rgb, zb * 0.25, clamp(uZoomBlur * 0.72, 0.0, 0.88));
                    }

                    if (uGhost > 0.001) {
                        vec2 go = vec2(0.012 + 0.010 * sin(uTime * 2.1), 0.006) * uGhost;
                        vec3 ghost = texture2D(uTexSampler, clamp(uv - go, 0.001, 0.999)).rgb;
                        rgb = mix(rgb, max(rgb, ghost), clamp(uGhost * 0.46, 0.0, 0.72));
                    }

                    if (uRgbSplit > 0.001) {
                        vec2 ro = vec2(uTexelSize.x * (3.0 + 12.0 * uRgbSplit), 0.0);
                        float rr = texture2D(uTexSampler, clamp(uv + ro, 0.001, 0.999)).r;
                        float bb = texture2D(uTexSampler, clamp(uv - ro, 0.001, 0.999)).b;
                        rgb.r = mix(rgb.r, rr, clamp(uRgbSplit, 0.0, 1.0));
                        rgb.b = mix(rgb.b, bb, clamp(uRgbSplit, 0.0, 1.0));
                    }

                    float grain = (hash21(vTexCoord * vec2(1920.0, 1080.0)) - 0.5) * 2.0;
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    float grainWeight = 0.55 + 0.45 * (1.0 - abs(luma * 2.0 - 1.0));
                    rgb += vec3(grain * uGrain * 0.10 * grainWeight);

                    float line = sin((vTexCoord.y * 1080.0 + uTime * 16.0) * 3.14159265);
                    rgb *= 1.0 - (0.025 + 0.035 * line) * clamp(uScanlines, 0.0, 1.0);

                    vec2 vp = vTexCoord * 2.0 - 1.0;
                    float edge = smoothstep(0.35, 1.35, dot(vp, vp));
                    rgb *= 1.0 - edge * uVignette * 0.42;

                    rgb.r += uWarm * 0.075;
                    rgb.g += uWarm * 0.020;
                    rgb.b -= uWarm * 0.055;

                    float flick = sin(uTime * 23.0 + uSeed * 9.0) * 0.5 + 0.5;
                    float flash = pow(flick, 8.0);
                    rgb *= 1.0 + (flick - 0.5) * uFlicker * 0.12;
                    rgb += vec3(flash * uFlicker * 0.22);

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
