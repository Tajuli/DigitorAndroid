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
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * V46 realism pass applied immediately after the portrait cutout matte.
 *
 * The incoming alpha already contains MODNet + semantic hair + V45 spatial-flow refinement. This
 * pass does not try to segment the person again. Instead it uses the original foreground RGB that
 * remains in the cutout texture as guidance for a compact joint-bilateral alpha correction. That
 * makes cloth/hijab boundaries follow visible source edges while preserving genuine soft/motion-
 * blurred edges rather than turning them into a hard sticker silhouette.
 *
 * The second half is a texture-preserving decontamination pass. It corrects mostly chroma at the
 * outer translucent edge and changes luminance only slightly, so fabric folds/shadows stay visible
 * instead of being painted with one interior colour.
 */
@UnstableApi
internal class FabricAwareCutoutRefineV46 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): FabricAwareCutoutRefineV46? =
            if (clip.resolvedCutoutV43().mode == CutoutModeV43.PERSON) {
                FabricAwareCutoutRefineV46(clip, preview)
            } else {
                null
            }
    }

    private class Program(
        private val snapshotClip: TimelineClip,
        private val preview: Boolean,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHdr,
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
                val clip = if (preview) PreviewProjectRegistry.clip(snapshotClip.id) ?: snapshotClip else snapshotClip
                val settings = clip.resolvedCutoutV43()
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uDehalo", settings.dehaloV44)
                program.setFloatUniform("uEdgeClean", settings.edgeCleanV44)
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
                uniform float uDehalo;
                uniform float uEdgeClean;
                varying vec2 vTexCoord;

                float colorWeight(vec3 center, vec3 sampleRgb, float spatialWeight) {
                    vec3 delta = center - sampleRgb;
                    float dist2 = dot(delta, delta);
                    // Rational approximation is cheaper and less fragile than exp() on older GLES
                    // drivers while behaving like a bilateral colour kernel.
                    return spatialWeight / (1.0 + 22.0 * dist2);
                }

                void accumulateSample(
                    inout float alphaSum,
                    inout float weightSum,
                    vec3 centerRgb,
                    vec2 offset,
                    float spatialWeight
                ) {
                    vec4 samplePx = texture2D(
                        uTexSampler,
                        clamp(vTexCoord + offset * uTexelSize, vec2(0.0), vec2(1.0))
                    );
                    float w = colorWeight(centerRgb, samplePx.rgb, spatialWeight);
                    alphaSum += samplePx.a * w;
                    weightSum += w;
                }

                vec3 texturePreservingDehalo(vec3 sourceRgb, float alpha) {
                    float uncertainty = clamp(4.0 * alpha * (1.0 - alpha), 0.0, 1.0);
                    // Decontaminate the exterior half of the soft boundary. Inner cloth pixels keep
                    // their original fold/shadow colours.
                    float outer = 1.0 - smoothstep(0.56, 0.88, alpha);
                    float edge = uncertainty * outer;
                    if (edge < 0.001 || uDehalo < 0.001) return sourceRgb;

                    float leftA = texture2D(uTexSampler, vTexCoord - vec2(uTexelSize.x * 2.0, 0.0)).a;
                    float rightA = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x * 2.0, 0.0)).a;
                    float downA = texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexelSize.y * 2.0)).a;
                    float upA = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y * 2.0)).a;
                    vec2 grad = vec2(rightA - leftA, upA - downA);
                    float gradLength = length(grad);
                    if (gradLength < 0.001) return sourceRgb;

                    vec2 inward = grad / gradLength;
                    vec2 interiorUv = clamp(
                        vTexCoord + inward * uTexelSize * (2.0 + 1.5 * uDehalo),
                        vec2(0.0),
                        vec2(1.0)
                    );
                    vec3 interiorRgb = texture2D(uTexSampler, interiorUv).rgb;
                    float rgbDistance = distance(sourceRgb, interiorRgb);
                    float contamination = smoothstep(0.025, 0.20, rgbDistance);
                    float w = clamp(uDehalo, 0.0, 1.0) * edge * contamination * 0.54;
                    if (w <= 0.0001) return sourceRgb;

                    // Preserve luminance texture (fabric folds, shadows and motion blur), while
                    // restoring more of the reliable interior foreground chroma.
                    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
                    float sourceY = dot(sourceRgb, LUMA);
                    float interiorY = dot(interiorRgb, LUMA);
                    float sourceCb = sourceRgb.b - sourceY;
                    float sourceCr = sourceRgb.r - sourceY;
                    float interiorCb = interiorRgb.b - interiorY;
                    float interiorCr = interiorRgb.r - interiorY;

                    float outY = mix(sourceY, interiorY, w * 0.16);
                    float outCb = mix(sourceCb, interiorCb, w * 0.68);
                    float outCr = mix(sourceCr, interiorCr, w * 0.68);
                    float outR = outY + outCr;
                    float outB = outY + outCb;
                    float outG = (outY - 0.2126 * outR - 0.0722 * outB) / 0.7152;
                    return clamp(vec3(outR, outG, outB), 0.0, 1.0);
                }

                void main() {
                    vec4 center = texture2D(uTexSampler, vTexCoord);
                    float alpha = center.a;
                    if (alpha <= 0.002 || alpha >= 0.998) {
                        gl_FragColor = center;
                        return;
                    }

                    float alphaSum = alpha;
                    float weightSum = 1.0;
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2(-1.0, 0.0), 0.82);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2( 1.0, 0.0), 0.82);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2(0.0, -1.0), 0.82);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2(0.0,  1.0), 0.82);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2(-1.0, -1.0), 0.58);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2( 1.0, -1.0), 0.58);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2(-1.0,  1.0), 0.58);
                    accumulateSample(alphaSum, weightSum, center.rgb, vec2( 1.0,  1.0), 0.58);
                    float guidedAlpha = alphaSum / max(weightSum, 0.0001);

                    vec3 leftRgb = texture2D(uTexSampler, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 rightRgb = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
                    vec3 downRgb = texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
                    vec3 upRgb = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
                    float sourceEdge = max(distance(leftRgb, rightRgb), distance(downRgb, upRgb));
                    float visibleEdge = smoothstep(0.018, 0.16, sourceEdge);
                    float uncertainty = clamp(4.0 * alpha * (1.0 - alpha), 0.0, 1.0);

                    // Real source edges receive more guided correction. Low-contrast motion blur is
                    // deliberately corrected less so it remains naturally translucent.
                    float strength = (0.24 + 0.34 * visibleEdge) * uncertainty;
                    strength *= 0.88 - 0.18 * clamp(uEdgeClean, 0.0, 1.0);
                    float limitedGuided = clamp(guidedAlpha, alpha - 0.17, alpha + 0.17);
                    float refinedAlpha = mix(alpha, limitedGuided, clamp(strength, 0.0, 0.58));

                    vec3 refinedRgb = texturePreservingDehalo(center.rgb, refinedAlpha);
                    gl_FragColor = vec4(refinedRgb, clamp(refinedAlpha, 0.0, 1.0));
                }
            """
        }
    }
}
