package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.CpuColorProcessor
import com.tajuli.digitorandroid.editor.processing.CpuNodeEffectsProcessor
import com.tajuli.digitorandroid.editor.processing.CpuTransformProcessor
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolve-style preview architecture for the editor viewer.
 *
 * The timeline playhead is the source of truth. For each requested playhead position we evaluate
 * only the video clips that are active at that instant, decode one frame from each source, apply
 * the clip transform/color/node pipeline, then composite the layers bottom-to-top into one viewer
 * frame. The viewer never asks a long-lived multi-input CompositionPlayer to own the whole video
 * timeline.
 *
 * This first implementation intentionally uses the already-tested CPU image pipeline at a reduced
 * preview resolution. It is deterministic and device-safe. The renderer is isolated behind this
 * engine so the decode/composite stage can later be replaced by MediaCodec + OpenGL without
 * changing timeline or UI behavior.
 *
 * Requests are conflated: while the user scrubs, stale positions are discarded and only the newest
 * pending playhead position is rendered. That mirrors a professional editor's interactive viewer
 * more closely than queueing every intermediate seek.
 */
class DavinciFramePreviewEngine(
    context: Context,
    private val maxPreviewLongEdge: Int = 720,
) : Closeable {

    data class Frame(
        val bitmap: Bitmap,
        val timelineUs: Long,
        val activeLayerCount: Int,
        val renderTimeMs: Long,
    )

    private data class Request(
        val project: TimelineProject,
        val timelineUs: Long,
        val revision: Long,
    )

    private val appContext = context.applicationContext
    private val revision = AtomicLong(0L)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val compositor = PreviewTimelineCompositor(appContext)
    private val mutableFrame = MutableStateFlow<Frame?>(null)

    val frame: StateFlow<Frame?> = mutableFrame.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                val startedNs = System.nanoTime()
                val rendered = withContext(Dispatchers.Default) {
                    compositor.render(
                        project = request.project,
                        timeUs = request.timelineUs,
                        maxLongEdge = maxPreviewLongEdge,
                    )
                }
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                // A newer cursor/project request arrived while this frame was decoding. Do not flash
                // the stale frame; immediately continue with the newest conflated request.
                if (request.revision == revision.get()) {
                    mutableFrame.value = Frame(
                        bitmap = rendered.bitmap,
                        timelineUs = request.timelineUs,
                        activeLayerCount = rendered.layerCount,
                        renderTimeMs = elapsedMs,
                    )
                }
            }
        }
    }

    fun submit(project: TimelineProject, timelineUs: Long) {
        val safeTimeUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        val nextRevision = revision.incrementAndGet()
        requests.trySend(Request(project, safeTimeUs, nextRevision))
    }

    override fun close() {
        requests.close()
        scope.cancel()
        compositor.close()
    }
}

internal data class RenderedPreviewFrame(
    val bitmap: Bitmap,
    val layerCount: Int,
)

/** First project video track is the top track; rendering therefore runs in reverse track order. */
internal fun activeVideoLayersAt(project: TimelineProject, timeUs: Long): List<TimelineClip> =
    project.tracks
        .withIndex()
        .filter { (_, track) -> track.kind == TrackKind.VIDEO && !track.muted }
        .flatMap { (trackIndex, track) ->
            track.clips
                .filter { clip -> timeUs in clip.timelineStartUs until clip.timelineEndUs }
                .map { clip -> trackIndex to clip }
        }
        .sortedByDescending { (trackIndex, _) -> trackIndex }
        .map { (_, clip) -> clip }

/**
 * Software implementation of the viewer renderer. It deliberately shares the same CPU transform,
 * color and node-effect processors used by fallback export, so preview semantics stay consistent.
 */
private class PreviewTimelineCompositor(private val context: Context) : Closeable {
    private val workerCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
    private val color = CpuColorProcessor(workerCount)
    private val effects = CpuNodeEffectsProcessor(workerCount)
    private val workers = Executors.newFixedThreadPool(workerCount)
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()

    fun render(project: TimelineProject, timeUs: Long, maxLongEdge: Int): RenderedPreviewFrame {
        val (outputWidth, outputHeight) = previewDimensions(project, maxLongEdge)
        val canvas = IntArray(outputWidth * outputHeight) { 0xFF000000.toInt() }
        val active = activeVideoLayersAt(project, timeUs)

        active.forEach { clip ->
            val clipLocalUs = (timeUs - clip.timelineStartUs).coerceAtLeast(0L)
            val sourceUs = (clip.sourceInUs + clipLocalUs)
                .coerceIn(clip.sourceInUs.coerceAtLeast(0L), clip.sourceOutUs.coerceAtLeast(clip.sourceInUs))
            val bitmap = frameFor(clip, sourceUs, outputWidth, outputHeight) ?: return@forEach
            val transformed = CpuTransformProcessor.render(
                source = bitmap,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                clip = clip,
                clipLocalUs = clipLocalUs,
            )
            val overlay = IntArray(outputWidth * outputHeight)
            transformed.getPixels(overlay, 0, outputWidth, 0, 0, outputWidth, outputHeight)
            color.processClipArgb8888(overlay, outputWidth, outputHeight, clip, sourceUs)
            effects.processClipArgb8888(overlay, outputWidth, outputHeight, clip, sourceUs)
            blend(canvas, overlay, outputWidth, outputHeight, clip.opacity)

            if (transformed !== bitmap) transformed.recycle()
            bitmap.recycle()
        }

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        output.setPixels(canvas, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        return RenderedPreviewFrame(output, active.size)
    }

    private fun frameFor(
        clip: TimelineClip,
        sourceTimeUs: Long,
        previewWidth: Int,
        previewHeight: Int,
    ): Bitmap? {
        val retriever = retrievers.getOrPut(clip.uri) {
            MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(clip.uri)) }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                sourceTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                previewWidth,
                previewHeight,
            )
        } else {
            retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    }

    private fun previewDimensions(project: TimelineProject, maxLongEdge: Int): Pair<Int, Int> {
        val sourceWidth = project.width.coerceAtLeast(2)
        val sourceHeight = project.height.coerceAtLeast(2)
        val longest = max(sourceWidth, sourceHeight)
        if (longest <= maxLongEdge.coerceAtLeast(2)) return sourceWidth to sourceHeight
        val scale = maxLongEdge.toFloat() / longest.toFloat()
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    private fun blend(base: IntArray, top: IntArray, width: Int, height: Int, opacity: Float) {
        val stripe = (height / workerCount).coerceAtLeast(1)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = min(height, y + stripe)
            jobs += Callable {
                var index = startY * width
                val end = endY * width
                while (index < end) {
                    val source = top[index]
                    val sourceAlpha = ((((source ushr 24) and 0xFF) / 255f) * opacity)
                        .coerceIn(0f, 1f)
                    if (sourceAlpha > 0f) {
                        val destination = base[index]
                        val sr = (source ushr 16) and 0xFF
                        val sg = (source ushr 8) and 0xFF
                        val sb = source and 0xFF
                        val dr = (destination ushr 16) and 0xFF
                        val dg = (destination ushr 8) and 0xFF
                        val db = destination and 0xFF
                        val r = (sr * sourceAlpha + dr * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        val g = (sg * sourceAlpha + dg * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        val b = (sb * sourceAlpha + db * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        base[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    index++
                }
            }
            y = endY
        }
        workers.invokeAll(jobs).forEach { it.get() }
    }

    override fun close() {
        retrievers.values.forEach { retriever -> runCatching { retriever.release() } }
        retrievers.clear()
        color.close()
        effects.close()
        workers.shutdownNow()
    }
}
