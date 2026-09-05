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
import kotlin.math.roundToInt

private const val GPU_FLOW_LONG_EDGE_V47 = 192
private const val GPU_FLOW_BLOCK_V47 = 12
private const val GPU_FLOW_RESET_GAP_US_V47 = 1_200_000L
private const val GPU_FLOW_SCENE_CUT_MAD_V47 = 52f

/**
 * V52 GPU temporal matte stage.
 *
 * One GPU flow pass is shared by temporal smoothing, persistent-person lock and confidence
 * hysteresis. The history texture packs stabilized alpha in R, person lock in G and temporal
 * confidence in B, so there is no second CPU optical-flow/person-memory pass. A tiny extraction pass
 * copies R into grayscale RGB only for the existing PNG cache contract.
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
    private val extractProgram = compileProgram(FULLSCREEN_VERTEX, EXTRACT_ALPHA_FRAGMENT)
    private val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]

    private var currentSourceTex = createTexture()
    private var previousSourceTex = createTexture()
    private var currentMatteTex = createTexture()
    private var previousStateTex = createTexture()
    private var outputStateTex = createTexture()
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
        renderState(
            hasPrevious = !reset,
            hasHair = hairMask != null,
            sourceWidth = source.width,
            sourceHeight = source.height,
            hairStrength = hairStrength,
            temporalStrength = temporalStrength,
        )
        renderAlphaExtract()
        val result = readOutputMatte(currentMatte.width, currentMatte.height)

        currentSourceTex = previousSourceTex.also { previousSourceTex = currentSourceTex }
        outputStateTex = previousStateTex.also { previousStateTex = outputStateTex }
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
        uniform2f(flowProgram, "uSourceSize", sourceWidth.toFloat(), sourceHeight.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("render V52 flow")
    }

    private fun renderState(
        hasPrevious: Boolean,
        hasHair: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        hairStrength: Float,
        temporalStrength: Float,
    ) {
        attachTexture(outputStateTex)
        GLES20.glViewport(0, 0, matteWidth, matteHeight)
        GLES20.glUseProgram(matteProgram)
        bindQuad(matteProgram)
        bindSampler(matteProgram, "uCurrentMatte", currentMatteTex, 0)
        bindSampler(matteProgram, "uPreviousState", previousStateTex, 1)
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
        checkGl("render V52 temporal/person state")
    }

    private fun renderAlphaExtract() {
        attachTexture(outputMatteTex)
        GLES20.glViewport(0, 0, matteWidth, matteHeight)
        GLES20.glUseProgram(extractProgram)
        bindQuad(extractProgram)
        bindSampler(extractProgram, "uState", outputStateTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("extract V52 alpha")
    }

    private fun readOutputMatte(width: Int, height: Int): Bitmap {
        attachTexture(outputMatteTex)
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        checkGl("read V52 matte")
        bytes.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.copyPixelsFromBuffer(bytes)
        }
    }

    private fun ensureOutputTextures(width: Int, height: Int) {
        if (width == matteWidth && height == matteHeight) return
        matteWidth = width.coerceAtLeast(1)
        matteHeight = height.coerceAtLeast(1)
        allocateTexture(previousStateTex, matteWidth, matteHeight)
        allocateTexture(outputStateTex, matteWidth, matteHeight)
        allocateTexture(outputMatteTex, matteWidth, matteHeight)
        hasHistory = false
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
            "V52 GPU framebuffer is incomplete"
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
        checkGl("upload V52 texture")
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
        check(status[0] == GLES20.GL_TRUE) { "V52 GPU program link failed: $log" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "V52 GPU shader compile failed: $log" }
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
            GLES20.glDeleteProgram(extractProgram)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(
                8,
                intArrayOf(
                    currentSourceTex,
                    previousSourceTex,
                    currentMatteTex,
                    previousStateTex,
                    outputStateTex,
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
            uniform sampler2D uPreviousState;
            uniform sampler2D uFlow;
            uniform sampler2D uHair;
            uniform vec2 uSearchStep;
            uniform float uHasPrevious;
            uniform float uHasHair;
            uniform float uHairStrength;
            uniform float uTemporalStrength;

            vec3 previousStateSupportAt(vec2 previousUv) {
                vec2 radius = uSearchStep * 1.75;
                vec3 support = texture2D(uPreviousState, previousUv).rgb;
                support = max(support, texture2D(uPreviousState, clamp(previousUv + vec2(radius.x, 0.0), vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv - vec2(radius.x, 0.0), vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv + vec2(0.0, radius.y), vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv - vec2(0.0, radius.y), vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv + radius, vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv - radius, vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv + vec2(radius.x, -radius.y), vec2(0.0), vec2(1.0))).rgb);
                support = max(support, texture2D(uPreviousState, clamp(previousUv + vec2(-radius.x, radius.y), vec2(0.0), vec2(1.0))).rgb);
                return support;
            }

            void main() {
                float currentAlpha = texture2D(uCurrentMatte, vUv).r;
                float uncertainty = clamp(4.0 * currentAlpha * (1.0 - currentAlpha), 0.0, 1.0);

                if (uHasHair > 0.5 && uHairStrength > 0.001) {
                    float hair = texture2D(uHair, vUv).r;
                    float hairWeight = hair * uHairStrength * (0.10 + 0.46 * uncertainty);
                    currentAlpha = max(currentAlpha, currentAlpha + (1.0 - currentAlpha) * hairWeight);
                }

                if (uHasPrevious < 0.5 || uTemporalStrength <= 0.001) {
                    float seedLock = smoothstep(0.58, 0.94, currentAlpha);
                    float seedConfidence = smoothstep(0.28, 0.88, currentAlpha);
                    gl_FragColor = vec4(currentAlpha, seedLock, seedConfidence, 1.0);
                    return;
                }

                vec3 flow = texture2D(uFlow, vUv).rgb;
                float dx = flow.r * 12.0 - 6.0;
                float dy = flow.g * 12.0 - 6.0;
                vec2 previousUv = clamp(vUv + vec2(dx * uSearchStep.x, dy * uSearchStep.y), vec2(0.0), vec2(1.0));
                vec3 previousState = texture2D(uPreviousState, previousUv).rgb;
                vec3 previousSupport = previousStateSupportAt(previousUv);
                float previousAlpha = previousState.r;
                float previousLock = previousState.g;
                float previousConfidence = previousState.b;
                float alphaSupport = previousSupport.r;
                float lockSupport = max(previousLock, previousSupport.g);

                float motionBlocks = length(vec2(dx, dy));
                float staticMatch = 1.0 - smoothstep(0.35, 1.65, motionBlocks);
                float reliableFlow = smoothstep(0.12, 0.42, flow.b);

                float unsupportedBirth = smoothstep(0.52, 0.90, currentAlpha) *
                    (1.0 - smoothstep(0.08, 0.44, alphaSupport));
                float oldBirthGuard = clamp(unsupportedBirth * staticMatch * reliableFlow, 0.0, 1.0);
                float guardedTarget = min(currentAlpha, alphaSupport + 0.055);
                currentAlpha = mix(currentAlpha, guardedTarget, oldBirthGuard * 0.97);

                float disagreement = abs(currentAlpha - previousAlpha);
                float agreement = clamp(1.0 - disagreement / 0.34, 0.0, 1.0);
                float occlusion = 1.0 - smoothstep(0.20, 0.55, disagreement);
                float edgeUncertainty = clamp(4.0 * currentAlpha * (1.0 - currentAlpha), 0.0, 1.0);
                float weight = uTemporalStrength * 0.74 * edgeUncertainty * flow.b * (0.22 + 0.78 * agreement) * occlusion;
                weight = clamp(weight, 0.0, 0.74);
                float refined = mix(currentAlpha, previousAlpha, weight);

                float established = max(
                    smoothstep(0.18, 0.70, lockSupport),
                    smoothstep(0.20, 0.78, previousConfidence)
                );
                float motionEvidence = smoothstep(0.35, 1.85, motionBlocks);
                float staticness = 1.0 - motionEvidence;
                float strongFreshEvidence = smoothstep(0.94, 0.995, refined);
                float movingFreshEvidence = motionEvidence * reliableFlow * smoothstep(0.70, 0.94, refined);
                float allowedFreshEntry = max(strongFreshEvidence, movingFreshEvidence);
                float unsupported = (1.0 - established) * (1.0 - smoothstep(0.08, 0.46, alphaSupport));
                float birthGuard = clamp(unsupported * staticness * reliableFlow * (1.0 - allowedFreshEntry), 0.0, 1.0);
                float birthGuardStrength = 0.76 + 0.22 * uTemporalStrength;
                float birthTarget = min(refined, alphaSupport + 0.045);
                refined = mix(refined, birthTarget, birthGuard * birthGuardStrength);

                float currentDrop = smoothstep(0.10, 0.56, previousAlpha - refined);
                float motionDamping = 1.0 - 0.58 * smoothstep(1.35, 4.8, motionBlocks);
                float exitHold = clamp(
                    established * reliableFlow * currentDrop * motionDamping * (0.46 + 0.54 * uTemporalStrength),
                    0.0,
                    0.82
                );
                float heldTarget = max(refined, min(previousAlpha, max(alphaSupport, previousAlpha * 0.94)));
                refined = mix(refined, heldTarget, exitHold);

                float confidencePermission = max(established, allowedFreshEntry);
                float outputEvidence = max(refined, currentAlpha * 0.86 * confidencePermission);
                float riseRate = clamp(0.28 + 0.30 * reliableFlow + 0.16 * motionEvidence, 0.28, 0.74);
                float fallRate = clamp(0.075 + 0.11 * (1.0 - reliableFlow) + 0.10 * motionEvidence, 0.075, 0.285);
                float nextConfidence = outputEvidence >= previousConfidence
                    ? previousConfidence + (outputEvidence - previousConfidence) * riseRate
                    : previousConfidence + (outputEvidence - previousConfidence) * fallRate;
                nextConfidence = clamp(nextConfidence, 0.0, 1.0);

                float strongBackground = 1.0 - smoothstep(0.05, 0.30, refined);
                float retainedLock = previousLock * (0.995 - 0.025 * strongBackground);
                float lockAcquireEvidence = smoothstep(0.64, 0.92, refined);
                float newLockPermission = max(established, max(movingFreshEvidence, strongFreshEvidence * 0.45));
                float acquiredLock = lockAcquireEvidence * newLockPermission;
                float nextLock = clamp(max(retainedLock, acquiredLock), 0.0, 1.0);

                gl_FragColor = vec4(clamp(refined, 0.0, 1.0), nextLock, nextConfidence, 1.0);
            }
        """

        const val EXTRACT_ALPHA_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uState;
            void main() {
                float alpha = texture2D(uState, vUv).r;
                gl_FragColor = vec4(alpha, alpha, alpha, 1.0);
            }
        """
    }
}

private class OffscreenEglV47 : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get EGL display for V52 cutout" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "Could not initialize EGL for V52 cutout" }
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
            "Could not choose EGL config for V52 cutout"
        }
        val config = configs[0] ?: error("Missing EGL config for V52 cutout")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context for V52 cutout" }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "Could not create EGL pbuffer for V52 cutout" }
        makeCurrent()
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Could not bind V52 EGL context" }
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
