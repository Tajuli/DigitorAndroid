package com.tajuli.digitorandroid.editor.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.roundToInt
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * Crash-safe adaptive person matte backend.
 *
 * Runtime order:
 * 1) MediaPipe Tasks GPU.
 * 2) Direct LiteRT GPU, bypassing MediaPipe Tasks and using OpenCL/OpenGL.
 * 3) Forced direct OpenGL delegate attempt when LiteRT's compatibility profile is conservative but
 *    the device exposes GLES 3.1+.
 * 4) MediaPipe CPU only as the final compatibility fallback.
 *
 * The old PP-MattingV2 NNAPI path is deliberately not instantiated by Analyze. Some vendor NNAPI
 * drivers can terminate the process on the first inference instead of returning a catchable Java
 * exception. Direct LiteRT keeps GPU execution available without entering that unstable path.
 */
internal class MediaPipePersonMatteV51(
    context: Context,
    requireGpu: Boolean = false,
) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "selfie_multiclass_256x256.tflite"
        const val BACKGROUND_CLASS = 0

        fun createSegmenter(context: Context, delegate: Delegate): ImageSegmenter {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
            return ImageSegmenter.createFromOptions(context, options)
        }
    }

    private val segmenter: ImageSegmenter?
    private val directLiteRtGpu: DirectLiteRtPersonMatteV52?
    override val backendLabel: String

    init {
        val app = context.applicationContext
        var resolvedSegmenter: ImageSegmenter? = null
        var resolvedDirectGpu: DirectLiteRtPersonMatteV52? = null
        var resolvedLabel: String? = null

        val mediaPipeGpu = runCatching { createSegmenter(app, Delegate.GPU) }
        if (mediaPipeGpu.isSuccess) {
            resolvedSegmenter = mediaPipeGpu.getOrThrow()
            resolvedLabel = "SelfieMulticlass · MediaPipe GPU · 256"
        } else if (!requireGpu) {
            val directGpu = runCatching { DirectLiteRtPersonMatteV52(app) }
            if (directGpu.isSuccess) {
                resolvedDirectGpu = directGpu.getOrThrow()
                resolvedLabel = resolvedDirectGpu.backendLabel
            } else {
                resolvedSegmenter = createSegmenter(app, Delegate.CPU)
                resolvedLabel = "SelfieMulticlass · CPU fallback · 256"
            }
        } else {
            throw IllegalStateException(
                "MediaPipe GPU person matte is unavailable on this device",
                mediaPipeGpu.exceptionOrNull(),
            )
        }

        segmenter = resolvedSegmenter
        directLiteRtGpu = resolvedDirectGpu
        backendLabel = resolvedLabel ?: error("Could not resolve portrait matte backend")
    }

    override fun infer(source: Bitmap): Bitmap {
        directLiteRtGpu?.let { return it.infer(source) }
        val mediaPipe = segmenter ?: error("Portrait matte backend was closed or not initialized")

        check(!source.isRecycled) { "Cannot run person matte on a recycled bitmap" }
        val result = mediaPipe.segment(BitmapImageBuilder(source).build())
        val masks = result.confidenceMasks().orElse(emptyList())
        val background = masks.getOrNull(BACKGROUND_CLASS)
            ?: error("SelfieMulticlass did not return the background confidence mask")
        val width = background.width.coerceAtLeast(1)
        val height = background.height.coerceAtLeast(1)
        val confidence = ByteBufferExtractor.extract(background).asFloatBuffer()
        confidence.rewind()
        check(confidence.remaining() >= width * height) {
            "SelfieMulticlass background mask is incomplete"
        }

        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val person = (1f - confidence.get().coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val x = ((person - .025f) / .95f).coerceIn(0f, 1f)
            val smooth = x * x * (3f - 2f * x)
            val v = (smooth * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, v, v, v)
        }

        val modelMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        modelMask.setPixels(pixels, 0, width, 0, 0, width, height)
        if (width == source.width && height == source.height) return modelMask
        return Bitmap.createScaledBitmap(modelMask, source.width, source.height, true).also {
            modelMask.recycle()
        }
    }

    override fun close() {
        runCatching { segmenter?.close() }
        runCatching { directLiteRtGpu?.close() }
    }
}

/**
 * Raw LiteRT GPU execution path for SelfieMulticlass.
 *
 * This bypasses MediaPipe Tasks completely. The model expects NHWC float RGB [0,1] input with
 * shape [1,256,256,3] and emits [1,256,256,6]. Create/run/close stay on the same inference worker
 * thread so the GPU delegate keeps EGL affinity.
 */
private class DirectLiteRtPersonMatteV52(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "selfie_multiclass_256x256.tflite"
        const val MODEL_SIZE = 256
        const val INPUT_CHANNELS = 3
        const val OUTPUT_CLASSES = 6
        const val MODEL_TOKEN = "digitor-selfie-multiclass-safe-gpu-v52"

        fun supportsGles31(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            return activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x00030001
        }

        fun loadModelBuffer(context: Context): ByteBuffer {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(bytes)
                    rewind()
                }
        }
    }

    private val gpuDelegate: GpuDelegate
    private val interpreter: Interpreter
    private val backendFlavor: String

    private val plane = MODEL_SIZE * MODEL_SIZE
    private val inputPixels = IntArray(plane)
    private val outputPixels = IntArray(plane)
    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inputBytes = ByteBuffer
        .allocateDirect(plane * INPUT_CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputBytes = ByteBuffer
        .allocateDirect(plane * OUTPUT_CLASSES * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    override val backendLabel: String
        get() = "SelfieMulticlass · $backendFlavor · 256"

    init {
        val app = context.applicationContext
        var usedCompatibilityProfile = false

        val delegateOptions = CompatibilityList().use { compatibility ->
            if (compatibility.isDelegateSupportedOnThisDevice) {
                usedCompatibilityProfile = true
                compatibility.bestOptionsForThisDevice
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    .setPrecisionLossAllowed(true)
                    .setSerializationParams(app.codeCacheDir.absolutePath, MODEL_TOKEN)
            } else {
                check(supportsGles31(app)) {
                    "LiteRT compatibility list rejected this GPU and OpenGL ES 3.1 is unavailable"
                }
                GpuDelegateFactory.Options()
                    .setForceBackend(GpuDelegateFactory.Options.GpuBackend.OPENGL)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    .setPrecisionLossAllowed(true)
                    .setSerializationParams(app.codeCacheDir.absolutePath, MODEL_TOKEN)
            }
        }

        backendFlavor = if (usedCompatibilityProfile) {
            "Direct LiteRT GPU"
        } else {
            "Direct LiteRT GPU/OpenGL"
        }

        val delegate = GpuDelegate(delegateOptions)
        val builtInterpreter = try {
            Interpreter(
                loadModelBuffer(app),
                Interpreter.Options().apply {
                    addDelegate(delegate)
                    setNumThreads(1)
                },
            )
        } catch (error: Throwable) {
            runCatching { delegate.close() }
            throw IllegalStateException("Direct LiteRT GPU interpreter could not initialize", error)
        }

        try {
            val inputShape = builtInterpreter.getInputTensor(0).shape()
            val outputShape = builtInterpreter.getOutputTensor(0).shape()
            check(inputShape.contentEquals(intArrayOf(1, MODEL_SIZE, MODEL_SIZE, INPUT_CHANNELS))) {
                "Unexpected SelfieMulticlass input shape ${inputShape.contentToString()}"
            }
            check(outputShape.contentEquals(intArrayOf(1, MODEL_SIZE, MODEL_SIZE, OUTPUT_CLASSES))) {
                "Unexpected SelfieMulticlass output shape ${outputShape.contentToString()}"
            }
        } catch (error: Throwable) {
            runCatching { builtInterpreter.close() }
            runCatching { delegate.close() }
            throw error
        }

        gpuDelegate = delegate
        interpreter = builtInterpreter
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run direct LiteRT person matte on a recycled bitmap" }

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

        val input = inputBytes.asFloatBuffer()
        input.clear()
        for (pixel in inputPixels) {
            // IMPORTANT: this model expects RGB float values in [0,1]. The old [-1,1] experiment
            // produced near-zero mattes/black preview even though GPU inference itself succeeded.
            input.put(Color.red(pixel) / 255f)
            input.put(Color.green(pixel) / 255f)
            input.put(Color.blue(pixel) / 255f)
        }
        inputBytes.rewind()
        outputBytes.rewind()

        interpreter.run(inputBytes, outputBytes)

        val output = outputBytes.asFloatBuffer()
        output.rewind()
        for (pixelIndex in 0 until plane) {
            val base = pixelIndex * OUTPUT_CLASSES
            var minimum = Float.POSITIVE_INFINITY
            var maximum = Float.NEGATIVE_INFINITY
            var scoreSum = 0f
            for (classIndex in 0 until OUTPUT_CLASSES) {
                val score = output.get(base + classIndex)
                minimum = minOf(minimum, score)
                maximum = maxOf(maximum, score)
                scoreSum += score
            }

            val backgroundProbability = if (
                minimum >= -.001f && maximum <= 1.001f && scoreSum in .85f..1.15f
            ) {
                (output.get(base) / scoreSum.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            } else {
                var expSum = 0.0
                var backgroundExp = 0.0
                for (classIndex in 0 until OUTPUT_CLASSES) {
                    val value = exp((output.get(base + classIndex) - maximum).toDouble())
                    if (classIndex == 0) backgroundExp = value
                    expSum += value
                }
                (backgroundExp / expSum.coerceAtLeast(1e-12)).toFloat().coerceIn(0f, 1f)
            }

            val person = (1f - backgroundProbability).coerceIn(0f, 1f)
            val x = ((person - .025f) / .95f).coerceIn(0f, 1f)
            val smooth = x * x * (3f - 2f * x)
            val value = (smooth * 255f).roundToInt().coerceIn(0, 255)
            outputPixels[pixelIndex] = Color.argb(255, value, value, value)
        }

        val modelMask = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        modelMask.setPixels(outputPixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        if (source.width == MODEL_SIZE && source.height == MODEL_SIZE) return modelMask
        return Bitmap.createScaledBitmap(modelMask, source.width, source.height, true).also {
            modelMask.recycle()
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
    }
}
