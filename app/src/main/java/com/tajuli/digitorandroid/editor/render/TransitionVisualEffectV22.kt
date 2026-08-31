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
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TransitionPairV22
import com.tajuli.digitorandroid.editor.model.TransitionStyleV22
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

internal const val TRANSITION_GHOST_ID_PREFIX_V22 = "__digitor_transition_ghost_v22__"

internal fun transitionGhostIdV22(pair: TransitionPairV22): String =
    "$TRANSITION_GHOST_ID_PREFIX_V22${pair.outgoing.id}__to__${pair.incoming.id}"

/**
 * Per-input transition pixel stage. Geometry/alpha motion is owned by ResolveVideoCompositorSettings;
 * this shader supplies the effects that cannot be expressed by OverlaySettings: blur/whip streak,
 * dip colors, flash, masks, circle/split wipes and the light-leak treatment.
 */
@UnstableApi
internal class TransitionVisualEffectV22 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
    private val outgoingGhost: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview, outgoingGhost, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): TransitionVisualEffectV22? {
            if (!clip.transition.hasCutTransitionV22) return null
            return TransitionVisualEffectV22(
                clip = clip,
                preview = preview,
                outgoingGhost = clip.id.startsWith(TRANSITION_GHOST_ID_PREFIX_V22),
            )
        }
    }

    private class Program(
        private val clip: TimelineClip,
        private val preview: Boolean,
        private val outgoingGhost: Boolean,
        useHighPrecisionColorComponents: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHighPrecisionColorComponents,
        /* texturePoolCapacity = */ 1,
    ) {
        private val program: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1

        init {
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { shader ->
                    shader.setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
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
                val currentClip = if (preview && !outgoingGhost) {
                    PreviewProjectRegistry.clip(clip.id) ?: clip
                } else {
                    clip
                }
                val transition = currentClip.transition.normalizedFor(currentClip.durationUs)
                val durationUs = transition.resolvedDurationUsV22.coerceAtLeast(1L)
                val localUs = if (outgoingGhost) {
                    presentationTimeUs.coerceAtLeast(0L)
                } else {
                    (ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs) - currentClip.sourceInUs)
                        .coerceAtLeast(0L)
                }
                val progress = (localUs.toDouble() / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uProgress", progress)
                program.setFloatUniform("uOutgoing", if (outgoingGhost) 1f else 0f)
                program.setFloatUniform("uStyle", styleCode(transition.resolvedStyleV22))
                program.bindAttributesAndUniforms()
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (error: VideoFrameProcessingException) {
                throw error
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private fun styleCode(style: TransitionStyleV22): Float = when (style) {
            TransitionStyleV22.NONE -> 0f
            TransitionStyleV22.CROSS_DISSOLVE -> 1f
            TransitionStyleV22.SMOOTH_CUT -> 2f
            TransitionStyleV22.DIP_TO_BLACK -> 3f
            TransitionStyleV22.DIP_TO_WHITE -> 4f
            TransitionStyleV22.FADE -> 5f
            TransitionStyleV22.PUSH_LEFT -> 6f
            TransitionStyleV22.PUSH_RIGHT -> 7f
            TransitionStyleV22.PUSH_UP -> 8f
            TransitionStyleV22.PUSH_DOWN -> 9f
            TransitionStyleV22.SLIDE -> 10f
            TransitionStyleV22.ZOOM_IN -> 11f
            TransitionStyleV22.ZOOM_OUT -> 12f
            TransitionStyleV22.BLUR -> 13f
            TransitionStyleV22.WHIP -> 14f
            TransitionStyleV22.SPIN -> 15f
            TransitionStyleV22.FLASH -> 16f
            TransitionStyleV22.MASK_WIPE -> 17f
            TransitionStyleV22.CIRCLE_WIPE -> 18f
            TransitionStyleV22.SPLIT -> 19f
            TransitionStyleV22.LIGHT_LEAK -> 20f
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
                uniform float uProgress;
                uniform float uOutgoing;
                uniform float uStyle;
                varying vec2 vTexCoord;

                vec3 sampleBlur(vec2 uv, float amount) {
                    vec2 o = uTexelSize * (1.0 + amount * 10.0);
                    vec3 c = texture2D(uTexSampler, uv).rgb * 4.0;
                    c += texture2D(uTexSampler, uv + vec2( o.x, 0.0)).rgb * 2.0;
                    c += texture2D(uTexSampler, uv + vec2(-o.x, 0.0)).rgb * 2.0;
                    c += texture2D(uTexSampler, uv + vec2(0.0,  o.y)).rgb * 2.0;
                    c += texture2D(uTexSampler, uv + vec2(0.0, -o.y)).rgb * 2.0;
                    c += texture2D(uTexSampler, uv + vec2( o.x,  o.y)).rgb;
                    c += texture2D(uTexSampler, uv + vec2(-o.x,  o.y)).rgb;
                    c += texture2D(uTexSampler, uv + vec2( o.x, -o.y)).rgb;
                    c += texture2D(uTexSampler, uv + vec2(-o.x, -o.y)).rgb;
                    return c / 16.0;
                }

                vec3 sampleWhip(vec2 uv, float amount) {
                    vec2 o = vec2(uTexelSize.x * (2.0 + amount * 28.0), 0.0);
                    vec3 c = texture2D(uTexSampler, uv).rgb * 0.30;
                    c += texture2D(uTexSampler, uv + o * 0.25).rgb * 0.20;
                    c += texture2D(uTexSampler, uv - o * 0.25).rgb * 0.20;
                    c += texture2D(uTexSampler, uv + o * 0.60).rgb * 0.15;
                    c += texture2D(uTexSampler, uv - o * 0.60).rgb * 0.15;
                    return c;
                }

                void main() {
                    vec4 center = texture2D(uTexSampler, vTexCoord);
                    vec3 rgb = center.rgb;
                    float alpha = center.a;
                    float p = clamp(uProgress, 0.0, 1.0);
                    float outgoing = step(0.5, uOutgoing);
                    float pulse = sin(3.14159265 * p);

                    if (uStyle > 2.5 && uStyle < 3.5) {
                        float amount = outgoing > 0.5 ? smoothstep(0.0, 0.58, p) : 1.0 - smoothstep(0.42, 1.0, p);
                        rgb = mix(rgb, vec3(0.0), amount);
                    } else if (uStyle > 3.5 && uStyle < 4.5) {
                        float amount = outgoing > 0.5 ? smoothstep(0.0, 0.58, p) : 1.0 - smoothstep(0.42, 1.0, p);
                        rgb = mix(rgb, vec3(1.0), amount);
                    } else if (uStyle > 12.5 && uStyle < 13.5) {
                        float amount = outgoing > 0.5 ? p : (1.0 - p);
                        rgb = mix(rgb, sampleBlur(vTexCoord, amount), 0.92 * amount);
                    } else if (uStyle > 13.5 && uStyle < 14.5) {
                        float amount = max(0.0, pulse);
                        rgb = mix(rgb, sampleWhip(vTexCoord, amount), 0.95 * amount);
                    } else if (uStyle > 15.5 && uStyle < 16.5) {
                        float flash = pow(max(0.0, pulse), 5.0);
                        rgb = mix(rgb, vec3(1.0), flash * 0.96);
                    } else if (uStyle > 16.5 && uStyle < 17.5 && outgoing > 0.5) {
                        alpha *= smoothstep(p - 0.035, p + 0.035, vTexCoord.x);
                    } else if (uStyle > 17.5 && uStyle < 18.5 && outgoing > 0.5) {
                        float radius = p * 0.76;
                        float dist = distance(vTexCoord, vec2(0.5));
                        alpha *= smoothstep(radius - 0.025, radius + 0.025, dist);
                    } else if (uStyle > 18.5 && uStyle < 19.5 && outgoing > 0.5) {
                        float radius = p * 0.52;
                        float dist = abs(vTexCoord.x - 0.5);
                        alpha *= smoothstep(radius - 0.025, radius + 0.025, dist);
                    } else if (uStyle > 19.5 && uStyle < 20.5) {
                        float leakX = mix(-0.25, 1.25, p);
                        float d = distance(vTexCoord, vec2(leakX, 0.48));
                        float leak = (1.0 - smoothstep(0.08, 0.72, d)) * max(0.0, pulse);
                        vec3 warm = vec3(1.0, 0.30, 0.04);
                        rgb += warm * leak * 0.72;
                        rgb = mix(rgb, vec3(1.0, 0.82, 0.58), leak * leak * 0.38);
                    } else if (uStyle > 1.5 && uStyle < 2.5) {
                        float amount = max(0.0, pulse) * 0.24;
                        rgb = mix(rgb, sampleBlur(vTexCoord, amount), amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
                }
            """
        }
    }
}
