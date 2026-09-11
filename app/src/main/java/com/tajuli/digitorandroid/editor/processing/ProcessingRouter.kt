package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.render.VisualOverlayRenderEnvironmentV19
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
    ): ExportResult = export(project, output, ExportSettingsV72(), onProgress)

    /** Compatibility overload retained for existing callers: quality changes, geometry/FPS stay Original. */
    suspend fun export(
        project: TimelineProject,
        output: File,
        quality: ExportQuality,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = export(
        project,
        output,
        ExportSettingsV72(quality = quality),
        onProgress,
    )

    suspend fun export(
        project: TimelineProject,
        output: File,
        settings: ExportSettingsV72,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult {
        // Export settings are applied to a snapshot only. Timeline/source trims and the editor project
        // remain untouched while render geometry, target FPS and bitrate all resolve consistently.
        val exportProject = settings.applyTo(project)
        val quality = settings.quality
        val formatLabel = "${exportProject.width}×${exportProject.height} · ${exportProject.frameRate} fps"

        // Export is always allowed, even when Pro Cutout is incomplete or was cancelled. The render
        // stages use whatever durable matte frames already exist and pass through the original frame
        // wherever no sufficiently-near matte exists. If analysis is still running, pause it first
        // so export can acquire the shared decoder/GPU lease without running two fragile native
        // pipelines concurrently. Wait on StateFlow instead of blocking the UI thread on the lease.
        if (CutoutAnalysisRuntimeV66.requestPauseForExport()) {
            onProgress(ExportProgress.Stage("Pausing Pro Cutout for export…", 0f))
            CutoutAnalysisRuntimeV66.state.first { !it.busy }
        }

        if (capabilities.supportsGpuEditing()) {
            val gpuName = capabilities.gpuDescription()
            onProgress(ExportProgress.Stage("GPU selected · $gpuName · $formatLabel · ${quality.label}", 0f))
            try {
                return gpu.export(exportProject, output, quality, onProgress)
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

        onProgress(ExportProgress.Stage("No compatible GPU · CPU fallback · $formatLabel · ${quality.label}", 0f))
        return cpu.export(exportProject, output, quality, onProgress)
    }
}
