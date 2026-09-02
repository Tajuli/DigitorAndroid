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
import com.tajuli.digitorandroid.editor.model.CINEMATIC_DARK_REFERENCE_V37
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.CreatorLookKernelV37
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.activeCreatorLookV37
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V37 full-frame creator-look renderer.
 *
 * The Normal / Digitor / CapCut comparison exposed a fundamental V36 mistake: a LOOK was being
 * tuned with skin-specific logic. That made the grade resemble a bright patch attached to a face
 * while the rest of the image stayed close to Normal. V37 removes all spatial semantics from LOOKS.
 * A look is now a deterministic RGB -> RGB transform for every pixel.
 *
 * Realtime preview keeps this one shader resident, so changing a marker/intensity is only a data
 * update and becomes visible on the next submitted frame. Export uses the exact same shader.
 *
 * For the supplied Cinematic Dark reference, V37 generates a tiny 17^3 Digitor-owned LUT from the
 * calibrated cubic RGB model when the shader is created. The LUT is flattened into a 289x17 RGBA8
 * atlas and sampled with two bilinear texture reads (manual blue-slice interpolation). This is much
 * cheaper per pixel than evaluating the cubic model directly, works on GLES2, and preserves the
 * full-frame mapping measured from the reference. No CapCut LUT, code, model or asset is bundled.
 */
@UnstableApi
internal class CreatorLookEffectV37 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, preview)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): CreatorLookEffectV37? {
            val active = clip.activeCreatorLookV37() != null
            return if (preview || active) CreatorLookEffectV37(clip, preview) else null
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
        private val referenceLutTexture: Int

        init {
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { shader ->
                    shader.setBufferAttribute(
                        "aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                    )
                }
                referenceLutTexture = createReferenceLutTexture()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val active = currentClip.activeCreatorLookV37()
                val corrections = active?.preset?.corrections ?: NodeCorrections()
                val log = active?.preset?.log ?: LogWheels()
                val intensity = active?.intensity ?: 0f
                val kernelMode = if (active?.kernel == CreatorLookKernelV37.CINEMATIC_DARK_REFERENCE) 1f else 0f

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uReferenceLut", referenceLutTexture, 1)
                program.setFloatUniform("uReferenceLutSize", REFERENCE_LUT_SIZE.toFloat())
                program.setFloatUniform("uKernelMode", kernelMode)
                program.setFloatUniform("uIntensity", intensity)
                program.setFloatUniform("uExposure", corrections.exposure)
                program.setFloatUniform("uContrast", corrections.contrast / 100f)
                program.setFloatUniform("uSaturation", corrections.saturation / 100f)
                program.setFloatUniform("uTemperature", corrections.temperature / 100f)
                program.setFloatUniform("uTint", corrections.tint / 100f)
                program.setFloatUniform("uHighlights", corrections.highlights / 100f)
                program.setFloatUniform("uShadows", corrections.shadows / 100f)
                program.setFloatUniform("uHueTurns", corrections.hue / 360f)
                program.setFloatUniform("uColorBoost", corrections.colorBoost / 100f)
                program.setFloatsUniform("uLogShadows", log.shadows.uniformV37())
                program.setFloatsUniform("uLogMidtones", log.midtones.uniformV37())
                program.setFloatsUniform("uLogHighlights", log.highlights.uniformV37())
                program.setFloatsUniform("uLogGlobal", log.global.uniformV37())
                program.setFloatUniform("uShadowRange", log.shadowRange)
                program.setFloatUniform("uHighlightRange", log.highlightRange)
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
            GLES20.glDeleteTextures(1, intArrayOf(referenceLutTexture), 0)
            try {
                program.delete()
                GlUtil.checkGlError()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private fun createReferenceLutTexture(): Int {
            val size = REFERENCE_LUT_SIZE
            val width = size * size
            val height = size
            val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

            // Atlas layout: X = blueSlice * size + red, Y = green. Texture origin and generated row
            // order both start at green=0, so shader coordinates can use the same normalized axes.
            for (greenIndex in 0 until size) {
                val g = greenIndex.toFloat() / (size - 1).toFloat()
                for (blueIndex in 0 until size) {
                    val b = blueIndex.toFloat() / (size - 1).toFloat()
                    for (redIndex in 0 until size) {
                        val r = redIndex.toFloat() / (size - 1).toFloat()
                        val mapped = CINEMATIC_DARK_REFERENCE_V37.mapRgb(r, g, b)
                        bytes.put((mapped[0] * 255f + .5f).toInt().coerceIn(0, 255).toByte())
                        bytes.put((mapped[1] * 255f + .5f).toInt().coerceIn(0, 255).toByte())
                        bytes.put((mapped[2] * 255f + .5f).toInt().coerceIn(0, 255).toByte())
                        bytes.put(0xFF.toByte())
                    }
                }
            }
            bytes.flip()

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                bytes,
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GlUtil.checkGlError()
            return texture
        }

        companion object {
            private const val REFERENCE_LUT_SIZE = 17

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
                uniform sampler2D uReferenceLut;
                uniform float uReferenceLutSize;
                uniform float uKernelMode;
                uniform float uIntensity;
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
                varying vec2 vTexCoord;

                const float PI2 = 6.28318530718;
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chromaSpan(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

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

                float softShoulder(float y) {
                    if (y <= .72) return y;
                    float d = y - .72;
                    return .72 + d / (1.0 + d * 2.6);
                }

                vec3 genericGlobalLook(vec3 src) {
                    vec3 rgb = src;
                    float y = luma(rgb) * exp2(uExposure);
                    y = mix(y, softShoulder(y), .32);
                    y = (y - .5) * max(0.0, 1.0 + uContrast) + .5;
                    float highW = smoothstep(.55, .96, y);
                    float shadowW = 1.0 - smoothstep(.04, .46, y);
                    y += uHighlights * .20 * highW + uShadows * .20 * shadowW;
                    rgb = withLumaPreserveHue(rgb, clamp(y, 0.0, 1.0));

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
                    return fitToGamutPreserveHue(rgb);
                }

                vec3 referenceLutLook(vec3 source) {
                    vec3 c = clamp(source, 0.0, 1.0);
                    float size = uReferenceLutSize;
                    float last = size - 1.0;
                    float atlasWidth = size * size;
                    float blue = c.b * last;
                    float slice0 = floor(blue);
                    float slice1 = min(slice0 + 1.0, last);
                    float blueMix = blue - slice0;
                    float redTexel = c.r * last;
                    float greenTexel = c.g * last;
                    float x0 = (slice0 * size + redTexel + .5) / atlasWidth;
                    float x1 = (slice1 * size + redTexel + .5) / atlasWidth;
                    float y = (greenTexel + .5) / size;
                    vec3 a = texture2D(uReferenceLut, vec2(x0, y)).rgb;
                    vec3 b = texture2D(uReferenceLut, vec2(x1, y)).rgb;
                    return mix(a, b, blueMix);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, clamp(vTexCoord, vec2(.001), vec2(.999)));
                    if (uIntensity <= .0001) {
                        gl_FragColor = source;
                        return;
                    }

                    vec3 graded = uKernelMode > .5
                        ? referenceLutLook(source.rgb)
                        : genericGlobalLook(source.rgb);

                    // Intensity is a final source/look blend. The LUT and generic recipes always
                    // represent their 100% look and slider response stays predictable.
                    gl_FragColor = vec4(mix(source.rgb, graded, clamp(uIntensity, 0.0, 1.0)), source.a);
                }
            """
        }
    }
}

private fun ColorWheelValue.uniformV37(): FloatArray = floatArrayOf(red, green, blue, luma)
