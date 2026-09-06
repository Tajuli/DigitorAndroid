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
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * PP-MattingV2/STDC1 512 portrait matte backend used by Pro Cutout.
 *
 * Primary path is ncnn Vulkan, which runs the converted PP-MattingV2 graph directly through the
 * mobile GPU and avoids both NNAPI and Paddle Lite. The ONNX Runtime implementation is retained as
 * a reliability fallback and is initialized lazily so the Z60 does not keep two ~large neural
 * runtimes/models resident while Vulkan is working.
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

    // Vulkan is attempted first on every device, including UNISOC/T606. The UNISOC guard below is
    // specifically for the known process-fatal NNAPI provider path, not for ncnn Vulkan.
    private var vulkanBackend: NcnnVulkanPortraitMatteV52? =
        NcnnVulkanPortraitMatteV52.tryCreate(appContext)

    // Keep the CPU model/session cold while Vulkan is active. This avoids duplicating the ONNX
    // model/session in RAM on low-memory phones such as the Symphony Z60.
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val modelBytes: ByteArray by lazy {
        appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
    }
    private var backend: BackendSession? = null
    private var inputName: String? = null
    private var directBytes: ByteBuffer? = null
    private var inputBuffer: FloatBuffer? = null
    private var inputTensor: OnnxTensor? = null

    private val plane = MODEL_SIZE * MODEL_SIZE
    private val inputPixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        val gpu = vulkanBackend
        if (gpu != null) {
            PortraitMatteRuntimeStatusV50.update(gpu.backendLabel)
        } else {
            PortraitMatteRuntimeStatusV50.update(ensureFallbackBackend().label)
        }
    }

    override val backendLabel: String
        get() = vulkanBackend?.backendLabel
            ?: backend?.label
            ?: "Matting: backend not initialized"

    /** Create the best non-Vulkan backend. Called only when Vulkan is unavailable/failed. */
    private fun createBestFallbackBackend(): BackendSession {
        unsafeNnapiDeviceReason()?.let { reason ->
            return createOrtCpuBackend("NNAPI disabled for device safety · $reason")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createNnapiBackend()?.let { return it }
        }
        createXnnpackBackend()?.let { return it }
        return createOrtCpuBackend()
    }

    private fun ensureFallbackBackend(): BackendSession {
        backend?.let { return it }
        val created = createBestFallbackBackend()
        backend = created
        inputName = created.session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        ensureOrtInputTensor()
        return created
    }

    private fun ensureOrtInputTensor(): OnnxTensor {
        inputTensor?.let { return it }
        val bytes = ByteBuffer
            .allocateDirect(plane * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val buffer = bytes.asFloatBuffer()
        val tensor = OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
        directBytes = bytes
        inputBuffer = buffer
        inputTensor = tensor
        return tensor
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
        val old = backend ?: return
        if (old.kind != BackendSession.Kind.NNAPI) return

        val replacement = createXnnpackBackend() ?: createOrtCpuBackend()
        backend = replacement
        inputName = replacement.session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 fallback session did not expose an input tensor")
        PortraitMatteRuntimeStatusV50.update(replacement.label)
        runCatching { old.session.close() }
        runCatching { old.options.close() }
    }

    private fun disableVulkanAfterFailure() {
        val old = vulkanBackend ?: return
        vulkanBackend = null
        runCatching { old.close() }
        val fallback = ensureFallbackBackend()
        PortraitMatteRuntimeStatusV50.update(fallback.label + " · Vulkan runtime fallback")
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        vulkanBackend?.let { gpu ->
            val result = runCatching { gpu.infer(source) }
            result.getOrNull()?.let { return it }
            disableVulkanAfterFailure()
        }

        val activeBackend = ensureFallbackBackend()
        val activeInputName = inputName
            ?: error("PP-MattingV2 ONNX input name is unavailable")
        val activeInputTensor = ensureOrtInputTensor()
        val activeInputBuffer = inputBuffer
            ?: error("PP-MattingV2 ONNX input buffer is unavailable")

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
            activeInputBuffer.put(i, Color.red(pixel) / 127.5f - 1f)
            activeInputBuffer.put(plane + i, Color.green(pixel) / 127.5f - 1f)
            activeInputBuffer.put(plane * 2 + i, Color.blue(pixel) / 127.5f - 1f)
        }

        val firstRun = runCatching {
            runSessionAndFillAlpha(activeBackend, activeInputName, activeInputTensor)
        }
        if (firstRun.isFailure && activeBackend.kind == BackendSession.Kind.NNAPI) {
            switchFromNnapiToCpuFallback()
            val fallback = ensureFallbackBackend()
            runSessionAndFillAlpha(
                fallback,
                inputName ?: error("PP-MattingV2 fallback input name is unavailable"),
                activeInputTensor,
            )
        } else {
            firstRun.getOrThrow()
        }

        val finalBackend = backend ?: activeBackend
        PortraitMatteRuntimeStatusV50.update(finalBackend.label)
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

    private fun runSessionAndFillAlpha(
        activeBackend: BackendSession,
        activeInputName: String,
        activeInputTensor: OnnxTensor,
    ) {
        activeBackend.session.run(mapOf(activeInputName to activeInputTensor)).use { result ->
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
        vulkanBackend?.let { runCatching { it.close() } }
        vulkanBackend = null
        inputTensor?.let { runCatching { it.close() } }
        inputTensor = null
        inputBuffer = null
        directBytes = null
        backend?.let { active ->
            runCatching { active.session.close() }
            runCatching { active.options.close() }
        }
        backend = null
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
