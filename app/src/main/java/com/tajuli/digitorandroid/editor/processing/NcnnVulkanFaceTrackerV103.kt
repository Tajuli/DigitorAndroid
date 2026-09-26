package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.EyePose
import com.tajuli.digitorandroid.editor.model.TrackedEye
import java.io.File

/** JNI bridge shares the same libncnn Vulkan runtime/library as PP-MattingV2. */
internal object NcnnVulkanFaceTrackingNativeV103 {
    init {
        System.loadLibrary("digitor_ppmatting_ncnn")
    }

    external fun createEngine(
        detectorParamPath: String,
        detectorBinPath: String,
        meshParamPath: String,
        meshBinPath: String,
        threads: Int,
        useGpu: Boolean,
    ): Long

    external fun runInto(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
        output: FloatArray,
    ): Boolean

    external fun isGpu(handle: Long): Boolean
    external fun gpuName(handle: Long): String
    external fun lastInferenceMs(handle: Long): Double
    external fun destroy(handle: Long)
}

/**
 * Native face detector + 468-point Face Mesh on ncnn Vulkan.
 *
 * This deliberately does not use MediaPipe Tasks at runtime. The detector/mesh models are
 * Apache-2.0 MediaPipe-derived ONNX graphs converted to ncnn during the build and executed by the
 * exact ncnn Vulkan runtime already used by PP-MattingV2.
 */
internal class NcnnVulkanFaceTrackerV103 private constructor(
    private var handle: Long,
    val gpuAccelerated: Boolean,
    val gpuName: String,
) : AutoCloseable {
    private val lock = Any()
    private var pixels = IntArray(0)
    private val output = FloatArray(18)

    val backendLabel: String
        get() = if (gpuAccelerated) {
            "ncnn Vulkan · $gpuName"
        } else {
            "ncnn CPU compatibility fallback"
        }

    val latestInferenceMs: Double
        get() = synchronized(lock) {
            if (handle == 0L) -1.0
            else NcnnVulkanFaceTrackingNativeV103.lastInferenceMs(handle)
        }

    fun detect(source: Bitmap): EyePose? = synchronized(lock) {
        val activeHandle = handle
        check(activeHandle != 0L) { "ncnn face tracker is closed" }
        check(!source.isRecycled) { "Cannot track a recycled bitmap" }

        val owned = if (source.config == Bitmap.Config.ARGB_8888) {
            null
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not convert tracking frame to ARGB_8888")
        }
        val bitmap = owned ?: source
        try {
            val required = bitmap.width * bitmap.height
            if (pixels.size != required) pixels = IntArray(required)
            bitmap.getPixels(
                pixels,
                0,
                bitmap.width,
                0,
                0,
                bitmap.width,
                bitmap.height,
            )
            if (!NcnnVulkanFaceTrackingNativeV103.runInto(
                    activeHandle,
                    pixels,
                    bitmap.width,
                    bitmap.height,
                    output,
                )
            ) {
                return@synchronized null
            }

            fun eye(offset: Int) = TrackedEye(
                x = output[offset].coerceIn(0f, 1f),
                y = output[offset + 1].coerceIn(0f, 1f),
                radius = output[offset + 2].coerceAtLeast(0f),
                roll = output[offset + 3],
                open = output[offset + 4].coerceIn(0f, 1f),
            )

            fun rect(offset: Int): BeautyRectV28 = BeautyRectV28(
                left = output[offset],
                top = output[offset + 1],
                right = output[offset + 2],
                bottom = output[offset + 3],
            ).normalized()

            EyePose(
                left = eye(0),
                right = eye(5),
                identity = 1,
                face = rect(10),
                mouth = rect(14),
            )
        } finally {
            owned?.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            val active = handle
            handle = 0L
            if (active != 0L) {
                runCatching { NcnnVulkanFaceTrackingNativeV103.destroy(active) }
            }
        }
    }

    companion object {
        private const val DETECTOR_PARAM = "digitor_blazeface_128.ncnn.param"
        private const val DETECTOR_BIN = "digitor_blazeface_128.ncnn.bin"
        private const val MESH_PARAM = "digitor_facemesh_192.ncnn.param"
        private const val MESH_BIN = "digitor_facemesh_192.ncnn.bin"

        private fun materialize(
            context: Context,
            name: String,
            minimumBytes: Long,
        ): File {
            val directory = File(context.codeCacheDir, "face-tracking-ncnn-v103").apply {
                mkdirs()
            }
            val target = File(directory, name)
            if (!target.isFile || target.length() < minimumBytes) {
                val temp = File(directory, "$name.tmp")
                if (temp.exists()) temp.delete()
                context.assets.open(name).use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
                check(temp.length() >= minimumBytes) {
                    "Packaged ncnn face model is unexpectedly small: $name"
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Could not materialize $name" }
            }
            return target
        }

        fun create(context: Context): NcnnVulkanFaceTrackerV103 {
            val app = context.applicationContext
            val detectorParam = materialize(app, DETECTOR_PARAM, 500L)
            val detectorBin = materialize(app, DETECTOR_BIN, 100_000L)
            val meshParam = materialize(app, MESH_PARAM, 500L)
            val meshBin = materialize(app, MESH_BIN, 500_000L)

            // Initialize/reuse the exact ncnn Vulkan runtime used by PP-MattingV2.
            val vulkanAvailable = runCatching {
                NcnnVulkanNativeV52.isVulkanAvailable()
            }.getOrDefault(false)

            var handle = NcnnVulkanFaceTrackingNativeV103.createEngine(
                detectorParam.absolutePath,
                detectorBin.absolutePath,
                meshParam.absolutePath,
                meshBin.absolutePath,
                2,
                vulkanAvailable,
            )
            if (handle == 0L && vulkanAvailable) {
                handle = NcnnVulkanFaceTrackingNativeV103.createEngine(
                    detectorParam.absolutePath,
                    detectorBin.absolutePath,
                    meshParam.absolutePath,
                    meshBin.absolutePath,
                    2,
                    false,
                )
            }
            check(handle != 0L) { "Could not create ncnn face tracking engine" }

            val gpu = NcnnVulkanFaceTrackingNativeV103.isGpu(handle)
            val gpuName = if (gpu) {
                NcnnVulkanFaceTrackingNativeV103.gpuName(handle)
                    .ifBlank { "Vulkan GPU" }
            } else {
                "CPU"
            }
            return NcnnVulkanFaceTrackerV103(handle, gpu, gpuName)
        }
    }
}
