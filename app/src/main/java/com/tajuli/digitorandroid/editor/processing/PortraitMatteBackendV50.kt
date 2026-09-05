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
 * V51 deliberately requires accelerator-only execution for the expensive ONNX graph. NNAPI's CPU
 * implementation is disabled and ONNX Runtime is forbidden from assigning unsupported nodes to its
 * CPU EP. This keeps the cutout hot path on the device's NNAPI accelerator (GPU/NPU/DSP selected by
 * the Android driver) instead of silently falling back to CPU. Android 10+ is required because
 * NNAPI CPU_DISABLED is only enforceable from API 29.
 *
 * The small Bitmap<->tensor packing boundary remains on CPU because the Java ONNX Runtime API does
 * not accept an Android GL texture as this model's NCHW input. Model inference itself is strict
 * hardware-only; if the device cannot execute the complete graph in NNAPI, session creation fails
 * rather than becoming unexpectedly slow.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
        const val DISABLE_CPU_EP_FALLBACK = "session.disable_cpu_ep_fallback"
    }

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession
    private val inputName: String

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
        get() = "PP-MattingV2 · NNAPI HW-only · FP16 · 512"

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Pro Cutout hardware-only portrait matting requires Android 10 (API 29) or newer"
        }

        // CPU_DISABLED prevents NNAPI itself from selecting nnapi-reference. The session config
        // separately prevents ONNX Runtime from sending unsupported graph nodes to the CPU EP.
        // Together they make accelerator loss explicit instead of turning into a slow CPU fallback.
        sessionOptions.addNnapi(
            EnumSet.of(
                NNAPIFlags.CPU_DISABLED,
                NNAPIFlags.USE_FP16,
            ),
        )
        sessionOptions.addConfigEntry(DISABLE_CPU_EP_FALLBACK, "1")

        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = try {
            environment.createSession(modelBytes, sessionOptions)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "This device cannot run the complete PP-MattingV2 graph on an NNAPI hardware accelerator; CPU fallback is disabled",
                error,
            )
        }
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

        // The fixed Paddle2ONNX export expects NCHW 512x512. Resize and pack only once into the
        // reusable direct tensor buffer; the expensive neural graph then stays on NNAPI hardware.
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
