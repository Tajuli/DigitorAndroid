package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

private const val DECODER_TIMEOUT_US_V47 = 10_000L
private const val FINAL_TARGET_EARLY_TOLERANCE_US_V47 = 50_000L

/**
 * Sequential V49 video sampler. MediaCodec decodes once from the trim start instead of asking
 * MediaMetadataRetriever to perform hundreds/thousands of independent seeks. Decoder output lands
 * on an OES SurfaceTexture, then an offscreen GL pass rotates/scales selected frames to analysis
 * size. HIGH mode can emit every decoded source frame; LOW/MEDIUM emit only requested anchors.
 *
 * The emitted Bitmap is ownership-transferred to [onFrame]. This removes V48's second full-frame
 * ARGB copy before GPU inference. If the callback throws before accepting ownership the decoder
 * still recycles the Bitmap, so failure paths stay leak-safe.
 */
internal class GpuSequentialCutoutDecoderV47(
    private val context: Context,
    private val analysisLongEdge: Int,
) {
    fun decodeTargets(
        uri: Uri,
        startUs: Long,
        endUs: Long,
        targetTimesUs: List<Long>,
        emitEveryFrame: Boolean = false,
        onFrame: (sourceTimeUs: Long, bitmap: Bitmap) -> Unit,
    ): Int {
        val targets = targetTimesUs
            .asSequence()
            .map { it.coerceIn(startUs, (endUs - 1L).coerceAtLeast(startUs)) }
            .distinct()
            .sorted()
            .toList()
        if (!emitEveryFrame && targets.isEmpty()) return 0

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: OesFrameReaderV47? = null
        try {
            extractor.setDataSource(context, uri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No video track for Pro Cutout")
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Video MIME is missing")
            val width = format.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(1)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(1)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            reader = OesFrameReaderV47(width, height, rotation, analysisLongEdge)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, reader.surface, null, 0)
            codec.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var targetIndex = 0
            var emitted = 0
            var idleRounds = 0

            while (
                !outputDone &&
                (emitEveryFrame || targetIndex < targets.size) &&
                idleRounds < 2_000
            ) {
                var progressed = false
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DECODER_TIMEOUT_US_V47)
                    if (inputIndex >= 0) {
                        progressed = true
                        val sampleTime = extractor.sampleTime
                        val buffer = codec.getInputBuffer(inputIndex)
                        if (sampleTime < 0L || sampleTime > endUs + FINAL_TARGET_EARLY_TOLERANCE_US_V47) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer?.clear()
                            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, DECODER_TIMEOUT_US_V47)
                when {
                    outputIndex >= 0 -> {
                        progressed = true
                        val pts = info.presentationTimeUs
                        val withinTrim = pts >= startUs && pts < endUs
                        val pending = targets.getOrNull(targetIndex)
                        val nearPending = pending != null && pts + FINAL_TARGET_EARLY_TOLERANCE_US_V47 >= pending
                        val render = withinTrim && (emitEveryFrame || nearPending)
                        codec.releaseOutputBuffer(outputIndex, render)
                        if (render) {
                            reader.awaitAndUpdateFrame()
                            val bitmap = reader.readBitmap()
                            var transferred = false
                            try {
                                onFrame(
                                    pts.coerceIn(startUs, (endUs - 1L).coerceAtLeast(startUs)),
                                    bitmap,
                                )
                                transferred = true
                            } finally {
                                if (!transferred && !bitmap.isRecycled) bitmap.recycle()
                            }
                            emitted++
                            if (!emitEveryFrame) {
                                while (
                                    targetIndex < targets.size &&
                                    targets[targetIndex] <= pts + FINAL_TARGET_EARLY_TOLERANCE_US_V47
                                ) {
                                    targetIndex++
                                }
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                }

                idleRounds = if (progressed) 0 else idleRounds + 1
            }
            check(idleRounds < 2_000) { "MediaCodec stalled during Pro Cutout analysis" }
            return emitted
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
            runCatching { extractor.release() }
        }
    }
}

/** OES decoder surface + offscreen scaler. */
private class OesFrameReaderV47(
    encodedWidth: Int,
    encodedHeight: Int,
    private val rotationDegrees: Int,
    analysisLongEdge: Int,
) : AutoCloseable {
    private val egl = DecoderEglV47()
    private val callbackThread = HandlerThread("DigitorCutoutV49Frame").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val frameLock = Object()
    @Volatile private var frameAvailable = false

    private val oesTexture: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    private val program: Int
    private val framebuffer: Int
    private val outputTexture: Int
    private val quad: FloatBuffer = floatBufferOfV47(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    private val textureMatrix = FloatArray(16)
    private val outputWidth: Int
    private val outputHeight: Int
    private val readbackBytes: ByteBuffer

    init {
        egl.makeCurrent()
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) encodedHeight else encodedWidth
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) encodedWidth else encodedHeight
        val longEdge = max(rotatedWidth, rotatedHeight).coerceAtLeast(1)
        val scale = if (longEdge <= analysisLongEdge) 1f else analysisLongEdge / longEdge.toFloat()
        outputWidth = (rotatedWidth * scale).roundToInt().coerceAtLeast(1)
        outputHeight = (rotatedHeight * scale).roundToInt().coerceAtLeast(1)
        readbackBytes = ByteBuffer
            .allocateDirect(outputWidth * outputHeight * 4)
            .order(ByteOrder.nativeOrder())

        oesTexture = createOesTexture()
        surfaceTexture = SurfaceTexture(oesTexture).apply {
            setDefaultBufferSize(encodedWidth, encodedHeight)
            setOnFrameAvailableListener(
                {
                    synchronized(frameLock) {
                        frameAvailable = true
                        frameLock.notifyAll()
                    }
                },
                callbackHandler,
            )
        }
        surface = Surface(surfaceTexture)
        program = compileProgramV47(VERTEX_SHADER, OES_FRAGMENT_SHADER)
        framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        outputTexture = create2dTexture(outputWidth, outputHeight)
    }

    fun awaitAndUpdateFrame(timeoutMs: Long = 2_500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(frameLock) {
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                check(remaining > 0L) { "Timed out waiting for MediaCodec cutout frame" }
                frameLock.wait(remaining)
            }
            frameAvailable = false
        }
        egl.makeCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
    }

    fun readBitmap(): Bitmap {
        egl.makeCurrent()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            outputTexture,
            0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "V49 decoder framebuffer is incomplete"
        }
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uTexMatrix"),
            1,
            false,
            textureMatrix,
            0,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotation"), rotationDegrees.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlV47("render sequential decode")

        readbackBytes.clear()
        GLES20.glReadPixels(
            0,
            0,
            outputWidth,
            outputHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            readbackBytes,
        )
        checkGlV47("read sequential decode")
        readbackBytes.rewind()
        return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            // Fragment shader swaps R/B and vertically maps for Android's little-endian ARGB memory.
            bitmap.copyPixelsFromBuffer(readbackBytes)
        }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun create2dTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
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
        return ids[0]
    }

    override fun close() {
        runCatching {
            egl.makeCurrent()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
            GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        callbackThread.quitSafely()
        runCatching { callbackThread.join(500L) }
        egl.close()
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aPosition * 0.5 + 0.5;
            }
        """

        const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            uniform float uRotation;

            vec2 rotateUv(vec2 uv) {
                if (uRotation > 269.0 && uRotation < 271.0) return vec2(1.0 - uv.y, uv.x);
                if (uRotation > 179.0 && uRotation < 181.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
                if (uRotation > 89.0 && uRotation < 91.0) return vec2(uv.y, 1.0 - uv.x);
                return uv;
            }

            void main() {
                // Flip for glReadPixels->Bitmap row order, then apply video rotation and the
                // SurfaceTexture crop/producer transform.
                vec2 uv = rotateUv(vec2(vUv.x, 1.0 - vUv.y));
                vec2 tex = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                vec4 c = texture2D(uTexture, tex);
                // RGBA bytes copied to Android ARGB_8888 little-endian memory need R/B swapped.
                gl_FragColor = vec4(c.b, c.g, c.r, c.a);
            }
        """
    }
}

private class DecoderEglV47 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get decoder EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize decoder EGL" }
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
            "Could not choose decoder EGL config"
        }
        val config = configs[0] ?: error("Missing decoder EGL config")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context for V49 cutout decode" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create decoder EGL pbuffer" }
        makeCurrent()
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind decoder EGL context" }
    }

    override fun close() {
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(display, surface) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        // Do not eglTerminate the shared default display; temporal analysis owns another EGL context.
    }
}

private fun compileProgramV47(vertex: String, fragment: String): Int {
    val vs = compileShaderV47(GLES20.GL_VERTEX_SHADER, vertex)
    val fs = compileShaderV47(GLES20.GL_FRAGMENT_SHADER, fragment)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    val log = GLES20.glGetProgramInfoLog(program)
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    check(status[0] == GLES20.GL_TRUE) { "V49 GPU decoder program link failed: $log" }
    return program
}

private fun compileShaderV47(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    val log = GLES20.glGetShaderInfoLog(shader)
    check(status[0] == GLES20.GL_TRUE) { "V49 GPU decoder shader compile failed: $log" }
    return shader
}

private fun checkGlV47(label: String) {
    val error = GLES20.glGetError()
    check(error == GLES20.GL_NO_ERROR) { "$label failed with GL error 0x${error.toString(16)}" }
}

private fun floatBufferOfV47(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }
