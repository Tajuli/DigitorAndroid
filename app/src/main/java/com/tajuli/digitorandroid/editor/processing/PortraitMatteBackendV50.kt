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
 * PP-MattingV2 portrait matte backend used by Pro Cutout.
 *
 * Primary path is ncnn Vulkan. Long-running High analysis is thermally adaptive instead of using a
 * one-size-fits-all fixed throttle: normal devices stay on GPU while Android reports a safe thermal
 * state, hot devices temporarily process a bounded CPU batch, and known fragile UNISOC/Mali
 * families also get a conservative GPU/CPU duty cycle even when their firmware does not report GPU
 * pressure accurately. Resume/checkpointing lives above this class, so backend switching never
 * discards already-completed mattes.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3

        // Keep the fast path long enough to avoid backend-churn, but evaluate thermal pressure before
        // the 200-250 frame failure window reported on low-end Mali/UNISOC firmware.
        const val GPU_BURST_FRAMES = 80
        const val CPU_COOLING_FRAMES = 20
        const val CPU_SEVERE_COOLING_FRAMES = 40
        const val CPU_EXTEND_MODERATE_FRAMES = 10
        const val CPU_EXTEND_SEVERE_FRAMES = 20
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
    private val fragileVulkanFamily = isFragileVulkanFamily()

    // Vulkan is attempted first on every device, including UNISOC/T606. NNAPI has a separate
    // safety deny-list because provider registration itself can be process-fatal on affected phones.
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

    private var gpuFramesInBurst = 0
    private var cpuCoolingFramesRemaining = 0
    private var cpuCoolingFramesCompleted = 0
    private var coolingCycleCount = 0
    private var coolingReason: String? = null
    private var gpuResumePending = false
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
            val cpu = backend
            if (cpuCoolingFramesRemaining > 0 && cpu != null) {
                return buildString {
                    append(cpu.label)
                    append(" · adaptive cooling")
                    append(" · ").append(cpuCoolingFramesCompleted).append("/")
                    append(cpuCoolingFramesCompleted + cpuCoolingFramesRemaining).append(" CPU frames")
                    coolingReason?.let { append(" · ").append(it) }
                    append(" · cycle ").append(coolingCycleCount)
                }
            }
            return vulkanBackend?.backendLabel
                ?: cpu?.label
                ?: "Matting: backend not initialized"
        }

    /** Read model bytes only when a CPU session is actually needed; do not pin a second model copy. */
    private fun readModelBytes(): ByteArray =
        appContext.assets.open(MODEL_ASSET).use { it.readBytes() }

    /** Create the best non-Vulkan backend for a true runtime failure. */
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

    private fun ensureFallbackBackend(): BackendSession {
        backend?.let { return it }
        val created = createBestFallbackBackend()
        installCpuBackend(created)
        return created
    }

    /**
     * Thermal cooling must be real CPU work. NNAPI is intentionally not used here because a vendor
     * NNAPI driver may select the same GPU/NPU and therefore would not give the graphics/compute
     * stack a cooling window.
     */
    private fun ensureCoolingCpuBackend(): BackendSession {
        backend?.let { active ->
            if (active.kind == BackendSession.Kind.XNNPACK || active.kind == BackendSession.Kind.ORT_CPU) {
                return active
            }
            closeCpuBackend()
        }

        val created = createXnnpackBackend(maxThreads = 2)
            ?: createOrtCpuBackend("adaptive GPU cooling")
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

    private fun isFragileVulkanFamily(): Boolean {
        val identity = deviceIdentity()
        return ("symphony" in identity && "z60" in identity) ||
            "t606" in identity ||
            "unisoc" in identity ||
            "spreadtrum" in identity ||
            "sprd" in identity ||
            "ums9230" in identity ||
            "ums512" in identity
    }

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

    private fun shouldStartCpuCooling(): Pair<Int, String>? {
        if (permanentlyFellBackFromVulkan || vulkanBackend == null) return null
        val thermal = currentThermalStatus()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            thermal >= PowerManager.THERMAL_STATUS_SEVERE
        ) {
            return CPU_SEVERE_COOLING_FRAMES to thermalName(thermal)
        }

        if (gpuFramesInBurst < GPU_BURST_FRAMES) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            thermal >= PowerManager.THERMAL_STATUS_MODERATE
        ) {
            return CPU_COOLING_FRAMES to thermalName(thermal)
        }

        // Some low-end Mali/UNISOC firmware process-dies under sustained Vulkan load without
        // escalating Android's thermal status. Give only those known families a preventive duty
        // cycle; high-end and well-behaved phones remain GPU-continuous when cool.
        if (fragileVulkanFamily) {
            return CPU_COOLING_FRAMES to "preventive fragile-driver cooling"
        }

        return null
    }

    private fun startCpuCooling(frames: Int, reason: String) {
        vulkanBackend?.let { gpu -> runCatching { gpu.close() } }
        vulkanBackend = null
        gpuFramesInBurst = 0
        coolingCycleCount += 1
        cpuCoolingFramesCompleted = 0
        cpuCoolingFramesRemaining = frames.coerceAtLeast(1)
        coolingReason = reason
        gpuResumePending = false
        ensureCoolingCpuBackend()
        PortraitMatteRuntimeStatusV50.update(backendLabel)
    }

    private fun extendCoolingIfStillHot() {
        if (cpuCoolingFramesRemaining > 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            gpuResumePending = true
            return
        }

        val thermal = currentThermalStatus()
        when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> {
                cpuCoolingFramesRemaining = CPU_EXTEND_SEVERE_FRAMES
                coolingReason = thermalName(thermal)
            }
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> {
                cpuCoolingFramesRemaining = CPU_EXTEND_MODERATE_FRAMES
                coolingReason = thermalName(thermal)
            }
            else -> gpuResumePending = true
        }
    }

    private fun tryResumeGpuIfReady() {
        if (!gpuResumePending || permanentlyFellBackFromVulkan) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            currentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE
        ) {
            cpuCoolingFramesRemaining = CPU_EXTEND_MODERATE_FRAMES
            coolingReason = thermalName(currentThermalStatus())
            gpuResumePending = false
            return
        }

        // Never keep the full ORT CPU session resident beside Vulkan on memory-constrained phones.
        closeCpuBackend()
        val replacement = NcnnVulkanPortraitMatteV52.tryCreate(appContext)
        if (replacement != null) {
            vulkanBackend = replacement
            gpuFramesInBurst = 0
            cpuCoolingFramesCompleted = 0
            cpuCoolingFramesRemaining = 0
            coolingReason = null
            gpuResumePending = false
            PortraitMatteRuntimeStatusV50.update(replacement.backendLabel + " · adaptive cycle $coolingCycleCount")
        } else {
            permanentlyFellBackFromVulkan = true
            gpuResumePending = false
            val fallback = ensureFallbackBackend()
            PortraitMatteRuntimeStatusV50.update(fallback.label + " · Vulkan resume unavailable")
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
        gpuResumePending = false
        cpuCoolingFramesRemaining = 0
        runCatching { old.close() }
        val fallback = ensureFallbackBackend()
        PortraitMatteRuntimeStatusV50.update(fallback.label + " · Vulkan runtime fallback")
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        tryResumeGpuIfReady()

        if (cpuCoolingFramesRemaining <= 0) {
            shouldStartCpuCooling()?.let { (frames, reason) ->
                startCpuCooling(frames, reason)
            }
        }

        if (cpuCoolingFramesRemaining > 0) {
            val output = inferWithCpu(source, cooling = true)
            cpuCoolingFramesRemaining -= 1
            cpuCoolingFramesCompleted += 1
            extendCoolingIfStillHot()
            PortraitMatteRuntimeStatusV50.update(backendLabel)
            return output
        }

        vulkanBackend?.let { gpu ->
            val result = runCatching { gpu.infer(source) }
            result.getOrNull()?.let {
                gpuFramesInBurst += 1
                return it
            }
            disableVulkanAfterFailure()
        }

        return inferWithCpu(source, cooling = false)
    }

    private fun inferWithCpu(source: Bitmap, cooling: Boolean): Bitmap {
        val activeBackend = if (cooling) ensureCoolingCpuBackend() else ensureFallbackBackend()
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
        if (!cooling) PortraitMatteRuntimeStatusV50.update(finalBackend.label)
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
