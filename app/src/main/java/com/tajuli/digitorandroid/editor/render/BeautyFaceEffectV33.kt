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

/**
 * V33 portrait stage focused on natural compositing rather than visibly painted beauty color.
 * Semantic face-skin confidence masks are temporally cross-faded and motion-warped per frame.
 */
@UnstableApi
internal class BeautyFaceEffectV33 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): BeautyFaceEffectV33? =
            if (clip.beautyStrengthsV28().isIdentity) null else BeautyFaceEffectV33(clip, preview)
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
        useHighPrecisionColorComponents: Boolean,
    ) : BaseGlShaderProgram(
        useHighPrecisionColorComponents = useHighPrecisionColorComponents,
        texturePoolCapacity = 1,
    ) {
        private val appContext = context.applicationContext
        private var faceTrack: BeautyFaceTrackV28? = BeautyFaceTrackStoreV28.load(appContext, clip)
        private var lastFaceTrackRefreshMs = 0L
        private val program: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1

        private var hairMaskTexture = 0
        private var skinMaskTextureA = 0
        private var skinMaskTextureB = 0
        private var loadedHairPath: String? = null
        private var loadedSkinPathA: String? = null
        private var loadedSkinPathB: String? = null

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
                skinMaskTextureA = createMaskTexture()
                skinMaskTextureB = createMaskTexture()
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
                val activeTrack = refreshFaceTrack(currentClip)
                val geometry = activeTrack?.geometryAt(sourceUs)

                val hairFrame = if (strengths.hairBrowDark > .001f) {
                    BeautyHairMaskStoreV29.index(appContext, currentClip).nearest(sourceUs)
                } else null
                val hairReference = hairFrame?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val hasHair = bindMask(hairMaskTexture, hairFrame?.file?.absolutePath, MaskSlot.HAIR)
                val hairWarp = maskWarp(geometry, hairReference)

                val needsSkin = strengths.skinBright > .001f || strengths.skinSmooth > .001f
                val skinBracket = if (needsSkin) skinBracket(currentClip, sourceUs) else SkinBracketV33.empty()
                val hasSkinA = bindMask(skinMaskTextureA, skinBracket.a?.file?.absolutePath, MaskSlot.SKIN_A)
                val hasSkinB = bindMask(skinMaskTextureB, skinBracket.b?.file?.absolutePath, MaskSlot.SKIN_B)
                val skinReferenceA = skinBracket.a?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val skinReferenceB = skinBracket.b?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val skinWarpA = maskWarp(geometry, skinReferenceA)
                val skinWarpB = maskWarp(geometry, skinReferenceB)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uHairMask", hairMaskTexture, 1)
                program.setSamplerTexIdUniform("uFaceSkinMaskA", skinMaskTextureA, 2)
                program.setSamplerTexIdUniform("uFaceSkinMaskB", skinMaskTextureB, 3)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                program.setFloatUniform("uHasHairMask", if (hasHair) 1f else 0f)
                program.setFloatUniform("uHasSkinA", if (hasSkinA) 1f else 0f)
                program.setFloatUniform("uHasSkinB", if (hasSkinB) 1f else 0f)
                program.setFloatUniform("uSkinTemporalMix", skinBracket.mix)
                program.setFloatUniform("uSkinBright", strengths.skinBright)
                program.setFloatUniform("uSkinSmooth", strengths.skinSmooth)
                program.setFloatUniform("uPinkLip", strengths.pinkLip)
                program.setFloatUniform("uHairBrowDark", strengths.hairBrowDark)
                program.setFloatUniform("uEyePop", strengths.eyePop)
                program.setFloatsUniform("uHairWarpM", hairWarp.matrix)
                program.setFloatsUniform("uHairWarpT", hairWarp.translation)
                program.setFloatsUniform("uSkinWarpMA", skinWarpA.matrix)
                program.setFloatsUniform("uSkinWarpTA", skinWarpA.translation)
                program.setFloatsUniform("uSkinWarpMB", skinWarpB.matrix)
                program.setFloatsUniform("uSkinWarpTB", skinWarpB.translation)
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

        private fun skinBracket(currentClip: TimelineClip, sourceUs: Long): SkinBracketV33 {
            val frames = BeautyFaceSkinMaskStoreV31.index(appContext, currentClip).frames
            if (frames.isEmpty()) return SkinBracketV33.empty()
            if (frames.size == 1) return SkinBracketV33(frames[0], frames[0], 0f)

            var rightIndex = frames.binarySearchBy(sourceUs) { it.sourceTimeUs }
            if (rightIndex >= 0) return SkinBracketV33(frames[rightIndex], frames[rightIndex], 0f)
            rightIndex = -rightIndex - 1
            val right = frames.getOrNull(rightIndex)
            val left = frames.getOrNull(rightIndex - 1)
            if (left == null) return SkinBracketV33(right, right, 0f)
            if (right == null) return SkinBracketV33(left, left, 0f)
            val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            return SkinBracketV33(left, right, mix)
        }

        private fun refreshFaceTrack(currentClip: TimelineClip): BeautyFaceTrackV28? {
            val now = SystemClock.elapsedRealtime()
            if (faceTrack == null || now - lastFaceTrackRefreshMs >= FACE_TRACK_REFRESH_MS) {
                lastFaceTrackRefreshMs = now
                BeautyFaceTrackStoreV28.load(appContext, currentClip)?.let { faceTrack = it }
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

        private fun maskWarp(current: BeautyFaceGeometryV28?, reference: BeautyFaceGeometryV28?): MaskWarpV33 {
            if (current == null || reference == null) return MaskWarpV33.identity()
            val cl = center(current.leftEye)
            val cr = center(current.rightEye)
            val rl = center(reference.leftEye)
            val rr = center(reference.rightEye)
            val cvx = cr[0] - cl[0]
            val cvy = cr[1] - cl[1]
            val rvx = rr[0] - rl[0]
            val rvy = rr[1] - rl[1]
            val cLen = sqrt(cvx * cvx + cvy * cvy)
            val rLen = sqrt(rvx * rvx + rvy * rvy)
            if (cLen < .005f || rLen < .005f) return faceScaleWarp(current.face, reference.face)
            val denom = cLen * rLen
            val cos = ((cvx * rvx + cvy * rvy) / denom).coerceIn(-1f, 1f)
            val sin = ((cvx * rvy - cvy * rvx) / denom).coerceIn(-1f, 1f)
            val scale = (rLen / cLen).coerceIn(.55f, 1.8f)
            val a = scale * cos
            val b = -scale * sin
            val c = scale * sin
            val d = scale * cos
            val cmx = (cl[0] + cr[0]) * .5f
            val cmy = (cl[1] + cr[1]) * .5f
            val rmx = (rl[0] + rr[0]) * .5f
            val rmy = (rl[1] + rr[1]) * .5f
            return MaskWarpV33(
                floatArrayOf(a, b, c, d),
                floatArrayOf(rmx - a * cmx - b * cmy, rmy - c * cmx - d * cmy),
            )
        }

        private fun faceScaleWarp(current: BeautyRectV28, reference: BeautyRectV28): MaskWarpV33 {
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
            return MaskWarpV33(
                floatArrayOf(scale, 0f, 0f, scale),
                floatArrayOf(rcx - scale * ccx, rcy - scale * ccy),
            )
        }

        private fun center(rect: BeautyRectV28): FloatArray {
            val r = rect.normalized()
            return floatArrayOf((r.left + r.right) * .5f, (r.top + r.bottom) * .5f)
        }

        private enum class MaskSlot { HAIR, SKIN_A, SKIN_B }

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

        private fun bindMask(texture: Int, path: String?, slot: MaskSlot): Boolean {
            val loaded = when (slot) {
                MaskSlot.HAIR -> loadedHairPath
                MaskSlot.SKIN_A -> loadedSkinPathA
                MaskSlot.SKIN_B -> loadedSkinPathB
            }
            if (path == null) {
                if (loaded != null) {
                    uploadMaskBitmap(texture, null)
                    setLoadedPath(slot, null)
                }
                return false
            }
            if (loaded == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(texture, bitmap)
                setLoadedPath(slot, path)
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun setLoadedPath(slot: MaskSlot, path: String?) {
            when (slot) {
                MaskSlot.HAIR -> loadedHairPath = path
                MaskSlot.SKIN_A -> loadedSkinPathA = path
                MaskSlot.SKIN_B -> loadedSkinPathB = path
            }
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
                listOf(hairMaskTexture, skinMaskTextureA, skinMaskTextureB).filter { it != 0 }.forEach { id ->
                    GLES20.glDeleteTextures(1, intArrayOf(id), 0)
                }
                hairMaskTexture = 0
                skinMaskTextureA = 0
                skinMaskTextureB = 0
                program.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class SkinBracketV33(
            val a: BeautyFaceSkinMaskFrameV31?,
            val b: BeautyFaceSkinMaskFrameV31?,
            val mix: Float,
        ) {
            companion object {
                fun empty() = SkinBracketV33(null, null, 0f)
            }
        }

        private data class MaskWarpV33(
            val matrix: FloatArray,
            val translation: FloatArray,
        ) {
            companion object {
                fun identity() = MaskWarpV33(
                    floatArrayOf(1f, 0f, 0f, 1f),
                    floatArrayOf(0f, 0f),
                )
            }
        }

        companion object {
            private const val FACE_TRACK_REFRESH_MS = 250L

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
                uniform sampler2D uFaceSkinMaskA;
                uniform sampler2D uFaceSkinMaskB;
                uniform vec2 uTexelSize;
                uniform float uHasFace;
                uniform float uHasHairMask;
                uniform float uHasSkinA;
                uniform float uHasSkinB;
                uniform float uSkinTemporalMix;
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
                uniform vec4 uSkinWarpMA;
                uniform vec2 uSkinWarpTA;
                uniform vec4 uSkinWarpMB;
                uniform vec2 uSkinWarpTB;
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
                    float cr = .5 * c.r - .418688 * c.g - .081312 * c.b + .5;
                    float cbBand = smoothstep(.23, .30, cb) * (1.0 - smoothstep(.53, .60, cb));
                    float crBand = smoothstep(.34, .40, cr) * (1.0 - smoothstep(.66, .73, cr));
                    return cbBand * crBand * smoothstep(.04, .15, y);
                }

                float similarityWeight(vec3 s, vec3 c) {
                    float dy = abs(luma(s) - luma(c));
                    float dc = length(s - c);
                    return 1.0 / (1.0 + dy * 30.0 + dc * 9.0);
                }

                vec3 edgeAwareSoftSample(vec2 uv) {
                    vec2 o = uTexelSize * 1.55;
                    vec3 centerColor = texture2D(uTexSampler, safeUv(uv)).rgb;
                    vec3 acc = centerColor * 5.0;
                    float weights = 5.0;
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

                vec3 replaceLumaPreserveChroma(vec3 c, float targetY) {
                    float y = luma(c);
                    vec3 colorPart = c - vec3(y);
                    return clamp(vec3(targetY) + colorPart, 0.0, 1.0);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uHasFace < .5 && uHasHairMask < .5 && uHasSkinA < .5 && uHasSkinB < .5) {
                        gl_FragColor = source;
                        return;
                    }

                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                    vec3 rgb = source.rgb;
                    float face = uHasFace > .5 ? ellipseMask(p, uFaceRect, .72, 1.08) : 0.0;
                    float faceWide = uHasFace > .5 ? ellipseMask(p, uFaceRect, .96, 1.48) : 1.0;
                    float lips = uHasFace > .5 ? ellipseMask(p, uLipRect, .50, .98) : 0.0;
                    float leftEye = uHasFace > .5 ? ellipseMask(p, uLeftEyeRect, .62, 1.16) : 0.0;
                    float rightEye = uHasFace > .5 ? ellipseMask(p, uRightEyeRect, .62, 1.16) : 0.0;
                    float eyes = max(leftEye, rightEye);
                    float leftBrow = uHasFace > .5 ? ellipseMask(p, uLeftBrowRect, .58, 1.10) : 0.0;
                    float rightBrow = uHasFace > .5 ? ellipseMask(p, uRightBrowRect, .58, 1.10) : 0.0;
                    float brows = max(leftBrow, rightBrow);

                    float semanticHair = 0.0;
                    if (uHasHairMask > .5) {
                        semanticHair = texture2D(uHairMask, safeUv(applyWarp(p, uHairWarpM, uHairWarpT))).r;
                    }

                    float skinA = 0.0;
                    float skinB = 0.0;
                    if (uHasSkinA > .5) skinA = texture2D(uFaceSkinMaskA, safeUv(applyWarp(p, uSkinWarpMA, uSkinWarpTA))).r;
                    if (uHasSkinB > .5) skinB = texture2D(uFaceSkinMaskB, safeUv(applyWarp(p, uSkinWarpMB, uSkinWarpTB))).r;
                    float semanticSkin = mix(skinA, skinB, clamp(uSkinTemporalMix, 0.0, 1.0)) * faceWide;
                    float fallbackSkin = face * skinProbability(rgb);
                    float skinBase = (uHasSkinA > .5 || uHasSkinB > .5) ? semanticSkin : fallbackSkin;
                    float featureProtect = clamp(lips + eyes * .97 + brows * .90, 0.0, 1.0);
                    float skin = clamp(skinBase * (1.0 - featureProtect), 0.0, 1.0);
                    // Confidence alpha stays genuinely translucent near boundaries.
                    float skinAlpha = skin * (.52 + .48 * skin);

                    if (uSkinSmooth > .001) {
                        float amount = clamp(uSkinSmooth * 2.0, 0.0, 2.0);
                        vec3 soft = edgeAwareSoftSample(vTexCoord);
                        vec3 detail = rgb - soft;
                        vec3 textureSafe = soft + detail * .78;
                        float edgeProtect = 1.0 - smoothstep(.07, .24, abs(luma(rgb) - luma(soft)) * 4.0);
                        float mixAmount = clamp(skinAlpha * edgeProtect * amount * .15, 0.0, .30);
                        rgb = mix(rgb, textureSafe, mixAmount);
                    }

                    if (uSkinBright > .001) {
                        float amount = clamp(uSkinBright * 2.0, 0.0, 2.0);
                        float y = luma(rgb);
                        float shadowGate = smoothstep(.07, .22, y);
                        float highlightGate = 1.0 - smoothstep(.66, .94, y);
                        float lift = .080 * amount * (1.0 - y) * shadowGate * (.45 + .55 * highlightGate);
                        float targetY = min(y + lift, .965);
                        vec3 lifted = replaceLumaPreserveChroma(rgb, targetY);
                        // The processed color is subtle; mask alpha controls spatial blending only.
                        rgb = mix(rgb, lifted, clamp(skinAlpha * .88, 0.0, .88));
                    }

                    if (uPinkLip > .001) {
                        float amount = clamp(uPinkLip * 2.0, 0.0, 2.0);
                        float y = luma(rgb);
                        float teethReject = 1.0 - smoothstep(.64, .84, y);
                        float naturalRed = (rgb.r - rgb.g) * .72 + (rgb.r - rgb.b) * .28;
                        float naturalLipGate = smoothstep(-.025, .105, naturalRed);
                        float lipMask = lips * teethReject * mix(.28, 1.0, naturalLipGate);
                        vec3 tintDelta = vec3(.032, -.011, .017) * amount;
                        vec3 tinted = clamp(rgb + tintDelta, 0.0, 1.0);
                        // Put original luminance back: tint changes chroma, not brightness/flatness.
                        tinted = replaceLumaPreserveChroma(tinted, y);
                        float mixAmount = clamp(lipMask * amount * .18, 0.0, .38);
                        rgb = mix(rgb, tinted, mixAmount);
                    }

                    if (uHairBrowDark > .001) {
                        float amount = clamp(uHairBrowDark, 0.0, 1.5);
                        float y = luma(rgb);
                        float browMask = brows * (1.0 - smoothstep(.55, .83, y));
                        float mask = clamp(max(semanticHair, browMask), 0.0, 1.0);
                        vec3 darker = rgb * (1.0 - amount * .32);
                        float gray = luma(darker);
                        darker = mix(darker, vec3(gray), semanticHair * .08 * amount);
                        rgb = mix(rgb, clamp(darker, 0.0, 1.0), mask * .92);
                    }

                    if (uEyePop > .001) {
                        float amount = clamp(uEyePop, 0.0, 1.5);
                        float y = luma(rgb);
                        float c = chroma(rgb);
                        vec3 contrast = clamp((rgb - .5) * (1.0 + .12 * amount) + .5, 0.0, 1.0);
                        float whiteGate = smoothstep(.50, .78, y) * (1.0 - smoothstep(.12, .30, c));
                        vec3 eyeLook = mix(contrast, clamp(contrast + vec3(.028), 0.0, 1.0), whiteGate * .38 * amount);
                        float irisGate = 1.0 - smoothstep(.28, .56, y);
                        eyeLook = mix(eyeLook, eyeLook * (1.0 - .055 * amount), irisGate * .40);
                        rgb = mix(rgb, eyeLook, eyes * .52 * amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}
