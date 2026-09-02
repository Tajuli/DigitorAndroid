package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
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
import com.tajuli.digitorandroid.editor.processing.BeautyHairMaskFrameV29
import com.tajuli.digitorandroid.editor.processing.BeautyHairMaskStoreV29

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
        private val appContext = context.applicationContext
        private val faceTrack = BeautyFaceTrackStoreV28.load(appContext, clip)
        private val program: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private var hairMaskTexture = 0
        private var loadedHairMaskPath: String? = null

        init {
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { shader ->
                    shader.setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
                ensureHairMaskTexture()
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
                val hairFrame = if (strengths.hairBrowDark > .001f) {
                    BeautyHairMaskStoreV29.index(appContext, currentClip).nearest(sourceUs)
                } else {
                    null
                }
                val hasHairMask = bindHairMask(hairFrame)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uHairMask", hairMaskTexture, 1)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                program.setFloatUniform("uHasHairMask", if (hasHairMask) 1f else 0f)
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
        }

        private fun setRect(name: String, rect: BeautyRectV28?) {
            val r = rect?.normalized()
            program.setFloatsUniform(
                name,
                if (r == null) floatArrayOf(0f, 0f, 0f, 0f)
                else floatArrayOf(r.left, r.top, r.right, r.bottom),
            )
        }

        private fun ensureHairMaskTexture() {
            if (hairMaskTexture != 0) return
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            hairMaskTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hairMaskTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            uploadHairMaskBitmap(null)
        }

        private fun bindHairMask(frame: BeautyHairMaskFrameV29?): Boolean {
            ensureHairMaskTexture()
            val path = frame?.file?.takeIf { it.isFile }?.absolutePath
            if (path == null) {
                if (loadedHairMaskPath != null) {
                    uploadHairMaskBitmap(null)
                    loadedHairMaskPath = null
                }
                return false
            }
            if (loadedHairMaskPath == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadHairMaskBitmap(bitmap)
                loadedHairMaskPath = path
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun uploadHairMaskBitmap(bitmap: Bitmap?) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hairMaskTexture)
            if (bitmap != null) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                val black = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                try {
                    black.eraseColor(android.graphics.Color.BLACK)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, black, 0)
                } finally {
                    black.recycle()
                }
            }
        }

        override fun release() {
            super.release()
            try {
                if (hairMaskTexture != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(hairMaskTexture), 0)
                    hairMaskTexture = 0
                }
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
                uniform sampler2D uHairMask;
                uniform vec2 uTexelSize;
                uniform float uHasFace;
                uniform float uHasHairMask;
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
                    if (uHasFace < .5 && uHasHairMask < .5) {
                        gl_FragColor = source;
                        return;
                    }

                    // Feature geometry and generated hair-mask PNGs use Android top-left coordinates.
                    // The mask texture is uploaded without a vertical flip, so p samples it directly.
                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                    vec3 rgb = source.rgb;
                    float face = uHasFace > .5 ? ellipseMask(p, uFaceRect, .72, 1.08) : 0.0;
                    float lips = uHasFace > .5 ? ellipseMask(p, uLipRect, .68, 1.18) : 0.0;
                    float leftEye = uHasFace > .5 ? ellipseMask(p, uLeftEyeRect, .62, 1.20) : 0.0;
                    float rightEye = uHasFace > .5 ? ellipseMask(p, uRightEyeRect, .62, 1.20) : 0.0;
                    float eyes = max(leftEye, rightEye);
                    float leftBrow = uHasFace > .5 ? ellipseMask(p, uLeftBrowRect, .58, 1.15) : 0.0;
                    float rightBrow = uHasFace > .5 ? ellipseMask(p, uRightBrowRect, .58, 1.15) : 0.0;
                    float brows = max(leftBrow, rightBrow);
                    float semanticHair = uHasHairMask > .5 ? texture2D(uHairMask, safeUv(p)).r : 0.0;

                    float skin = face * skinProbability(rgb) * (1.0 - clamp(lips + eyes * .90 + brows * .85, 0.0, 1.0));

                    if (uSkinSmooth > .001) {
                        vec3 soft = softSample(vTexCoord);
                        float textureProtect = 1.0 - smoothstep(.16, .48, abs(luma(rgb) - luma(soft)) * 4.0);
                        rgb = mix(rgb, soft, skin * textureProtect * clamp(uSkinSmooth, 0.0, 1.5) * .34);
                    }

                    if (uSkinBright > .001) {
                        vec3 lifted = pow(clamp(rgb, 0.0, 1.0), vec3(.90));
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
                        float amount = clamp(uHairBrowDark, 0.0, 1.5);
                        float y = luma(rgb);
                        float browMask = brows * (1.0 - smoothstep(.54, .82, y));
                        float mask = clamp(max(semanticHair, browMask), 0.0, 1.0);
                        float darken = amount * .40;
                        vec3 darker = rgb * (1.0 - darken);
                        // A small neutralization makes brown hair/brows read deeper without a blue cast.
                        float gray = luma(darker);
                        darker = mix(darker, vec3(gray), semanticHair * .16 * amount);
                        rgb = mix(rgb, clamp(darker, 0.0, 1.0), mask);
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
