package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val GPU_FLOW_LONG_EDGE_V47 = 192
private const val GPU_FLOW_BLOCK_V47 = 12
private const val GPU_FLOW_RESET_GAP_US_V47 = 1_200_000L
private const val GPU_FLOW_SCENE_CUT_MAD_V47 = 52f

/**
 * Near-fully-GPU V47 temporal matte stage.
 *
 * The current/previous source, current PP-MattingV2 matte and optional hair mask are textures. Pass
 * one estimates a local motion field on a small block grid entirely in a fragment shader. Pass two
 * fuses hair only in the uncertain portrait band and uses the flow-warped previous matte only as a
 * small edge-stability hint. The current PP-MattingV2 alpha remains authoritative, so a bad or
 * ambiguous block match can never punch large holes into confident foreground/background regions.
 * Only the final grayscale RGBA matte is read back for the existing cache contract.
 */
internal class GpuSpatialFlowTemporalMatteStabilizerV47 : AutoCloseable {
    private val egl = OffscreenEglV47()
    private val quad = floatBufferOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )

    private val flowProgram = compileProgram(FULLSCREEN_VERTEX, FLOW_FRAGMENT)
    private val matteProgram = compileProgram(FULLSCREEN_VERTEX, MATTE_FRAGMENT)
    private val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]

    private var currentSourceTex = createTexture()
    private var previousSourceTex = createTexture()
    private var currentMatteTex = createTexture()
    private var previousMatteTex = createTexture()
    private var outputMatteTex = createTexture()
    private var hairTex = createTexture()
    private var flowTex = createTexture()

    private var matteWidth = 0
    private var matteHeight = 0
    private var flowCols = 0
    private var flowRows = 0
    private var previousTimeUs = Long.MIN_VALUE
    private var previousSignature: IntArray? = null
    private var hasHistory = false

    fun stabilize(
        source: Bitmap,
        currentMatte: Bitmap,
        hairMask: Bitmap?,
        sourceTimeUs: Long,
        hairStrength: Float,
        temporalStrength: Float,
    ): Bitmap {
        egl.makeCurrent()
        ensureOutputTextures(currentMatte.width, currentMatte.height)
        val flowSize = flowDimensions(source.width, source.height)
        ensureFlowTexture(flowSize.first, flowSize.second)

        uploadBitmap(currentSourceTex, source)
        uploadBitmap(currentMatteTex, currentMatte)
        if (hairMask != null) uploadBitmap(hairTex, hairMask)

        val signature = lumaSignature(source)
        val reset = !hasHistory ||
            sourceTimeUs <= previousTimeUs ||
            sourceTimeUs - previousTimeUs > GPU_FLOW_RESET_GAP_US_V47 ||
            isSceneCut(previousSignature, signature)

        if (!reset) {
            renderFlow(source.width, source.height, flowSize.first, flowSize.second)
        }
        renderMatte(
            hasPrevious = !reset,
            hasHair = hairMask != null,
            sourceWidth = source.width,
            sourceHeight = source.height,
            hairStrength = hairStrength,
            temporalStrength = temporalStrength,
        )

        val result = readOutputMatte(currentMatte.width, currentMatte.height)

        // Keep GPU history resident: no full-frame Bitmap copies for temporal state.
        currentSourceTex = previousSourceTex.also { previousSourceTex = currentSourceTex }
        outputMatteTex = previousMatteTex.also { previousMatteTex = outputMatteTex }
        previousTimeUs = sourceTimeUs
        previousSignature = signature
        hasHistory = true
        return result
    }

    private fun renderFlow(sourceWidth: Int, sourceHeight: Int, flowWidth: Int, flowHeight: Int) {
        attachTexture(flowTex)
        GLES20.glViewport(0, 0, flowCols, flowRows)
        GLES20.glUseProgram(flowProgram)
        bindQuad(flowProgram)
        bindSampler(flowProgram, "uCurrent", currentSourceTex, 0)
        bindSampler(flowProgram, "uPrevious", previousSourceTex, 1)
        uniform2f(
            flowProgram,
            "uSearchStep",
            1f / flowWidth.coerceAtLeast(1).toFloat(),
            1f / flowHeight.coerceAtLeast(1).toFloat(),
        )
        // The source aspect is kept for future tuning and prevents optimizer-specific dead uniforms.
        uniform2f(flowProgram, "uSourceSize", sourceWidth.toFloat(), sourceHeight.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("render V47 flow")
    }

    private fun renderMatte(
        hasPrevious: Boolean,
        hasHair: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        hairStrength: Float,
        temporalStrength: Float,
    ) {
        attachTexture(outputMatteTex)
        GLES20.glViewport(0, 0, matteWidth, matteHeight)
        GLES20.glUseProgram(matteProgram)
        bindQuad(matteProgram)
        bindSampler(matteProgram, "uCurrentMatte", currentMatteTex, 0)
        bindSampler(matteProgram, "uPreviousMatte", previousMatteTex, 1)
        bindSampler(matteProgram, "uFlow", flowTex, 2)
        bindSampler(matteProgram, "uHair", hairTex, 3)
        uniform1f(matteProgram, "uHasPrevious", if (hasPrevious) 1f else 0f)
        uniform1f(matteProgram, "uHasHair", if (hasHair) 1f else 0f)
        uniform1f(matteProgram, "uHairStrength", hairStrength.coerceIn(0f, 1f))
        uniform1f(matteProgram, "uTemporalStrength", temporalStrength.coerceIn(0f, .92f))
        val flowSize = flowDimensions(sourceWidth, sourceHeight)
        uniform2f(
            matteProgram,
            "uSearchStep",
            1f / flowSize.first.coerceAtLeast(1).toFloat(),
            1f / flowSize.second.coerceAtLeast(1).toFloat(),
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("render V47 matte")
    }

    private fun readOutputMatte(width: Int, height: Int): Bitmap {
        attachTexture(outputMatteTex)
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        checkGl("read V47 matte")
        bytes.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            // Output RGB is grayscale and A=255, so RGBA byte order maps safely to ARGB_8888 memory.
            bitmap.copyPixelsFromBuffer(bytes)
        }
    }

    private fun ensureOutputTextures(width: Int, height: Int) {
        if (width == matteWidth && height == matteHeight) return
        matteWidth = width.coerceAtLeast(1)
        matteHeight = height.coerceAtLeast(1)
        allocateTexture(previousMatteTex, matteWidth, matteHeight)
        allocateTexture(outputMatteTex, matteWidth, matteHeight)
    }

    private fun ensureFlowTexture(cols: Int, rows: Int) {
        val blockCols = max(1, (cols + GPU_FLOW_BLOCK_V47 - 1) / GPU_FLOW_BLOCK_V47)
        val blockRows = max(1, (rows + GPU_FLOW_BLOCK_V47 - 1) / GPU_FLOW_BLOCK_V47)
        if (blockCols == flowCols && blockRows == flowRows) return
        flowCols = blockCols
        flowRows = blockRows
        allocateTexture(flowTex, flowCols, flowRows)
    }

    private fun flowDimensions(width: Int, height: Int): Pair<Int, Int> {
        val longEdge = max(width, height).coerceAtLeast(1)
        if (longEdge <= GPU_FLOW_LONG_EDGE_V47) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val scale = GPU_FLOW_LONG_EDGE_V47 / longEdge.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(16) to
            (height * scale).roundToInt().coerceAtLeast(16)
    }

    private fun lumaSignature(bitmap: Bitmap): IntArray {
        val cols = 16
        val rows = 16
        val out = IntArray(cols * rows)
        for (gy in 0 until rows) {
            val y = ((gy + .5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until cols) {
                val x = ((gx + .5f) * bitmap.width / cols).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[gy * cols + gx] = (77 * r + 150 * g + 29 * b) shr 8
            }
        }
        return out
    }

    private fun isSceneCut(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size) return true
        var sum = 0L
        for (i in current.indices) sum += abs(current[i] - previous[i])
        return sum.toFloat() / current.size.coerceAtLeast(1) >= GPU_FLOW_SCENE_CUT_MAD_V47
    }

    private fun attachTexture(texture: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "V47 GPU framebuffer is incomplete"
        }
    }

    private fun bindQuad(program: Int) {
        val location = GLES20.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, quad)
    }

    private fun bindSampler(program: Int, name: String, texture: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun uniform1f(program: Int, name: String, value: Float) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), value)
    }

    private fun uniform2f(program: Int, name: String, x: Float, y: Float) {
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, name), x, y)
    }

    private fun uploadBitmap(texture: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGl("upload V47 texture")
    }

    private fun allocateTexture(texture: Int, width: Int, height: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        allocateTexture(ids[0], 1, 1)
        return ids[0]
    }

    private fun compileProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(status[0] == GLES20.GL_TRUE) { "V47 GPU program link failed: $log" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "V47 GPU shader compile failed: $log" }
        return shader
    }

    private fun checkGl(label: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$label failed with GL error 0x${error.toString(16)}" }
    }

    override fun close() {
        runCatching {
            egl.makeCurrent()
            GLES20.glDeleteProgram(flowProgram)
            GLES20.glDeleteProgram(matteProgram)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(
                7,
                intArrayOf(
                    currentSourceTex,
                    previousSourceTex,
                    currentMatteTex,
                    previousMatteTex,
                    outputMatteTex,
                    hairTex,
                    flowTex,
                ),
                0,
            )
        }
        egl.close()
    }

    private companion object {
        const val FULLSCREEN_VERTEX = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aPosition * 0.5 + 0.5;
            }
        """

        const val FLOW_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uCurrent;
            uniform sampler2D uPrevious;
            uniform vec2 uSearchStep;
            uniform vec2 uSourceSize;

            float lumaAt(sampler2D tex, vec2 uv) {
                vec3 rgb = texture2D(tex, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
                return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            float patchSad(vec2 currentUv, vec2 previousUv) {
                vec2 px = uSearchStep;
                float sad = abs(lumaAt(uCurrent, currentUv) - lumaAt(uPrevious, previousUv));
                sad += abs(lumaAt(uCurrent, currentUv + vec2(px.x, 0.0)) - lumaAt(uPrevious, previousUv + vec2(px.x, 0.0)));
                sad += abs(lumaAt(uCurrent, currentUv - vec2(px.x, 0.0)) - lumaAt(uPrevious, previousUv - vec2(px.x, 0.0)));
                sad += abs(lumaAt(uCurrent, currentUv + vec2(0.0, px.y)) - lumaAt(uPrevious, previousUv + vec2(0.0, px.y)));
                sad += abs(lumaAt(uCurrent, currentUv - vec2(0.0, px.y)) - lumaAt(uPrevious, previousUv - vec2(0.0, px.y)));
                return sad * 0.2;
            }

            void main() {
                float best = 10.0;
                float second = 10.0;
                float bestX = 0.0;
                float bestY = 0.0;
                for (int yy = -6; yy <= 6; yy++) {
                    for (int xx = -6; xx <= 6; xx++) {
                        vec2 candidate = vUv + vec2(float(xx) * uSearchStep.x, float(yy) * uSearchStep.y);
                        float sad = patchSad(vUv, candidate);
                        if (sad < best) {
                            second = best;
                            best = sad;
                            bestX = float(xx);
                            bestY = float(yy);
                        } else if (sad < second) {
                            second = sad;
                        }
                    }
                }
                float photo = clamp(1.0 - best / 0.22, 0.0, 1.0);
                float unique = clamp(((second - best) / max(second, 0.0001)) * 5.0, 0.0, 1.0);
                float confidence = photo * (0.35 + 0.65 * unique);
                gl_FragColor = vec4((bestX + 6.0) / 12.0, (bestY + 6.0) / 12.0, confidence, 1.0);
            }
        """

        const val MATTE_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uCurrentMatte;
            uniform sampler2D uPreviousMatte;
            uniform sampler2D uFlow;
            uniform sampler2D uHair;
            uniform vec2 uSearchStep;
            uniform float uHasPrevious;
            uniform float uHasHair;
            uniform float uHairStrength;
            uniform float uTemporalStrength;

            void main() {
                float currentAlpha = texture2D(uCurrentMatte, vUv).r;
                float uncertainty = clamp(4.0 * currentAlpha * (1.0 - currentAlpha), 0.0, 1.0);

                // Hair may recover only the uncertain PP-MattingV2 boundary. It never removes alpha.
                if (uHasHair > 0.5 && uHairStrength > 0.001) {
                    float hair = texture2D(uHair, vUv).r;
                    float hairWeight = hair * uHairStrength * (0.10 + 0.46 * uncertainty);
                    currentAlpha = max(currentAlpha, currentAlpha + (1.0 - currentAlpha) * hairWeight);
                }

                if (uHasPrevious < 0.5 || uTemporalStrength <= 0.001) {
                    gl_FragColor = vec4(currentAlpha, currentAlpha, currentAlpha, 1.0);
                    return;
                }

                // The previous V47 shader contained an aggressive "unsupported foreground birth"
                // gate. On textureless/low-detail regions a block-flow match can be ambiguous while
                // still reporting high confidence; that gate then drove valid current foreground
                // toward a wrongly warped previous background. The visible result was exactly the
                // horizontal/rectangular transparency corruption seen in the target-phone export.
                // PP-MattingV2 is now authoritative: flow can smooth only uncertain edges.
                if (currentAlpha <= 0.03 || currentAlpha >= 0.97) {
                    gl_FragColor = vec4(currentAlpha, currentAlpha, currentAlpha, 1.0);
                    return;
                }

                vec3 flow = texture2D(uFlow, vUv).rgb;
                float dx = flow.r * 12.0 - 6.0;
                float dy = flow.g * 12.0 - 6.0;
                vec2 previousUv = clamp(
                    vUv + vec2(dx * uSearchStep.x, dy * uSearchStep.y),
                    vec2(0.0),
                    vec2(1.0)
                );
                float previousAlpha = texture2D(uPreviousMatte, previousUv).r;

                float disagreement = abs(currentAlpha - previousAlpha);
                float agreement = clamp(1.0 - disagreement / 0.28, 0.0, 1.0);
                float occlusion = 1.0 - smoothstep(0.16, 0.36, disagreement);
                float edgeUncertainty = clamp(4.0 * currentAlpha * (1.0 - currentAlpha), 0.0, 1.0);
                float reliableFlow = smoothstep(0.22, 0.55, flow.b);

                // Keep the temporal hint deliberately weak. Even a bad block vector is limited to a
                // few alpha points relative to this frame's PP-MattingV2 result, so corruption cannot
                // accumulate through the resident previous-matte history.
                float weight = uTemporalStrength * 0.34 * edgeUncertainty * reliableFlow * agreement * occlusion;
                weight = clamp(weight, 0.0, 0.30);
                float candidate = mix(currentAlpha, previousAlpha, weight);
                float maxCorrection = mix(0.018, 0.075, edgeUncertainty);
                float alpha = clamp(candidate, currentAlpha - maxCorrection, currentAlpha + maxCorrection);
                alpha = clamp(alpha, 0.0, 1.0);
                gl_FragColor = vec4(alpha, alpha, alpha, 1.0);
            }
        """
    }
}

/** Minimal ES2 pbuffer context dedicated to analysis; preview/export GL contexts remain untouched. */
private class OffscreenEglV47 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get EGL display for V47 cutout" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize EGL for V47 cutout" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Could not choose EGL config for V47 cutout"
        }
        val config = configs[0] ?: error("Missing EGL config for V47 cutout")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context for V47 cutout" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create EGL pbuffer for V47 cutout" }
        makeCurrent()
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind V47 EGL context" }
    }

    override fun close() {
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(display, surface) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        runCatching { EGL14.eglTerminate(display) }
    }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }
