package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.processing.CpuColorProcessor
import com.tajuli.digitorandroid.editor.processing.CpuNodeEffectsProcessor
import com.tajuli.digitorandroid.editor.processing.CpuTransformProcessor
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import com.tajuli.digitorandroid.editor.render.resolvePreviewOutputSize
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
 * Resolve-style playhead preview backed by the same Media3 GPU topology used by export.
 *
 * MediaCodec decodes the active video streams, Media3 GL effects process color/node state, and the
 * Resolve compositor combines tracks at a reduced preview resolution. The final RGBA surface is
 * read back through ImageReader only so the existing Compose bitmap viewer can remain unchanged.
 * No CPU transform/color/effect/compositing work is performed on the normal path.
 *
 * While normal playback advances, nearby requests keep the GPU player running continuously instead
 * of random-seeking every 33 ms. Scrubs/large jumps seek the prepared composition. Project edits
 * replace the composition at the current playhead. If the device rejects the experimental Media3
 * preview graph, the previous software renderer remains available as a safety fallback.
 */
@UnstableApi
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
        val startedNs: Long = System.nanoTime(),
    )

    private val appContext = context.applicationContext
    private val compositionBuilder = Media3CompositionBuilder()
    private val revision = AtomicLong(0L)
    private val latestRequest = AtomicReference<Request?>(null)
    private val pendingGpuRequest = AtomicReference<Request?>(null)
    private val mutableFrame = MutableStateFlow<Frame?>(null)
    private val closed = AtomicBoolean(false)

    private val playerThread = HandlerThread("DigitorGpuPreviewPlayer").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val readbackThread = HandlerThread("DigitorGpuPreviewReadback").apply { start() }
    private val readbackHandler = Handler(readbackThread.looper)

    // Software path is cold unless Media3 reports that this device cannot run the GPU graph.
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fallbackRequests = Channel<Request>(Channel.CONFLATED)
    private val softwareFallback = SoftwarePreviewTimelineCompositor(appContext)

    @Volatile
    private var gpuDisabled = false

    // Player-thread-only state.
    private var player: CompositionPlayer? = null
    private var imageReader: ImageReader? = null
    private var loadedProject: TimelineProject? = null
    private var loadedOutputSize: Pair<Int, Int>? = null
    private var lastHandledTimelineUs = -1L
    private var lastHandledNs = 0L

    val frame: StateFlow<Frame?> = mutableFrame.asStateFlow()

    private val gpuDrain = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val request = pendingGpuRequest.getAndSet(null) ?: return
            if (gpuDisabled) {
                fallbackRequests.trySend(request)
            } else {
                runCatching { handleGpuRequest(request) }
                    .onFailure { disableGpuAndFallback(it, request) }
            }
            if (pendingGpuRequest.get() != null && !closed.get()) {
                playerHandler.post(this)
            }
        }
    }

    init {
        fallbackScope.launch {
            for (request in fallbackRequests) {
                val rendered = withContext(Dispatchers.Default) {
                    softwareFallback.render(
                        project = request.project,
                        timeUs = request.timelineUs,
                        maxLongEdge = maxPreviewLongEdge,
                    )
                }
                if (request.revision == revision.get()) {
                    mutableFrame.value = Frame(
                        bitmap = rendered.bitmap,
                        timelineUs = request.timelineUs,
                        activeLayerCount = rendered.layerCount,
                        renderTimeMs = (System.nanoTime() - request.startedNs) / 1_000_000L,
                    )
                }
            }
        }
    }

    fun submit(project: TimelineProject, timelineUs: Long) {
        if (closed.get()) return
        val safeTimeUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        val request = Request(
            project = project,
            timelineUs = safeTimeUs,
            revision = revision.incrementAndGet(),
        )
        latestRequest.set(request)

        if (gpuDisabled) {
            fallbackRequests.trySend(request)
            return
        }

        // Conflate cursor traffic before it reaches the CompositionPlayer application thread.
        pendingGpuRequest.set(request)
        playerHandler.removeCallbacks(gpuDrain)
        playerHandler.post(gpuDrain)
    }

    private fun handleGpuRequest(request: Request) {
        val targetMs = request.timelineUs / 1000L
        val projectChanged = loadedProject !== request.project
        val outputSize = resolvePreviewOutputSize(request.project, maxPreviewLongEdge)
        val outputChanged = loadedOutputSize != outputSize

        if (player == null || projectChanged || outputChanged) {
            configureGpuGraph(request, outputSize)
            lastHandledTimelineUs = request.timelineUs
            lastHandledNs = System.nanoTime()
            return
        }

        val activePlayer = checkNotNull(player)
        val nowNs = System.nanoTime()
        val timelineDeltaUs = request.timelineUs - lastHandledTimelineUs
        val wallDeltaMs = if (lastHandledNs == 0L) Long.MAX_VALUE else (nowNs - lastHandledNs) / 1_000_000L
        val sequentialPlayback =
            timelineDeltaUs in 5_000L..160_000L && wallDeltaMs in 5L..250L

        if (sequentialPlayback) {
            // Let MediaCodec decode forward continuously. Only correct drift when the editor/audio
            // clock and the video player diverge enough to become visible.
            activePlayer.setScrubbingModeEnabled(false)
            val playerUs = activePlayer.currentPosition.coerceAtLeast(0L) * 1000L
            if (abs(playerUs - request.timelineUs) > 180_000L) {
                activePlayer.seekTo(targetMs)
            }
            if (!activePlayer.isPlaying) activePlayer.play()
        } else {
            // Scrub/edit/large jump: pause and ask the prepared graph for the requested frame.
            activePlayer.pause()
            activePlayer.setScrubbingModeEnabled(true)
            activePlayer.seekTo(targetMs)
            activePlayer.experimentalRedrawLastFrame()
        }

        lastHandledTimelineUs = request.timelineUs
        lastHandledNs = nowNs
    }

    private fun configureGpuGraph(request: Request, outputSize: Pair<Int, Int>) {
        val (width, height) = outputSize
        if (imageReader == null || loadedOutputSize != outputSize) {
            imageReader?.close()
            imageReader = createReadbackSurface(width, height)
            loadedOutputSize = outputSize
        }

        val activePlayer = player ?: CompositionPlayer.Builder(appContext)
            .setLooper(playerThread.looper)
            .build()
            .also { created ->
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        disableGpuAndFallback(error, latestRequest.get())
                    }
                })
                player = created
            }

        activePlayer.pause()
        activePlayer.stop()
        activePlayer.setVideoSurface(
            checkNotNull(imageReader).surface,
            Size(width, height),
        )
        activePlayer.setScrubbingModeEnabled(true)
        activePlayer.setComposition(
            compositionBuilder.buildGpuPreview(request.project, maxPreviewLongEdge),
            request.timelineUs / 1000L,
        )
        activePlayer.prepare()
        activePlayer.experimentalRedrawLastFrame()
        loadedProject = request.project
    }

    private fun createReadbackSurface(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val pool = BitmapReadbackPool(width, height)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val request = latestRequest.get() ?: return@setOnImageAvailableListener
                val bitmap = pool.copyFrom(image)
                // Do not publish a frame for a request superseded while GL/readback was in flight.
                if (request.revision == revision.get()) {
                    mutableFrame.value = Frame(
                        bitmap = bitmap,
                        timelineUs = request.timelineUs,
                        activeLayerCount = activeVideoLayersAt(request.project, request.timelineUs).size,
                        renderTimeMs = (System.nanoTime() - request.startedNs) / 1_000_000L,
                    )
                }
            } catch (error: Throwable) {
                playerHandler.post { disableGpuAndFallback(error, latestRequest.get()) }
            } finally {
                image.close()
            }
        }, readbackHandler)
        return reader
    }

    private fun disableGpuAndFallback(error: Throwable, request: Request?) {
        if (gpuDisabled) {
            request?.let { fallbackRequests.trySend(it) }
            return
        }
        gpuDisabled = true
        runCatching { player?.pause() }
        runCatching { player?.release() }
        player = null
        runCatching { imageReader?.close() }
        imageReader = null
        loadedProject = null
        loadedOutputSize = null
        request?.let { fallbackRequests.trySend(it) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingGpuRequest.set(null)
        latestRequest.set(null)
        fallbackRequests.close()
        fallbackScope.cancel()
        softwareFallback.close()

        playerHandler.removeCallbacksAndMessages(null)
        playerHandler.post {
            runCatching { player?.release() }
            player = null
            runCatching { imageReader?.close() }
            imageReader = null
            playerThread.quitSafely()
        }
        readbackThread.quitSafely()
    }
}

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

private data class RenderedPreviewFrame(
    val bitmap: Bitmap,
    val layerCount: Int,
)

/** Small rotating bitmap pool: GPU does the processing; this is only the final surface readback. */
private class BitmapReadbackPool(
    private val width: Int,
    private val height: Int,
) {
    private val outputs = Array(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private var staging: Array<Bitmap>? = null
    private var index = 0

    fun copyFrom(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("GPU preview image has no RGBA plane")
        require(plane.pixelStride == 4) {
            "Unsupported GPU readback pixel stride ${plane.pixelStride}"
        }
        val output = outputs[index]
        val slot = index
        index = (index + 1) % outputs.size

        val buffer = plane.buffer
        buffer.rewind()
        val tightRowBytes = width * 4
        if (plane.rowStride == tightRowBytes) {
            output.copyPixelsFromBuffer(buffer)
            return output
        }

        val paddedWidth = plane.rowStride / plane.pixelStride
        require(paddedWidth >= width) { "Invalid GPU readback row stride ${plane.rowStride}" }
        var padded = staging
        if (padded == null || padded[0].width != paddedWidth) {
            padded = Array(3) { Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888) }
            staging = padded
        }
        val stagingBitmap = padded[slot]
        stagingBitmap.copyPixelsFromBuffer(buffer)
        Canvas(output).drawBitmap(stagingBitmap, 0f, 0f, null)
        return output
    }
}

/**
 * Device-safety fallback copied from the previously tested viewer. It is intentionally never used
 * when the Media3 GPU preview graph is healthy.
 */
private class SoftwarePreviewTimelineCompositor(private val context: Context) : Closeable {
    private val workerCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
    private val color = CpuColorProcessor(workerCount)
    private val effects = CpuNodeEffectsProcessor(workerCount)
    private val workers = Executors.newFixedThreadPool(workerCount)
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()

    fun render(project: TimelineProject, timeUs: Long, maxLongEdge: Int): RenderedPreviewFrame {
        val (outputWidth, outputHeight) = resolvePreviewOutputSize(project, maxLongEdge)
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

    private fun blend(base: IntArray, top: IntArray, width: Int, height: Int, opacity: Float) {
        val stripe = (height / workerCount).coerceAtLeast(1)
        val jobs = mutableListOf<Callable<Unit>>()
        var y = 0
        while (y < height) {
            val startY = y
            val endY = min(height, y + stripe)
            jobs += Callable {
                var pixelIndex = startY * width
                val end = endY * width
                while (pixelIndex < end) {
                    val source = top[pixelIndex]
                    val sourceAlpha = ((((source ushr 24) and 0xFF) / 255f) * opacity)
                        .coerceIn(0f, 1f)
                    if (sourceAlpha > 0f) {
                        val destination = base[pixelIndex]
                        val sr = (source ushr 16) and 0xFF
                        val sg = (source ushr 8) and 0xFF
                        val sb = source and 0xFF
                        val dr = (destination ushr 16) and 0xFF
                        val dg = (destination ushr 8) and 0xFF
                        val db = destination and 0xFF
                        val r = (sr * sourceAlpha + dr * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        val g = (sg * sourceAlpha + dg * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        val b = (sb * sourceAlpha + db * (1f - sourceAlpha) + .5f).toInt().coerceIn(0, 255)
                        base[pixelIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    pixelIndex++
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
