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
 * Shared V22/V24 transition pixel stage. V22 style codes remain the compatibility fallback while
 * V24 preset codes add genuinely distinct looks for commonly used CapCut-style variants.
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
                val localUs = (ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs) - currentClip.sourceInUs)
                    .coerceAtLeast(0L)
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
                program.setFloatUniform("uPreset", TransitionPresetShaderV24.code(transition.presetIdV24))
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
                uniform float uPreset;
                varying vec2 vTexCoord;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(0.001), vec2(0.999)); }
                float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }

                vec3 sampleBlur(vec2 uv, float amount) {
                    vec2 o = uTexelSize * (1.0 + amount * 10.0);
                    vec3 c = texture2D(uTexSampler, safeUv(uv)).rgb * 4.0;
                    c += texture2D(uTexSampler, safeUv(uv + vec2( o.x, 0.0))).rgb * 2.0;
                    c += texture2D(uTexSampler, safeUv(uv + vec2(-o.x, 0.0))).rgb * 2.0;
                    c += texture2D(uTexSampler, safeUv(uv + vec2(0.0,  o.y))).rgb * 2.0;
                    c += texture2D(uTexSampler, safeUv(uv + vec2(0.0, -o.y))).rgb * 2.0;
                    c += texture2D(uTexSampler, safeUv(uv + vec2( o.x,  o.y))).rgb;
                    c += texture2D(uTexSampler, safeUv(uv + vec2(-o.x,  o.y))).rgb;
                    c += texture2D(uTexSampler, safeUv(uv + vec2( o.x, -o.y))).rgb;
                    c += texture2D(uTexSampler, safeUv(uv + vec2(-o.x, -o.y))).rgb;
                    return c / 16.0;
                }

                vec3 directionalBlur(vec2 uv, vec2 direction, float amount) {
                    vec2 o = direction * uTexelSize * (3.0 + amount * 35.0);
                    vec3 c = texture2D(uTexSampler, safeUv(uv)).rgb * 0.30;
                    c += texture2D(uTexSampler, safeUv(uv + o * .25)).rgb * .20;
                    c += texture2D(uTexSampler, safeUv(uv - o * .25)).rgb * .20;
                    c += texture2D(uTexSampler, safeUv(uv + o * .60)).rgb * .15;
                    c += texture2D(uTexSampler, safeUv(uv - o * .60)).rgb * .15;
                    return c;
                }

                vec3 radialBlur(vec2 uv, float amount) {
                    vec2 d = uv - vec2(.5);
                    vec3 c = texture2D(uTexSampler, safeUv(uv)).rgb * .34;
                    c += texture2D(uTexSampler, safeUv(uv - d * amount * .08)).rgb * .25;
                    c += texture2D(uTexSampler, safeUv(uv - d * amount * .16)).rgb * .20;
                    c += texture2D(uTexSampler, safeUv(uv - d * amount * .28)).rgb * .13;
                    c += texture2D(uTexSampler, safeUv(uv - d * amount * .42)).rgb * .08;
                    return c;
                }

                void main() {
                    vec4 center = texture2D(uTexSampler, safeUv(vTexCoord));
                    vec3 rgb = center.rgb;
                    float alpha = center.a;
                    float p = clamp(uProgress, 0.0, 1.0);
                    float outgoing = step(0.5, uOutgoing);
                    float pulse = max(0.0, sin(3.14159265 * p));

                    if (uPreset > 100.5) {
                        if (uPreset < 101.5) {
                            float a = outgoing > .5 ? p : 1.0 - p;
                            rgb = mix(rgb, sampleBlur(vTexCoord, a), .94 * a);
                        } else if (uPreset < 102.5) {
                            rgb = mix(rgb, directionalBlur(vTexCoord, vec2(1.0, .18), pulse), .96 * pulse);
                        } else if (uPreset < 103.5) {
                            rgb = mix(rgb, directionalBlur(vTexCoord, vec2(0.0, 1.0), pulse), .95 * pulse);
                        } else if (uPreset < 104.5) {
                            rgb = mix(rgb, directionalBlur(vTexCoord, vec2(1.0, 0.0), pulse), .95 * pulse);
                        } else if (uPreset < 105.5) {
                            rgb = mix(rgb, radialBlur(vTexCoord, pulse), .96 * pulse);
                        } else if (uPreset < 106.5) {
                            float f = pow(pulse, 4.0);
                            rgb = mix(rgb, vec3(1.0), f);
                        } else if (uPreset < 107.5) {
                            float f = pow(pulse, 7.0);
                            float vignette = 1.0 - smoothstep(.12, .72, distance(vTexCoord, vec2(.5)));
                            rgb = mix(rgb, vec3(1.0), clamp(f * (.60 + .55 * vignette), 0.0, 1.0));
                        } else if (uPreset < 108.5) {
                            float flicker = step(.48, fract(p * 10.0)) * pulse;
                            rgb = mix(rgb, vec3(1.0), flicker * .82);
                        } else if (uPreset < 109.5) {
                            vec2 flarePos = vec2(mix(-.15, 1.15, p), .42);
                            float d = distance(vTexCoord, flarePos);
                            float flare = (1.0 - smoothstep(.02, .42, d)) * pulse;
                            float ring = (1.0 - smoothstep(.015, .05, abs(d - .22))) * pulse;
                            rgb += vec3(1.0, .72, .34) * flare * .75 + vec3(.30, .55, 1.0) * ring * .18;
                        } else if (uPreset < 110.5) {
                            float n = rand(floor(vTexCoord * 42.0) + vec2(floor(p * 18.0)));
                            float edge = smoothstep(.0, .8, p + (n - .5) * .40);
                            vec3 burn = mix(vec3(.45, .02, .0), vec3(1.0, .48, .05), edge);
                            rgb = mix(rgb, burn, pulse * (.28 + .42 * n));
                            rgb += vec3(1.0, .82, .48) * pow(pulse, 5.0) * .35;
                        } else if (uPreset < 111.5) {
                            float band = floor(vTexCoord.y * 28.0);
                            float shift = (rand(vec2(band, floor(p * 20.0))) - .5) * .10 * pulse;
                            rgb = texture2D(uTexSampler, safeUv(vTexCoord + vec2(shift, 0.0))).rgb;
                        } else if (uPreset < 112.5) {
                            float shift = (.006 + .026 * pulse) * (outgoing > .5 ? 1.0 : -1.0);
                            float r = texture2D(uTexSampler, safeUv(vTexCoord + vec2( shift, 0.0))).r;
                            float g = texture2D(uTexSampler, safeUv(vTexCoord + vec2(0.0, shift * .28))).g;
                            float b = texture2D(uTexSampler, safeUv(vTexCoord - vec2( shift, 0.0))).b;
                            rgb = vec3(r, g, b);
                        } else if (uPreset < 113.5) {
                            float row = floor(vTexCoord.y * 44.0);
                            float block = floor(vTexCoord.x * 18.0);
                            float n = rand(vec2(row + floor(p * 30.0), block));
                            float shift = (n - .5) * .16 * step(.62, n) * pulse;
                            vec2 uv = vTexCoord + vec2(shift, (rand(vec2(block, row)) - .5) * .012 * pulse);
                            rgb = texture2D(uTexSampler, safeUv(uv)).rgb;
                            rgb *= .86 + .28 * step(.50, fract(row * .37 + p * 13.0));
                        } else if (uPreset < 114.5) {
                            vec2 uv = vTexCoord;
                            uv.x += sin((uv.y + p) * 22.0) * .026 * pulse;
                            uv.y += sin((uv.x - p) * 17.0) * .018 * pulse;
                            rgb = texture2D(uTexSampler, safeUv(uv)).rgb;
                        } else if (uPreset < 115.5) {
                            vec2 d = vTexCoord - vec2(.5);
                            float radius = length(d);
                            vec2 uv = vec2(.5) + d * (1.0 + sin(radius * 22.0 - p * 8.0) * .11 * pulse);
                            rgb = texture2D(uTexSampler, safeUv(uv)).rgb;
                        } else if (uPreset < 116.5) {
                            float edge = mix(1.0, 0.0, p);
                            float shadow = 1.0 - smoothstep(.0, .18, abs(vTexCoord.x - edge));
                            rgb *= 1.0 - shadow * .38 * pulse;
                            rgb += vec3(1.0, .92, .72) * shadow * .08;
                        } else if (uPreset < 117.5) {
                            if (outgoing > .5) {
                                float diagonal = (vTexCoord.x + vTexCoord.y) * .5;
                                alpha *= smoothstep(p - .045, p + .045, diagonal);
                            }
                        } else if (uPreset < 118.5) {
                            float beat = pow(abs(sin(p * 3.14159265 * 6.0)), 12.0) * pulse;
                            rgb = mix(rgb, vec3(1.0), beat * .92);
                            rgb = mix(rgb, radialBlur(vTexCoord, beat), beat * .35);
                        } else if (uPreset < 119.5) {
                            float amount = pulse;
                            float shift = .008 * amount;
                            vec3 rb = radialBlur(vTexCoord, amount);
                            float r = texture2D(uTexSampler, safeUv(vTexCoord + vec2(shift, 0.0))).r;
                            float b = texture2D(uTexSampler, safeUv(vTexCoord - vec2(shift, 0.0))).b;
                            rgb = mix(rgb, vec3(r, rb.g, b), .68 * amount);
                        } else if (uPreset < 120.5) {
                            rgb = mix(rgb, radialBlur(vTexCoord, pulse * .65), pulse * .62);
                        }
                    } else if (uStyle > 2.5 && uStyle < 3.5) {
                        float amount = outgoing > 0.5 ? smoothstep(0.0, 0.58, p) : 1.0 - smoothstep(0.42, 1.0, p);
                        rgb = mix(rgb, vec3(0.0), amount);
                    } else if (uStyle > 3.5 && uStyle < 4.5) {
                        float amount = outgoing > 0.5 ? smoothstep(0.0, 0.58, p) : 1.0 - smoothstep(0.42, 1.0, p);
                        rgb = mix(rgb, vec3(1.0), amount);
                    } else if (uStyle > 12.5 && uStyle < 13.5) {
                        float amount = outgoing > 0.5 ? p : (1.0 - p);
                        rgb = mix(rgb, sampleBlur(vTexCoord, amount), 0.92 * amount);
                    } else if (uStyle > 13.5 && uStyle < 14.5) {
                        rgb = mix(rgb, directionalBlur(vTexCoord, vec2(1.0, 0.0), pulse), 0.95 * pulse);
                    } else if (uStyle > 15.5 && uStyle < 16.5) {
                        float flash = pow(pulse, 5.0);
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
                        float leak = (1.0 - smoothstep(0.08, 0.72, d)) * pulse;
                        vec3 warm = vec3(1.0, 0.30, 0.04);
                        rgb += warm * leak * 0.72;
                        rgb = mix(rgb, vec3(1.0, 0.82, 0.58), leak * leak * 0.38);
                    } else if (uStyle > 1.5 && uStyle < 2.5) {
                        float amount = pulse * 0.24;
                        rgb = mix(rgb, sampleBlur(vTexCoord, amount), amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
                }
            """
        }
    }
}
