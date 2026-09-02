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
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.combinedCreatorLookV36
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * Persistent V36 creator-look stage.
 *
 * The stage is always present in realtime preview, even when no filter is selected. Filter taps only
 * change lightweight NodeEffect markers, and this shader reads the newest immutable clip snapshot on
 * every frame. That avoids MediaCodec/GL graph recreation and makes a newly selected look visible on
 * the very next submitted frame. Export uses the same shader and combined recipe.
 *
 * Multiple look markers are collapsed on the CPU into one bounded recipe. A single selected look is
 * exact; stacked looks are composed additively in one high-precision pass to keep creator UX fast and
 * avoid repeated full-frame GPU passes.
 */
@UnstableApi
internal class CreatorLookStackEffectV36 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): CreatorLookStackEffectV36? {
            val active = clip.combinedCreatorLookV36().strength > .001f
            return if (preview || active) CreatorLookStackEffectV36(clip, preview) else null
        }
    }

    private class Program(
        private val clip: TimelineClip,
        private val preview: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ true,
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
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val look = currentClip.combinedCreatorLookV36()
                val c = look.corrections
                val log = look.log

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uExposure", c.exposure)
                program.setFloatUniform("uContrast", c.contrast / 100f)
                program.setFloatUniform("uSaturation", c.saturation / 100f)
                program.setFloatUniform("uTemperature", c.temperature / 100f)
                program.setFloatUniform("uTint", c.tint / 100f)
                program.setFloatUniform("uHighlights", c.highlights / 100f)
                program.setFloatUniform("uShadows", c.shadows / 100f)
                program.setFloatUniform("uHueTurns", c.hue / 360f)
                program.setFloatUniform("uColorBoost", c.colorBoost / 100f)
                program.setFloatsUniform("uLogShadows", log.shadows.uniformV36())
                program.setFloatsUniform("uLogMidtones", log.midtones.uniformV36())
                program.setFloatsUniform("uLogHighlights", log.highlights.uniformV36())
                program.setFloatsUniform("uLogGlobal", log.global.uniformV36())
                program.setFloatUniform("uShadowRange", log.shadowRange)
                program.setFloatUniform("uHighlightRange", log.highlightRange)
                program.setFloatUniform("uLookStrength", look.strength)
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
                uniform float uExposure;
                uniform float uContrast;
                uniform float uSaturation;
                uniform float uTemperature;
                uniform float uTint;
                uniform float uHighlights;
                uniform float uShadows;
                uniform float uHueTurns;
                uniform float uColorBoost;
                uniform vec4 uLogShadows;
                uniform vec4 uLogMidtones;
                uniform vec4 uLogHighlights;
                uniform vec4 uLogGlobal;
                uniform float uShadowRange;
                uniform float uHighlightRange;
                uniform float uLookStrength;
                varying vec2 vTexCoord;

                const float PI2 = 6.28318530718;
                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chromaSpan(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

                float softShoulder(float y) {
                    if (y <= .70) return y;
                    float d = y - .70;
                    return .70 + d / (1.0 + d * 3.0);
                }

                float chromaFitScale(vec3 chromaVector, float targetY) {
                    float fit = 1.0;
                    if (chromaVector.r > .0001) fit = min(fit, (1.0 - targetY) / chromaVector.r);
                    if (chromaVector.g > .0001) fit = min(fit, (1.0 - targetY) / chromaVector.g);
                    if (chromaVector.b > .0001) fit = min(fit, (1.0 - targetY) / chromaVector.b);
                    if (chromaVector.r < -.0001) fit = min(fit, targetY / -chromaVector.r);
                    if (chromaVector.g < -.0001) fit = min(fit, targetY / -chromaVector.g);
                    if (chromaVector.b < -.0001) fit = min(fit, targetY / -chromaVector.b);
                    return clamp(fit, 0.0, 1.0);
                }

                vec3 withLumaPreserveHue(vec3 c, float targetY) {
                    float y = luma(c);
                    vec3 chromaVector = c - vec3(y);
                    return vec3(targetY) + chromaVector * chromaFitScale(chromaVector, targetY);
                }

                vec3 fitToGamutPreserveHue(vec3 c) {
                    float rawY = luma(c);
                    float y = clamp(rawY, 0.0, 1.0);
                    vec3 chromaVector = c - vec3(rawY);
                    return vec3(y) + chromaVector * chromaFitScale(chromaVector, y);
                }

                vec3 hueRotateYiq(vec3 c, float turns) {
                    if (abs(turns) < .00001) return c;
                    float y = dot(c, vec3(.299, .587, .114));
                    float i = dot(c, vec3(.596, -.274, -.322));
                    float q = dot(c, vec3(.211, -.523, .312));
                    float a = turns * PI2;
                    float cs = cos(a);
                    float sn = sin(a);
                    float ir = i * cs - q * sn;
                    float qr = i * sn + q * cs;
                    return vec3(
                        y + .956 * ir + .621 * qr,
                        y - .272 * ir - .647 * qr,
                        y - 1.106 * ir + 1.703 * qr
                    );
                }

                float skinProbability(vec3 c) {
                    float y = luma(c);
                    float cb = -.168736 * c.r - .331264 * c.g + .5 * c.b + .5;
                    float cr = .5 * c.r - .418688 * c.g - .081312 * c.b + .5;
                    float cbBand = smoothstep(.245, .305, cb) * (1.0 - smoothstep(.515, .585, cb));
                    float crBand = smoothstep(.345, .405, cr) * (1.0 - smoothstep(.66, .725, cr));
                    return cbBand * crBand * smoothstep(.06, .17, y) * (1.0 - smoothstep(.93, 1.0, y));
                }

                float microDetail(vec2 uv, vec3 center) {
                    vec2 o = uTexelSize * 1.35;
                    float yc = luma(center);
                    float yx1 = luma(texture2D(uTexSampler, safeUv(uv + vec2(o.x, 0.0))).rgb);
                    float yx2 = luma(texture2D(uTexSampler, safeUv(uv - vec2(o.x, 0.0))).rgb);
                    float yy1 = luma(texture2D(uTexSampler, safeUv(uv + vec2(0.0, o.y))).rgb);
                    float yy2 = luma(texture2D(uTexSampler, safeUv(uv - vec2(0.0, o.y))).rgb);
                    float blurY = yc * .44 + (yx1 + yx2 + yy1 + yy2) * .14;
                    return yc - blurY;
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uLookStrength <= .0001) {
                        gl_FragColor = source;
                        return;
                    }

                    vec3 src = source.rgb;
                    vec3 rgb = src;

                    float y = luma(rgb) * exp2(uExposure);
                    y = mix(y, softShoulder(y), clamp(.20 + uLookStrength * .42, 0.0, .64));
                    y = (y - .5) * max(0.0, 1.0 + uContrast) + .5;
                    float highW = smoothstep(.55, .96, y);
                    float shadowW = 1.0 - smoothstep(.04, .46, y);
                    y += uHighlights * .20 * highW + uShadows * .20 * shadowW;
                    y = clamp(y, 0.0, 1.0);
                    rgb = withLumaPreserveHue(rgb, y);

                    float warm = clamp(uTemperature, -1.0, 1.0);
                    float tint = clamp(uTint, -1.0, 1.0);
                    rgb *= vec3(
                        1.0 + warm * .12 + tint * .04,
                        1.0 - tint * .05,
                        1.0 - warm * .12 + tint * .04
                    );
                    rgb = hueRotateYiq(rgb, uHueTurns);

                    float yc = luma(rgb);
                    float span = chromaSpan(rgb);
                    float vibrance = 1.0 + uColorBoost * (1.0 - smoothstep(.08, .55, span));
                    rgb = vec3(yc) + (rgb - vec3(yc)) * max(0.0, 1.0 + uSaturation) * vibrance;

                    float logY = clamp(luma(rgb), 0.0, 1.0);
                    float sw = 1.0 - smoothstep(max(0.0, uShadowRange - .12), min(1.0, uShadowRange + .12), logY);
                    float hw = smoothstep(max(0.0, uHighlightRange - .12), min(1.0, uHighlightRange + .12), logY);
                    float mw = clamp(1.0 - sw - hw, 0.0, 1.0);
                    vec3 wheelDelta =
                        sw * (uLogShadows.rgb + vec3(uLogShadows.a)) +
                        mw * (uLogMidtones.rgb + vec3(uLogMidtones.a)) +
                        hw * (uLogHighlights.rgb + vec3(uLogHighlights.a)) +
                        uLogGlobal.rgb + vec3(uLogGlobal.a);
                    rgb += wheelDelta * .22;
                    rgb = fitToGamutPreserveHue(rgb);

                    // Positive portrait looks lift skin midtones more than an already-white wall.
                    // This is the key response visible in the supplied CapCut reference.
                    float skin = skinProbability(src);
                    float portraitLiftSignal = clamp(
                        max(uExposure, 0.0) * 2.8 + max(uShadows, 0.0) * .8 + max(-uHighlights, 0.0) * .25,
                        0.0,
                        1.0
                    );
                    float skinTargetY = min(luma(rgb) + skin * portraitLiftSignal * .035 * (1.0 - luma(rgb)), .965);
                    rgb = withLumaPreserveHue(rgb, skinTargetY);

                    // Pull excessive creator chroma back toward source hue on likely skin while
                    // preserving the tone move.
                    vec3 toneOnly = withLumaPreserveHue(src, luma(rgb));
                    rgb = mix(rgb, toneOnly, skin * mix(.16, .34, uLookStrength));

                    float detail = microDetail(vTexCoord, src);
                    float edgeGate = 1.0 - smoothstep(.045, .16, abs(detail));
                    float highlightGate = 1.0 - smoothstep(.64, .96, luma(src));
                    float targetY = clamp(
                        luma(rgb) + detail * mix(.06, .15, uLookStrength) * edgeGate * highlightGate,
                        0.0,
                        1.0
                    );
                    rgb = fitToGamutPreserveHue(withLumaPreserveHue(rgb, targetY));

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}

private fun ColorWheelValue.uniformV36(): FloatArray = floatArrayOf(red, green, blue, luma)
