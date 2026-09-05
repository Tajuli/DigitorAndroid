package com.tajuli.digitorandroid.editor.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * CI804 PP-MattingV2/STDC1 512 portrait matte with hardware-first execution.
 *
 * The model, preprocessing, output alpha and all downstream CI804 cutout/refinement behavior are
 * intentionally unchanged. Only the ONNX Runtime execution provider changes: Android 10+ first
 * tries NNAPI with the NNAPI CPU device disabled, so supported PP-MattingV2 nodes are assigned to
 * a real hardware accelerator (GPU/NPU/DSP chosen by the vendor NNAPI driver). Unsupported nodes
 * may remain on ORT's CPU EP instead of making session creation fatal. This is deliberately safer
 * than the earlier strict all-or-nothing NNAPI experiment which disabled ORT CPU-EP fallback and
 * could crash/fail on vendor drivers.
 *
 * If NNAPI session creation itself is unavailable, the exact same PP-MattingV2 model falls back to
 * the original CI804 CPU session. Therefore backend availability changes speed, not matte quality.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
    }

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private val usingHardwareAcceleration: Boolean

    private val plane = MODEL_SIZE * MODEL_SIZE
    private val inputPixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val directBytes = ByteBuffer
        .allocateDirect(plane * CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val inputBuffer = directBytes.asFloatBuffer()
    private val inputTensor: OnnxTensor

    override val backendLabel: String
        get() = if (usingHardwareAcceleration) {
            "PP-MattingV2 · NNAPI accelerator · 512"
        } else {
            "PP-MattingV2 · ONNX Runtime CPU fallback · 512"
        }

    init {
        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }

        var resolvedOptions: OrtSession.SessionOptions? = null
        var resolvedSession: OrtSession? = null
        var accelerated = false

        // CPU_DISABLED applies to NNAPI's own CPU device on Android 10+. We intentionally do NOT
        // set session.disable_cpu_ep_fallback: unsupported lightweight graph nodes can stay on ORT
        // CPU while the supported heavy PP-MattingV2 operators run on vendor hardware. This keeps
        // the CI804 model/output intact and avoids the fragile all-or-nothing session partition.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hardwareOptions = OrtSession.SessionOptions()
            val hardwareSession = runCatching {
                hardwareOptions.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                environment.createSession(modelBytes, hardwareOptions)
            }.getOrNull()

            if (hardwareSession != null) {
                resolvedOptions = hardwareOptions
                resolvedSession = hardwareSession
                accelerated = true
            } else {
                runCatching { hardwareOptions.close() }
            }
        }

        if (resolvedSession == null) {
            val cpuOptions = OrtSession.SessionOptions()
            resolvedOptions = cpuOptions
            resolvedSession = environment.createSession(modelBytes, cpuOptions)
        }

        sessionOptions = checkNotNull(resolvedOptions)
        session = checkNotNull(resolvedSession)
        usingHardwareAcceleration = accelerated
        inputName = session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        // Exact CI804 preprocessing: fixed NCHW 512x512 and RGB [0,255] -> [-1,1].
        inputCanvas.drawBitmap(
            source,
            null,
            Rect(0, 0, MODEL_SIZE, MODEL_SIZE),
            filterPaint,
        )
        inputSquare.getPixels(
            inputPixels,
            0,
            MODEL_SIZE,
            0,
            0,
            MODEL_SIZE,
            MODEL_SIZE,
        )

        for (i in 0 until plane) {
            val pixel = inputPixels[i]
            inputBuffer.put(i, Color.red(pixel) / 127.5f - 1f)
            inputBuffer.put(plane + i, Color.green(pixel) / 127.5f - 1f)
            inputBuffer.put(plane * 2 + i, Color.blue(pixel) / 127.5f - 1f)
        }

        session.run(mapOf(inputName to inputTensor)).use { result ->
            val output = result[0] as? OnnxTensor
                ?: error("PP-MattingV2 first output is not a tensor")
            val alpha = output.floatBuffer
                ?: error("PP-MattingV2 output is not float/fp16/bf16")
            check(alpha.remaining() >= plane) {
                "PP-MattingV2 alpha output has ${alpha.remaining()} values; expected at least $plane"
            }
            for (i in 0 until plane) {
                val value = alpha.get(i).coerceIn(0f, 1f)
                val v = (value * 255f).roundToInt().coerceIn(0, 255)
                alphaPixels[i] = Color.argb(255, v, v, v)
            }
        }

        alphaSquare.setPixels(
            alphaPixels,
            0,
            MODEL_SIZE,
            0,
            0,
            MODEL_SIZE,
            MODEL_SIZE,
        )
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(
                alphaSquare,
                null,
                Rect(0, 0, source.width, source.height),
                filterPaint,
            )
        }
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
