package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.beautyStrengthsV28
import com.tajuli.digitorandroid.editor.render.VisualOverlayRenderEnvironmentV19
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        prepareBeautyTracks(project, onProgress)

        if (capabilities.supportsGpuEditing()) {
            val gpuName = capabilities.gpuDescription()
            onProgress(ExportProgress.Stage("GPU selected · $gpuName · ${quality.label}", 0f))
            try {
                return gpu.export(project, output, quality, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (gpuFailure: Throwable) {
                // A GPU-capable device must not silently fall back to the video-only CPU path because
                // CPU export does not yet preserve mixed audio/text parity. Surface a useful Media3
                // error code instead, so a remaining device-specific failure is actionable from the UI.
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

    /**
     * Face-aware beauty is driven by local ML geometry/masks rather than a full-frame LUT. Export
     * must therefore not start until every clip with an active beauty layer has the required cache.
     * This also fixes the common workflow where a user taps Skin Bright/Pink Lips and immediately
     * exports before the editor's background analysis coroutine has finished.
     */
    private suspend fun prepareBeautyTracks(
        project: TimelineProject,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val beautyClips = project.tracks.asSequence()
            .filter { it.kind == TrackKind.VIDEO && !it.muted }
            .flatMap { it.clips.asSequence() }
            .map { clip -> clip to clip.beautyStrengthsV28() }
            .filter { (_, strengths) -> !strengths.isIdentity }
            .toList()
        if (beautyClips.isEmpty()) return

        beautyClips.forEachIndexed { index, (clip, strengths) ->
            val requireHairMask = strengths.hairBrowDark > .001f
            val fraction = ((index.toFloat() / beautyClips.size.toFloat()) * .08f).coerceIn(0f, .08f)
            onProgress(
                ExportProgress.Stage(
                    "Preparing beauty ${index + 1}/${beautyClips.size} · ${clip.label}",
                    fraction,
                ),
            )
            val track = withContext(Dispatchers.Default) {
                BeautyFaceAnalyzerV28(appContext).analyzeAndStore(
                    clip = clip,
                    requireHairMask = requireHairMask,
                )
            }
            if (track.samples.none { sample -> sample.geometry != null }) {
                onProgress(
                    ExportProgress.Stage(
                        "Beauty: no clear face detected · ${clip.label}",
                        fraction,
                    ),
                )
            }
        }
    }
}
