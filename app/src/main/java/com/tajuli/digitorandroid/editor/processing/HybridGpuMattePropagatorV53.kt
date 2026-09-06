package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.roundToInt

private const val HYBRID_FLOW_LONG_EDGE_V53 = 192
private const val HYBRID_FLOW_BLOCK_V53 = 12
private const val HYBRID_RESET_GAP_US_V53 = 850_000L
private const val HYBRID_SCENE_CUT_MAD_V53 = 46f

/**
 * Fast between-anchor matte propagation for Pro Cutout.
 *
 * PP-MattingV2 still creates high-quality semantic anchor mattes. Between anchors this class keeps
 * the previous source/matte on GPU, estimates a low-resolution local motion field and warps the
 * previous matte to the current frame. Only the final grayscale matte is read back for the existing
 * mask cache contract. A scene cut / timestamp discontinuity returns null so the caller refreshes
 * with PP-MattingV2 instead of propagating stale semantics.
 */
internal class HybridGpuMattePropagatorV53 : AutoCloseable {
    private val egl = HybridOffscreenEglV53()
    private val quad = floatBufferOfV53(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val flowProgram = compileProgram(FULLSCREEN_VERTEX, FLOW_FRAGMENT)
    private val warpProgram = compileProgram(FULLSCREEN_VERTEX, WARP_FRAGMENT)
    private val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]

    private var currentSourceTex = createTexture()
    private var previousSourceTex = createTexture()
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

    fun seed(source: Bitmap, matte: Bitmap, sourceTimeUs: Long) {
        egl.makeCurrent()
        ensureMatteTextures(matte.width, matte.height)
        uploadBitmap(previousSourceTex, source)
        uploadBitmap(previousMatteTex, matte)
        previousTimeUs = sourceTimeUs
        previousSignature = lumaSignature(source)
        hasHistory = true
    }

    fun propagate(
        source: Bitmap,
        sourceTimeUs: Long,
        hairMask: Bitmap?,
        hairStrength: Float,
    ): Bitmap? {
        if (!hasHistory || sourceTimeUs <= previousTimeUs || sourceTimeUs - previousTimeUs > HYBRID_RESET_GAP_US_V53) {
            return null
        }
        val signature = lumaSignature(source)
        if (isSceneCut(previousSignature, signature)) return null

        egl.makeCurrent()
        uploadBitmap(currentSourceTex, source)
        if (hairMask != null) uploadBitmap(hairTex, hairMask)

        val flowSize = flowDimensions(source.width, source.height)
        ensureFlowTexture(flowSize.first, flowSize.second)
        renderFlow(flowSize.first, flowSize.second)
        renderWarp(
            hasHair = hairMask != null,
            sourceWidth = source.width,
            sourceHeight = source.height,
            hairStrength = hairStrength,
        )
        val result = readOutputMatte(matteWidth, matteHeight)

        // Output becomes the next previous matte; current source becomes previous source.
        currentSourceTex = previousSourceTex.also { previousSourceTex = currentSourceTex }
        outputMatteTex = previousMatteTex.also { previousMatteTex = outputMatteTex }
        previousTimeUs = sourceTimeUs
        previousSignature = signature
        return result
    }

    private fun renderFlow(flowWidth: Int, flowHeight: Int) {
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("hybrid flow")
    }

    private fun renderWarp(
        hasHair: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        hairStrength: Float,
    ) {
        attachTexture(outputMatteTex)
        GLES20.glViewport(0, 0, matteWidth, matteHeight)
        GLES20.glUseProgram(warpProgram)
        bindQuad(warpProgram)
        bindSampler(warpProgram, "uPreviousMatte", previousMatteTex, 0)
        bindSampler(warpProgram, "uFlow", flowTex, 1)
        bindSampler(warpProgram, "uHair", hairTex, 2)
        uniform1f(warpProgram, "uHasHair", if (hasHair) 1f else 0f)
        uniform1f(warpProgram, "uHairStrength", hairStrength.coerceIn(0f, 1f))
        val flowSize = flowDimensions(sourceWidth, sourceHeight)
        uniform2f(
            warpProgram,
            "uSearchStep",
            1f / flowSize.first.coerceAtLeast(1).toFloat(),
            1f / flowSize.second.coerceAtLeast(1).toFloat(),
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("hybrid warp")
    }

    private fun readOutputMatte(width: Int, height: Int): Bitmap {
        attachTexture(outputMatteTex)
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        checkGl("hybrid readback")
        bytes.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(bytes)
        }
    }

    private fun flowDimensions(width: Int, height: Int): Pair<Int, Int> {
        val longEdge = max(width, height).coerceAtLeast(1)
        if (longEdge <= HYBRID_FLOW_LONG_EDGE_V53) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val scale = HYBRID_FLOW_LONG_EDGE_V53 / longEdge.toFloat()
        return (width * scale).roundToInt().coerceAtLeast(16) to
            (height * scale).roundToInt().coerceAtLeast(16)
    }

    private fun ensureMatteTextures(width: Int, height: Int) {
        if (width == matteWidth && height == matteHeight) return
        matteWidth = width.coerceAtLeast(1)
        matteHeight = height.coerceAtLeast(1)
        allocateTexture(previousMatteTex, matteWidth, matteHeight)
        allocateTexture(outputMatteTex, matteWidth, matteHeight)
    }

    private fun ensureFlowTexture(width: Int, height: Int) {
        val cols = max(1, (width + HYBRID_FLOW_BLOCK_V53 - 1) / HYBRID_FLOW_BLOCK_V53)
        val rows = max(1, (height + HYBRID_FLOW_BLOCK_V53 - 1) / HYBRID_FLOW_BLOCK_V53)
        if (cols == flowCols && rows == flowRows) return
        flowCols = cols
        flowRows = rows
        allocateTexture(flowTex, flowCols, flowRows)
    }

    private fun lumaSignature(bitmap: Bitmap): IntArray {
        val cols = 12
        val rows = 12
        val out = IntArray(cols * rows)
        for (gy in 0 until rows) {
            val y = ((gy + .5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until cols) {
                val x = ((gx + .5f) * bitmap.width / cols).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                out[gy * cols + gx] = (77 * r + 150 * g + 29 * b) shr 8
            }
        }
        return out
    }

    private fun isSceneCut(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size) return true
        var sum = 0L
        for (i in current.indices) sum += abs(current[i] - previous[i])
        return sum.toFloat() / current.size.coerceAtLeast(1) >= HYBRID_SCENE_CUT_MAD_V53
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
            "Hybrid cutout framebuffer incomplete"
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
        checkGl("hybrid texture upload")
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
        check(status[0] == GLES20.GL_TRUE) { "Hybrid GPU program link failed: $log" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Hybrid GPU shader compile failed: $log" }
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
            GLES20.glDeleteProgram(warpProgram)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(
                6,
                intArrayOf(
                    currentSourceTex,
                    previousSourceTex,
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
                for (int yy = -5; yy <= 5; yy++) {
                    for (int xx = -5; xx <= 5; xx++) {
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
                float photo = clamp(1.0 - best / 0.24, 0.0, 1.0);
                float unique = clamp(((second - best) / max(second, 0.0001)) * 5.0, 0.0, 1.0);
                float confidence = photo * (0.30 + 0.70 * unique);
                gl_FragColor = vec4((bestX + 5.0) / 10.0, (bestY + 5.0) / 10.0, confidence, 1.0);
            }
        """

        const val WARP_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uPreviousMatte;
            uniform sampler2D uFlow;
            uniform sampler2D uHair;
            uniform vec2 uSearchStep;
            uniform float uHasHair;
            uniform float uHairStrength;

            void main() {
                vec3 flow = texture2D(uFlow, vUv).rgb;
                float dx = flow.r * 10.0 - 5.0;
                float dy = flow.g * 10.0 - 5.0;
                vec2 previousUv = clamp(vUv + vec2(dx * uSearchStep.x, dy * uSearchStep.y), vec2(0.0), vec2(1.0));
                float alpha = texture2D(uPreviousMatte, previousUv).r;

                // Low-confidence flow is safer near static regions than an aggressive displacement.
                float stillAlpha = texture2D(uPreviousMatte, vUv).r;
                alpha = mix(stillAlpha, alpha, smoothstep(0.10, 0.36, flow.b));

                if (uHasHair > 0.5 && uHairStrength > 0.001) {
                    float hair = texture2D(uHair, vUv).r;
                    float uncertainty = clamp(4.0 * alpha * (1.0 - alpha), 0.0, 1.0);
                    float weight = hair * uHairStrength * (0.08 + 0.38 * uncertainty);
                    alpha = max(alpha, alpha + (1.0 - alpha) * weight);
                }
                gl_FragColor = vec4(alpha, alpha, alpha, 1.0);
            }
        """
    }
}

private class HybridOffscreenEglV53 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get EGL display for hybrid cutout" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize hybrid EGL" }
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
            "Could not choose hybrid EGL config"
        }
        val config = configs[0] ?: error("Missing hybrid EGL config")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create hybrid EGL context" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create hybrid EGL surface" }
        makeCurrent()
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind hybrid EGL context" }
    }

    override fun close() {
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(display, surface) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        runCatching { EGL14.eglTerminate(display) }
    }
}

private fun floatBufferOfV53(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }
