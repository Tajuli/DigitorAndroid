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
 * Adaptive PP-MattingV2/STDC1 512 backend used by Pro Cutout V56.
 *
 * The OpenCL model was optimized with `valid_targets=opencl` only. It cannot silently schedule an
 * unsupported node on ARM: predictor creation plus a warm-up inference must succeed before the UI
 * reports GPU. If OpenCL is unavailable or later fails, the same PP-MattingV2 network is reopened
 * from an ARM-only model. This keeps CI804 matte quality on both paths without SelfieMulticlass.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val OPENCL_MODEL_ASSET = "ppmattingv2_stdc1_human_512_opencl.nb"
        const val ARM_MODEL_ASSET = "ppmattingv2_stdc1_human_512_arm.nb"
    }

    private val appContext = context.applicationContext
    private var engine: PaddleLitePpMattingV56

    override var backendLabel: String
        private set

    init {
        val gpu = runCatching { createWarmedEngine(OPENCL_MODEL_ASSET, usingGpu = true) }
        engine = gpu.getOrElse {
            createWarmedEngine(ARM_MODEL_ASSET, usingGpu = false)
        }
        backendLabel = engine.backendLabel
    }

    override fun infer(source: Bitmap): Bitmap {
        val active = engine
        return try {
            active.infer(source)
        } catch (gpuFailure: Throwable) {
            if (!active.usingGpu) throw gpuFailure
            runCatching { active.close() }
            val cpu = createEngine(ARM_MODEL_ASSET, usingGpu = false)
            try {
                cpu.warmUp()
                engine = cpu
                backendLabel = cpu.backendLabel + " · recovered after OpenCL failure"
                cpu.infer(source)
            } catch (cpuFailure: Throwable) {
                runCatching { cpu.close() }
                cpuFailure.addSuppressed(gpuFailure)
                throw cpuFailure
            }
        }
    }

    private fun createEngine(assetName: String, usingGpu: Boolean): PaddleLitePpMattingV56 {
        val modelFile = materializePaddleModelV56(appContext, assetName)
        return PaddleLitePpMattingV56(modelFile, usingGpu)
    }

    private fun createWarmedEngine(assetName: String, usingGpu: Boolean): PaddleLitePpMattingV56 {
        val candidate = createEngine(assetName, usingGpu)
        return try {
            candidate.warmUp()
            candidate
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    override fun close() {
        runCatching { engine.close() }
    }
}

private class PaddleLitePpMattingV56(
    modelFile: File,
    val usingGpu: Boolean,
) : AutoCloseable {
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
    private val predictor: ReleasablePaddlePredictorV56
    private val inputTensor: Tensor

    val backendLabel: String = if (usingGpu) {
        "PP-MattingV2 · Paddle Lite OpenCL GPU · FP16 · 512"
    } else {
        "PP-MattingV2 · Paddle Lite ARM CPU fallback · FP32 · 512"
    }

    init {
        check(modelFile.isFile && modelFile.length() > 10_000_000L) {
            "PP-MattingV2 Paddle Lite model is missing or incomplete: ${modelFile.name}"
        }
        val config = MobileConfig().apply {
            setModelFromFile(modelFile.absolutePath)
            setThreads(if (usingGpu) 1 else Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setPowerMode(if (usingGpu) PowerMode.LITE_POWER_NO_BIND else PowerMode.LITE_POWER_HIGH)
        }
        predictor = ReleasablePaddlePredictorV56(config)
        inputTensor = predictor.getInput(0)
            ?: error("Paddle Lite could not create the PP-MattingV2 input tensor")
        check(inputTensor.resize(longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()))) {
            "Paddle Lite rejected the PP-MattingV2 1x3x512x512 input shape"
        }
    }

    fun warmUp() {
        // Zero is normalized mid-grey. A real run validates model load, every selected kernel and
        // the OpenCL device before the UI is allowed to claim that the backend is GPU.
        inputData.fill(0f)
        check(inputTensor.setData(inputData)) { "Paddle Lite rejected PP-MattingV2 warm-up input" }
        check(predictor.run()) { "Paddle Lite PP-MattingV2 warm-up inference failed" }
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
        check(predictor.run()) { "Paddle Lite PP-MattingV2 inference failed" }

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
private class ReleasablePaddlePredictorV56(config: MobileConfig) :
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

private val paddleModelInstallLockV56 = Any()

private fun materializePaddleModelV56(context: Context, assetName: String): File =
    synchronized(paddleModelInstallLockV56) {
        val modelDir = File(context.noBackupFilesDir, "ppmattingv2_paddle_lite_v56")
        val target = File(modelDir, assetName)
        if (target.isFile && target.length() > 10_000_000L) return@synchronized target

        modelDir.mkdirs()
        val temporary = File(modelDir, "$assetName.installing")
        if (temporary.exists()) temporary.delete()
        context.assets.open(assetName).buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        check(temporary.length() > 10_000_000L) { "Bundled PP-MattingV2 model is incomplete" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Could not install PP-MattingV2 Paddle Lite model" }
        target
    }
