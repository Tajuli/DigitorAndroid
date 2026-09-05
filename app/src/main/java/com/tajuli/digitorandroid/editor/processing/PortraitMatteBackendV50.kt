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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * Stable V51 fast-path portrait matte for Pro Cutout.
 *
 * The 256x256 MediaPipe SelfieMulticlass model predicts background, hair, body-skin, face-skin,
 * clothes and accessories. Person alpha is therefore simply 1 - background confidence. The neural
 * graph runs on MediaPipe's GPU delegate; only the small confidence-buffer -> Bitmap conversion is
 * CPU-side before the existing GLES temporal/hair refinement stage.
 *
 * Unlike the NNAPI PP-MattingV2 path below, this backend does not enter vendor NNAPI drivers that can
 * process-abort on some phones during the first frame. Pro Cutout uses requireGpu=true so it either
 * gets the MediaPipe GPU delegate or fails cleanly with a Kotlin exception that the editor can show.
 */
internal class MediaPipePersonMatteV51(
    context: Context,
    requireGpu: Boolean = true,
) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "selfie_multiclass_256x256.tflite"
        const val BACKGROUND_CLASS = 0
    }

    private val segmenter: ImageSegmenter
    private val usingGpuDelegate: Boolean

    override val backendLabel: String
        get() = "SelfieMulticlass · MediaPipe ${if (usingGpuDelegate) "GPU" else "CPU fallback"} · 256"

    init {
        val app = context.applicationContext
        val gpu = runCatching { createSegmenter(app, Delegate.GPU) }
        if (gpu.isSuccess) {
            segmenter = gpu.getOrThrow()
            usingGpuDelegate = true
        } else if (requireGpu) {
            throw IllegalStateException(
                "MediaPipe GPU person matte is unavailable on this device",
                gpu.exceptionOrNull(),
            )
        } else {
            segmenter = createSegmenter(app, Delegate.CPU)
            usingGpuDelegate = false
        }
    }

    override fun infer(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot run person matte on a recycled bitmap" }
        val result = segmenter.segment(BitmapImageBuilder(source).build())
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
            // Preserve a soft matte while suppressing low-confidence background haze.
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
        segmenter.close()
    }

    private companion object Factory {
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
}

/**
 * PP-MattingV2/STDC1 512 portrait matte backend retained for compatibility experiments.
 *
 * V51 deliberately requires accelerator-only execution for the expensive ONNX graph. NNAPI's CPU
 * implementation is disabled and ONNX Runtime is forbidden from assigning unsupported nodes to its
 * CPU EP. This path is NOT used by the main Analyze flow because several vendor NNAPI drivers can
 * terminate the app process instead of returning a catchable error on unsupported graphs.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
        const val DISABLE_CPU_EP_FALLBACK = "session.disable_cpu_ep_fallback"
    }

    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession
    private val inputName: String

    private val plane = MODEL_SIZE * MODEL_SIZE
    private val inputPixels = IntArray(plane)
    private val alphaPixels = IntArray(plane)
    private val inputSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val alphaSquare = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val inputCanvas = Canvas(inputSquare)
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val directBytes = ByteBuffer
        .allocateDirect(plane * CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val inputBuffer = directBytes.asFloatBuffer()
    private val inputTensor: OnnxTensor

    override val backendLabel: String
        get() = "PP-MattingV2 · NNAPI HW-only · FP16 · 512"

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "PP-MattingV2 hardware-only portrait matting requires Android 10 (API 29) or newer"
        }
        sessionOptions.addNnapi(
            EnumSet.of(
                NNAPIFlags.CPU_DISABLED,
                NNAPIFlags.USE_FP16,
            ),
        )
        sessionOptions.addConfigEntry(DISABLE_CPU_EP_FALLBACK, "1")

        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = try {
            environment.createSession(modelBytes, sessionOptions)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "This device cannot run the complete PP-MattingV2 graph on an NNAPI hardware accelerator",
                error,
            )
        }
        inputName = session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
    }

    override fun infer(source: Bitmap): Bitmap {
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

        for (i in 0 until plane) {
            val pixel = inputPixels[i]
            inputBuffer.put(i, Color.red(pixel) / 127.5f - 1f)
            inputBuffer.put(plane + i, Color.green(pixel) / 127.5f - 1f)
            inputBuffer.put(plane * 2 + i, Color.blue(pixel) / 127.5f - 1f)
        }

        session.run(mapOf(inputName to inputTensor)).use { result ->
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

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
        if (!inputSquare.isRecycled) inputSquare.recycle()
        if (!alphaSquare.isRecycled) alphaSquare.recycle()
    }
}
