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
import com.tajuli.digitorandroid.editor.model.QualifierFinesseKeys
import com.tajuli.digitorandroid.editor.model.qualifierFinesse
import kotlin.math.max

/**
 * Neighborhood-aware qualifier pre-filter.
 *
 * A 3D color LUT can soften in H/S/L space, but it cannot see neighboring pixels, so flat walls
 * and compression noise can still reveal islands/steps at the key boundary. This pass only blends
 * pixels where the local qualifier matte changes across the neighborhood. Areas fully inside or
 * outside the matte stay untouched, so the full frame is not globally blurred.
 */
@UnstableApi
internal class QualifierSpatialFeatherEffect private constructor(
    private val params: Params,
) : GlEffect {

    data class Params(
        val hueCenterDegrees: Float,
        val hueWidthDegrees: Float,
        val hueSoftness: Float,
        val saturationMin: Float,
        val saturationMax: Float,
        val saturationLowSoftness: Float,
        val saturationHighSoftness: Float,
        val luminanceMin: Float,
        val luminanceMax: Float,
        val luminanceLowSoftness: Float,
        val luminanceHighSoftness: Float,
        val radiusPx: Float,
        val strength: Float,
    )

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        QualifierSpatialFeatherShaderProgram(params, useHdr)

    companion object {
        fun fromNode(node: ColorNode): QualifierSpatialFeatherEffect? {
            val q = node.advancedColor.qualifier
            if (!q.enabled) return null

            val satLow = node.qualifierFinesse(QualifierFinesseKeys.SAT_LOW_SOFT, .08f).coerceIn(0f, 1f)
            val satHigh = node.qualifierFinesse(QualifierFinesseKeys.SAT_HIGH_SOFT, .08f).coerceIn(0f, 1f)
            val lumLow = node.qualifierFinesse(QualifierFinesseKeys.LUM_LOW_SOFT, .08f).coerceIn(0f, 1f)
            val lumHigh = node.qualifierFinesse(QualifierFinesseKeys.LUM_HIGH_SOFT, .08f).coerceIn(0f, 1f)
            val blurRadius = node.qualifierFinesse(QualifierFinesseKeys.BLUR_RADIUS, 0f).coerceIn(0f, 10f)
            val softness = max(q.softness.coerceIn(0f, 1f), max(max(satLow, satHigh), max(lumLow, lumHigh)))

            // Softness itself now creates a visible spatial feather; Blur Radius extends it further.
            val radiusPx = (1f + softness * 8f + blurRadius * 1.2f).coerceIn(1f, 18f)
            val strength = (.18f + softness * .70f + blurRadius / 10f * .12f).coerceIn(.18f, .95f)

            return QualifierSpatialFeatherEffect(
                Params(
                    hueCenterDegrees = q.hueCenterDegrees,
                    hueWidthDegrees = q.hueWidthDegrees,
                    hueSoftness = q.softness.coerceIn(0f, 1f),
                    saturationMin = q.saturationMin,
                    saturationMax = q.saturationMax,
                    saturationLowSoftness = satLow,
                    saturationHighSoftness = satHigh,
                    luminanceMin = q.luminanceMin,
                    luminanceMax = q.luminanceMax,
                    luminanceLowSoftness = lumLow,
                    luminanceHighSoftness = lumHigh,
                    radiusPx = radiusPx,
                    strength = strength,
                ),
            )
        }
    }
}

@UnstableApi
private class QualifierSpatialFeatherShaderProgram(
    private val params: QualifierSpatialFeatherEffect.Params,
    useHdr: Boolean,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ useHdr,
    /* texturePoolCapacity = */ 1,
) {
    private val glProgram: GlProgram
    private var inputWidth = 1
    private var inputHeight = 1

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
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
            )
            glProgram.setFloatUniform("uHueCenter", params.hueCenterDegrees)
            glProgram.setFloatUniform("uHueWidth", params.hueWidthDegrees)
            glProgram.setFloatUniform("uHueSoft", params.hueSoftness)
            glProgram.setFloatUniform("uSatMin", params.saturationMin)
            glProgram.setFloatUniform("uSatMax", params.saturationMax)
            glProgram.setFloatUniform("uSatLowSoft", params.saturationLowSoftness)
            glProgram.setFloatUniform("uSatHighSoft", params.saturationHighSoftness)
            glProgram.setFloatUniform("uLumMin", params.luminanceMin)
            glProgram.setFloatUniform("uLumMax", params.luminanceMax)
            glProgram.setFloatUniform("uLumLowSoft", params.luminanceLowSoftness)
            glProgram.setFloatUniform("uLumHighSoft", params.luminanceHighSoftness)
            glProgram.setFloatUniform("uRadiusPx", params.radiusPx)
            glProgram.setFloatUniform("uStrength", params.strength)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
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
            uniform float uHueCenter;
            uniform float uHueWidth;
            uniform float uHueSoft;
            uniform float uSatMin;
            uniform float uSatMax;
            uniform float uSatLowSoft;
            uniform float uSatHighSoft;
            uniform float uLumMin;
            uniform float uLumMax;
            uniform float uLumLowSoft;
            uniform float uLumHighSoft;
            uniform float uRadiusPx;
            uniform float uStrength;
            varying vec2 vTexCoord;

            float smoother(float a, float b, float x) {
                if (abs(b - a) < 0.000001) return x < a ? 0.0 : 1.0;
                float t = clamp((x - a) / (b - a), 0.0, 1.0);
                return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
            }

            vec3 rgbToHsl(vec3 c) {
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float l = (mx + mn) * 0.5;
                float d = mx - mn;
                if (d < 0.000001) return vec3(0.0, 0.0, l);
                float s = l > 0.5 ? d / max(0.000001, 2.0 - mx - mn) : d / max(0.000001, mx + mn);
                float h;
                if (mx == c.r) h = ((c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0)) / 6.0;
                else if (mx == c.g) h = ((c.b - c.r) / d + 2.0) / 6.0;
                else h = ((c.r - c.g) / d + 4.0) / 6.0;
                return vec3(h, s, l);
            }

            float rangeMask(float v, float lo, float hi, float lowSoft, float highSoft) {
                lo = clamp(min(lo, hi), 0.0, 1.0);
                hi = clamp(max(lo, hi), 0.0, 1.0);
                float lowMask = 1.0;
                if (lo > 0.0001 && v < lo) {
                    float feather = min(clamp(lowSoft, 0.0, 1.0), lo);
                    lowMask = feather <= 0.0001 ? 0.0 : smoother(lo - feather, lo, v);
                }
                float highMask = 1.0;
                if (hi < 0.9999 && v > hi) {
                    float feather = min(clamp(highSoft, 0.0, 1.0), 1.0 - hi);
                    highMask = feather <= 0.0001 ? 0.0 : 1.0 - smoother(hi, hi + feather, v);
                }
                return min(lowMask, highMask);
            }

            float hueMask(float hueDeg, float sat) {
                float width = clamp(uHueWidth, 1.0, 360.0);
                if (width >= 359.999) return 1.0;
                float delta = mod(hueDeg - uHueCenter + 540.0, 360.0) - 180.0;
                float hard = width * 0.5;
                float distance = abs(delta);
                float raw;
                if (distance <= hard) raw = 1.0;
                else {
                    float feather = min(clamp(uHueSoft, 0.0, 1.0) * 180.0, max(0.0, 180.0 - hard));
                    raw = feather <= 0.0001 || distance >= hard + feather
                        ? 0.0
                        : 1.0 - smoother(hard, hard + feather, distance);
                }
                float reliability = smoother(0.035, 0.18, sat);
                return mix(1.0, raw, reliability);
            }

            float keyMask(vec3 rgb) {
                vec3 hsl = rgbToHsl(clamp(rgb, 0.0, 1.0));
                float h = hueMask(hsl.x * 360.0, hsl.y);
                float s = rangeMask(hsl.y, uSatMin, uSatMax, uSatLowSoft, uSatHighSoft);
                float l = rangeMask(hsl.z, uLumMin, uLumMax, uLumLowSoft, uLumHighSoft);
                return min(h, min(s, l));
            }

            void main() {
                vec2 o = uTexelSize * uRadiusPx;
                vec4 c  = texture2D(uTexSampler, vTexCoord);
                vec4 l  = texture2D(uTexSampler, vTexCoord + vec2(-o.x, 0.0));
                vec4 r  = texture2D(uTexSampler, vTexCoord + vec2( o.x, 0.0));
                vec4 u  = texture2D(uTexSampler, vTexCoord + vec2(0.0,  o.y));
                vec4 d  = texture2D(uTexSampler, vTexCoord + vec2(0.0, -o.y));
                vec4 ul = texture2D(uTexSampler, vTexCoord + vec2(-o.x,  o.y));
                vec4 ur = texture2D(uTexSampler, vTexCoord + vec2( o.x,  o.y));
                vec4 dl = texture2D(uTexSampler, vTexCoord + vec2(-o.x, -o.y));
                vec4 dr = texture2D(uTexSampler, vTexCoord + vec2( o.x, -o.y));

                float mc = keyMask(c.rgb);
                float ml = keyMask(l.rgb);
                float mr = keyMask(r.rgb);
                float mu = keyMask(u.rgb);
                float md = keyMask(d.rgb);
                float mul = keyMask(ul.rgb);
                float mur = keyMask(ur.rgb);
                float mdl = keyMask(dl.rgb);
                float mdr = keyMask(dr.rgb);
                float minM = min(mc, min(min(ml, mr), min(min(mu, md), min(min(mul, mur), min(mdl, mdr)))));
                float maxM = max(mc, max(max(ml, mr), max(max(mu, md), max(max(mul, mur), max(mdl, mdr)))));
                float matteEdge = smoother(0.015, 0.30, maxM - minM);

                vec3 neighborhood = (
                    c.rgb * 4.0 +
                    (l.rgb + r.rgb + u.rgb + d.rgb) * 2.0 +
                    ul.rgb + ur.rgb + dl.rgb + dr.rgb
                ) / 16.0;

                // Only soften where the qualifier matte actually changes. Fully selected/unselected
                // regions remain pixel-identical to the input.
                float amount = matteEdge * uStrength;
                gl_FragColor = vec4(mix(c.rgb, neighborhood, amount), c.a);
            }
        """
    }
}
