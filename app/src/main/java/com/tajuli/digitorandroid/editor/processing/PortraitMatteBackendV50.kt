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
import android.os.PowerManager
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * PP-MattingV2 portrait matte backend used by Pro Cutout.
 *
 * Primary path is ncnn Vulkan. Physical-device testing showed that creating a second ONNX Runtime
 * CPU session while a long-lived Vulkan engine is parked can itself destabilize low-memory/fragile
 * GPU stacks. Therefore thermal/latency pressure no longer causes a live GPU -> CPU switch.
 *
 * While Vulkan is healthy we keep one persistent GPU engine and reduce load with short adaptive
 * pauses. CPU fallback is created only after a Java-visible Vulkan inference failure or when Vulkan
 * was unavailable from the start. This avoids the risky transition during normal healthy GPU work.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3

        const val LATENCY_HISTORY = 12
        const val LATENCY_MIN_BASELINE_MS = 450.0
        const val LATENCY_TRIGGER_MULTIPLIER = 1.85

        const val BACKOFF_LATENCY_MS = 180L
        const val BACKOFF_LIGHT_MS = 80L
        const val BACKOFF_MODERATE_MS = 300L
        const val BACKOFF_SEVERE_MS = 900L
        const val BACKOFF_CRITICAL_MS = 1_800L
        const val BACKOFF_EMERGENCY_MS = 3_000L
        const val FAILURE_RELEASE_DELAY_MS = 180L
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
    private val powerManager: PowerManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appContext.getSystemService(PowerManager::class.java)
    } else {
        null
    }

    private var vulkanBackend: NcnnVulkanPortraitMatteV52? =
        NcnnVulkanPortraitMatteV52.tryCreate(appContext)

    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
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

    private val gpuLatencyHistory = ArrayDeque<Double>()
    private var slowGpuSamples = 0
    private var latencyPressureReason: String? = null
    private var lastBackoffReason: String? = null
    private var permanentlyFellBackFromVulkan = false

    init {
        val gpu = vulkanBackend
        if (gpu != null) {
            PortraitMatteRuntimeStatusV50.update(gpu.backendLabel)
        } else {
            permanentlyFellBackFromVulkan = true
            PortraitMatteRuntimeStatusV50.update(ensureFallbackBackend().label)
        }
    }

    override val backendLabel: String
        get() {
            val gpu = vulkanBackend
            if (gpu != null) {
                return buildString {
                    append(gpu.backendLabel)
                    latestGpuLatency()?.let {
                        append(" · recent ").append("%.1f".format(it)).append(" ms")
                    }
                    lastBackoffReason?.let {
                        append(" · adaptive GPU backoff · ").append(it)
                    }
                }
            }
            return backend?.label ?: "Matting: backend not initialized"
        }

    private fun readModelBytes(): ByteArray =
        appContext.assets.open(MODEL_ASSET).use { it.readBytes() }

    private fun createBestFallbackBackend(): BackendSession {
        unsafeNnapiDeviceReason()?.let { reason ->
            return createOrtCpuBackend("NNAPI disabled for device safety · $reason")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createNnapiBackend()?.let { return it }
        }
        createXnnpackBackend(maxThreads = 4)?.let { return it }
        return createOrtCpuBackend()
    }

    /**
     * Vulkan failure fallback must be actual CPU. Do not route through NNAPI here because a vendor
     * may select the same GPU/NPU that just failed or overheated.
     */
    private fun createCpuAfterVulkanFailure(): BackendSession {
        return createXnnpackBackend(maxThreads = 2)
            ?: createOrtCpuBackend("Vulkan runtime fallback")
    }

    private fun ensureFallbackBackend(): BackendSession {
        backend?.let { return it }
        val created = createBestFallbackBackend()
        installCpuBackend(created)
        return created
    }

    private fun ensureCpuAfterVulkanFailure(): BackendSession {
        backend?.let { active ->
            if (active.kind == BackendSession.Kind.XNNPACK || active.kind == BackendSession.Kind.ORT_CPU) {
                return active
            }
            closeCpuBackend()
        }
        val created = createCpuAfterVulkanFailure()
        installCpuBackend(created)
        return created
    }

    private fun installCpuBackend(created: BackendSession) {
        backend = created
        inputName = created.session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        ensureOrtInputTensor()
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

    private fun deviceIdentity(): String = buildList {
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

    private fun unsafeNnapiDeviceReason(): String? {
        val identity = deviceIdentity()
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
            val session = environment.createSession(readModelBytes(), options)
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

    private fun createXnnpackBackend(maxThreads: Int): BackendSession? {
        val options = OrtSession.SessionOptions()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, maxThreads)
        return try {
            options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            val session = environment.createSession(readModelBytes(), options)
            BackendSession(
                session = session,
                options = options,
                label = "Matting: CPU (XNNPACK) · $threads thread(s) · 512",
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
                session = environment.createSession(readModelBytes(), options),
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

    private fun currentThermalStatus(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    } else {
        PowerManager.THERMAL_STATUS_NONE
    }

    private fun thermalName(status: Int): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "thermal none"
            PowerManager.THERMAL_STATUS_LIGHT -> "thermal light"
            PowerManager.THERMAL_STATUS_MODERATE -> "thermal moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "thermal severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "thermal critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "thermal emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "thermal shutdown"
            else -> "thermal $status"
        }
    } else {
        "thermal API unavailable"
    }

    private fun latestGpuLatency(): Double? = gpuLatencyHistory.lastOrNull()

    private fun recordGpuLatency(value: Double) {
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return

        val baseline = if (gpuLatencyHistory.size >= 6) {
            gpuLatencyHistory.average().coerceAtLeast(LATENCY_MIN_BASELINE_MS)
        } else {
            null
        }

        if (baseline != null && value >= baseline * LATENCY_TRIGGER_MULTIPLIER) {
            slowGpuSamples += 1
            if (slowGpuSamples >= 2) {
                latencyPressureReason =
                    "gpu load high · latency %.0f→%.0f ms".format(baseline, value)
            }
        } else {
            slowGpuSamples = 0
            latencyPressureReason = null
        }

        gpuLatencyHistory.addLast(value)
        while (gpuLatencyHistory.size > LATENCY_HISTORY) gpuLatencyHistory.removeFirst()
    }

    /**
     * Reduce GPU duty cycle without changing inference backend. This is intentionally just a quiet
     * scheduling gap: no ORT session allocation, no ncnn engine destruction, and no backend switch.
     */
    private fun applyGpuBackoffIfNeeded() {
        if (vulkanBackend == null || permanentlyFellBackFromVulkan) return

        val thermal = currentThermalStatus()
        val (delayMs, reason) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                thermal >= PowerManager.THERMAL_STATUS_EMERGENCY ->
                    BACKOFF_EMERGENCY_MS to thermalName(thermal)
                thermal >= PowerManager.THERMAL_STATUS_CRITICAL ->
                    BACKOFF_CRITICAL_MS to thermalName(thermal)
                thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                    BACKOFF_SEVERE_MS to thermalName(thermal)
                thermal >= PowerManager.THERMAL_STATUS_MODERATE ->
                    BACKOFF_MODERATE_MS to thermalName(thermal)
                thermal >= PowerManager.THERMAL_STATUS_LIGHT ->
                    BACKOFF_LIGHT_MS to thermalName(thermal)
                latencyPressureReason != null ->
                    BACKOFF_LATENCY_MS to latencyPressureReason.orEmpty()
                else -> 0L to ""
            }
        } else if (latencyPressureReason != null) {
            BACKOFF_LATENCY_MS to latencyPressureReason.orEmpty()
        } else {
            0L to ""
        }

        if (delayMs <= 0L) {
            lastBackoffReason = null
            return
        }

        lastBackoffReason = reason
        PortraitMatteRuntimeStatusV50.update(backendLabel)
        SystemClock.sleep(delayMs)

        // A latency-triggered pause is a one-shot response. Build a fresh local baseline after the
        // rest period rather than immediately retriggering from stale hot samples.
        if (latencyPressureReason != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                thermal < PowerManager.THERMAL_STATUS_MODERATE)
        ) {
            gpuLatencyHistory.clear()
            slowGpuSamples = 0
            latencyPressureReason = null
        }
    }

    private fun switchFromNnapiToCpuFallback() {
        val old = backend ?: return
        if (old.kind != BackendSession.Kind.NNAPI) return

        val replacement = createXnnpackBackend(maxThreads = 4) ?: createOrtCpuBackend()
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
        permanentlyFellBackFromVulkan = true
        lastBackoffReason = "Vulkan inference failed"

        // At this point the Vulkan call already returned a Java-visible failure, so leave the normal
        // inference path before releasing native state. This is the only runtime GPU -> CPU switch.
        runCatching { old.close() }
        SystemClock.sleep(FAILURE_RELEASE_DELAY_MS)

        val fallback = ensureCpuAfterVulkanFailure()
        PortraitMatteRuntimeStatusV50.update(fallback.label + " · Vulkan runtime fallback")
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        vulkanBackend?.let { gpu ->
            applyGpuBackoffIfNeeded()
            val result = runCatching { gpu.infer(source) }
            result.getOrNull()?.let { bitmap ->
                val recentMs = gpu.latestInferenceMs
                if (recentMs > 0.0) recordGpuLatency(recentMs)
                PortraitMatteRuntimeStatusV50.update(backendLabel)
                return bitmap
            }
            disableVulkanAfterFailure()
        }

        return inferWithCpu(source)
    }

    private fun inferWithCpu(source: Bitmap): Bitmap {
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

    private fun closeCpuBackend() {
        inputTensor?.let { runCatching { it.close() } }
        inputTensor = null
        inputBuffer = null
        directBytes = null
        inputName = null
        backend?.let { active ->
            runCatching { active.session.close() }
            runCatching { active.options.close() }
        }
        backend = null
    }

    override fun close() {
        vulkanBackend?.let { runCatching { it.close() } }
        vulkanBackend = null
        closeCpuBackend()
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
