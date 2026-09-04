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
 * Packs a prepared 512x512 RGB bitmap into normalized NCHW MODNet floats on OpenGL ES 3.
 *
 * LiteRT 2.1.5's Kotlin TensorBuffer still accepts a host FloatArray, so one bulk GPU readback is
 * unavoidable here. The expensive per-pixel Kotlin Color extraction, normalization and CHW
 * transposition are removed from the hot loop. Unsupported float-render-target devices simply do
 * not construct this helper and keep the proven CPU fallback.
 */
internal class GpuModNetTensorPackerV48 : AutoCloseable {
    private val egl = PackerEglV48()
    private val program: Int
    private val framebuffer: Int
    private val inputTexture: Int
    private val outputTexture: Int
    private val quad: FloatBuffer = floatBufferV48(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    private val readback = ByteBuffer
        .allocateDirect(MODNET_PACK_FLOATS_V48 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        egl.makeCurrent()
        try {
            val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
            check(version.contains("OpenGL ES 3")) { "OpenGL ES 3 is required for GPU tensor packing" }
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            check(extensions.contains("GL_EXT_color_buffer_float")) {
                "GL_EXT_color_buffer_float is required for GPU tensor packing"
            }
            program = compileProgramV48(VERTEX_SHADER, PACK_FRAGMENT_SHADER)
            framebuffer = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            inputTexture = createInputTextureV48()
            outputTexture = createOutputTextureV48()

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                outputTexture,
                0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "V48 MODNet float framebuffer is incomplete"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } finally {
            // Do not leave our auxiliary context current while LiteRT builds/runs its own GPU delegate.
            egl.releaseCurrent()
        }
    }

    /** Returns true when [destination] was filled with 3x512x512 normalized NCHW floats. */
    fun pack(prepared512: Bitmap, destination: FloatArray): Boolean {
        check(prepared512.width == MODNET_PACK_SIZE_V48 && prepared512.height == MODNET_PACK_SIZE_V48)
        check(destination.size >= MODNET_PACK_FLOATS_V48)
        egl.makeCurrent()
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, prepared512, 0)
            checkGlV48("upload MODNet input")

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glViewport(0, 0, MODNET_PACK_WIDTH_V48, MODNET_PACK_HEIGHT_V48)
            GLES30.glUseProgram(program)
            val position = GLES30.glGetAttribLocation(program, "aPosition")
            quad.position(0)
            GLES30.glEnableVertexAttribArray(position)
            GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uInput"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlV48("pack MODNet tensor")

            readback.clear()
            GLES30.glReadPixels(
                0,
                0,
                MODNET_PACK_WIDTH_V48,
                MODNET_PACK_HEIGHT_V48,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                readback,
            )
            checkGlV48("read MODNet tensor")
            readback.rewind()
            readback.get(destination, 0, MODNET_PACK_FLOATS_V48)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return true
        } finally {
            // LiteRT/MediaPipe/temporal GL all run on this same worker thread. Return it with no
            // foreign EGL context bound so each GPU runtime can bind its own context safely.
            egl.releaseCurrent()
        }
    }

    override fun close() {
        runCatching {
            egl.makeCurrent()
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(inputTexture), 0)
            GLES30.glDeleteTextures(1, intArrayOf(outputTexture), 0)
            egl.releaseCurrent()
        }
        egl.close()
    }

    private fun createInputTextureV48(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createOutputTextureV48(): Int {
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

    private companion object {
        const val VERTEX_SHADER = """#version 300 es
            in vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val PACK_FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            precision highp int;
            uniform sampler2D uInput;
            out vec4 outValue;

            const int SIZE = 512;
            const int PLANE = 262144;

            float tensorValue(int index) {
                int channel = index / PLANE;
                int spatial = index - channel * PLANE;
                int x = spatial - (spatial / SIZE) * SIZE;
                int y = spatial / SIZE;
                vec3 rgb = texelFetch(uInput, ivec2(x, y), 0).rgb;
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
    }
}

private class PackerEglV48 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get V48 packer EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize V48 packer EGL" }
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
            "Could not choose V48 ES3 packer EGL config"
        }
        val config = configs[0] ?: error("Missing V48 packer EGL config")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create V48 ES3 packer context" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create V48 packer pbuffer" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind V48 packer EGL context" }
    }

    fun releaseCurrent() {
        check(
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            ),
        ) { "Could not release V48 packer EGL context" }
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
    check(status[0] == GLES30.GL_TRUE) { "V48 MODNet packer link failed: $log" }
    return program
}

private fun compileShaderV48(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    val log = GLES30.glGetShaderInfoLog(shader)
    check(status[0] == GLES30.GL_TRUE) { "V48 MODNet packer shader compile failed: $log" }
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
