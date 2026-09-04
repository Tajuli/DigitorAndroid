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
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskFrameV43
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43

/**
 * Shared GPU alpha stage for V44 Pro Cutout and Chroma Key.
 *
 * PERSON consumes MODNet soft alpha mattes that already include hair fusion + motion-aware temporal
 * stabilization. The shader therefore avoids re-binarizing the matte: it performs realtime edge
 * shift/cleanup and foreground-color decontamination (dehalo) while preserving translucent detail.
 * Chroma mode keeps the proven neighbourhood-filtered Cb/Cr keyer from V43.
 */
@UnstableApi
internal class CutoutEffectV43 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context.applicationContext, clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): CutoutEffectV43? =
            if (clip.resolvedCutoutV43().mode == CutoutModeV43.NONE) null else CutoutEffectV43(clip, preview)
    }

    private class Program(
        private val appContext: Context,
        private val snapshotClip: TimelineClip,
        private val preview: Boolean,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHdr,
        /* texturePoolCapacity = */ 1,
    ) {
        private val program: GlProgram
        private var maskTextureA = 0
        private var maskTextureB = 0
        private var loadedPathA: String? = null
        private var loadedPathB: String? = null
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
                maskTextureA = createMaskTexture()
                maskTextureB = createMaskTexture()
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
                val sourceUs = ParityRenderContract.sourceTimeUs(clip, presentationTimeUs)
                val bracket = if (settings.mode == CutoutModeV43.PERSON) personBracket(clip, sourceUs) else MaskBracket.empty()
                val hasMaskA = bindMask(maskTextureA, bracket.a?.file?.absolutePath, true)
                val hasMaskB = bindMask(maskTextureB, bracket.b?.file?.absolutePath, false)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uMaskA", maskTextureA, 1)
                program.setSamplerTexIdUniform("uMaskB", maskTextureB, 2)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uMode", when (settings.mode) {
                    CutoutModeV43.PERSON -> 1f
                    CutoutModeV43.CHROMA_KEY -> 2f
                    CutoutModeV43.NONE -> 0f
                })
                program.setFloatUniform("uHasMaskA", if (hasMaskA) 1f else 0f)
                program.setFloatUniform("uHasMaskB", if (hasMaskB) 1f else 0f)
                program.setFloatUniform("uTemporalMix", bracket.mix)
                program.setFloatUniform("uPersonThreshold", settings.personThreshold)
                program.setFloatUniform("uPersonFeather", settings.personFeather)
                program.setFloatUniform("uEdgeShiftV44", settings.edgeShiftV44)
                program.setFloatUniform("uEdgeCleanV44", settings.edgeCleanV44)
                program.setFloatUniform("uDehaloV44", settings.dehaloV44)
                program.setFloatsUniform("uKeyRgb", floatArrayOf(settings.keyRed, settings.keyGreen, settings.keyBlue))
                program.setFloatUniform("uChromaSimilarity", settings.chromaSimilarity)
                program.setFloatUniform("uChromaSoftness", settings.chromaSoftness)
                program.setFloatUniform("uSpill", settings.spillSuppression)
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

        private fun personBracket(clip: TimelineClip, sourceUs: Long): MaskBracket {
            val frames = PersonCutoutMaskStoreV43.index(appContext, clip).frames
            if (frames.isEmpty()) return MaskBracket.empty()
            if (frames.size == 1) return MaskBracket(frames[0], frames[0], 0f)
            var rightIndex = frames.binarySearchBy(sourceUs) { it.sourceTimeUs }
            if (rightIndex >= 0) return MaskBracket(frames[rightIndex], frames[rightIndex], 0f)
            rightIndex = -rightIndex - 1
            val right = frames.getOrNull(rightIndex)
            val left = frames.getOrNull(rightIndex - 1)
            if (left == null) return MaskBracket(right, right, 0f)
            if (right == null) return MaskBracket(left, left, 0f)
            val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            return MaskBracket(left, right, mix)
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

        private fun bindMask(texture: Int, path: String?, slotA: Boolean): Boolean {
            val loaded = if (slotA) loadedPathA else loadedPathB
            if (path == null) {
                if (loaded != null) {
                    uploadMaskBitmap(texture, null)
                    if (slotA) loadedPathA = null else loadedPathB = null
                }
                return false
            }
            if (loaded == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(texture, bitmap)
                if (slotA) loadedPathA = path else loadedPathB = path
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
                listOf(maskTextureA, maskTextureB).filter { it != 0 }.forEach { texture ->
                    GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                }
                maskTextureA = 0
                maskTextureB = 0
                program.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class MaskBracket(
            val a: PersonCutoutMaskFrameV43?,
            val b: PersonCutoutMaskFrameV43?,
            val mix: Float,
        ) {
            companion object { fun empty() = MaskBracket(null, null, 0f) }
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
                uniform sampler2D uMaskA;
                uniform sampler2D uMaskB;
                uniform vec2 uTexelSize;
                uniform float uMode;
                uniform float uHasMaskA;
                uniform float uHasMaskB;
                uniform float uTemporalMix;
                uniform float uPersonThreshold;
                uniform float uPersonFeather;
                uniform float uEdgeShiftV44;
                uniform float uEdgeCleanV44;
                uniform float uDehaloV44;
                uniform vec3 uKeyRgb;
                uniform float uChromaSimilarity;
                uniform float uChromaSoftness;
                uniform float uSpill;
                varying vec2 vTexCoord;

                vec2 rgbToChroma(vec3 rgb) {
                    return vec2(
                        -0.168736 * rgb.r - 0.331264 * rgb.g + 0.500000 * rgb.b,
                         0.500000 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b
                    );
                }

                // Android bitmap texture Y is opposite Media3 frame UV. Only matte UV is flipped.
                vec2 personMaskUv(vec2 videoUv) {
                    return vec2(videoUv.x, 1.0 - videoUv.y);
                }

                float rawPersonAt(vec2 uv) {
                    if (uHasMaskA < 0.5 && uHasMaskB < 0.5) return 1.0;
                    vec2 maskUv = personMaskUv(clamp(uv, vec2(0.0), vec2(1.0)));
                    float a = uHasMaskA > 0.5 ? texture2D(uMaskA, maskUv).r : 0.0;
                    float b = uHasMaskB > 0.5 ? texture2D(uMaskB, maskUv).r : a;
                    if (uHasMaskA < 0.5) a = b;
                    return mix(a, b, clamp(uTemporalMix, 0.0, 1.0));
                }

                float refinedPersonMask() {
                    float raw = rawPersonAt(vTexCoord);

                    // Legacy threshold becomes a subtle alpha bias rather than a hard binary cut.
                    // V44 edge shift is the creator-facing shrink/grow control.
                    float thresholdBias = (0.5 - uPersonThreshold) * 0.22;
                    float shifted = clamp(raw + uEdgeShiftV44 + thresholdBias, 0.0, 1.0);

                    // Clean ambiguous matte pixels while leaving near-0 and near-1 MODNet alpha
                    // untouched. This keeps fine hair translucency instead of re-binarizing it.
                    float contrast = 1.0 + clamp(uEdgeCleanV44, 0.0, 1.0) * 1.65;
                    float cleaned = clamp((shifted - 0.5) * contrast + 0.5, 0.0, 1.0);

                    // Feather is now a small spatial anti-jaggedness blend, not a broad confidence
                    // threshold. It only acts strongly in the uncertain alpha band.
                    float neighbor = (
                        rawPersonAt(vTexCoord + vec2(uTexelSize.x, 0.0)) +
                        rawPersonAt(vTexCoord - vec2(uTexelSize.x, 0.0)) +
                        rawPersonAt(vTexCoord + vec2(0.0, uTexelSize.y)) +
                        rawPersonAt(vTexCoord - vec2(0.0, uTexelSize.y))
                    ) * 0.25;
                    float uncertain = 1.0 - abs(cleaned * 2.0 - 1.0);
                    float featherMix = clamp(uPersonFeather * 2.2, 0.0, 0.42) * uncertain;
                    return mix(cleaned, clamp(neighbor + uEdgeShiftV44, 0.0, 1.0), featherMix);
                }

                vec3 dehaloPersonRgb(vec3 sourceRgb, float matte) {
                    float edge = (1.0 - abs(matte * 2.0 - 1.0));
                    edge *= smoothstep(0.015, 0.12, matte) * (1.0 - smoothstep(0.88, 0.995, matte));
                    if (edge <= 0.0001 || uDehaloV44 <= 0.0001) return sourceRgb;

                    // Alpha gradient points from contaminated exterior toward reliable foreground.
                    float l = rawPersonAt(vTexCoord - vec2(uTexelSize.x * 2.0, 0.0));
                    float r = rawPersonAt(vTexCoord + vec2(uTexelSize.x * 2.0, 0.0));
                    float d = rawPersonAt(vTexCoord - vec2(0.0, uTexelSize.y * 2.0));
                    float u = rawPersonAt(vTexCoord + vec2(0.0, uTexelSize.y * 2.0));
                    vec2 grad = vec2(r - l, u - d);
                    float gradLength = length(grad);
                    if (gradLength < 0.0002) return sourceRgb;
                    vec2 inward = grad / gradLength;
                    float reach = 1.5 + 3.0 * clamp(uDehaloV44, 0.0, 1.0);
                    vec2 interiorUv = clamp(vTexCoord + inward * uTexelSize * reach, vec2(0.0), vec2(1.0));
                    vec3 interiorRgb = texture2D(uTexSampler, interiorUv).rgb;
                    float weight = clamp(uDehaloV44, 0.0, 1.0) * edge * 0.82;
                    return mix(sourceRgb, interiorRgb, weight);
                }

                float chromaDistance(vec3 rgb) {
                    return distance(rgbToChroma(rgb), rgbToChroma(uKeyRgb));
                }

                float filteredChromaDistance(vec3 centerRgb) {
                    vec2 halfPx = uTexelSize * 0.5;
                    vec2 point0 = vec2(uTexelSize.x, halfPx.y);
                    vec2 point1 = vec2(halfPx.x, -uTexelSize.y);
                    float dist = chromaDistance(texture2D(uTexSampler, vTexCoord - point0).rgb);
                    dist += chromaDistance(texture2D(uTexSampler, vTexCoord + point0).rgb);
                    dist += chromaDistance(texture2D(uTexSampler, vTexCoord - point1).rgb);
                    dist += chromaDistance(texture2D(uTexSampler, vTexCoord + point1).rgb);
                    dist *= 2.0;
                    dist += chromaDistance(centerRgb);
                    return dist / 9.0;
                }

                float keyedMatte(float distanceToKey) {
                    float baseMask = distanceToKey - max(0.0, uChromaSimilarity);
                    float softness = max(0.0005, uChromaSoftness);
                    return pow(clamp(baseMask / softness, 0.0, 1.0), 1.5);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, vTexCoord);
                    float matte = 1.0;
                    vec3 rgb = source.rgb;

                    if (uMode > 0.5 && uMode < 1.5) {
                        matte = refinedPersonMask();
                        rgb = dehaloPersonRgb(rgb, matte);
                    } else if (uMode >= 1.5) {
                        float distanceToKey = filteredChromaDistance(rgb);
                        matte = keyedMatte(distanceToKey);

                        float baseMask = distanceToKey - max(0.0, uChromaSimilarity);
                        float spillRange = max(0.005, uChromaSoftness * 2.5);
                        float cleanColor = pow(clamp(baseMask / spillRange, 0.0, 1.0), 1.5);
                        float spillWeight = (1.0 - cleanColor) * clamp(uSpill, 0.0, 1.0);
                        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                        rgb = mix(rgb, vec3(luma), spillWeight * 0.78);
                    }

                    gl_FragColor = vec4(rgb, source.a * clamp(matte, 0.0, 1.0));
                }
            """
        }
    }
}
