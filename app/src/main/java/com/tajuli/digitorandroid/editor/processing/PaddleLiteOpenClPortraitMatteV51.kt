package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode
import com.baidu.paddle.lite.Tensor
import java.io.File
import kotlin.math.roundToInt

/**
 * PP-MattingV2/STDC1 512 running through Paddle Lite's official Android Java/JNI OpenCL publish.
 *
 * The .nb model is optimized with valid_targets=opencl,arm, so OpenCL is the first target and ARM
 * remains available only for operators that cannot execute through the OpenCL backend. Using the
 * prebuilt Paddle JNI module avoids linking Paddle's legacy shared C++ ELF into Digitor itself.
 */
internal class PaddleLiteOpenClPortraitMatteV51 private constructor(
    private var predictor: PaddlePredictor?,
    private var inputTensor: Tensor?,
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
            // The bundled Paddle Lite OpenCL JNI publish is ARM64 for the Z60/Mali-G57 target.
            if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return null

            return runCatching {
                val appContext = context.applicationContext
                val model = materializeModel(appContext)
                val config = MobileConfig().apply {
                    setModelFromFile(model.absolutePath)
                    setThreads(2)
                    setPowerMode(PowerMode.LITE_POWER_NO_BIND)
                }
                val predictor = PaddlePredictor.createPaddlePredictor(config)
                    ?: error("Paddle Lite could not create the OpenCL predictor")
                val inputTensor = predictor.getInput(0)
                    ?: error("PP-MattingV2 Paddle predictor did not expose input 0")
                check(inputTensor.resize(longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()))) {
                    "PP-MattingV2 Paddle input resize failed"
                }
                PaddleLiteOpenClPortraitMatteV51(predictor, inputTensor)
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
    private var outputTensor: Tensor? = null
    private var lastMs: Double = -1.0

    override val backendLabel: String
        get() = buildString {
            append("Matting: Paddle Lite OpenCL · GPU preferred")
            if (lastMs >= 0.0) append(" · ").append("%.1f".format(lastMs)).append(" ms")
            append(" · 512")
            append(" · ARM op fallback possible")
            if (Build.MODEL.isNotBlank()) append(" · ").append(Build.MODEL)
        }

    override fun infer(source: Bitmap): Bitmap {
        val activePredictor = predictor
            ?: error("Paddle Lite OpenCL backend is closed")
        val activeInputTensor = inputTensor
            ?: error("Paddle Lite OpenCL input tensor is closed")
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }

        inputCanvas.drawBitmap(source, null, Rect(0, 0, MODEL_SIZE, MODEL_SIZE), filterPaint)
        inputSquare.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        for (i in 0 until PLANE) {
            val pixel = pixels[i]
            input[i] = Color.red(pixel) / 127.5f - 1f
            input[PLANE + i] = Color.green(pixel) / 127.5f - 1f
            input[PLANE * 2 + i] = Color.blue(pixel) / 127.5f - 1f
        }
        check(activeInputTensor.setData(input)) { "PP-MattingV2 Paddle input upload failed" }

        val started = System.nanoTime()
        check(activePredictor.run()) { "PP-MattingV2 Paddle Lite inference returned false" }
        lastMs = (System.nanoTime() - started) / 1_000_000.0

        val activeOutputTensor = outputTensor ?: activePredictor.getOutput(0)?.also {
            outputTensor = it
        } ?: error("PP-MattingV2 Paddle predictor did not expose output 0")
        val alpha = activeOutputTensor.floatData
        check(alpha.size >= PLANE) { "PP-MattingV2 OpenCL output has ${alpha.size} values" }

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
        // Paddle's Java API owns native lifetime through its Tensor/Predictor wrappers. Drop the
        // tensor references first so their finalizers cannot outlive a manually-destroyed predictor.
        outputTensor = null
        inputTensor = null
        predictor = null
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
