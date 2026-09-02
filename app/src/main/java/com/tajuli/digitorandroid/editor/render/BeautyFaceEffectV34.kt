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
 * V34 portrait renderer.
 *
 * The previous single beauty pass mixed base retouch and cosmetic finishing after the creative look.
 * V34 splits the same user-visible beauty strengths into two deterministic passes:
 *
 *  1. BASE   - semantic skin smoothing + relighting before global color/looks.
 *  2. FINISH - lips, brows/hair and eyes after the creative look.
 *
 * Both skin and hair masks are temporally cross-faded and independently motion-warped from their
 * semantic-anchor geometry. The shader always requests high-precision intermediate color so strong
 * beauty + look combinations do not accumulate avoidable 8-bit-style rounding in the portrait pass.
 */
@UnstableApi
internal class BeautyFaceEffectV34 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
    private val stage: Stage,
) : GlEffect {
    enum class Stage { BASE, FINISH }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview, stage)

    companion object {
        fun baseForClip(clip: TimelineClip, preview: Boolean): BeautyFaceEffectV34? {
            val strengths = clip.beautyStrengthsV28()
            return if (strengths.skinBright <= .001f && strengths.skinSmooth <= .001f) null
            else BeautyFaceEffectV34(clip, preview, Stage.BASE)
        }

        fun finishForClip(clip: TimelineClip, preview: Boolean): BeautyFaceEffectV34? {
            val strengths = clip.beautyStrengthsV28()
            return if (
                strengths.pinkLip <= .001f &&
                strengths.hairBrowDark <= .001f &&
                strengths.eyePop <= .001f
            ) null else BeautyFaceEffectV34(clip, preview, Stage.FINISH)
        }
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
        private val stage: Stage,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ true,
        /* texturePoolCapacity = */ 1,
    ) {
        private val appContext = context.applicationContext
        private var faceTrack: BeautyFaceTrackV28? = BeautyFaceTrackStoreV28.load(appContext, clip)
        private var lastFaceTrackRefreshMs = 0L
        private val program: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1

        private var hairMaskTextureA = 0
        private var hairMaskTextureB = 0
        private var skinMaskTextureA = 0
        private var skinMaskTextureB = 0
        private var loadedHairPathA: String? = null
        private var loadedHairPathB: String? = null
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
                hairMaskTextureA = createMaskTexture()
                hairMaskTextureB = createMaskTexture()
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

                val baseStage = stage == Stage.BASE
                val needsSkin = baseStage && (strengths.skinBright > .001f || strengths.skinSmooth > .001f)
                val skinBracket = if (needsSkin) skinBracket(currentClip, sourceUs) else SkinBracketV34.empty()
                val hasSkinA = bindMask(skinMaskTextureA, skinBracket.a?.file?.absolutePath, MaskSlot.SKIN_A)
                val hasSkinB = bindMask(skinMaskTextureB, skinBracket.b?.file?.absolutePath, MaskSlot.SKIN_B)
                val skinReferenceA = skinBracket.a?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val skinReferenceB = skinBracket.b?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val skinWarpA = maskWarp(geometry, skinReferenceA)
                val skinWarpB = maskWarp(geometry, skinReferenceB)

                val needsHair = !baseStage && strengths.hairBrowDark > .001f
                val hairBracket = if (needsHair) hairBracket(currentClip, sourceUs) else HairBracketV34.empty()
                val hasHairA = bindMask(hairMaskTextureA, hairBracket.a?.file?.absolutePath, MaskSlot.HAIR_A)
                val hasHairB = bindMask(hairMaskTextureB, hairBracket.b?.file?.absolutePath, MaskSlot.HAIR_B)
                val hairReferenceA = hairBracket.a?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val hairReferenceB = hairBracket.b?.let { activeTrack?.geometryAt(it.sourceTimeUs) }
                val hairWarpA = maskWarp(geometry, hairReferenceA)
                val hairWarpB = maskWarp(geometry, hairReferenceB)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uHairMaskA", hairMaskTextureA, 1)
                program.setSamplerTexIdUniform("uHairMaskB", hairMaskTextureB, 2)
                program.setSamplerTexIdUniform("uFaceSkinMaskA", skinMaskTextureA, 3)
                program.setSamplerTexIdUniform("uFaceSkinMaskB", skinMaskTextureB, 4)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                program.setFloatUniform("uHasHairA", if (hasHairA) 1f else 0f)
                program.setFloatUniform("uHasHairB", if (hasHairB) 1f else 0f)
                program.setFloatUniform("uHasSkinA", if (hasSkinA) 1f else 0f)
                program.setFloatUniform("uHasSkinB", if (hasSkinB) 1f else 0f)
                program.setFloatUniform("uHairTemporalMix", hairBracket.mix)
                program.setFloatUniform("uSkinTemporalMix", skinBracket.mix)
                program.setFloatUniform("uSkinBright", if (baseStage) strengths.skinBright else 0f)
                program.setFloatUniform("uSkinSmooth", if (baseStage) strengths.skinSmooth else 0f)
                program.setFloatUniform("uPinkLip", if (baseStage) 0f else strengths.pinkLip)
                program.setFloatUniform("uHairBrowDark", if (baseStage) 0f else strengths.hairBrowDark)
                program.setFloatUniform("uEyePop", if (baseStage) 0f else strengths.eyePop)
                program.setFloatsUniform("uHairWarpMA", hairWarpA.matrix)
                program.setFloatsUniform("uHairWarpTA", hairWarpA.translation)
                program.setFloatsUniform("uHairWarpMB", hairWarpB.matrix)
                program.setFloatsUniform("uHairWarpTB", hairWarpB.translation)
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

        private fun skinBracket(currentClip: TimelineClip, sourceUs: Long): SkinBracketV34 {
            val frames = BeautyFaceSkinMaskStoreV31.index(appContext, currentClip).frames
            if (frames.isEmpty()) return SkinBracketV34.empty()
            if (frames.size == 1) return SkinBracketV34(frames[0], frames[0], 0f)
            var rightIndex = frames.binarySearchBy(sourceUs) { it.sourceTimeUs }
            if (rightIndex >= 0) return SkinBracketV34(frames[rightIndex], frames[rightIndex], 0f)
            rightIndex = -rightIndex - 1
            val right = frames.getOrNull(rightIndex)
            val left = frames.getOrNull(rightIndex - 1)
            if (left == null) return SkinBracketV34(right, right, 0f)
            if (right == null) return SkinBracketV34(left, left, 0f)
            val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            return SkinBracketV34(left, right, mix)
        }

        private fun hairBracket(currentClip: TimelineClip, sourceUs: Long): HairBracketV34 {
            val frames = BeautyHairMaskStoreV29.index(appContext, currentClip).frames
            if (frames.isEmpty()) return HairBracketV34.empty()
            if (frames.size == 1) return HairBracketV34(frames[0], frames[0], 0f)
            var rightIndex = frames.binarySearchBy(sourceUs) { it.sourceTimeUs }
            if (rightIndex >= 0) return HairBracketV34(frames[rightIndex], frames[rightIndex], 0f)
            rightIndex = -rightIndex - 1
            val right = frames.getOrNull(rightIndex)
            val left = frames.getOrNull(rightIndex - 1)
            if (left == null) return HairBracketV34(right, right, 0f)
            if (right == null) return HairBracketV34(left, left, 0f)
            val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            return HairBracketV34(left, right, mix)
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

        private fun maskWarp(current: BeautyFaceGeometryV28?, reference: BeautyFaceGeometryV28?): MaskWarpV34 {
            if (current == null || reference == null) return MaskWarpV34.identity()
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
            return MaskWarpV34(
                floatArrayOf(a, b, c, d),
                floatArrayOf(rmx - a * cmx - b * cmy, rmy - c * cmx - d * cmy),
            )
        }

        private fun faceScaleWarp(current: BeautyRectV28, reference: BeautyRectV28): MaskWarpV34 {
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
            return MaskWarpV34(
                floatArrayOf(scale, 0f, 0f, scale),
                floatArrayOf(rcx - scale * ccx, rcy - scale * ccy),
            )
        }

        private fun center(rect: BeautyRectV28): FloatArray {
            val r = rect.normalized()
            return floatArrayOf((r.left + r.right) * .5f, (r.top + r.bottom) * .5f)
        }

        private enum class MaskSlot { HAIR_A, HAIR_B, SKIN_A, SKIN_B }

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
                MaskSlot.HAIR_A -> loadedHairPathA
                MaskSlot.HAIR_B -> loadedHairPathB
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
                MaskSlot.HAIR_A -> loadedHairPathA = path
                MaskSlot.HAIR_B -> loadedHairPathB = path
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
                listOf(hairMaskTextureA, hairMaskTextureB, skinMaskTextureA, skinMaskTextureB)
                    .filter { it != 0 }
                    .forEach { id -> GLES20.glDeleteTextures(1, intArrayOf(id), 0) }
                hairMaskTextureA = 0
                hairMaskTextureB = 0
                skinMaskTextureA = 0
                skinMaskTextureB = 0
                program.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class SkinBracketV34(
            val a: BeautyFaceSkinMaskFrameV31?,
            val b: BeautyFaceSkinMaskFrameV31?,
            val mix: Float,
        ) {
            companion object { fun empty() = SkinBracketV34(null, null, 0f) }
        }

        private data class HairBracketV34(
            val a: BeautyHairMaskFrameV29?,
            val b: BeautyHairMaskFrameV29?,
            val mix: Float,
        ) {
            companion object { fun empty() = HairBracketV34(null, null, 0f) }
        }

        private data class MaskWarpV34(
            val matrix: FloatArray,
            val translation: FloatArray,
        ) {
            companion object {
                fun identity() = MaskWarpV34(
                    floatArrayOf(1f, 0f, 0f, 1f),
                    floatArrayOf(0f, 0f),
                )
            }
        }

        companion object {
            private const val FACE_TRACK_REFRESH_MS = 180L

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
                uniform sampler2D uHairMaskA;
                uniform sampler2D uHairMaskB;
                uniform sampler2D uFaceSkinMaskA;
                uniform sampler2D uFaceSkinMaskB;
                uniform vec2 uTexelSize;
                uniform float uHasFace;
                uniform float uHasHairA;
                uniform float uHasHairB;
                uniform float uHasSkinA;
                uniform float uHasSkinB;
                uniform float uHairTemporalMix;
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
                uniform vec4 uHairWarpMA;
                uniform vec2 uHairWarpTA;
                uniform vec4 uHairWarpMB;
                uniform vec2 uHairWarpTB;
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

                float similarityWeight(vec3 sampleColor, vec3 centerColor) {
                    float dy = abs(luma(sampleColor) - luma(centerColor));
                    float dc = length(sampleColor - centerColor);
                    return 1.0 / (1.0 + dy * 25.0 + dc * 7.5);
                }

                vec3 edgeAwareSoftSample(vec2 uv, float radius) {
                    vec2 o = uTexelSize * radius;
                    vec3 centerColor = texture2D(uTexSampler, safeUv(uv)).rgb;
                    vec3 acc = centerColor * 5.5;
                    float weights = 5.5;
                    vec3 s;
                    float w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x, 0.0))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x, 0.0))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(0.0,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(0.0, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x,  o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2( o.x, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    s = texture2D(uTexSampler, safeUv(uv + vec2(-o.x, -o.y))).rgb; w = similarityWeight(s, centerColor); acc += s*w; weights += w;
                    return acc / max(weights, .001);
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

                vec3 relightPreserveHue(vec3 c, float targetY, float amount) {
                    float y = luma(c);
                    vec3 chromaVector = c - vec3(y);
                    float highlightDesat = smoothstep(.62, .94, targetY) * .12 * amount;
                    float wantedScale = 1.0 - highlightDesat;
                    float safeScale = chromaFitScale(chromaVector, targetY);
                    return vec3(targetY) + chromaVector * min(wantedScale, safeScale);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (
                        uHasFace < .5 && uHasHairA < .5 && uHasHairB < .5 &&
                        uHasSkinA < .5 && uHasSkinB < .5
                    ) {
                        gl_FragColor = source;
                        return;
                    }

                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                    vec3 rgb = source.rgb;
                    float face = uHasFace > .5 ? ellipseMask(p, uFaceRect, .70, 1.06) : 0.0;
                    float faceWide = uHasFace > .5 ? ellipseMask(p, uFaceRect, .96, 1.44) : 1.0;
                    float lips = uHasFace > .5 ? ellipseMask(p, uLipRect, .44, .90) : 0.0;
                    float leftEye = uHasFace > .5 ? ellipseMask(p, uLeftEyeRect, .57, 1.08) : 0.0;
                    float rightEye = uHasFace > .5 ? ellipseMask(p, uRightEyeRect, .57, 1.08) : 0.0;
                    float eyes = max(leftEye, rightEye);
                    float leftBrow = uHasFace > .5 ? ellipseMask(p, uLeftBrowRect, .54, 1.04) : 0.0;
                    float rightBrow = uHasFace > .5 ? ellipseMask(p, uRightBrowRect, .54, 1.04) : 0.0;
                    float brows = max(leftBrow, rightBrow);

                    float hairA = 0.0;
                    float hairB = 0.0;
                    if (uHasHairA > .5) hairA = texture2D(uHairMaskA, safeUv(applyWarp(p, uHairWarpMA, uHairWarpTA))).r;
                    if (uHasHairB > .5) hairB = texture2D(uHairMaskB, safeUv(applyWarp(p, uHairWarpMB, uHairWarpTB))).r;
                    float semanticHair = mix(hairA, hairB, clamp(uHairTemporalMix, 0.0, 1.0));

                    float skinA = 0.0;
                    float skinB = 0.0;
                    if (uHasSkinA > .5) skinA = texture2D(uFaceSkinMaskA, safeUv(applyWarp(p, uSkinWarpMA, uSkinWarpTA))).r;
                    if (uHasSkinB > .5) skinB = texture2D(uFaceSkinMaskB, safeUv(applyWarp(p, uSkinWarpMB, uSkinWarpTB))).r;
                    float semanticSkin = mix(skinA, skinB, clamp(uSkinTemporalMix, 0.0, 1.0)) * faceWide;
                    float fallbackSkin = face * skinProbability(rgb);
                    float skinBase = (uHasSkinA > .5 || uHasSkinB > .5) ? semanticSkin : fallbackSkin;
                    float featureProtect = clamp(lips + eyes * .98 + brows * .90, 0.0, 1.0);
                    float skin = clamp(skinBase * (1.0 - featureProtect), 0.0, 1.0);
                    float skinAlpha = skin * (.40 + .60 * skin);

                    if (uSkinSmooth > .001) {
                        float amount = clamp(uSkinSmooth, 0.0, 1.5);
                        vec3 fine = edgeAwareSoftSample(vTexCoord, 1.35);
                        vec3 broad = edgeAwareSoftSample(vTexCoord, 3.15);
                        vec3 base = mix(fine, broad, .42);
                        vec3 detail = rgb - fine;
                        vec3 textureSafe = base + detail * mix(.76, .58, clamp(amount, 0.0, 1.0));
                        float edgeSignal = abs(luma(rgb) - luma(fine)) * 4.0;
                        float edgeProtect = 1.0 - smoothstep(.08, .30, edgeSignal);
                        float mixAmount = clamp(skinAlpha * edgeProtect * amount * .33, 0.0, .38);
                        rgb = mix(rgb, textureSafe, mixAmount);
                    }

                    if (uSkinBright > .001) {
                        float amount = clamp(uSkinBright, 0.0, 1.5);
                        float y = luma(rgb);
                        float blackGate = smoothstep(.055, .18, y);
                        float specularProtect = 1.0 - smoothstep(.70, .96, y);
                        float midWeight = .52 + .48 * specularProtect;
                        float lift = .082 * amount * (1.0 - y) * blackGate * midWeight;
                        float targetY = min(y + lift, .955);
                        vec3 lifted = relightPreserveHue(rgb, targetY, amount);
                        rgb = mix(rgb, lifted, clamp(skinAlpha * .84, 0.0, .84));
                    }

                    if (uPinkLip > .001) {
                        float amount = clamp(uPinkLip, 0.0, 1.5);
                        float y = luma(rgb);
                        float teethReject = 1.0 - smoothstep(.58, .80, y);
                        float naturalRed = (rgb.r - rgb.g) * .74 + (rgb.r - rgb.b) * .26;
                        float naturalLipGate = smoothstep(-.020, .095, naturalRed);
                        float lipMask = lips * teethReject * mix(.18, 1.0, naturalLipGate);
                        vec3 target = vec3(rgb.r + .034 * amount, rgb.g - .010 * amount, rgb.b + .015 * amount);
                        float targetY = luma(target);
                        vec3 tintVector = target - vec3(targetY);
                        float safeScale = chromaFitScale(tintVector, y);
                        vec3 tinted = vec3(y) + tintVector * safeScale;
                        rgb = mix(rgb, tinted, clamp(lipMask * amount * .24, 0.0, .34));
                    }

                    if (uHairBrowDark > .001) {
                        float amount = clamp(uHairBrowDark, 0.0, 1.5);
                        float y = luma(rgb);
                        float browMask = brows * (1.0 - smoothstep(.50, .82, y));
                        float mask = clamp(max(semanticHair, browMask), 0.0, 1.0);
                        vec3 darker = rgb * (1.0 - amount * .24);
                        float gray = luma(darker);
                        darker = mix(darker, vec3(gray), semanticHair * .045 * amount);
                        rgb = mix(rgb, clamp(darker, 0.0, 1.0), mask * .88);
                    }

                    if (uEyePop > .001) {
                        float amount = clamp(uEyePop, 0.0, 1.5);
                        float y = luma(rgb);
                        float c = chroma(rgb);
                        vec3 contrast = clamp((rgb - .5) * (1.0 + .085 * amount) + .5, 0.0, 1.0);
                        float whiteGate = smoothstep(.52, .80, y) * (1.0 - smoothstep(.10, .28, c));
                        vec3 eyeLook = mix(contrast, clamp(contrast + vec3(.020), 0.0, 1.0), whiteGate * .32 * amount);
                        float irisGate = 1.0 - smoothstep(.25, .52, y);
                        eyeLook = mix(eyeLook, eyeLook * (1.0 - .040 * amount), irisGate * .34);
                        rgb = mix(rgb, eyeLook, eyes * .46 * amount);
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}
