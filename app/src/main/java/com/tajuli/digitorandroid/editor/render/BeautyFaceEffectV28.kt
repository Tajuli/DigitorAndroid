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
import com.tajuli.digitorandroid.editor.model.BeautyFaceGeometryV28
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.beautyStrengthsV28
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28

/** Shared GPU stage for stackable, face-aware portrait beauty layers. */
@UnstableApi
internal class BeautyFaceEffectV28 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): BeautyFaceEffectV28? =
            if (clip.beautyStrengthsV28().isIdentity) null else BeautyFaceEffectV28(clip, preview)
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
        useHighPrecisionColorComponents: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHighPrecisionColorComponents,
        /* texturePoolCapacity = */ 1,
    ) {
        private val faceTrack = BeautyFaceTrackStoreV28.load(context, clip)
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
                val strengths = currentClip.beautyStrengthsV28()
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                val geometry = faceTrack?.geometryAt(sourceUs)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                program.setFloatUniform("uSkinBright", strengths.skinBright)
                program.setFloatUniform("uSkinSmooth", strengths.skinSmooth)
                program.setFloatUniform("uPinkLip", strengths.pinkLip)
                program.setFloatUniform("uHairBrowDark", strengths.hairBrowDark)
                program.setFloatUniform("uEyePop", strengths.eyePop)
                setGeometry(geometry)
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

        private fun setGeometry(geometry: BeautyFaceGeometryV28?) {
            setRect("uFaceRect", geometry?.face)
            setRect("uLipRect", geometry?.lips)
            setRect("uLeftEyeRect", geometry?.leftEye)
            setRect("uRightEyeRect", geometry?.rightEye)
            setRect("uLeftBrowRect", geometry?.leftBrow)
            setRect("uRightBrowRect", geometry?.rightBrow)
            setRect("uHairRect", geometry?.hair)
        }

        private fun setRect(name: String, rect: BeautyRectV28?) {
            val r = rect?.normalized()
            program.setFloatsUniform(
                name,
                if (r == null) floatArrayOf(0f, 0f, 0f, 0f)
                else floatArrayOf(r.left, r.top, r.right, r.bottom),
            )
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
                uniform float uHasFace;
                uniform float uSkinBright;
                uniform float uSkinSmooth;
                uniform float uPinkLip;
                uniform float uHairBrowDark;
                uniform float uEyePop;
                uniform vec4 uFaceRect;
                uniform vec4 uLipRect;
                uniform vec4 uLeftEyeRect;
                uniform vec4 uRightEyeRect;
                uniform vec4 uLeftBrowRect;
                uniform vec4 uRightBrowRect;
                uniform vec4 uHairRect;
                varying vec2 vTexCoord;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chroma(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

                float ellipseMask(vec2 p, vec4 rect, float inner, float outer) {
                    vec2 halfSize = max((rect.zw - rect.xy) * .5, vec2(.0005));
                    vec2 center = (rect.xy + rect.zw) * .5;
                    vec2 q = (p - center) / halfSize;
                    float d = dot(q, q);
                    return 1.0 - smoothstep(inner, outer, d);
                }

                float skinProbability(vec3 c) {
                    float y = luma(c);
                    float cb = -.168736 * c.r - .331264 * c.g + .5 * c.b + .5;
                    float cr =  .5 * c.r - .418688 * c.g - .081312 * c.b + .5;
                    float cbBand = smoothstep(.20, .28, cb) * (1.0 - smoothstep(.55, .64, cb));
                    float crBand = smoothstep(.31, .38, cr) * (1.0 - smoothstep(.69, .78, cr));
                    float exposureGate = smoothstep(.035, .16, y);
                    return cbBand * crBand * exposureGate;
                }

                vec3 softSample(vec2 uv) {
                    vec2 o = uTexelSize * 1.8;
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

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uHasFace < .5) {
                        gl_FragColor = source;
                        return;
                    }

                    // Detector coordinates are Android top-left; GL texture coordinates are bottom-left.
                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                    vec3 rgb = source.rgb;
                    float face = ellipseMask(p, uFaceRect, .72, 1.08);
                    float lips = ellipseMask(p, uLipRect, .68, 1.18);
                    float leftEye = ellipseMask(p, uLeftEyeRect, .62, 1.20);
                    float rightEye = ellipseMask(p, uRightEyeRect, .62, 1.20);
                    float eyes = max(leftEye, rightEye);
                    float leftBrow = ellipseMask(p, uLeftBrowRect, .58, 1.15);
                    float rightBrow = ellipseMask(p, uRightBrowRect, .58, 1.15);
                    float brows = max(leftBrow, rightBrow);
                    float hairRegion = ellipseMask(p, uHairRect, .74, 1.12);

                    float skin = face * skinProbability(rgb) * (1.0 - clamp(lips + eyes * .90 + brows * .85, 0.0, 1.0));

                    if (uSkinSmooth > .001) {
                        vec3 soft = softSample(vTexCoord);
                        float textureProtect = 1.0 - smoothstep(.16, .48, abs(luma(rgb) - luma(soft)) * 4.0);
                        rgb = mix(rgb, soft, skin * textureProtect * clamp(uSkinSmooth, 0.0, 1.5) * .34);
                    }

                    if (uSkinBright > .001) {
                        vec3 lifted = pow(clamp(rgb, 0.0, 1.0), vec3(.90));
                        // Slightly warm-neutral lift avoids a chalky blue/gray complexion.
                        lifted += vec3(.040, .037, .031) * clamp(uSkinBright, 0.0, 1.5);
                        rgb = mix(rgb, clamp(lifted, 0.0, 1.0), skin * clamp(uSkinBright, 0.0, 1.5) * .58);
                    }

                    if (uPinkLip > .001) {
                        float lipLum = luma(rgb);
                        float teethReject = 1.0 - smoothstep(.68, .88, lipLum);
                        float lipMask = lips * teethReject;
                        vec3 pink = rgb;
                        pink.r = max(pink.r * 1.08 + .045, lipLum * 1.02 + .055);
                        pink.g = pink.g * .94 + .008;
                        pink.b = pink.b * 1.06 + .025;
                        rgb = mix(rgb, clamp(pink, 0.0, 1.0), lipMask * clamp(uPinkLip, 0.0, 1.5) * .62);
                    }

                    if (uHairBrowDark > .001) {
                        float y = luma(rgb);
                        float darkGate = 1.0 - smoothstep(.34, .68, y);
                        float neutralGate = 1.0 - smoothstep(.22, .52, chroma(rgb));
                        float hairMask = hairRegion * darkGate * neutralGate;
                        float browMask = brows * (1.0 - smoothstep(.48, .78, y));
                        float mask = clamp(hairMask + browMask, 0.0, 1.0);
                        float darken = clamp(uHairBrowDark, 0.0, 1.5) * .34;
                        rgb = mix(rgb, rgb * (1.0 - darken), mask);
                    }

                    if (uEyePop > .001) {
                        float amount = clamp(uEyePop, 0.0, 1.5);
                        float y = luma(rgb);
                        float c = chroma(rgb);
                        vec3 contrast = clamp((rgb - .5) * (1.0 + .22 * amount) + .5, 0.0, 1.0);
                        float whiteGate = smoothstep(.48, .76, y) * (1.0 - smoothstep(.13, .34, c));
                        vec3 eyeLook = mix(contrast, clamp(contrast + vec3(.055), 0.0, 1.0), whiteGate * .55 * amount);
                        float irisGate = 1.0 - smoothstep(.30, .58, y);
                        eyeLook = mix(eyeLook, eyeLook * (1.0 - .10 * amount), irisGate * .55);
                        rgb = mix(rgb, eyeLook, eyes * .72 * amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}
