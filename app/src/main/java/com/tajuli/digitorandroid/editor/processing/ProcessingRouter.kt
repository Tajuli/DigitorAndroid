package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.render.VisualOverlayRenderEnvironmentV19
import java.io.File
import kotlinx.coroutines.CancellationException

@UnstableApi
class ProcessingRouter(context: Context) {
    private val appContext = context.applicationContext.also(VisualOverlayRenderEnvironmentV19::install)
    private val capabilities = DeviceCapabilityProbe(appContext)
    private val gpu = GpuExportBackend(appContext)
    private val cpu = CpuExportBackend(appContext)

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
        // CapCut-style latency rule: export must never wait for a whole-video ML beauty scan.
        // BeautyFaceEffectV28 has an instant GPU fallback and opportunistically consumes any
        // face/semantic masks already produced by background preview refinement. This makes
        // tapping Export start the normal Media3 pipeline immediately instead of spending
        // minutes in a blocking "Preparing beauty" phase.
        if (capabilities.supportsGpuEditing()) {
            val gpuName = capabilities.gpuDescription()
            onProgress(ExportProgress.Stage("GPU selected · $gpuName · ${quality.label}", 0f))
            try {
                return gpu.export(project, output, quality, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (gpuFailure: Throwable) {
                val exportException = generateSequence(gpuFailure as Throwable?) { it.cause }
                    .filterIsInstance<ExportException>()
                    .firstOrNull()
                val detail = buildString {
                    if (exportException != null) {
                        append("Media3 code=")
                        append(exportException.errorCode)
                        append(" · ")
                    }
                    append(gpuFailure.message ?: gpuFailure::class.java.simpleName)
                }
                throw IllegalStateException(
                    "GPU export failed on $gpuName. $detail",
                    gpuFailure,
                )
            }
        }

        onProgress(ExportProgress.Stage("No compatible GPU · CPU fallback · ${quality.label}", 0f))
        return cpu.export(project, output, quality, onProgress)
    }
}
