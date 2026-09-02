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
import com.tajuli.digitorandroid.editor.model.AdvancedColorGrade
import com.tajuli.digitorandroid.editor.model.BEAUTY_EYE_POP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_HAIR_BROW_DARK_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_PINK_LIP_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_BRIGHT_V28
import com.tajuli.digitorandroid.editor.model.BEAUTY_SKIN_SMOOTH_V28
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.LogWheels
import com.tajuli.digitorandroid.editor.model.NodeCorrections
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import kotlin.math.abs
import kotlin.math.max

/**
 * V35 high-precision creator-look renderer.
 *
 * PR #47 stored creator looks as ordinary final serial color nodes. That was convenient, but the
 * complete look recipe was then baked into the same ARGB_8888 33^3 LUT used by the normal grading
 * graph. 33^3 is sufficient for interactive correction work, but strong creator looks can still
 * quantize smooth skin/highlight gradients and an 8-bit cube has no access to neighbouring pixels
 * for texture-preserving detail recovery.
 *
 * V35 keeps the node-graph UX/persistence contract but gives creator looks their own deterministic
 * GPU stage. The normal LUT evaluates every non-look part of the graph, while this shader evaluates
 * each final creator-look node in graph order with highp float components. It additionally provides:
 *
 *  - luminance-domain exposure/contrast/highlight/shadow shaping with a soft highlight shoulder;
 *  - gamut-aware chroma fitting instead of channel clipping;
 *  - conservative skin-chroma protection so warm/cinematic looks do not push faces orange/magenta;
 *  - small adaptive luma-detail recovery that restores micro-texture without sharpening hard edges;
 *  - the exact same shader in realtime preview and export.
 *
 * The existing filter cards and saved projects remain valid. Beauty filter nodes are deliberately
 * excluded; they continue through BeautyFaceEffectV34's semantic BASE/FINISH passes.
 */
@UnstableApi
internal class CreatorLookEffectV35 private constructor(
    private val clip: TimelineClip,
    private val nodeId: String,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(clip, nodeId, preview)

    companion object {
        fun effectsForClip(clip: TimelineClip, preview: Boolean): List<CreatorLookEffectV35> =
            CreatorLookNodeV35.managedLookNodes(clip.nodeGraph.nodes)
                .map { node -> CreatorLookEffectV35(clip, node.id, preview) }
    }

    private class Program(
        private val clip: TimelineClip,
        private val nodeId: String,
        private val preview: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ true,
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
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                val evaluated = currentClip.nodeAnimations.evaluateGraph(currentClip.nodeGraph, sourceUs)
                val node = evaluated.nodes.firstOrNull { it.id == nodeId }
                    ?.takeIf(CreatorLookNodeV35::isManagedLookNode)

                val corrections = node?.corrections ?: NodeCorrections()
                val log = node?.advancedColor?.log ?: LogWheels()
                val strength = node?.let(CreatorLookNodeV35::lookStrength) ?: 0f

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
                )
                program.setFloatUniform("uExposure", corrections.exposure)
                program.setFloatUniform("uContrast", corrections.contrast / 100f)
                program.setFloatUniform("uSaturation", corrections.saturation / 100f)
                program.setFloatUniform("uTemperature", corrections.temperature / 100f)
                program.setFloatUniform("uTint", corrections.tint / 100f)
                program.setFloatUniform("uHighlights", corrections.highlights / 100f)
                program.setFloatUniform("uShadows", corrections.shadows / 100f)
                program.setFloatUniform("uHueTurns", corrections.hue / 360f)
                program.setFloatUniform("uColorBoost", corrections.colorBoost / 100f)
                program.setFloatsUniform("uLogShadows", log.shadows.asUniformV35())
                program.setFloatsUniform("uLogMidtones", log.midtones.asUniformV35())
                program.setFloatsUniform("uLogHighlights", log.highlights.asUniformV35())
                program.setFloatsUniform("uLogGlobal", log.global.asUniformV35())
                program.setFloatUniform("uShadowRange", log.shadowRange)
                program.setFloatUniform("uHighlightRange", log.highlightRange)
                program.setFloatUniform("uLookStrength", strength)
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
                uniform float uLookStrength;
                varying vec2 vTexCoord;

                const float PI2 = 6.28318530718;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                float luma(vec3 c) { return dot(c, vec3(.2126, .7152, .0722)); }
                float chromaSpan(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }

                float softShoulder(float y) {
                    if (y <= .72) return y;
                    float d = y - .72;
                    return .72 + d / (1.0 + d * 2.8);
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

                vec3 withLumaPreserveHue(vec3 c, float targetY) {
                    float y = luma(c);
                    vec3 chromaVector = c - vec3(y);
                    float fit = chromaFitScale(chromaVector, targetY);
                    return vec3(targetY) + chromaVector * fit;
                }

                vec3 fitToGamutPreserveHue(vec3 c) {
                    float y = clamp(luma(c), 0.0, 1.0);
                    vec3 chromaVector = c - vec3(luma(c));
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

                float skinProbability(vec3 c) {
                    float y = luma(c);
                    float cb = -.168736 * c.r - .331264 * c.g + .5 * c.b + .5;
                    float cr = .5 * c.r - .418688 * c.g - .081312 * c.b + .5;
                    float cbBand = smoothstep(.25, .31, cb) * (1.0 - smoothstep(.51, .58, cb));
                    float crBand = smoothstep(.35, .41, cr) * (1.0 - smoothstep(.65, .72, cr));
                    float lumaGate = smoothstep(.07, .18, y) * (1.0 - smoothstep(.91, 1.0, y));
                    return cbBand * crBand * lumaGate;
                }

                float microDetail(vec2 uv, vec3 center) {
                    vec2 o = uTexelSize * 1.35;
                    float yc = luma(center);
                    float yx1 = luma(texture2D(uTexSampler, safeUv(uv + vec2(o.x, 0.0))).rgb);
                    float yx2 = luma(texture2D(uTexSampler, safeUv(uv - vec2(o.x, 0.0))).rgb);
                    float yy1 = luma(texture2D(uTexSampler, safeUv(uv + vec2(0.0, o.y))).rgb);
                    float yy2 = luma(texture2D(uTexSampler, safeUv(uv - vec2(0.0, o.y))).rgb);
                    float blurY = yc * .44 + (yx1 + yx2 + yy1 + yy2) * .14;
                    return yc - blurY;
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, safeUv(vTexCoord));
                    if (uLookStrength <= .0001) {
                        gl_FragColor = source;
                        return;
                    }

                    vec3 src = source.rgb;
                    vec3 rgb = src;

                    // Tone shaping is luminance-first. This keeps hue vectors stable while the
                    // screenshot-style midtone lift/highlight protection is applied.
                    float y0 = luma(rgb);
                    float y = y0 * exp2(uExposure);
                    float shouldered = softShoulder(y);
                    y = mix(y, shouldered, clamp(.18 + uLookStrength * .42, 0.0, .60));
                    y = (y - .5) * max(0.0, 1.0 + uContrast) + .5;
                    float highW = smoothstep(.55, .96, y);
                    float shadowW = 1.0 - smoothstep(.04, .46, y);
                    y += uHighlights * .20 * highW + uShadows * .20 * shadowW;
                    y = clamp(y, 0.0, 1.0);
                    rgb = withLumaPreserveHue(rgb, y);

                    // White-balance and hue/chroma shaping stay modest and are fitted back to gamut
                    // later rather than clipped per channel.
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
                    float satScale = max(0.0, 1.0 + uSaturation) * vibrance;
                    rgb = vec3(yc) + (rgb - vec3(yc)) * satScale;

                    // Preserve the existing Resolve-like log-wheel semantics, but execute them in
                    // high precision instead of first quantizing the whole look into an 8-bit cube.
                    float logY = clamp(luma(rgb), 0.0, 1.0);
                    float logShadowW = 1.0 - smoothstep(
                        max(0.0, uShadowRange - .12),
                        min(1.0, uShadowRange + .12),
                        logY
                    );
                    float logHighW = smoothstep(
                        max(0.0, uHighlightRange - .12),
                        min(1.0, uHighlightRange + .12),
                        logY
                    );
                    float logMidW = clamp(1.0 - logShadowW - logHighW, 0.0, 1.0);
                    vec3 wheelDelta =
                        logShadowW * (uLogShadows.rgb + vec3(uLogShadows.a)) +
                        logMidW * (uLogMidtones.rgb + vec3(uLogMidtones.a)) +
                        logHighW * (uLogHighlights.rgb + vec3(uLogHighlights.a)) +
                        uLogGlobal.rgb + vec3(uLogGlobal.a);
                    rgb += wheelDelta * .22;
                    rgb = fitToGamutPreserveHue(rgb);

                    // Faces keep the tone move, while a fraction of the creator-look chroma shift is
                    // pulled back toward the source hue. This is deliberately color-based rather
                    // than face-box based so it also behaves naturally on hands/arms.
                    float skin = skinProbability(src);
                    vec3 toneOnly = withLumaPreserveHue(src, luma(rgb));
                    float skinProtect = skin * mix(.20, .40, uLookStrength);
                    rgb = mix(rgb, toneOnly, skinProtect);

                    // Restore only micro-detail. Large edge residuals are suppressed to avoid halos,
                    // and highlights receive less recovery to keep skin/specular rolloff smooth.
                    float detail = microDetail(vTexCoord, src);
                    float edgeGate = 1.0 - smoothstep(.045, .16, abs(detail));
                    float highlightGate = 1.0 - smoothstep(.64, .96, luma(src));
                    float detailGain = mix(.07, .16, uLookStrength);
                    float targetY = clamp(luma(rgb) + detail * detailGain * edgeGate * highlightGate, 0.0, 1.0);
                    rgb = withLumaPreserveHue(rgb, targetY);
                    rgb = fitToGamutPreserveHue(rgb);

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
                }
            """
        }
    }
}

/** Shared creator-look classification/neutralisation contract used by the LUT and V35 shader. */
internal object CreatorLookNodeV35 {
    private const val FILTER_NODE_PREFIX_V28 = "FilterV28 · "
    private const val LEGACY_FILTER_NODE_PREFIX_V27 = "Filter · "

    private val beautyEffectNames = setOf(
        BEAUTY_SKIN_BRIGHT_V28,
        BEAUTY_SKIN_SMOOTH_V28,
        BEAUTY_PINK_LIP_V28,
        BEAUTY_HAIR_BROW_DARK_V28,
        BEAUTY_EYE_POP_V28,
    )

    fun isManagedLookNode(node: ColorNode): Boolean =
        (node.label.startsWith(FILTER_NODE_PREFIX_V28) || node.label.startsWith(LEGACY_FILTER_NODE_PREFIX_V27)) &&
            node.effects.none { effect -> effect.name in beautyEffectNames }

    fun managedLookNodes(nodes: List<ColorNode>): List<ColorNode> =
        nodes.filter(::isManagedLookNode)
            .sortedWith(compareBy<ColorNode> { it.position.x }.thenBy { it.position.y })

    /**
     * Remove only the preset recipe fields handled by CreatorLookEffectV35. Primary wheels, curves
     * and qualifiers remain in the normal color graph, so advanced manual edits are not discarded.
     */
    fun neutralizeRecipeForLut(node: ColorNode): ColorNode {
        if (!isManagedLookNode(node)) return node
        return node.copy(
            corrections = NodeCorrections(),
            advancedColor = node.advancedColor.copy(log = LogWheels()),
        )
    }

    fun lookStrength(node: ColorNode): Float {
        val c = node.corrections
        val log = node.advancedColor.log
        val wheelPeak = listOf(log.shadows, log.midtones, log.highlights, log.global).maxOf { wheel ->
            max(
                max(abs(wheel.red), abs(wheel.green)),
                max(abs(wheel.blue), abs(wheel.luma)),
            )
        }
        return maxOf(
            abs(c.exposure) / .12f,
            abs(c.contrast) / 18f,
            abs(c.saturation) / 22f,
            abs(c.temperature) / 25f,
            abs(c.tint) / 12f,
            abs(c.highlights) / 20f,
            abs(c.shadows) / 20f,
            abs(c.hue) / 18f,
            abs(c.colorBoost) / 24f,
            wheelPeak / .07f,
        ).coerceIn(0f, 1f)
    }
}

private fun com.tajuli.digitorandroid.editor.model.ColorWheelValue.asUniformV35(): FloatArray =
    floatArrayOf(red, green, blue, luma)
