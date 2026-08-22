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
    ): ExportResult {
        if (capabilities.supportsGpuEditing()) {
            try {
                onProgress(ExportProgress.Stage("GPU selected", 0f))
                return gpu.export(project, output, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (gpuFailure: Throwable) {
                onProgress(ExportProgress.Stage("GPU failed → CPU fallback", 0f))
            }
        } else {
            onProgress(ExportProgress.Stage("No suitable GPU → CPU fallback", 0f))
        }
        return cpu.export(project, output, onProgress)
    }
}
