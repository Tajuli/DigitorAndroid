package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode
import com.baidu.paddle.lite.Tensor
import java.io.File
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * PP-MattingV2/STDC1 512 GPU-only backend used by Pro Cutout V57.
 *
 * The bundled model is generated with Paddle Lite `valid_targets=opencl` only. That is important:
 * there are no ARM kernels in this program, so Paddle Lite cannot silently execute unsupported
 * graph nodes on the CPU. Predictor creation plus a real 512x512 warm-up inference must succeed
 * before the UI is allowed to report GPU.
 *
 * V57 intentionally uses the native Paddle inference graph instead of the ONNX -> TFLite conversion
 * used by the previous revision. The converted TFLite model was only CPU-validated at build time and
 * could be a valid TFLite model while still containing a graph the LiteRT GPU delegate could not
 * fully execute. PP-MattingV2 is a Paddle model, so keeping it in Paddle Lite removes that conversion
 * mismatch and lets the optimizer select an OpenCL-only kernel program ahead of time.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val OPENCL_MODEL_ASSET = "ppmattingv2_stdc1_human_512_opencl.nb"
    }

    private val appContext = context.applicationContext
    private val engine: PaddleLitePpMattingV57 = createWarmedEngine()

    override val backendLabel: String
        get() = engine.backendLabel

    override fun infer(source: Bitmap): Bitmap = try {
        engine.infer(source)
    } catch (error: Throwable) {
        throw IllegalStateException(
            "PP-MattingV2 512 Paddle Lite OpenCL GPU inference failed" +
                (error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
            error,
        )
    }

    private fun createWarmedEngine(): PaddleLitePpMattingV57 {
        val modelFile = materializePaddleModelV57(appContext, OPENCL_MODEL_ASSET)
        val candidate = try {
            PaddleLitePpMattingV57(modelFile)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "PP-MattingV2 512 Paddle Lite OpenCL GPU could not create the predictor" +
                    (error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                error,
            )
        }
        return try {
            candidate.warmUp()
            candidate
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw IllegalStateException(
                "PP-MattingV2 512 Paddle Lite OpenCL GPU warm-up failed" +
                    (error.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                error,
            )
        }
    }

    override fun close() {
        runCatching { engine.close() }
    }
}

private class PaddleLitePpMattingV57(modelFile: File) : AutoCloseable {
    private companion object {
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
    }

    private val plane = MODEL_SIZE * MODEL_SIZE
    private val inputPixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val inputData = FloatArray(plane * CHANNELS)
    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val predictor: ReleasablePaddlePredictorV57
    private val inputTensor: Tensor

    val backendLabel: String = "PP-MattingV2 · Paddle Lite OpenCL GPU · FP16 · 512"

    init {
        check(modelFile.isFile && modelFile.length() > 10_000_000L) {
            "PP-MattingV2 Paddle Lite OpenCL model is missing or incomplete: ${modelFile.name}"
        }
        val config = MobileConfig().apply {
            setModelFromFile(modelFile.absolutePath)
            // The .nb program itself was optimized for OpenCL only. One host thread is sufficient;
            // neural kernels execute through Paddle Lite's OpenCL backend on the device GPU.
            setThreads(1)
            setPowerMode(PowerMode.LITE_POWER_NO_BIND)
        }
        predictor = ReleasablePaddlePredictorV57(config)
        inputTensor = predictor.getInput(0)
            ?: error("Paddle Lite could not create the PP-MattingV2 input tensor")
        check(inputTensor.resize(longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()))) {
            "Paddle Lite rejected the PP-MattingV2 1x3x512x512 input shape"
        }
    }

    fun warmUp() {
        // A successful predictor object alone does not prove OpenCL execution. Run the full graph
        // once before advertising GPU so unsupported drivers/devices fail immediately and honestly.
        inputData.fill(0f)
        check(inputTensor.setData(inputData)) { "Paddle Lite rejected PP-MattingV2 warm-up input" }
        check(predictor.run()) { "Paddle Lite PP-MattingV2 OpenCL warm-up inference failed" }
        validateOutput(predictor.getOutput(0))
    }

    fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run PP-MattingV2 on a recycled bitmap" }
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
        fillPpMattingV2InputV56(inputPixels, inputData)
        check(inputTensor.setData(inputData)) { "Paddle Lite rejected PP-MattingV2 input" }
        check(predictor.run()) { "Paddle Lite PP-MattingV2 OpenCL inference failed" }

        val outputTensor = predictor.getOutput(0)
        validateOutput(outputTensor)
        val alpha = outputTensor.getFloatData()
        check(alpha.size >= plane) {
            "PP-MattingV2 alpha output has ${alpha.size} values; expected at least $plane"
        }
        for (i in 0 until plane) {
            val value = alpha[i].coerceIn(0f, 1f)
            val v = (value * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(
                alphaSquare,
                null,
                Rect(0, 0, source.width, source.height),
                filterPaint,
            )
        }
    }

    private fun validateOutput(output: Tensor?) {
        val tensor = output ?: error("Paddle Lite did not expose PP-MattingV2 output tensor 0")
        val shape = tensor.shape()
        val valueCount = shape.fold(1L) { total, value -> total * value.coerceAtLeast(1L) }
        check(valueCount >= plane) {
            "Unexpected PP-MattingV2 output shape ${shape.contentToString()}"
        }
    }

    override fun close() {
        runCatching { predictor.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}

/** Makes Paddle Lite's protected native release deterministic instead of waiting for finalization. */
private class ReleasablePaddlePredictorV57(config: MobileConfig) :
    PaddlePredictor(config), AutoCloseable {
    override fun close() {
        clear()
    }
}

internal fun fillPpMattingV2InputV56(pixels: IntArray, destination: FloatArray) {
    val plane = pixels.size
    require(destination.size >= plane * 3)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        destination[i] = ((pixel ushr 16) and 0xff) / 127.5f - 1f
        destination[plane + i] = ((pixel ushr 8) and 0xff) / 127.5f - 1f
        destination[plane * 2 + i] = (pixel and 0xff) / 127.5f - 1f
    }
}

private val paddleModelInstallLockV57 = Any()

private fun materializePaddleModelV57(context: Context, assetName: String): File =
    synchronized(paddleModelInstallLockV57) {
        val modelDir = File(context.noBackupFilesDir, "ppmattingv2_paddle_lite_v57")
        val target = File(modelDir, assetName)
        if (target.isFile && target.length() > 10_000_000L) return@synchronized target

        modelDir.mkdirs()
        val temporary = File(modelDir, "$assetName.installing")
        if (temporary.exists()) temporary.delete()
        context.assets.open(assetName).buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        check(temporary.length() > 10_000_000L) { "Bundled PP-MattingV2 OpenCL model is incomplete" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Could not install PP-MattingV2 Paddle Lite OpenCL model" }
        target
    }
