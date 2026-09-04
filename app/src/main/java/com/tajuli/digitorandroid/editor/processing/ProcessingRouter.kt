package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
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
        validateAutoCutoutReady(project)

        // Low-latency contract: Export never waits for whole-video ML preprocessing for beauty.
        // Auto Cutout is different: its matte is the actual edit, not an optional quality upgrade.
        // Export must therefore refuse to silently pass through the original background when the
        // requested clip has not finished semantic analysis.
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

    private fun validateAutoCutoutReady(project: TimelineProject) {
        val missing = project.tracks
            .asSequence()
            .flatMap { track -> track.clips.asSequence() }
            .firstOrNull { clip ->
                clip.resolvedCutoutV43().mode == CutoutModeV43.PERSON &&
                    !hasPersonCutoutCoverageV43(appContext, clip)
            }
            ?: return

        val name = missing.label.takeIf { it.isNotBlank() } ?: "selected clip"
        throw IllegalStateException(
            "Auto Cutout is not ready for $name. Open Cutout, tap Analyze, and wait for 'Person matte ready' before exporting.",
        )
    }
}
