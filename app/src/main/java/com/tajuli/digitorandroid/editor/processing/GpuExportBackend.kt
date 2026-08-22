package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult as Media3ExportResult
import androidx.media3.transformer.Transformer
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@UnstableApi
class GpuExportBackend(
    private val context: Context,
    private val compositionBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) : ExportBackend {
    override suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        onProgress(ExportProgress.Stage("GPU: building multitrack composition", 0.05f))
        val composition = compositionBuilder.build(project)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: Media3ExportResult) {
                    if (continuation.isActive) {
                        onProgress(ExportProgress.Stage("GPU: complete", 1f))
                        continuation.resume(ExportResult(output, Backend.GPU))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: Media3ExportResult,
                    exportException: ExportException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })
            .build()

        continuation.invokeOnCancellation {
            Handler(transformer.applicationLooper).post { runCatching { transformer.cancel() } }
        }
        onProgress(ExportProgress.Stage("GPU: MediaCodec + OpenGL export", 0.1f))
        transformer.start(composition, output.absolutePath)
    }
}
