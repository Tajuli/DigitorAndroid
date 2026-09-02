package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.tajuli.digitorandroid.editor.model.BeautyFaceGeometryV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceTrackV28
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.beautyStrengthsV28
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.BeautyFaceSkinMaskFrameV31
import com.tajuli.digitorandroid.editor.processing.BeautyFaceSkinMaskStoreV31
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28
import com.tajuli.digitorandroid.editor.processing.BeautyHairMaskFrameV29
import com.tajuli.digitorandroid.editor.processing.BeautyHairMaskStoreV29
import kotlin.math.sqrt

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
        private var faceTrack: BeautyFaceTrackV28? = BeautyFaceTrackStoreV28.load(appContext, clip)
        private var lastFaceTrackRefreshMs = 0L
        private val program: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private var hairMaskTexture = 0
        private var faceSkinMaskTexture = 0
        private var loadedHairMaskPath: String? = null
        private var loadedFaceSkinMaskPath: String? = null

        init {
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { shader ->
                    shader.setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
                hairMaskTexture = createMaskTexture()
                faceSkinMaskTexture = createMaskTexture()
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
                val activeFaceTrack = refreshFaceTrack(currentClip)
                val geometry = activeFaceTrack?.geometryAt(sourceUs)

                val hairFrame = if (strengths.hairBrowDark > .001f) {
                    BeautyHairMaskStoreV29.index(appContext, currentClip).nearest(sourceUs)
                } else {
                    null
                }
                val needsSemanticSkin = strengths.skinBright > .001f || strengths.skinSmooth > .001f
                val faceSkinFrame = if (needsSemanticSkin) {
                    BeautyFaceSkinMaskStoreV31.index(appContext, currentClip).nearest(sourceUs)
                } else {
                    null
                }
                val hairReferenceGeometry = hairFrame?.let { activeFaceTrack?.geometryAt(it.sourceTimeUs) }
                val faceSkinReferenceGeometry = faceSkinFrame?.let { activeFaceTrack?.geometryAt(it.sourceTimeUs) }
                val hasHairMask = bindHairMask(hairFrame)
                val hasFaceSkinMask = bindFaceSkinMask(faceSkinFrame)
                val hairWarp = maskWarp(geometry, hairReferenceGeometry)
                val faceSkinWarp = maskWarp(geometry, faceSkinReferenceGeometry)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uHairMask", hairMaskTexture, 1)
                program.setSamplerTexIdUniform("uFaceSkinMask", faceSkinMaskTexture, 2)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                program.setFloatUniform("uHasHairMask", if (hasHairMask) 1f else 0f)
                program.setFloatUniform("uHasFaceSkinMask", if (hasFaceSkinMask) 1f else 0f)
                program.setFloatUniform("uSkinBright", strengths.skinBright)
                program.setFloatUniform("uSkinSmooth", strengths.skinSmooth)
                program.setFloatUniform("uPinkLip", strengths.pinkLip)
                program.setFloatUniform("uHairBrowDark", strengths.hairBrowDark)
                program.setFloatUniform("uEyePop", strengths.eyePop)
                program.setFloatsUniform("uHairWarpM", hairWarp.matrix)
                program.setFloatsUniform("uHairWarpT", hairWarp.translation)
                program.setFloatsUniform("uSkinWarpM", faceSkinWarp.matrix)
                program.setFloatsUniform("uSkinWarpT", faceSkinWarp.translation)
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

        private fun refreshFaceTrack(currentClip: TimelineClip): BeautyFaceTrackV28? {
            val now = SystemClock.elapsedRealtime()
            if (faceTrack == null || now - lastFaceTrackRefreshMs >= FACE_TRACK_REFRESH_MS) {
                lastFaceTrackRefreshMs = now
                BeautyFaceTrackStoreV28.load(appContext, currentClip)?.let { latest -> faceTrack = latest }
            }
            return faceTrack
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

        /** Maps a current-frame point into the sampled-mask frame using eyes as a similarity anchor. */
        private fun maskWarp(
            current: BeautyFaceGeometryV28?,
            reference: BeautyFaceGeometryV28?,
        ): MaskWarpV31 {
            if (current == null || reference == null) return MaskWarpV31.identity()
            val currentLeft = center(current.leftEye)
            val currentRight = center(current.rightEye)
            val referenceLeft = center(reference.leftEye)
            val referenceRight = center(reference.rightEye)
            val cvx = currentRight[0] - currentLeft[0]
            val cvy = currentRight[1] - currentLeft[1]
            val rvx = referenceRight[0] - referenceLeft[0]
            val rvy = referenceRight[1] - referenceLeft[1]
            val currentLength = sqrt(cvx * cvx + cvy * cvy)
            val referenceLength = sqrt(rvx * rvx + rvy * rvy)
            if (currentLength < .005f || referenceLength < .005f) {
                return faceScaleWarp(current.face, reference.face)
            }
            val denom = currentLength * referenceLength
            val cos = ((cvx * rvx + cvy * rvy) / denom).coerceIn(-1f, 1f)
            val sin = ((cvx * rvy - cvy * rvx) / denom).coerceIn(-1f, 1f)
            val scale = (referenceLength / currentLength).coerceIn(.55f, 1.8f)
            val a = scale * cos
            val b = -scale * sin
            val c = scale * sin
            val d = scale * cos
            val currentMidX = (currentLeft[0] + currentRight[0]) * .5f
            val currentMidY = (currentLeft[1] + currentRight[1]) * .5f
            val referenceMidX = (referenceLeft[0] + referenceRight[0]) * .5f
            val referenceMidY = (referenceLeft[1] + referenceRight[1]) * .5f
            return MaskWarpV31(
                matrix = floatArrayOf(a, b, c, d),
                translation = floatArrayOf(
                    referenceMidX - a * currentMidX - b * currentMidY,
                    referenceMidY - c * currentMidX - d * currentMidY,
                ),
            )
        }

        private fun faceScaleWarp(current: BeautyRectV28, reference: BeautyRectV28): MaskWarpV31 {
            val c = current.normalized()
            val r = reference.normalized()
            val cw = (c.right - c.left).coerceAtLeast(.005f)
            val ch = (c.bottom - c.top).coerceAtLeast(.005f)
            val rw = (r.right - r.left).coerceAtLeast(.005f)
            val rh = (r.bottom - r.top).coerceAtLeast(.005f)
            val scale = (((rw / cw) + (rh / ch)) * .5f).coerceIn(.55f, 1.8f)
            val ccx = (c.left + c.right) * .5f
            val ccy = (c.top + c.bottom) * .5f
            val rcx = (r.left + r.right) * .5f
            val rcy = (r.top + r.bottom) * .5f
            return MaskWarpV31(
                matrix = floatArrayOf(scale, 0f, 0f, scale),
                translation = floatArrayOf(rcx - scale * ccx, rcy - scale * ccy),
            )
        }

        private fun center(rect: BeautyRectV28): FloatArray {
            val r = rect.normalized()
            return floatArrayOf((r.left + r.right) * .5f, (r.top + r.bottom) * .5f)
        }

        private fun createMaskTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            uploadMaskBitmap(texture, null)
            return texture
        }

        private fun bindHairMask(frame: BeautyHairMaskFrameV29?): Boolean {
            val path = frame?.file?.takeIf { it.isFile }?.absolutePath
            if (path == null) {
                if (loadedHairMaskPath != null) {
                    uploadMaskBitmap(hairMaskTexture, null)
                    loadedHairMaskPath = null
                }
                return false
            }
            if (loadedHairMaskPath == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(hairMaskTexture, bitmap)
                loadedHairMaskPath = path
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun bindFaceSkinMask(frame: BeautyFaceSkinMaskFrameV31?): Boolean {
            val path = frame?.file?.takeIf { it.isFile }?.absolutePath
            if (path == null) {
                if (loadedFaceSkinMaskPath != null) {
                    uploadMaskBitmap(faceSkinMaskTexture, null)
                    loadedFaceSkinMaskPath = null
                }
                return false
            }
            if (loadedFaceSkinMaskPath == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(faceSkinMaskTexture, bitmap)
                loadedFaceSkinMaskPath = path
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun uploadMaskBitmap(texture: Int, bitmap: Bitmap?) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
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
                if (faceSkinMaskTexture != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(faceSkinMaskTexture), 0)
                    faceSkinMaskTexture = 0
                }
                program.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class MaskWarpV31(
            val matrix: FloatArray,
            val translation: FloatArray,
        ) {
            companion object {
                fun identity() = MaskWarpV31(
                    matrix = floatArrayOf(1f, 0f, 0f, 1f),
                    translation = floatArrayOf(0f, 0f),
                )
            }
        }

        companion object {
            private const val FACE_TRACK_REFRESH_MS = 300L

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
                uniform sampler2D uFaceSkinMask;
                uniform vec2 uTexelSize;
                uniform float uHasFace;
                uniform float uHasHairMask;
                uniform float uHasFaceSkinMask;
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
                uniform vec4 uHairWarpM;
                uniform vec2 uHairWarpT;
                uniform vec4 uSkinWarpM;
                uniform vec2 uSkinWarpT;
                varying vec2 vTexCoord;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chroma(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

                vec2 applyWarp(vec2 p, vec4 m, vec2 t) {
                    return vec2(m.x * p.x + m.y * p.y, m.z * p.x + m.w * p.y) + t;
                }

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
                    float cbBand = smoothstep(.23, .30, cb) * (1.0 - smoothstep(.53, .60, cb));
                    float crBand = smoothstep(.34, .40, cr) * (1.0 - smoothstep(.66, .73, cr));
                    return cbBand * crBand * smoothstep(.04, .15, y);
                }

                float similarityWeight(vec3 sampleColor, vec3 centerColor) {
                    float dy = abs(luma(sampleColor) - luma(centerColor));
                    float dc = length(sampleColor - centerColor);
                    return 1.0 / (1.0 + dy * 24.0 + dc * 7.0);
                }

                vec3 edgeAwareSoftSample(vec2 uv) {
                    vec2 o = uTexelSize * 1.65;
                    vec3 centerColor = texture2D(uTexSampler, safeUv(uv)).rgb;
                    vec3 acc = centerColor * 4.0;
                    float weights = 4.0;
                    vec3 s;
                    float w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x, 0.0))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x, 0.0))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(0.0,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(0.0, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s * w; weights += w;
                    return acc / max(weights, .001);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uHasFace < .5 && uHasHairMask < .5 && uHasFaceSkinMask < .5) {
                        gl_FragColor = source;
                        return;
                    }

                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                    vec3 rgb = source.rgb;
                    float face = uHasFace > .5 ? ellipseMask(p, uFaceRect, .72, 1.08) : 0.0;
                    float faceWide = uHasFace > .5 ? ellipseMask(p, uFaceRect, .92, 1.48) : 1.0;
                    float lips = uHasFace > .5 ? ellipseMask(p, uLipRect, .68, 1.18) : 0.0;
                    float leftEye = uHasFace > .5 ? ellipseMask(p, uLeftEyeRect, .62, 1.20) : 0.0;
                    float rightEye = uHasFace > .5 ? ellipseMask(p, uRightEyeRect, .62, 1.20) : 0.0;
                    float eyes = max(leftEye, rightEye);
                    float leftBrow = uHasFace > .5 ? ellipseMask(p, uLeftBrowRect, .58, 1.15) : 0.0;
                    float rightBrow = uHasFace > .5 ? ellipseMask(p, uRightBrowRect, .58, 1.15) : 0.0;
                    float brows = max(leftBrow, rightBrow);

                    float semanticHair = 0.0;
                    if (uHasHairMask > .5) {
                        semanticHair = texture2D(uHairMask, safeUv(applyWarp(p, uHairWarpM, uHairWarpT))).r;
                    }

                    float semanticSkin = 0.0;
                    if (uHasFaceSkinMask > .5) {
                        semanticSkin = texture2D(uFaceSkinMask, safeUv(applyWarp(p, uSkinWarpM, uSkinWarpT))).r * faceWide;
                    }
                    float fallbackSkin = face * skinProbability(rgb);
                    float skinBase = uHasFaceSkinMask > .5 ? semanticSkin : fallbackSkin;
                    float featureProtect = clamp(lips + eyes * .96 + brows * .88, 0.0, 1.0);
                    float skin = clamp(skinBase * (1.0 - featureProtect), 0.0, 1.0);

                    // 2x maximum range, but with CapCut-style quality safeguards: semantic skin,
                    // edge-aware detail preservation, hue-neutral luminance lift and highlight rolloff.
                    if (uSkinSmooth > .001) {
                        float amount = clamp(uSkinSmooth * 2.0, 0.0, 2.0);
                        vec3 soft = edgeAwareSoftSample(vTexCoord);
                        vec3 detail = rgb - soft;
                        vec3 textureSafe = soft + detail * .55;
                        float textureProtect = 1.0 - smoothstep(.10, .32, abs(luma(rgb) - luma(soft)) * 4.0);
                        float mixAmount = clamp(skin * textureProtect * amount * .28, 0.0, .56);
                        rgb = mix(rgb, textureSafe, mixAmount);
                    }

                    if (uSkinBright > .001) {
                        float amount = clamp(uSkinBright * 2.0, 0.0, 2.0);
                        float y = luma(rgb);
                        float highlightProtect = 1.0 - .72 * smoothstep(.68, .98, y);
                        float lift = (.035 + .065 * (1.0 - y)) * amount * highlightProtect;
                        vec3 lifted = clamp(rgb + vec3(lift), 0.0, 1.0);
                        float liftedY = luma(lifted);
                        vec3 liftedChroma = lifted - vec3(liftedY);
                        lifted = clamp(vec3(liftedY) + liftedChroma * (1.0 - .025 * amount), 0.0, 1.0);
                        float mixAmount = clamp(skin * (.36 + .12 * amount), 0.0, .68);
                        rgb = mix(rgb, lifted, mixAmount);
                    }

                    if (uPinkLip > .001) {
                        float amount = clamp(uPinkLip * 2.0, 0.0, 2.0);
                        float lipLum = luma(rgb);
                        float teethReject = 1.0 - smoothstep(.66, .86, lipLum);
                        float lipMask = lips * teethReject;
                        vec3 roseDelta = vec3(.070, -.028, .040);
                        roseDelta -= vec3(luma(roseDelta));
                        vec3 pink = clamp(rgb + roseDelta * amount, 0.0, 1.0);
                        float mixAmount = clamp(lipMask * amount * .34, 0.0, .68);
                        rgb = mix(rgb, pink, mixAmount);
                    }

                    if (uHairBrowDark > .001) {
                        float amount = clamp(uHairBrowDark, 0.0, 1.5);
                        float y = luma(rgb);
                        float browMask = brows * (1.0 - smoothstep(.56, .84, y));
                        float mask = clamp(max(semanticHair, browMask), 0.0, 1.0);
                        vec3 darker = rgb * (1.0 - amount * .36);
                        float gray = luma(darker);
                        darker = mix(darker, vec3(gray), semanticHair * .12 * amount);
                        rgb = mix(rgb, clamp(darker, 0.0, 1.0), mask);
                    }

                    if (uEyePop > .001) {
                        float amount = clamp(uEyePop, 0.0, 1.5);
                        float y = luma(rgb);
                        float c = chroma(rgb);
                        vec3 contrast = clamp((rgb - .5) * (1.0 + .16 * amount) + .5, 0.0, 1.0);
                        float whiteGate = smoothstep(.50, .78, y) * (1.0 - smoothstep(.12, .31, c));
                        vec3 eyeLook = mix(contrast, clamp(contrast + vec3(.040), 0.0, 1.0), whiteGate * .45 * amount);
                        float irisGate = 1.0 - smoothstep(.28, .56, y);
                        eyeLook = mix(eyeLook, eyeLook * (1.0 - .075 * amount), irisGate * .45);
                        rgb = mix(rgb, eyeLook, eyes * .62 * amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}
