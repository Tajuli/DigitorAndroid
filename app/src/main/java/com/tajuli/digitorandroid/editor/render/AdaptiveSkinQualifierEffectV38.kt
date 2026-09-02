package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.opengl.GLES20
import android.os.SystemClock
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.tajuli.digitorandroid.editor.model.BeautyFaceTrackV28
import com.tajuli.digitorandroid.editor.model.CreatorLookKernelV37
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.activeCreatorLookV37
import com.tajuli.digitorandroid.editor.model.skinQualifierStrengthV38
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28

/**
 * V38 adaptive COLOR qualifier for Skin Bright and portrait-friendly creator looks.
 *
 * This intentionally does NOT render through a face/skin mask. Face geometry is used only as an
 * automatic eyedropper: three safe points inside the detected face provide a representative skin
 * chroma. The shader then qualifies pixels by COLOR only. If that same color exists on a scarf,
 * hand, neck, dress or another object, it receives the same response. Therefore there is no ellipse,
 * segmentation edge or semantic-mask boundary that can look like a bright layer pasted on a face.
 *
 * The qualifier is YCbCr/HSL-style rather than a hard threshold: chroma distance, saturation and
 * luminance all have wide soft falloff. Brightening is luminance-first with highlight protection and
 * hue-preserving gamut fit. If face geometry is not ready, a broad generic skin-chroma center keeps
 * the first frame responsive; once geometry arrives the eyedropper center updates automatically.
 */
@UnstableApi
internal class AdaptiveSkinQualifierEffectV38 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): AdaptiveSkinQualifierEffectV38? {
            val skinBright = clip.skinQualifierStrengthV38()
            val look = clip.activeCreatorLookV37()
            val lookUsesQualifier = look?.kernel == CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE
            return if (preview || skinBright > .001f || lookUsesQualifier) {
                AdaptiveSkinQualifierEffectV38(clip, preview)
            } else {
                null
            }
        }
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ true,
        /* texturePoolCapacity = */ 1,
    ) {
        private val appContext = context.applicationContext
        private val program: GlProgram
        private var faceTrack: BeautyFaceTrackV28? = BeautyFaceTrackStoreV28.load(appContext, clip)
        private var lastFaceTrackRefreshMs = 0L

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

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val beautyAmount = currentClip.skinQualifierStrengthV38().coerceIn(0f, 1.5f)
                val activeLook = currentClip.activeCreatorLookV37()
                val lookAmount = if (activeLook?.kernel == CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE) {
                    activeLook.intensity.coerceIn(0f, 1f) * .34f
                } else {
                    0f
                }
                // Explicit Skin Bright wins over the smaller automatic look refinement instead of
                // stacking twice and overexposing the portrait.
                val amount = maxOf(beautyAmount, lookAmount)
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                val geometry = refreshFaceTrack(currentClip)?.geometryAt(sourceUs)
                val face = geometry?.face?.normalized()

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatUniform("uAmount", amount)
                program.setFloatUniform("uHasFace", if (face == null) 0f else 1f)
                program.setFloatsUniform(
                    "uFaceRect",
                    if (face == null) floatArrayOf(0f, 0f, 0f, 0f)
                    else floatArrayOf(face.left, face.top, face.right, face.bottom),
                )
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
                BeautyFaceTrackStoreV28.load(appContext, currentClip)?.let { faceTrack = it }
            }
            return faceTrack
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
            private const val FACE_TRACK_REFRESH_MS = 350L

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
                uniform float uAmount;
                uniform float uHasFace;
                uniform vec4 uFaceRect;
                varying vec2 vTexCoord;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chromaSpan(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

                vec2 cbcr(vec3 c) {
                    return vec2(
                        -.114572 * c.r - .385428 * c.g + .500000 * c.b + .5,
                         .500000 * c.r - .454153 * c.g - .045847 * c.b + .5
                    );
                }

                float genericSkinPrior(vec3 c) {
                    float y = luma(c);
                    vec2 cc = cbcr(c);
                    float cbBand = smoothstep(.30, .38, cc.x) * (1.0 - smoothstep(.50, .57, cc.x));
                    float crBand = smoothstep(.49, .53, cc.y) * (1.0 - smoothstep(.66, .72, cc.y));
                    float satGate = smoothstep(.025, .09, chromaSpan(c));
                    return cbBand * crBand * satGate * smoothstep(.12, .28, y) * (1.0 - smoothstep(.93, .985, y));
                }

                // Face rectangle is Android top-left coordinates. Convert an internal face point to
                // texture coordinates only for COLOR SAMPLING; this position never enters the pixel
                // qualifier itself.
                vec2 faceSampleUv(float nx, float ny) {
                    vec2 p = mix(uFaceRect.xy, uFaceRect.zw, vec2(nx, ny));
                    return safeUv(vec2(p.x, 1.0 - p.y));
                }

                vec2 autoQualifierCenter() {
                    vec2 fallbackCenter = vec2(.462, .557);
                    if (uHasFace < .5) return fallbackCenter;

                    // Cheeks + lower forehead avoid eyes, lips and the scarf/hair boundary.
                    vec3 a = texture2D(uTexSampler, faceSampleUv(.31, .57)).rgb;
                    vec3 b = texture2D(uTexSampler, faceSampleUv(.69, .57)).rgb;
                    vec3 c = texture2D(uTexSampler, faceSampleUv(.50, .31)).rgb;
                    float wa = max(genericSkinPrior(a), .08);
                    float wb = max(genericSkinPrior(b), .08);
                    float wc = max(genericSkinPrior(c), .08);
                    float sumW = wa + wb + wc;
                    vec2 measured = (cbcr(a) * wa + cbcr(b) * wb + cbcr(c) * wc) / max(sumW, .001);

                    // Keep bad detections from producing an arbitrary qualifier. The wide clamp still
                    // supports a broad range of skin tones and warm/cool lighting.
                    measured.x = clamp(measured.x, .34, .52);
                    measured.y = clamp(measured.y, .50, .68);
                    return measured;
                }

                float qualifierWeight(vec3 c, vec2 center) {
                    float y = luma(c);
                    float span = chromaSpan(c);
                    vec2 cc = cbcr(c);
                    vec2 delta = vec2((cc.x - center.x) / .075, (cc.y - center.y) / .078);
                    float distance = length(delta);
                    float colorGate = 1.0 - smoothstep(.48, 1.55, distance);
                    float satGate = smoothstep(.025, .095, span);
                    float darkGate = smoothstep(.10, .27, y);
                    float whiteGate = 1.0 - smoothstep(.88, .985, y);
                    return clamp(colorGate * satGate * darkGate * whiteGate, 0.0, 1.0);
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
                    float highlightDesat = smoothstep(.72, .96, targetY) * .12 * amount;
                    float safeScale = chromaFitScale(chromaVector, targetY);
                    return vec3(targetY) + chromaVector * min(1.0 - highlightDesat, safeScale);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uAmount <= .0001) {
                        gl_FragColor = source;
                        return;
                    }

                    vec3 rgb = source.rgb;
                    float y = luma(rgb);
                    vec2 center = autoQualifierCenter();
                    float q = qualifierWeight(rgb, center);

                    // Color-only relight. There is intentionally no face/skin ellipse or segmentation
                    // multiplication here. Same input color => same brightness response everywhere.
                    float amount = clamp(uAmount, 0.0, 1.5);
                    float headroom = pow(max(1.0 - y, 0.0), .72);
                    float highlightProtect = 1.0 - smoothstep(.72, .97, y);
                    float lift = .145 * amount * headroom * (.72 + .28 * highlightProtect);
                    float targetY = min(y + lift * q, .972);
                    vec3 lifted = relightPreserveHue(rgb, targetY, amount);

                    gl_FragColor = vec4(clamp(lifted, 0.0, 1.0), source.a);
                }
            """
        }
    }
}
