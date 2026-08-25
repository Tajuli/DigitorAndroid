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
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.visibleEffects

/**
 * Real GPU renderer for the built-in node effects.
 *
 * One pass is created for each editable node that contains visible effects (or has an Effects
 * animation track). Amounts are evaluated from the same source-time keyframe store used by the
 * editor. Preview is anchored to [PreviewTransformClock] so seeking/reloading effects cannot reset
 * an animation; Transformer export evaluates deterministically from item-local timestamps.
 *
 * Supported effects:
 * - Blur: 9-tap spatial blur with amount-controlled radius/mix.
 * - Sharpen: unsharp-mask style local contrast.
 * - Glow: thresholded blurred highlights added back to the image.
 * - Film Grain: deterministic per-frame luminance grain.
 */
@UnstableApi
internal class AnimatedNodeEffectsEffect private constructor(
    private val clip: TimelineClip,
    private val nodeId: String,
    private val preview: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, nodeId, preview, useHdr)

    companion object {
        fun forNode(clip: TimelineClip, nodeId: String, preview: Boolean): AnimatedNodeEffectsEffect? {
            val node = clip.nodeGraph.nodes.firstOrNull { it.id == nodeId } ?: return null
            val hasStatic = node.visibleEffects().any { it.enabled && it.amount > 0f }
            val animated = clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
            return if (hasStatic || animated) AnimatedNodeEffectsEffect(clip, nodeId, preview) else null
        }
    }

    private class Program(
        private val clip: TimelineClip,
        private val nodeId: String,
        private val preview: Boolean,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHdr,
        /* texturePoolCapacity = */ 1,
    ) {
        private val glProgram: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private var previewRevision = Long.MIN_VALUE
        private var previewAnchorPresentationUs = 0L
        private var previewAnchorSourceUs = clip.sourceInUs

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(1)
            this.inputHeight = inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val sourceUs = sourceTimeUs(presentationTimeUs)
                val baseNode = clip.nodeGraph.nodes.firstOrNull { it.id == nodeId }
                val node = baseNode?.let { clip.nodeAnimations.evaluateNode(it, sourceUs) }
                val effects = node?.visibleEffects().orEmpty()

                fun amount(name: String): Float = effects
                    .firstOrNull { it.name.equals(name, ignoreCase = true) && it.enabled }
                    ?.amount
                    ?.coerceIn(0f, 1f)
                    ?: 0f

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                glProgram.setFloatUniform("uBlur", amount("Blur"))
                glProgram.setFloatUniform("uSharpen", amount("Sharpen"))
                glProgram.setFloatUniform("uGlow", amount("Glow"))
                glProgram.setFloatUniform("uGrain", amount("Film Grain"))
                glProgram.setFloatUniform("uTime", (sourceUs % 10_000_000L).toFloat() / 1_000_000f)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
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

        override fun release() {
            super.release()
            try {
                glProgram.delete()
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

            private const val FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexelSize;
                uniform float uBlur;
                uniform float uSharpen;
                uniform float uGlow;
                uniform float uGrain;
                uniform float uTime;
                varying vec2 vTexCoord;

                float hash21(vec2 p) {
                    p = fract(p * vec2(123.34, 345.45));
                    float bias = 34.345 + uTime * 0.173;
                    p += dot(p, p + vec2(bias));
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
        }
    }
}
