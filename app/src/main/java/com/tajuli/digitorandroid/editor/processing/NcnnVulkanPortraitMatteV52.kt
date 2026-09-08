package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

/** Native ncnn Vulkan bridge. JNI names are kept stable by proguard-rules.pro. */
internal object NcnnVulkanNativeV52 {
    init {
        System.loadLibrary("digitor_ppmatting_ncnn")
    }

    external fun isVulkanAvailable(): Boolean
    external fun createEngine(paramPath: String, binPath: String, threads: Int): Long
    external fun modelSize(handle: Long): Int
    external fun run(handle: Long, input: FloatArray): FloatArray
    external fun lastInferenceMs(handle: Long): Double
    external fun gpuName(handle: Long): String
    external fun destroy(handle: Long)
}

/**
 * PP-MattingV2/STDC1 using ncnn Vulkan.
 *
 * The native library is compiled against the exact fixed graph packaged in the APK. Phone CI now
 * uses a genuine PaddleSeg-exported 384x384 graph while ordinary/local builds can still use the
 * proven 512 graph. Kotlin asks native for the compiled size and allocates preprocessing/output
 * buffers from that exact profile, so graph and runtime tensor sizes cannot drift apart.
 *
 * Some UNISOC/Mali firmware is stable for short Vulkan bursts but can process-die after a few
 * hundred uninterrupted PP-Matting frames. On those devices we run in bounded Vulkan batches:
 * periodically destroy/recreate only the ncnn engine (durable matte checkpoints stay untouched),
 * and add a tiny thermal backoff only when Android reports meaningful thermal pressure. This keeps
 * PP-MattingV2 GPU-first while avoiding an ever-long native Vulkan session.
 */
internal class NcnnVulkanPortraitMatteV52 private constructor(
    private val appContext: Context,
    private var handle: Long,
    private val gpuName: String,
    private val modelSize: Int,
    private val paramPath: String,
    private val binPath: String,
    private val threads: Int,
    private val boundedVulkanSession: Boolean,
) : PortraitMatteBackendV50 {
    internal companion object {
        const val PARAM_ASSET = "ppmattingv2_stdc1_human_vulkan.ncnn.param"
        const val BIN_ASSET = "ppmattingv2_stdc1_human_vulkan.ncnn.bin"

        // Reported Z60/T606 failures start around 200-250 High-mode frames. Recycle well before that
        // native-driver window while keeping the model resident for long enough to preserve speed.
        private const val UNISOC_ENGINE_BATCH_FRAMES = 120

        private fun materializeAsset(
            context: Context,
            assetName: String,
            minimumBytes: Long,
        ): File {
            // New cache namespace is intentional: the packaged Vulkan graph changed from true-256
            // to true-384 while retaining the same asset filenames. Never reuse a stale 256 model
            // from codeCache after an APK update.
            val directory = File(context.codeCacheDir, "ppmatting-ncnn-v56-fixed384-profile").apply { mkdirs() }
            val target = File(directory, assetName)
            if (!target.isFile || target.length() < minimumBytes) {
                val temp = File(directory, "$assetName.tmp")
                if (temp.exists()) temp.delete()
                context.assets.open(assetName).use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                check(temp.length() >= minimumBytes) {
                    "Packaged PP-MattingV2 ncnn asset is unexpectedly small: $assetName"
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Could not materialize $assetName" }
            }
            return target
        }

        private fun needsBoundedVulkanSession(): Boolean {
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

            return ("symphony" in identity && "z60" in identity) ||
                "t606" in identity ||
                "unisoc" in identity ||
                "spreadtrum" in identity ||
                "sprd" in identity ||
                "ums9230" in identity ||
                "ums512" in identity
        }

        fun tryCreate(context: Context): NcnnVulkanPortraitMatteV52? = runCatching {
            check(NcnnVulkanNativeV52.isVulkanAvailable()) {
                "No usable Vulkan compute device was reported by ncnn"
            }

            val appContext = context.applicationContext
            val param = materializeAsset(appContext, PARAM_ASSET, 1_000L)
            val bin = materializeAsset(appContext, BIN_ASSET, 5_000_000L)
            val threads = 2
            val engine = NcnnVulkanNativeV52.createEngine(param.absolutePath, bin.absolutePath, threads)
            check(engine != 0L) { "ncnn could not create the PP-MattingV2 Vulkan engine" }

            val size = NcnnVulkanNativeV52.modelSize(engine)
            if (size != 256 && size != 384 && size != 512) {
                NcnnVulkanNativeV52.destroy(engine)
                error("Unsupported compiled PP-MattingV2 graph size: $size")
            }
            val gpu = runCatching { NcnnVulkanNativeV52.gpuName(engine) }
                .getOrDefault("Vulkan GPU")
                .ifBlank { "Vulkan GPU" }
            NcnnVulkanPortraitMatteV52(
                appContext = appContext,
                handle = engine,
                gpuName = gpu,
                modelSize = size,
                paramPath = param.absolutePath,
                binPath = bin.absolutePath,
                threads = threads,
                boundedVulkanSession = needsBoundedVulkanSession(),
            )
        }.getOrNull()
    }

    private val plane = modelSize * modelSize
    private val inputCount = plane * 3
    private val inputSquare = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val input = FloatArray(inputCount)
    private var lastMs: Double = -1.0
    private var framesOnCurrentEngine: Int = 0
    private var engineRecycleCount: Int = 0

    override val backendLabel: String
        get() = buildString {
            append("Matting: GPU (ncnn Vulkan)")
            if (lastMs >= 0.0) append(" · ").append("%.1f".format(lastMs)).append(" ms")
            append(" · PP-MattingV2 ").append(modelSize).append(" fixed")
            append(" · ").append(gpuName)
            append(" · CPU op fallback possible")
            if (boundedVulkanSession) append(" · bounded Vulkan session")
            if (engineRecycleCount > 0) append(" · recycled ").append(engineRecycleCount).append("x")
            if (Build.MODEL.isNotBlank() && !gpuName.contains(Build.MODEL, ignoreCase = true)) {
                append(" · ").append(Build.MODEL)
            }
        }

    private fun thermalBackoffIfNeeded() {
        if (!boundedVulkanSession || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_MODERATE -> SystemClock.sleep(80L)
            PowerManager.THERMAL_STATUS_SEVERE -> SystemClock.sleep(220L)
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> SystemClock.sleep(500L)
        }
    }

    private fun recycleVulkanEngineIfNeeded() {
        if (!boundedVulkanSession || framesOnCurrentEngine < UNISOC_ENGINE_BATCH_FRAMES) return
        val oldHandle = handle
        check(oldHandle != 0L) { "ncnn Vulkan PP-MattingV2 backend is closed" }

        // Create the replacement first. If recreation fails, keep the known-good current engine so
        // the caller can still finish/checkpoint the run instead of turning a stability guard into
        // an avoidable hard failure.
        val replacement = NcnnVulkanNativeV52.createEngine(paramPath, binPath, threads)
        if (replacement == 0L) {
            framesOnCurrentEngine = 0
            return
        }
        val replacementSize = NcnnVulkanNativeV52.modelSize(replacement)
        if (replacementSize != modelSize) {
            NcnnVulkanNativeV52.destroy(replacement)
            framesOnCurrentEngine = 0
            return
        }

        handle = replacement
        framesOnCurrentEngine = 0
        engineRecycleCount += 1
        runCatching { NcnnVulkanNativeV52.destroy(oldHandle) }
        // Give Mali/UNISOC a short scheduling gap after releasing the old allocator/pipeline set.
        SystemClock.sleep(60L)
    }

    override fun infer(source: Bitmap): Bitmap {
        recycleVulkanEngineIfNeeded()
        thermalBackoffIfNeeded()

        val activeHandle = handle
        check(activeHandle != 0L) { "ncnn Vulkan PP-MattingV2 backend is closed" }
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        inputCanvas.drawBitmap(source, null, Rect(0, 0, modelSize, modelSize), filterPaint)
        inputSquare.getPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)
        for (i in 0 until plane) {
            val pixel = pixels[i]
            input[i] = Color.red(pixel) / 127.5f - 1f
            input[plane + i] = Color.green(pixel) / 127.5f - 1f
            input[plane * 2 + i] = Color.blue(pixel) / 127.5f - 1f
        }

        val alpha = NcnnVulkanNativeV52.run(activeHandle, input)
        framesOnCurrentEngine += 1
        check(alpha.size >= plane) {
            "PP-MattingV2 $modelSize Vulkan output has ${alpha.size} values; expected at least $plane"
        }
        lastMs = NcnnVulkanNativeV52.lastInferenceMs(activeHandle)

        for (i in 0 until plane) {
            val value = alpha[i].coerceIn(0f, 1f)
            val v = (value * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, modelSize, 0, 0, modelSize, modelSize)

        PortraitMatteRuntimeStatusV50.update(backendLabel)
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(alphaSquare, null, Rect(0, 0, source.width, source.height), filterPaint)
        }
    }

    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) runCatching { NcnnVulkanNativeV52.destroy(activeHandle) }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
