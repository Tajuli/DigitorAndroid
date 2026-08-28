package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineProject
import java.io.File
import kotlinx.coroutines.CancellationException

@UnstableApi
class ProcessingRouter(context: Context) {
    private val capabilities = DeviceCapabilityProbe(context)
    private val gpu = GpuExportBackend(context)
    private val cpu = CpuExportBackend(context)

    suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = export(project, output, ExportQuality.HIGH, onProgress)

    suspend fun export(
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        if (capabilities.supportsGpuEditing()) {
            val gpuName = capabilities.gpuDescription()
            onProgress(ExportProgress.Stage("GPU selected · $gpuName · ${quality.label}", 0f))
            try {
                return gpu.export(project, output, quality, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (gpuFailure: Throwable) {
                // A GPU-capable device must not silently fall back to CPU. Hiding the
                // GPU error made it look as if CPU was preferred. Surface the real
                // failure so the GPU path can be fixed for that device/codec.
                throw IllegalStateException(
                    "GPU export failed on $gpuName. CPU fallback was not used because a GPU is available. " +
                        (gpuFailure.message ?: gpuFailure::class.java.simpleName),
                    gpuFailure,
                )
            }
        }

        onProgress(ExportProgress.Stage("No compatible GPU · CPU fallback · ${quality.label}", 0f))
        return cpu.export(project, output, quality, onProgress)
    }
}
