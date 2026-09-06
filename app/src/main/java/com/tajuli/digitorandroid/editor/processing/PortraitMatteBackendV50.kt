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
 * Primary backend on ARM devices is Paddle Lite OpenCL. The optimized .nb model is built with
 * valid_targets=opencl,arm and the native runtime checks IsOpenCLBackendValid before creating a
 * predictor. This is the direct Mali/Adreno path and avoids Android NNAPI entirely.
 *
 * ONNX Runtime is retained as a reliability fallback. On known unsafe UNISOC NNAPI firmware the
 * fallback stays on CPU; other devices may still use NNAPI if direct OpenCL is unavailable.
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
    private var openClBackend: PaddleLiteOpenClPortraitMatteV51? =
        PaddleLiteOpenClPortraitMatteV51.tryCreate(appContext)
    private val environment = OrtEnvironment.getEnvironment()
    private val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }

    private var backend = createBestFallbackBackend().also {
        PortraitMatteRuntimeStatusV50.update(openClBackend?.backendLabel ?: it.label)
    }
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
        get() = openClBackend?.backendLabel ?: backend.label

    private fun createBestFallbackBackend(): BackendSession {
        // If direct OpenCL is already available, do not register NNAPI at all. The CPU session is
        // initialized only as a warm reliability fallback if the OpenCL predictor later throws.
        if (openClBackend != null) {
            return createOrtCpuBackend("OpenCL primary; CPU fallback ready")
        }

        unsafeNnapiDeviceReason()?.let { reason ->
            return createOrtCpuBackend("NNAPI disabled for device safety · $reason")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createNnapiBackend()?.let { return it }
        }
        createXnnpackBackend()?.let { return it }
        return createOrtCpuBackend()
    }

    private fun unsafeNnapiDeviceReason(): String? {
        val identity = buildList {
            add(Build.MANUFACTURER)
            add(Build.BRAND)
            add(Build.MODEL)
            add(Build.DEVICE)
            add(Build.PRODUCT)
            add(Build.BOARD)
            add(Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Build.SOC_MANUFACTURER)
                add(Build.SOC_MODEL)
            }
        }.joinToString("|") { it.orEmpty() }.lowercase()

        val exactZ60 = "symphony" in identity && "z60" in identity
        val t606 = "t606" in identity
        val unisocFamily =
            "unisoc" in identity ||
                "spreadtrum" in identity ||
                "sprd" in identity ||
                "ums9230" in identity ||
                "ums512" in identity

        return when {
            exactZ60 -> "Symphony Z60"
            t606 -> "UNISOC T606"
            unisocFamily -> "UNISOC/Spreadtrum"
            else -> null
        }
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

    private fun createOrtCpuBackend(reason: String? = null): BackendSession {
        val options = OrtSession.SessionOptions()
        return try {
            BackendSession(
                session = environment.createSession(modelBytes, options),
                options = options,
                label = buildString {
                    append("Matting: CPU (ONNX Runtime) · 512")
                    if (!reason.isNullOrBlank()) append(" · ").append(reason)
                },
                kind = BackendSession.Kind.ORT_CPU,
            )
        } catch (error: Throwable) {
            runCatching { options.close() }
            throw error
        }
    }

    private fun switchFromNnapiToCpuFallback() {
        if (backend.kind != BackendSession.Kind.NNAPI) return
        val old = backend
        backend = createXnnpackBackend() ?: createOrtCpuBackend()
        inputName = backend.session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 fallback session did not expose an input tensor")
        PortraitMatteRuntimeStatusV50.update(backend.label)
        runCatching { old.session.close() }
        runCatching { old.options.close() }
    }

    private fun disableOpenClAfterFailure() {
        val old = openClBackend ?: return
        openClBackend = null
        runCatching { old.close() }
        PortraitMatteRuntimeStatusV50.update(backend.label + " · OpenCL runtime fallback")
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        openClBackend?.let { gpu ->
            val result = runCatching { gpu.infer(source) }
            result.getOrNull()?.let { return it }
            disableOpenClAfterFailure()
        }

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

        PortraitMatteRuntimeStatusV50.update(backend.label)
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
        openClBackend?.let { runCatching { it.close() } }
        openClBackend = null
        runCatching { inputTensor.close() }
        runCatching { backend.session.close() }
        runCatching { backend.options.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
