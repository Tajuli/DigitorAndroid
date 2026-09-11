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
        val formatLabel = exportProject.exportFormatLabelV73()

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
                val firstExportException = gpuFailure.media3ExportExceptionV73()
                val retryProject = codecSafeGpuRetryProjectV73(exportProject)
                val retryQuality = codecSafeGpuRetryQualityV73(quality)
                val retryChangesRequest = retryProject.width != exportProject.width ||
                    retryProject.height != exportProject.height ||
                    retryProject.frameRate != exportProject.frameRate ||
                    retryQuality != quality

                // Camera H.264 can be valid but still trigger runtime errors in a vendor hardware
                // decoder (notably some UNISOC/SPRD stacks). Retry decoder failures once with Media3's
                // software AVC preference. Also use the conservative output envelope so the retry does
                // not immediately run into a second, unrelated encoder capability limit.
                if (firstExportException?.isDecoderFailureV74() == true) {
                    val retryLabel = retryProject.exportFormatLabelV73()
                    runCatching { if (output.exists()) output.delete() }
                    onProgress(
                        ExportProgress.Stage(
                            "AVC decoder failed · retrying software decode · $retryLabel · ${retryQuality.label}",
                            0f,
                        ),
                    )
                    try {
                        val retryResult = gpu.export(
                            project = retryProject,
                            output = output,
                            quality = retryQuality,
                            forceSoftwareAvcDecoder = true,
                            onProgress = onProgress,
                        )
                        return retryResult.copy(
                            note = buildString {
                                retryResult.note?.takeIf { it.isNotBlank() }?.let {
                                    append(it)
                                    append(" · ")
                                }
                                append("software AVC compatibility retry after ")
                                append(gpuFailure.media3DetailV73())
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (retryFailure: Throwable) {
                        throw IllegalStateException(
                            "GPU export failed on $gpuName. Requested decode: ${gpuFailure.media3DetailV73()}. " +
                                "Software AVC retry ($retryLabel · ${retryQuality.label}) also failed: " +
                                retryFailure.media3DetailV73(),
                            retryFailure,
                        )
                    }
                }

                // High-resolution/high-FPS AVC requests can be valid editor settings but outside a
                // particular phone's hardware encoder envelope. Retry only real encoder failures;
                // shader, audio and project errors remain visible instead of being hidden.
                if (firstExportException?.isEncoderFailureV73() == true && retryChangesRequest) {
                    val retryLabel = retryProject.exportFormatLabelV73()
                    runCatching { if (output.exists()) output.delete() }
                    onProgress(
                        ExportProgress.Stage(
                            "Encoder rejected requested format · retrying $retryLabel · ${retryQuality.label}",
                            0f,
                        ),
                    )
                    try {
                        val retryResult = gpu.export(retryProject, output, retryQuality, onProgress)
                        return retryResult.copy(
                            note = buildString {
                                retryResult.note?.takeIf { it.isNotBlank() }?.let {
                                    append(it)
                                    append(" · ")
                                }
                                append("compatibility retry after ")
                                append(gpuFailure.media3DetailV73())
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (retryFailure: Throwable) {
                        throw IllegalStateException(
                            "GPU export failed on $gpuName. Requested: ${gpuFailure.media3DetailV73()}. " +
                                "Compatibility retry ($retryLabel · ${retryQuality.label}) also failed: " +
                                retryFailure.media3DetailV73(),
                            retryFailure,
                        )
                    }
                }

                throw IllegalStateException(
                    "GPU export failed on $gpuName. ${gpuFailure.media3DetailV73()}",
                    gpuFailure,
                )
            }
        }

        onProgress(ExportProgress.Stage("No compatible GPU · CPU fallback · $formatLabel · ${quality.label}", 0f))
        return cpu.export(exportProject, output, quality, onProgress)
    }
}

private fun TimelineProject.exportFormatLabelV73(): String =
    "${width}×${height} · ${frameRate} fps"

private fun Throwable.media3ExportExceptionV73(): ExportException? =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<ExportException>()
        .firstOrNull()

private fun Throwable.media3DetailV73(): String {
    val exportException = media3ExportExceptionV73()
    return buildString {
        if (exportException != null) {
            append("Media3 code=")
            append(exportException.errorCode)
            append(" · ")
        }
        append(message ?: this@media3DetailV73::class.java.simpleName)
    }
}

private fun ExportException.isEncoderFailureV73(): Boolean =
    errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
        errorCode == ExportException.ERROR_CODE_ENCODING_FAILED ||
        errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED

private fun ExportException.isDecoderFailureV74(): Boolean =
    errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == ExportException.ERROR_CODE_DECODING_FAILED ||
        errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
