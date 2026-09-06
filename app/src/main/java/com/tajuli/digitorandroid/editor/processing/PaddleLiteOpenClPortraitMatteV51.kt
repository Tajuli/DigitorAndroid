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

/** JNI bridge to Paddle Lite's direct OpenCL runtime. */
internal object PaddleLiteOpenClNativeV51 {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("digitor_ppmatting_opencl")
        true
    }.getOrDefault(false)

    fun available(): Boolean = loaded && runCatching { isOpenClAvailable() }.getOrDefault(false)
    fun createEngine(modelPath: String, threads: Int): Long = if (loaded) create(modelPath, threads) else 0L
    fun infer(handle: Long, input: FloatArray): FloatArray = run(handle, input)
    fun inferenceMs(handle: Long): Double = lastInferenceMs(handle)
    fun release(handle: Long) { if (loaded && handle != 0L) destroy(handle) }

    private external fun isOpenClAvailable(): Boolean
    private external fun create(modelPath: String, threads: Int): Long
    private external fun run(handle: Long, input: FloatArray): FloatArray
    private external fun lastInferenceMs(handle: Long): Double
    private external fun destroy(handle: Long)
}

/**
 * PP-MattingV2/STDC1 512 running through Paddle Lite OpenCL.
 *
 * The .nb model is optimized with valid_targets=opencl,arm, so supported layers prefer the GPU and
 * ARM is retained only as compatibility fallback for an operator without an OpenCL kernel. This
 * path avoids Android NNAPI entirely and is intended for Mali-G series devices such as Mali-G57.
 */
internal class PaddleLiteOpenClPortraitMatteV51 private constructor(
    private var handle: Long,
) : PortraitMatteBackendV50 {
    internal companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512_opencl.nb"
        const val MODEL_SIZE = 512
        const val PLANE = MODEL_SIZE * MODEL_SIZE
        const val INPUT_COUNT = PLANE * 3

        private fun materializeModel(context: Context): File {
            val target = File(context.codeCacheDir, MODEL_ASSET)
            val expected = context.assets.open(MODEL_ASSET).use { it.available().toLong() }
            if (!target.isFile || target.length() != expected) {
                target.parentFile?.mkdirs()
                context.assets.open(MODEL_ASSET).use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
                }
            }
            return target
        }

        fun tryCreate(context: Context): PaddleLiteOpenClPortraitMatteV51? {
            // The bundled Paddle Lite OpenCL binary is arm64 for the Z60/Mali-G57 target.
            if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return null
            if (!PaddleLiteOpenClNativeV51.available()) return null
            return runCatching {
                val appContext = context.applicationContext
                val model = materializeModel(appContext)
                val handle = PaddleLiteOpenClNativeV51.createEngine(model.absolutePath, 2)
                check(handle != 0L) { "Paddle Lite could not create the OpenCL predictor" }
                PaddleLiteOpenClPortraitMatteV51(handle)
            }.getOrNull()
        }
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
            append("Matting: GPU (Paddle Lite OpenCL)")
            if (lastMs >= 0.0) append(" · ").append("%.1f".format(lastMs)).append(" ms")
            append(" · 512")
            append(" · ARM op fallback possible")
            if (Build.MODEL.isNotBlank()) append(" · ").append(Build.MODEL)
        }

    override fun infer(source: Bitmap): Bitmap {
        check(handle != 0L) { "Paddle Lite OpenCL backend is closed" }
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        inputCanvas.drawBitmap(source, null, Rect(0, 0, MODEL_SIZE, MODEL_SIZE), filterPaint)
        inputSquare.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        for (i in 0 until PLANE) {
            val pixel = pixels[i]
            input[i] = Color.red(pixel) / 127.5f - 1f
            input[PLANE + i] = Color.green(pixel) / 127.5f - 1f
            input[PLANE * 2 + i] = Color.blue(pixel) / 127.5f - 1f
        }

        val alpha = PaddleLiteOpenClNativeV51.infer(handle, input)
        check(alpha.size >= PLANE) { "PP-MattingV2 OpenCL output has ${alpha.size} values" }
        lastMs = PaddleLiteOpenClNativeV51.inferenceMs(handle)
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
        val old = handle
        handle = 0L
        PaddleLiteOpenClNativeV51.release(old)
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
