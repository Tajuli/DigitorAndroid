package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/** Native ncnn Vulkan bridge. JNI names are kept stable by proguard-rules.pro. */
internal object NcnnVulkanNativeV52 {
    init {
        System.loadLibrary("digitor_ppmatting_ncnn")
    }

    external fun isVulkanAvailable(): Boolean
    external fun createEngine(paramPath: String, binPath: String, threads: Int, modelSize: Int): Long
    external fun modelSize(handle: Long): Int
    external fun run(handle: Long, input: FloatArray): FloatArray
    external fun runInto(handle: Long, input: FloatArray, output: FloatArray): Boolean
    external fun lastInferenceMs(handle: Long): Double
    external fun gpuName(handle: Long): String
    external fun destroy(handle: Long)
}

/**
 * PP-MattingV2/STDC1 using one persistent ncnn Vulkan engine at a user-selected fixed resolution.
 *
 * V69 packages independent fixed 256/320/384/512 graphs. Only the selected graph is materialized and
 * loaded for one analysis run, so offering four quality/speed choices does not create four Vulkan
 * engines or multiply live GPU memory. The engine is never resized or recreated mid-run.
 *
 * Input and alpha tensors are persistent on the Kotlin side. Native inference writes directly into
 * [alpha], avoiding a new Java FloatArray for every analyzed frame.
 */
internal class NcnnVulkanPortraitMatteV52 private constructor(
    private var handle: Long,
    private val gpuName: String,
    private val modelSize: Int,
) : PortraitMatteBackendV50 {
    internal companion object {
        // Retained for source compatibility with old tests/tools; V69 runtime uses the sized helpers.
        const val PARAM_ASSET = "ppmattingv2_stdc1_human_vulkan_384.ncnn.param"
        const val BIN_ASSET = "ppmattingv2_stdc1_human_vulkan_384.ncnn.bin"

        private fun materializeAsset(
            context: Context,
            assetName: String,
            minimumBytes: Long,
        ): File {
            val directory = File(context.codeCacheDir, "ppmatting-ncnn-v69-fixed-multires").apply { mkdirs() }
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

        fun tryCreate(context: Context): NcnnVulkanPortraitMatteV52? = runCatching {
            check(NcnnVulkanNativeV52.isVulkanAvailable()) {
                "No usable Vulkan compute device was reported by ncnn"
            }

            val requestedSize = PpMattingResolutionRuntimeV69.currentSize()
            check(requestedSize in PpMattingResolutionRuntimeV69.supportedSizes) {
                "Unsupported PP-MattingV2 operating point: $requestedSize"
            }

            val appContext = context.applicationContext
            val paramAsset = PpMattingResolutionRuntimeV69.paramAsset(requestedSize)
            val binAsset = PpMattingResolutionRuntimeV69.binAsset(requestedSize)
            val param = materializeAsset(appContext, paramAsset, 1_000L)
            val bin = materializeAsset(appContext, binAsset, 5_000_000L)
            val engine = NcnnVulkanNativeV52.createEngine(
                param.absolutePath,
                bin.absolutePath,
                2,
                requestedSize,
            )
            check(engine != 0L) { "ncnn could not create the PP-MattingV2 Vulkan engine" }

            val actualSize = NcnnVulkanNativeV52.modelSize(engine)
            if (actualSize != requestedSize || actualSize !in PpMattingResolutionRuntimeV69.supportedSizes) {
                NcnnVulkanNativeV52.destroy(engine)
                error("PP-MattingV2 graph/runtime size mismatch: requested=$requestedSize actual=$actualSize")
            }
            val gpu = runCatching { NcnnVulkanNativeV52.gpuName(engine) }
                .getOrDefault("Vulkan GPU")
                .ifBlank { "Vulkan GPU" }
            NcnnVulkanPortraitMatteV52(
                handle = engine,
                gpuName = gpu,
                modelSize = actualSize,
            )
        }.getOrNull()
    }

    private val nativeLock = Any()
    private val plane = modelSize * modelSize
    private val inputCount = plane * 3
    private val inputSquare = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val input = FloatArray(inputCount)
    private val alpha = FloatArray(plane)
    @Volatile private var lastMs: Double = -1.0

    internal val latestInferenceMs: Double
        get() = lastMs

    override val backendLabel: String
        get() = buildString {
            append("Matting: GPU (ncnn Vulkan)")
            if (lastMs >= 0.0) append(" · ").append("%.1f".format(lastMs)).append(" ms")
            append(" · PP-MattingV2 ").append(modelSize).append(" fixed")
            append(" · ").append(gpuName)
            append(" · CPU op fallback possible")
            append(" · persistent Vulkan engine")
            append(" · reusable tensor buffers")
            append(" · fixed graph locked for run")
            if (Build.MODEL.isNotBlank() && !gpuName.contains(Build.MODEL, ignoreCase = true)) {
                append(" · ").append(Build.MODEL)
            }
        }

    override fun infer(source: Bitmap): Bitmap = synchronized(nativeLock) {
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

        check(NcnnVulkanNativeV52.runInto(activeHandle, input, alpha)) {
            "ncnn Vulkan PP-MattingV2 did not fill the reusable alpha tensor"
        }
        lastMs = NcnnVulkanNativeV52.lastInferenceMs(activeHandle)

        for (i in 0 until plane) {
            val value = alpha[i].coerceIn(0f, 1f)
            val v = (value * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, modelSize, 0, 0, modelSize, modelSize)

        PortraitMatteRuntimeStatusV50.update(backendLabel)
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(alphaSquare, null, Rect(0, 0, source.width, source.height), filterPaint)
        }
    }

    override fun close() {
        synchronized(nativeLock) {
            val activeHandle = handle
            handle = 0L
            if (activeHandle != 0L) runCatching { NcnnVulkanNativeV52.destroy(activeHandle) }
        }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
