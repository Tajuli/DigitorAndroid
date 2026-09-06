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
import kotlin.math.abs
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * PP-MattingV2/STDC1 512 GPU-only backend used by Pro Cutout V57+.
 *
 * The bundled model is generated with Paddle Lite `valid_targets=opencl` only. That is important:
 * there are no ARM kernels in this program, so Paddle Lite cannot silently execute unsupported
 * graph nodes on the CPU. Predictor creation plus a real 512x512 warm-up inference must succeed
 * before the UI is allowed to report GPU.
 *
 * V60 additionally guards the OpenCL *base* matte itself. A small number of mobile OpenCL drivers
 * can occasionally return a completed tensor containing a tile/stripe-corrupted alpha even though
 * predictor.run() succeeds. The older V59 guard lived after hair/temporal refinement, so a corrupt
 * PP-MattingV2 base could still be treated as authoritative. V60 checks spatial banding plus
 * source-aware temporal consistency, retries suspicious GPU inference once, and only if both GPU
 * results remain implausible holds the last accepted base matte for that single anchor. There is no
 * CPU neural fallback; every attempted PP-MattingV2 inference remains Paddle Lite OpenCL GPU.
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
        const val GUARD_SIDE_V60 = 48
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

    private var previousAcceptedAlphaV60: FloatArray? = null
    private var previousSourceSignatureV60: FloatArray? = null

    val backendLabel: String = "PP-MattingV2 · Paddle Lite OpenCL GPU · FP32 · 512"

    init {
        check(modelFile.isFile && modelFile.length() > 10_000_000L) {
            "PP-MattingV2 Paddle Lite OpenCL model is missing or incomplete: ${modelFile.name}"
        }
        val config = MobileConfig().apply {
            setModelFromFile(modelFile.absolutePath)
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
        inputSquare.getPixels(inputPixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        fillPpMattingV2InputV56(inputPixels, inputData)

        val sourceSignature = sourceSignatureV60(inputPixels)
        val first = runAlphaV60()
        val previousAlpha = previousAcceptedAlphaV60
        val previousSource = previousSourceSignatureV60
        val firstSane = isIntrinsicAlphaSaneV60(first) &&
            isTemporallyPlausibleV60(previousAlpha, previousSource, first, sourceSignature)

        val selected: FloatArray
        val acceptedCurrent: Boolean
        if (firstSane) {
            selected = first
            acceptedCurrent = true
        } else {
            val retry = runAlphaV60()
            val retrySane = isIntrinsicAlphaSaneV60(retry) &&
                isTemporallyPlausibleV60(previousAlpha, previousSource, retry, sourceSignature)
            when {
                retrySane -> {
                    selected = retry
                    acceptedCurrent = true
                }
                previousAlpha != null && previousSource != null &&
                    meanAbsDeltaV60(previousSource, sourceSignature) <= .14f -> {
                    // Both GPU attempts look implausible while the source is still close enough to
                    // the previous frame. Hold one known-clean matte; do not poison temporal history.
                    selected = previousAlpha
                    acceptedCurrent = false
                }
                isIntrinsicAlphaSaneV60(retry) -> {
                    // Large motion/cut: prefer the current source's retry rather than freezing a
                    // previous subject merely because temporal difference is high.
                    selected = retry
                    acceptedCurrent = true
                }
                else -> error("PP-MattingV2 OpenCL produced two spatially corrupted alpha tensors")
            }
        }

        if (acceptedCurrent) {
            previousAcceptedAlphaV60 = selected.copyOf()
            previousSourceSignatureV60 = sourceSignature
        }

        writeAlphaBitmapV60(selected)
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(alphaSquare, null, Rect(0, 0, source.width, source.height), filterPaint)
        }
    }

    private fun runAlphaV60(): FloatArray {
        check(inputTensor.setData(inputData)) { "Paddle Lite rejected PP-MattingV2 input" }
        check(predictor.run()) { "Paddle Lite PP-MattingV2 OpenCL inference failed" }
        val outputTensor = predictor.getOutput(0)
        validateOutput(outputTensor)
        val raw = outputTensor.getFloatData()
        check(raw.size >= plane) {
            "PP-MattingV2 alpha output has ${raw.size} values; expected at least $plane"
        }
        return FloatArray(plane) { index -> raw[index] }
    }

    private fun writeAlphaBitmapV60(alpha: FloatArray) {
        check(alpha.size >= plane)
        for (i in 0 until plane) {
            val value = alpha[i]
            check(value.isFinite()) { "PP-MattingV2 alpha contained a non-finite value" }
            val v = (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            alphaPixels[i] = Color.argb(255, v, v, v)
        }
        alphaSquare.setPixels(alphaPixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
    }

    /** Reject full-width/full-height tile or stripe discontinuities while allowing silhouette edges. */
    private fun isIntrinsicAlphaSaneV60(alpha: FloatArray): Boolean {
        if (alpha.size < plane) return false
        val grid = FloatArray(GUARD_SIDE_V60 * GUARD_SIDE_V60)
        for (gy in 0 until GUARD_SIDE_V60) {
            val y = ((gy + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
            for (gx in 0 until GUARD_SIDE_V60) {
                val x = ((gx + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
                val value = alpha[y * MODEL_SIZE + x]
                if (!value.isFinite()) return false
                grid[gy * GUARD_SIDE_V60 + gx] = value.coerceIn(0f, 1f)
            }
        }

        for (gy in 1 until GUARD_SIDE_V60) {
            var severe = 0
            var meanDelta = 0f
            for (gx in 0 until GUARD_SIDE_V60) {
                val delta = abs(
                    grid[(gy - 1) * GUARD_SIDE_V60 + gx] -
                        grid[gy * GUARD_SIDE_V60 + gx],
                )
                meanDelta += delta
                if (delta >= .62f) severe++
            }
            meanDelta /= GUARD_SIDE_V60.toFloat()
            if (severe >= (GUARD_SIDE_V60 * .46f).toInt() && meanDelta >= .30f) return false
        }
        for (gx in 1 until GUARD_SIDE_V60) {
            var severe = 0
            var meanDelta = 0f
            for (gy in 0 until GUARD_SIDE_V60) {
                val delta = abs(
                    grid[gy * GUARD_SIDE_V60 + gx - 1] -
                        grid[gy * GUARD_SIDE_V60 + gx],
                )
                meanDelta += delta
                if (delta >= .62f) severe++
            }
            meanDelta /= GUARD_SIDE_V60.toFloat()
            if (severe >= (GUARD_SIDE_V60 * .46f).toInt() && meanDelta >= .30f) return false
        }
        return true
    }

    private fun isTemporallyPlausibleV60(
        previousAlpha: FloatArray?,
        previousSource: FloatArray?,
        currentAlpha: FloatArray,
        currentSource: FloatArray,
    ): Boolean {
        if (previousAlpha == null || previousSource == null) return true
        val sourceDelta = meanAbsDeltaV60(previousSource, currentSource)
        if (sourceDelta >= .14f) return true

        var alphaDelta = 0f
        var severeFlips = 0
        var samples = 0
        for (gy in 0 until GUARD_SIDE_V60) {
            val y = ((gy + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
            for (gx in 0 until GUARD_SIDE_V60) {
                val x = ((gx + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
                val index = y * MODEL_SIZE + x
                val old = previousAlpha[index].coerceIn(0f, 1f)
                val now = currentAlpha[index]
                if (!now.isFinite()) return false
                val current = now.coerceIn(0f, 1f)
                alphaDelta += abs(old - current)
                if ((old <= .06f && current >= .72f) || (old >= .86f && current <= .22f)) {
                    severeFlips++
                }
                samples++
            }
        }
        if (samples == 0) return false
        val meanAlphaDelta = alphaDelta / samples.toFloat()
        val flipRate = severeFlips / samples.toFloat()
        return when {
            sourceDelta < .04f -> meanAlphaDelta <= .16f && flipRate <= .08f
            sourceDelta < .08f -> meanAlphaDelta <= .23f && flipRate <= .14f
            else -> meanAlphaDelta <= .31f && flipRate <= .21f
        }
    }

    private fun sourceSignatureV60(pixels: IntArray): FloatArray {
        val signature = FloatArray(GUARD_SIDE_V60 * GUARD_SIDE_V60)
        for (gy in 0 until GUARD_SIDE_V60) {
            val y = ((gy + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
            for (gx in 0 until GUARD_SIDE_V60) {
                val x = ((gx + .5f) * MODEL_SIZE / GUARD_SIDE_V60).toInt().coerceIn(0, MODEL_SIZE - 1)
                val pixel = pixels[y * MODEL_SIZE + x]
                val r = ((pixel ushr 16) and 0xff) / 255f
                val g = ((pixel ushr 8) and 0xff) / 255f
                val b = (pixel and 0xff) / 255f
                signature[gy * GUARD_SIDE_V60 + gx] = .2126f * r + .7152f * g + .0722f * b
            }
        }
        return signature
    }

    private fun meanAbsDeltaV60(a: FloatArray, b: FloatArray): Float {
        val count = minOf(a.size, b.size)
        if (count <= 0) return 1f
        var total = 0f
        for (i in 0 until count) total += abs(a[i] - b[i])
        return total / count.toFloat()
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
        previousAcceptedAlphaV60 = null
        previousSourceSignatureV60 = null
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
