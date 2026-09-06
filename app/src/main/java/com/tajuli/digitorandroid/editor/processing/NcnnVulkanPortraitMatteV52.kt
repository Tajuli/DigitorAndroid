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
    external fun createEngine(paramPath: String, binPath: String, threads: Int): Long
    external fun run(handle: Long, input: FloatArray): FloatArray
    external fun lastInferenceMs(handle: Long): Double
    external fun gpuName(handle: Long): String
    external fun destroy(handle: Long)
}

/**
 * PP-MattingV2/STDC1 512 using ncnn's Vulkan compute backend.
 *
 * This deliberately does not use NNAPI or Paddle Lite. The pinned PP-MattingV2 ONNX model is
 * converted to ncnn during the Android build, then ncnn dispatches the neural graph through Vulkan
 * to the phone GPU. This is the primary Z60 / Mali-G57 path.
 */
internal class NcnnVulkanPortraitMatteV52 private constructor(
    private var handle: Long,
    private val gpuName: String,
) : PortraitMatteBackendV50 {
    internal companion object {
        const val PARAM_ASSET = "ppmattingv2_stdc1_human_512_vulkan.ncnn.param"
        const val BIN_ASSET = "ppmattingv2_stdc1_human_512_vulkan.ncnn.bin"
        const val MODEL_SIZE = 512
        const val PLANE = MODEL_SIZE * MODEL_SIZE
        const val INPUT_COUNT = PLANE * 3

        private fun materializeAsset(
            context: Context,
            assetName: String,
            minimumBytes: Long,
        ): File {
            val directory = File(context.codeCacheDir, "ppmatting-ncnn-v52").apply { mkdirs() }
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

            val appContext = context.applicationContext
            val param = materializeAsset(appContext, PARAM_ASSET, 1_000L)
            val bin = materializeAsset(appContext, BIN_ASSET, 5_000_000L)
            val engine = NcnnVulkanNativeV52.createEngine(param.absolutePath, bin.absolutePath, 2)
            check(engine != 0L) { "ncnn could not create the PP-MattingV2 Vulkan engine" }
            val gpu = runCatching { NcnnVulkanNativeV52.gpuName(engine) }
                .getOrDefault("Vulkan GPU")
                .ifBlank { "Vulkan GPU" }
            NcnnVulkanPortraitMatteV52(engine, gpu)
        }.getOrNull()
    }

    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(PLANE)
    private val alphaPixels = IntArray(PLANE)
    private val input = FloatArray(INPUT_COUNT)
    private var lastMs: Double = -1.0

    override val backendLabel: String
        get() = buildString {
            append("Matting: GPU (ncnn Vulkan)")
            if (lastMs >= 0.0) append(" · ").append("%.1f".format(lastMs)).append(" ms")
            append(" · 512")
            append(" · ").append(gpuName)
            append(" · CPU op fallback possible")
            if (Build.MODEL.isNotBlank() && !gpuName.contains(Build.MODEL, ignoreCase = true)) {
                append(" · ").append(Build.MODEL)
            }
        }

    override fun infer(source: Bitmap): Bitmap {
        val activeHandle = handle
        check(activeHandle != 0L) { "ncnn Vulkan PP-MattingV2 backend is closed" }
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        inputCanvas.drawBitmap(source, null, Rect(0, 0, MODEL_SIZE, MODEL_SIZE), filterPaint)
        inputSquare.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        for (i in 0 until PLANE) {
            val pixel = pixels[i]
            input[i] = Color.red(pixel) / 127.5f - 1f
            input[PLANE + i] = Color.green(pixel) / 127.5f - 1f
            input[PLANE * 2 + i] = Color.blue(pixel) / 127.5f - 1f
        }

        val alpha = NcnnVulkanNativeV52.run(activeHandle, input)
        check(alpha.size >= PLANE) { "PP-MattingV2 Vulkan output has ${alpha.size} values" }
        lastMs = NcnnVulkanNativeV52.lastInferenceMs(activeHandle)

        for (i in 0 until PLANE) {
            val value = alpha[i].coerceIn(0f, 1f)
            val v = (value * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

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
