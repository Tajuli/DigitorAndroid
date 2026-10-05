package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.Closeable
import java.io.File

/**
 * Narrow Media3 compatibility fallback, used after the native render route is unavailable.
 * Only raw retimed video is materialized. Original clip metadata still owns effects/tracking and
 * original A-track sources still own sound. No project URI is replaced and all files are temporary.
 */
internal class CompatibilityRetimeSources private constructor(
    val videoInputs: Map<TimelineClip, String>,
    private val files: List<File>,
) : Closeable {
    override fun close() { files.forEach { it.delete() } }

    companion object {
        fun prepare(context: Context, project: TimelineProject, cancelled: () -> Boolean,
                    onProgress: (ExportProgress) -> Unit): CompatibilityRetimeSources {
            val files = mutableListOf<File>()
            val inputs = linkedMapOf<TimelineClip, String>()
            try {
                project.tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }
                    .flatMap { it.clips }.filter {
                        !it.isImageV21 && it.retime?.curve?.let { curve -> curve.smoothSlowMotion && curve.hasSlowMotion } == true
                    }.forEach { clip ->
                        val file = File.createTempFile("digitor-compat-retime-", ".mp4", context.cacheDir)
                        files += file
                        var encoder: CpuAvcEncoder? = null
                        var width = 0
                        var height = 0
                        var pixels = IntArray(0)
                        try {
                            onProgress(ExportProgress.Stage("Compatibility: preparing smooth video", 0f))
                            SmoothRetimeFrameProducer(context).produce(clip, project.frameRate,
                                minOf(maxOf(project.width, project.height), 4096), cancelled) { bitmap, time ->
                                if (encoder == null) {
                                    width = (bitmap.width + 1) / 2 * 2
                                    height = (bitmap.height + 1) / 2 * 2
                                    pixels = IntArray(width * height)
                                    encoder = CpuAvcEncoder(width, height, project.frameRate, file,
                                        bitrate = ExportQuality.HIGH.videoBitrate(width, height, project.frameRate))
                                }
                                val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else
                                    Bitmap.createScaledBitmap(bitmap, width, height, true)
                                try { scaled.getPixels(pixels, 0, width, 0, 0, width, height) }
                                finally { if (scaled !== bitmap) scaled.recycle() }
                                checkNotNull(encoder).encodeFrame(pixels, time)
                            }
                            checkNotNull(encoder) { "No frames in smooth compatibility source" }.finish()
                        } finally { encoder?.close() }
                        inputs[clip] = Uri.fromFile(file).toString()
                    }
                return CompatibilityRetimeSources(inputs, files)
            } catch (error: Throwable) {
                files.forEach { it.delete() }
                throw error
            }
        }
    }
}
