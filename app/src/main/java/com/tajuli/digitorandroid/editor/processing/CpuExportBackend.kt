package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** CPU fallback: no OpenGL. Decode -> multithread color/composite -> byte-buffer AVC encode. */
class CpuExportBackend(private val context: Context) : ExportBackend {
    override suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val problems = project.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        require(project.durationUs > 0) { "Timeline is empty" }
        require(project.tracks.any { it.kind == TrackKind.VIDEO && it.clips.isNotEmpty() }) {
            "CPU fallback currently requires at least one video track"
        }

        val compositor = CpuTimelineCompositor(context)
        val frameDurationUs = 1_000_000L / project.frameRate
        val frameCount = ((project.durationUs + frameDurationUs - 1) / frameDurationUs).toInt()
        try {
            CpuAvcEncoder(project.width, project.height, project.frameRate, output).use { encoder ->
                for (frameIndex in 0 until frameCount) {
                    val timeUs = frameIndex * frameDurationUs
                    val pixels = compositor.render(project, timeUs)
                    encoder.encodeFrame(pixels, timeUs)
                    if (frameIndex % 4 == 0 || frameIndex == frameCount - 1) {
                        onProgress(
                            ExportProgress.Stage(
                                "CPU: shared per-pixel color + AVC encode",
                                (frameIndex + 1f) / frameCount,
                            )
                        )
                    }
                }
                encoder.finish()
            }
        } finally {
            compositor.close()
        }
        ExportResult(
            output,
            Backend.CPU,
            "CPU fallback MP4 complete (video-only; CPU audio mixing is not implemented yet).",
        )
    }
}

private class CpuTimelineCompositor(private val context: Context) : AutoCloseable {
    private val workerCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
    private val color = CpuColorProcessor(workerCount)
    private val workers = Executors.newFixedThreadPool(workerCount)
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()

    fun render(project: TimelineProject, timeUs: Long): IntArray {
        val canvas = IntArray(project.width * project.height) { 0xFF000000.toInt() }
        val active = project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.muted }
            .flatMapIndexed { z, track ->
                track.clips.filter { timeUs in it.timelineStartUs until it.timelineEndUs }
                    .map { z to it }
            }
            .sortedByDescending { it.first }

        active.forEach { (_, clip) ->
            val localUs = clip.sourceInUs + (timeUs - clip.timelineStartUs)
            val bitmap = frameFor(clip, localUs) ?: return@forEach
            val scaled = if (bitmap.width == project.width && bitmap.height == project.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, project.width, project.height, true)
            val overlay = IntArray(project.width * project.height)
            scaled.getPixels(overlay, 0, project.width, 0, 0, project.width, project.height)
            color.processClipArgb8888(overlay, project.width, project.height, clip)
            blend(canvas, overlay, project.width, project.height, clip.opacity)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        return canvas
    }

    private fun frameFor(clip: TimelineClip, sourceTimeUs: Long): Bitmap? {
        val retriever = retrievers.getOrPut(clip.uri) {
            MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(clip.uri)) }
        }
        return retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    private fun blend(base: IntArray, top: IntArray, width: Int, height: Int, opacity: Float) {
        val stripe = (height / workerCount).coerceAtLeast(1)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = min(height, y + stripe)
            jobs += Callable {
                var i = startY * width
                val end = endY * width
                while (i < end) {
                    val s = top[i]
                    val sa = (((s ushr 24) and 0xFF) / 255f * opacity).coerceIn(0f, 1f)
                    if (sa > 0f) {
                        val d = base[i]
                        val sr = (s ushr 16) and 0xFF
                        val sg = (s ushr 8) and 0xFF
                        val sb = s and 0xFF
                        val dr = (d ushr 16) and 0xFF
                        val dg = (d ushr 8) and 0xFF
                        val db = d and 0xFF
                        val r = (sr * sa + dr * (1f - sa) + 0.5f).toInt().coerceIn(0, 255)
                        val g = (sg * sa + dg * (1f - sa) + 0.5f).toInt().coerceIn(0, 255)
                        val b = (sb * sa + db * (1f - sa) + 0.5f).toInt().coerceIn(0, 255)
                        base[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    i++
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
    }

    override fun close() {
        retrievers.values.forEach { it.release() }
        color.close()
        workers.shutdown()
    }
}
