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
 * PP-MattingV2/STDC1 512 portrait matte backend used by Pro Cutout.
 *
 * V52 is accelerator-first on Android. API 29+ first requests NNAPI with the NNAPI CPU device
 * disabled plus FP16 relaxation, so supported graph partitions execute on a GPU/NPU/DSP rather
 * than the slow NNAPI reference CPU. If a vendor driver cannot create that session, we retry with
 * ordinary NNAPI, then finally retain an ORT CPU compatibility fallback rather than failing Cutout.
 *
 * Important: the Java ONNX Runtime API still has a host tensor boundary, so packing the fixed NCHW
 * input and reading the final alpha are small CPU copies. The expensive PP-MattingV2 graph itself is
 * accelerator-first; GPU temporal refinement is handled separately by GpuSpatialFlowTemporalMatte.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
    }

    private data class SessionBundle(
        val options: OrtSession.SessionOptions,
        val session: OrtSession,
        val label: String,
    )

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private val resolvedBackendLabel: String

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
        get() = "PP-MattingV2 · $resolvedBackendLabel · 512"

    init {
        // Model bytes are read once per analyzer session. The ~36 MB ONNX file stays build-time pinned.
        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        val bundle = createBestSession(modelBytes)
        sessionOptions = bundle.options
        session = bundle.session
        resolvedBackendLabel = bundle.label
        inputName = session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
    }

    private fun createBestSession(modelBytes: ByteArray): SessionBundle {
        // Android 10+ can explicitly exclude the NNAPI CPU device. This is the preferred path for
        // sustained video analysis because it prevents a driver from silently choosing the very slow
        // NNAPI reference CPU for otherwise acceleratable graph partitions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strict = OrtSession.SessionOptions()
            try {
                configureCommon(strict)
                strict.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16))
                return SessionBundle(
                    options = strict,
                    session = environment.createSession(modelBytes, strict),
                    label = "NNAPI accelerator · CPU device disabled · FP16",
                )
            } catch (_: Throwable) {
                runCatching { strict.close() }
            }
        }

        // Android 8.1+ NNAPI fallback. NNAPI is a unified Android accelerator interface and may map
        // supported partitions to GPU/NPU/DSP depending on the phone/driver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val nnapi = OrtSession.SessionOptions()
            try {
                configureCommon(nnapi)
                nnapi.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                return SessionBundle(
                    options = nnapi,
                    session = environment.createSession(modelBytes, nnapi),
                    label = "NNAPI accelerator · FP16",
                )
            } catch (_: Throwable) {
                runCatching { nnapi.close() }
            }
        }

        val cpu = OrtSession.SessionOptions()
        configureCommon(cpu)
        // CPU is compatibility only. Keep thread count bounded so a fallback does not starve decoder,
        // MediaPipe and PNG writer threads on mid-range phones.
        runCatching { cpu.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) }
        runCatching { cpu.setInterOpNumThreads(1) }
        return SessionBundle(
            options = cpu,
            session = environment.createSession(modelBytes, cpu),
            label = "ORT CPU fallback",
        )
    }

    private fun configureCommon(options: OrtSession.SessionOptions) {
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setMemoryPatternOptimization(true)
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        // The fixed Paddle2ONNX export expects NCHW 512x512. Resize once and normalize RGB from
        // [0,255] to [-1,1]. The expensive graph runs on the selected accelerator whenever supported.
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
