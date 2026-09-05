package com.tajuli.digitorandroid.editor.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal interface PortraitMatteBackendV50 : AutoCloseable {
    val backendLabel: String
    fun infer(source: Bitmap): Bitmap
}

/**
 * PP-MattingV2/STDC1 512 portrait matte backend used by Pro Cutout.
 *
 * V53 keeps Android NNAPI accelerator-first, but deliberately avoids CPU_DISABLED / FP16 forcing.
 * Those flags are useful for controlled benchmarks but are too aggressive for a general editor:
 * NNAPI behavior is model/driver/device specific and a broken vendor accelerator can terminate the
 * native process before Kotlin/Java gets an exception. The default NNAPI provider still assigns
 * supported graph partitions to the phone accelerator and lets ORT handle unsupported work safely.
 *
 * A synchronous crash sentinel is written only while the first NNAPI inference is in flight. If a
 * vendor driver kills the app during that first run, the next process launch detects the stale
 * sentinel and permanently disables NNAPI for Cutout on that install, preventing a crash loop.
 * Clearing app data/reinstalling resets the guard. Normal successful NNAPI runs clear the sentinel.
 */
internal class PpMattingV2PortraitMatteV50(context: Context) : PortraitMatteBackendV50 {
    private companion object {
        const val MODEL_ASSET = "ppmattingv2_stdc1_human_512.onnx"
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
        const val PREFS = "digitor_cutout_accelerator_v53"
        const val KEY_NNAPI_IN_FLIGHT = "nnapi_first_inference_in_flight"
        const val KEY_NNAPI_DISABLED = "nnapi_disabled_after_native_crash"
    }

    private data class SessionBundle(
        val options: OrtSession.SessionOptions,
        val session: OrtSession,
        val label: String,
        val usesNnapi: Boolean,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private val resolvedBackendLabel: String
    private val usesNnapi: Boolean
    private var firstAcceleratedInferencePending = false

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
        get() = "PP-MattingV2 · $resolvedBackendLabel · 512"

    init {
        // If the previous process died while its first NNAPI run was marked in-flight, treat that as
        // a native-driver crash. Native SIGSEGV/SIGABRT cannot be recovered by runCatching, so the
        // only reliable generic protection is to avoid repeating the same provider on next launch.
        if (prefs.getBoolean(KEY_NNAPI_IN_FLIGHT, false)) {
            prefs.edit()
                .putBoolean(KEY_NNAPI_DISABLED, true)
                .remove(KEY_NNAPI_IN_FLIGHT)
                .commit()
        }

        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        val bundle = createBestSession(modelBytes)
        sessionOptions = bundle.options
        session = bundle.session
        resolvedBackendLabel = bundle.label
        usesNnapi = bundle.usesNnapi
        firstAcceleratedInferencePending = usesNnapi
        inputName = session.inputNames.firstOrNull()
            ?: error("PP-MattingV2 ONNX did not expose an input tensor")
        inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1L, 3L, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()),
        )
    }

    private fun createBestSession(modelBytes: ByteArray): SessionBundle {
        val nnapiDisabledForInstall = prefs.getBoolean(KEY_NNAPI_DISABLED, false)

        // Default/empty-flag NNAPI is intentionally used. It is still accelerator-first for supported
        // subgraphs, but unlike the previous CPU_DISABLED+FP16 configuration it lets ORT/NNAPI choose
        // safe fallbacks for unsupported operators and avoids forcing fragile vendor paths.
        if (!nnapiDisabledForInstall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val nnapi = OrtSession.SessionOptions()
            try {
                configureCommon(nnapi)
                nnapi.addNnapi()
                return SessionBundle(
                    options = nnapi,
                    session = environment.createSession(modelBytes, nnapi),
                    label = "NNAPI accelerator · safe flags",
                    usesNnapi = true,
                )
            } catch (_: Throwable) {
                runCatching { nnapi.close() }
            }
        }

        val cpu = OrtSession.SessionOptions()
        configureCommon(cpu)
        runCatching { cpu.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) }
        runCatching { cpu.setInterOpNumThreads(1) }
        return SessionBundle(
            options = cpu,
            session = environment.createSession(modelBytes, cpu),
            label = if (nnapiDisabledForInstall) {
                "ORT CPU fallback · NNAPI disabled after native crash"
            } else {
                "ORT CPU fallback"
            },
            usesNnapi = false,
        )
    }

    private fun configureCommon(options: OrtSession.SessionOptions) {
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setMemoryPatternOptimization(true)
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

        val markCrashGuard = firstAcceleratedInferencePending && usesNnapi
        if (markCrashGuard) {
            // commit(), not apply(): the marker must be on disk before entering vendor native code.
            prefs.edit().putBoolean(KEY_NNAPI_IN_FLIGHT, true).commit()
        }

        try {
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
        } finally {
            if (markCrashGuard) {
                // This line is reached only if native inference returned to Java/Kotlin. A process
                // death leaves the marker behind and disables NNAPI on the next app launch.
                prefs.edit().remove(KEY_NNAPI_IN_FLIGHT).commit()
                firstAcceleratedInferencePending = false
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
