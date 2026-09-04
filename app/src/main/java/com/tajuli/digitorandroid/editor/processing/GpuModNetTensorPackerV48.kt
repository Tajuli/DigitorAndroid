package com.tajuli.digitorandroid.editor.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val MODNET_PACK_SIZE_V48 = 512
private const val MODNET_PACK_PLANE_V48 = MODNET_PACK_SIZE_V48 * MODNET_PACK_SIZE_V48
private const val MODNET_PACK_FLOATS_V48 = MODNET_PACK_PLANE_V48 * 3
private const val MODNET_PACK_WIDTH_V48 = 512
private const val MODNET_PACK_HEIGHT_V48 = 384 // 512*384*4 == 3*512*512 floats

/**
 * OpenGL ES 3 pre/post processor for MODNet.
 *
 * V49 removes both CPU Canvas scaling stages from the accelerated path. The source analysis Bitmap
 * is uploaded once, letterboxed/resampled and normalized into NCHW floats by a shader, then the
 * host-visible LiteRT tensor boundary is crossed in one bulk readback. After inference, the returned
 * alpha FloatArray is bulk-uploaded and the crop/scale back to analysis resolution is also rendered
 * by a shader. There are no per-pixel Kotlin color/alpha loops on the supported GPU fast path.
 *
 * LiteRT 2.1.5 Kotlin still exposes host TensorBuffer read/write APIs, so literal zero-copy GPU
 * model I/O is not available here; these two bulk boundaries are intentionally the only model-side
 * CPU transfers.
 */
internal class GpuModNetTensorPackerV48 : AutoCloseable {
    private val egl = PackerEglV48()
    private val packProgram: Int
    private val alphaProgram: Int
    private val framebuffer: Int
    private val inputTexture: Int
    private val packedTexture: Int
    private val alphaTexture: Int
    private val alphaOutputTexture: Int
    private val quad: FloatBuffer = floatBufferV48(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    private val tensorReadback = ByteBuffer
        .allocateDirect(MODNET_PACK_FLOATS_V48 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val alphaUpload = ByteBuffer
        .allocateDirect(MODNET_PACK_PLANE_V48 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var alphaOutputWidth = 1
    private var alphaOutputHeight = 1
    private var alphaReadback = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    init {
        egl.makeCurrent()
        try {
            val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            check(version.contains("OpenGL ES 3")) { "OpenGL ES 3 is required for GPU tensor packing" }
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            check(extensions.contains("GL_EXT_color_buffer_float")) {
                "GL_EXT_color_buffer_float is required for GPU tensor packing"
            }
            packProgram = compileProgramV48(FULLSCREEN_VERTEX, PACK_FRAGMENT_SHADER)
            alphaProgram = compileProgramV48(FULLSCREEN_VERTEX, ALPHA_FRAGMENT_SHADER)
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            inputTexture = createInputTextureV48()
            packedTexture = createPackedTextureV48()
            alphaTexture = createAlphaTextureV49()
            alphaOutputTexture = createAlphaOutputTextureV49()

            attachTextureV49(packedTexture)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "V49 MODNet float framebuffer is incomplete"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } finally {
            // Do not leave our auxiliary context current while LiteRT builds/runs its own GPU delegate.
            egl.releaseCurrent()
        }
    }

    /** Compatibility overload used by the instrumentation test and any already-prepared 512 input. */
    fun pack(prepared512: Bitmap, destination: FloatArray): Boolean = pack(
        source = prepared512,
        letterboxLeft = 0,
        letterboxTop = 0,
        letterboxWidth = MODNET_PACK_SIZE_V48,
        letterboxHeight = MODNET_PACK_SIZE_V48,
        destination = destination,
    )

    /**
     * GPU letterbox + bilinear resize + RGB normalization + NCHW packing.
     * [letterboxLeft]/[letterboxTop]/[letterboxWidth]/[letterboxHeight] describe where [source]
     * would have been drawn inside a 512x512 MODNet canvas.
     */
    fun pack(
        source: Bitmap,
        letterboxLeft: Int,
        letterboxTop: Int,
        letterboxWidth: Int,
        letterboxHeight: Int,
        destination: FloatArray,
    ): Boolean {
        check(destination.size >= MODNET_PACK_FLOATS_V48)
        check(letterboxWidth > 0 && letterboxHeight > 0)
        egl.makeCurrent()
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
            checkGlV48("upload MODNet source")

            attachTextureV49(packedTexture)
            GLES30.glViewport(0, 0, MODNET_PACK_WIDTH_V48, MODNET_PACK_HEIGHT_V48)
            GLES30.glUseProgram(packProgram)
            bindQuadV49(packProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(packProgram, "uInput"), 0)
            GLES30.glUniform4i(
                GLES30.glGetUniformLocation(packProgram, "uLetterbox"),
                letterboxLeft,
                letterboxTop,
                letterboxWidth,
                letterboxHeight,
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlV48("pack MODNet tensor")

            tensorReadback.clear()
            GLES30.glReadPixels(
                0,
                0,
                MODNET_PACK_WIDTH_V48,
                MODNET_PACK_HEIGHT_V48,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                tensorReadback,
            )
            checkGlV48("read MODNet tensor")
            tensorReadback.rewind()
            tensorReadback.get(destination, 0, MODNET_PACK_FLOATS_V48)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return true
        } finally {
            // LiteRT/MediaPipe/temporal GL all run on this same worker thread. Return it with no
            // foreign EGL context bound so each GPU runtime can bind its own context safely.
            egl.releaseCurrent()
        }
    }

    /**
     * Bulk-upload MODNet alpha and crop/scale it back to analysis resolution on GPU. The returned
     * grayscale Bitmap is the unavoidable cache/MediaPipe interoperability boundary used by the
     * existing temporal renderer and async matte writer.
     */
    fun unpackAlpha(
        alpha: FloatArray,
        letterboxLeft: Int,
        letterboxTop: Int,
        letterboxWidth: Int,
        letterboxHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Bitmap {
        check(alpha.size >= MODNET_PACK_PLANE_V48)
        check(outputWidth > 0 && outputHeight > 0)
        egl.makeCurrent()
        try {
            alphaUpload.clear()
            alphaUpload.put(alpha, 0, MODNET_PACK_PLANE_V48)
            alphaUpload.rewind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTexture)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                MODNET_PACK_SIZE_V48,
                MODNET_PACK_SIZE_V48,
                GLES30.GL_RED,
                GLES30.GL_FLOAT,
                alphaUpload,
            )
            checkGlV48("upload MODNet alpha")

            ensureAlphaOutputV49(outputWidth, outputHeight)
            attachTextureV49(alphaOutputTexture)
            GLES30.glViewport(0, 0, outputWidth, outputHeight)
            GLES30.glUseProgram(alphaProgram)
            bindQuadV49(alphaProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(alphaProgram, "uAlpha"), 0)
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(alphaProgram, "uLetterbox"),
                letterboxLeft.toFloat(),
                letterboxTop.toFloat(),
                letterboxWidth.toFloat(),
                letterboxHeight.toFloat(),
            )
            GLES30.glUniform2f(
                GLES30.glGetUniformLocation(alphaProgram, "uOutputSize"),
                outputWidth.toFloat(),
                outputHeight.toFloat(),
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlV48("render MODNet alpha")

            alphaReadback.clear()
            GLES30.glReadPixels(
                0,
                0,
                outputWidth,
                outputHeight,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                alphaReadback,
            )
            checkGlV48("read MODNet alpha")
            alphaReadback.rewind()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.copyPixelsFromBuffer(alphaReadback)
            }
        } finally {
            egl.releaseCurrent()
        }
    }

    override fun close() {
        runCatching {
            egl.makeCurrent()
            GLES30.glDeleteProgram(packProgram)
            GLES30.glDeleteProgram(alphaProgram)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(
                4,
                intArrayOf(inputTexture, packedTexture, alphaTexture, alphaOutputTexture),
                0,
            )
            egl.releaseCurrent()
        }
        egl.close()
    }

    private fun bindQuadV49(program: Int) {
        val position = GLES30.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad)
    }

    private fun attachTextureV49(texture: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
    }

    private fun ensureAlphaOutputV49(width: Int, height: Int) {
        if (width == alphaOutputWidth && height == alphaOutputHeight) return
        alphaOutputWidth = width
        alphaOutputHeight = height
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, alphaOutputTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        checkGlV48("resize MODNet alpha output")
        alphaReadback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }

    private fun createInputTextureV48(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createPackedTextureV48(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA32F,
            MODNET_PACK_WIDTH_V48,
            MODNET_PACK_HEIGHT_V48,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_FLOAT,
            null,
        )
        checkGlV48("allocate MODNet tensor target")
        return ids[0]
    }

    private fun createAlphaTextureV49(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R32F,
            MODNET_PACK_SIZE_V48,
            MODNET_PACK_SIZE_V48,
            0,
            GLES30.GL_RED,
            GLES30.GL_FLOAT,
            null,
        )
        checkGlV48("allocate MODNet alpha texture")
        return ids[0]
    }

    private fun createAlphaOutputTextureV49(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            1,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        return ids[0]
    }

    private companion object {
        const val FULLSCREEN_VERTEX = """#version 300 es
            in vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val PACK_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            precision highp int;
            uniform sampler2D uInput;
            uniform ivec4 uLetterbox;
            out vec4 outValue;

            const int SIZE = 512;
            const int PLANE = 262144;

            vec3 letterboxedRgb(int x, int y) {
                int left = uLetterbox.x;
                int top = uLetterbox.y;
                int width = uLetterbox.z;
                int height = uLetterbox.w;
                if (x < left || y < top || x >= left + width || y >= top + height) {
                    return vec3(0.0);
                }
                vec2 uv = vec2(
                    (float(x - left) + 0.5) / float(width),
                    (float(y - top) + 0.5) / float(height)
                );
                return texture(uInput, uv).rgb;
            }

            float tensorValue(int index) {
                int channel = index / PLANE;
                int spatial = index - channel * PLANE;
                int x = spatial - (spatial / SIZE) * SIZE;
                int y = spatial / SIZE;
                vec3 rgb = letterboxedRgb(x, y);
                float value = channel == 0 ? rgb.r : (channel == 1 ? rgb.g : rgb.b);
                return value * 2.0 - 1.0;
            }

            void main() {
                int x = int(gl_FragCoord.x - 0.5);
                int y = int(gl_FragCoord.y - 0.5);
                int base = (y * 512 + x) * 4;
                outValue = vec4(
                    tensorValue(base),
                    tensorValue(base + 1),
                    tensorValue(base + 2),
                    tensorValue(base + 3)
                );
            }
        """

        const val ALPHA_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D uAlpha;
            uniform vec4 uLetterbox;
            uniform vec2 uOutputSize;
            out vec4 outValue;

            void main() {
                vec2 outputUv = gl_FragCoord.xy / uOutputSize;
                vec2 modelPixel = uLetterbox.xy + outputUv * uLetterbox.zw;
                vec2 modelUv = modelPixel / 512.0;
                float a = clamp(texture(uAlpha, modelUv).r, 0.0, 1.0);
                outValue = vec4(a, a, a, 1.0);
            }
        """
    }
}

private class PackerEglV48 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get V49 packer EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize V49 packer EGL" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "Could not choose V49 ES3 packer EGL config"
        }
        val config = configs[0] ?: error("Missing V49 packer EGL config")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create V49 ES3 packer context" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create V49 packer pbuffer" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind V49 packer EGL context" }
    }

    fun releaseCurrent() {
        check(
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            ),
        ) { "Could not release V49 packer EGL context" }
    }

    override fun close() {
        runCatching { releaseCurrent() }
        runCatching { EGL14.eglDestroySurface(display, surface) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        // Do not eglTerminate(EGL_DEFAULT_DISPLAY): decoder/temporal/model contexts may share it.
    }
}

private fun compileProgramV48(vertex: String, fragment: String): Int {
    val vs = compileShaderV48(GLES30.GL_VERTEX_SHADER, vertex)
    val fs = compileShaderV48(GLES30.GL_FRAGMENT_SHADER, fragment)
    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vs)
    GLES30.glAttachShader(program, fs)
    GLES30.glLinkProgram(program)
    val status = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
    val log = GLES30.glGetProgramInfoLog(program)
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    check(status[0] == GLES30.GL_TRUE) { "V49 MODNet GPU program link failed: $log" }
    return program
}

private fun compileShaderV48(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    val log = GLES30.glGetShaderInfoLog(shader)
    check(status[0] == GLES30.GL_TRUE) { "V49 MODNet GPU shader compile failed: $log" }
    return shader
}

private fun checkGlV48(label: String) {
    val error = GLES30.glGetError()
    check(error == GLES30.GL_NO_ERROR) { "$label failed with GL error 0x${error.toString(16)}" }
}

private fun floatBufferV48(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }
