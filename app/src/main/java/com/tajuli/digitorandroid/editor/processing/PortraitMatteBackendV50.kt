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
 * Upstream PP-MattingV2 is published by PaddleSeg under Apache-2.0. The fixed 512x512 ONNX export
 * is downloaded and SHA-256 pinned by app/build.gradle.kts; ONNX Runtime Android is MIT licensed.
 *
 * Backend order on Android 10+:
 * 1) NNAPI with NNAPI's CPU device disabled, so NNAPI partitions are sent to vendor hardware
 *    accelerators (GPU/NPU/DSP where the device driver supports them). Unsupported ORT nodes may
 *    still remain on ORT CPU, so the UI deliberately reports this as hardware/mixed rather than
 *    claiming that every model op ran on the GPU.
 * 2) XNNPACK CPU.
 * 3) Default ORT CPU.
 *
 * Android 9 and older skip the hardware-only NNAPI attempt because CPU_DISABLED is only effective
 * from API 29; this avoids accidentally selecting the slow NNAPI reference CPU backend.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
    }

    private data class BackendSession(
        val session: OrtSession,
        val options: OrtSession.SessionOptions,
        val label: String,
        val kind: Kind,
    ) {
        enum class Kind { NNAPI, XNNPACK, ORT_CPU }
    }

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }

    private var backend = createBestBackend()
    private var inputName: String = backend.session.inputNames.firstOrNull()
        ?: error("PP-MattingV2 ONNX did not expose an input tensor")

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
    private val inputTensor: OnnxTensor = OnnxTensor.createTensor(
        environment,
        inputBuffer,
        longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
    )

    override val backendLabel: String
        get() = backend.label

    private fun createBestBackend(): BackendSession {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createNnapiBackend()?.let { return it }
        }
        createXnnpackBackend()?.let { return it }
        return createOrtCpuBackend()
    }

    private fun createNnapiBackend(): BackendSession? {
        val options = OrtSession.SessionOptions()
        return try {
            options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            val session = environment.createSession(modelBytes, options)
            BackendSession(
                session = session,
                options = options,
                label = "Matting: Hardware (NNAPI GPU/NPU) · ORT CPU fallback possible · 512",
                kind = BackendSession.Kind.NNAPI,
            )
        } catch (_: Throwable) {
            runCatching { options.close() }
            null
        }
    }

    private fun createXnnpackBackend(): BackendSession? {
        val options = OrtSession.SessionOptions()
        return try {
            options.addXnnpack(
                mapOf(
                    "intra_op_num_threads" to Runtime.getRuntime().availableProcessors()
                        .coerceIn(1, 4)
                        .toString(),
                ),
            )
            val session = environment.createSession(modelBytes, options)
            BackendSession(
                session = session,
                options = options,
                label = "Matting: CPU (XNNPACK) · 512",
                kind = BackendSession.Kind.XNNPACK,
            )
        } catch (_: Throwable) {
            runCatching { options.close() }
            null
        }
    }

    private fun createOrtCpuBackend(): BackendSession {
        val options = OrtSession.SessionOptions()
        return try {
            BackendSession(
                session = environment.createSession(modelBytes, options),
                options = options,
                label = "Matting: CPU (ONNX Runtime) · 512",
                kind = BackendSession.Kind.ORT_CPU,
            )
        } catch (error: Throwable) {
            runCatching { options.close() }
            throw error
        }
    }

    /**
     * Some vendor NNAPI drivers accept a graph at session creation but fail when the first real
     * frame executes. If that happens, recreate the session on XNNPACK/ORT CPU and retry once.
     */
    private fun switchFromNnapiToCpuFallback() {
        if (backend.kind != BackendSession.Kind.NNAPI) return
        val old = backend
        backend = createXnnpackBackend() ?: createOrtCpuBackend()
        inputName = backend.session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 fallback session did not expose an input tensor")
        runCatching { old.session.close() }
        runCatching { old.options.close() }
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        // The fixed Paddle2ONNX export expects NCHW 512x512. Like PaddleSeg deployment, resize to
        // the model shape and normalize RGB from [0,255] to [-1,1] (mean/std 0.5).
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

        val firstRun = runCatching { runSessionAndFillAlpha() }
        if (firstRun.isFailure && backend.kind == BackendSession.Kind.NNAPI) {
            switchFromNnapiToCpuFallback()
            runSessionAndFillAlpha()
        } else {
            firstRun.getOrThrow()
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

    private fun runSessionAndFillAlpha() {
        backend.session.run(mapOf(inputName to inputTensor)).use { result ->
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
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { backend.session.close() }
        runCatching { backend.options.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
