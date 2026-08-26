package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
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
 * Resolve-style playhead preview backed by MediaCodec + Media3 OpenGL.
 *
 * Normal preview is zero-readback: the final GPU-composited frame is rendered directly into the
 * viewer SurfaceView. There is no ImageReader, RGBA copy, Bitmap upload, or Compose texture upload
 * in the healthy path. This matches the export architecture much more closely and is especially
 * important on low-end devices where GPU->CPU readback can take longer than a frame interval.
 *
 * UI playhead requests are clock hints. During normal forward playback the prepared player runs
 * continuously; only real scrubs/jumps seek. A CPU bitmap renderer remains as device-safety fallback.
 */
@UnstableApi
class DavinciFramePreviewEngine(
    context: Context,
    private val maxPreviewLongEdge: Int = 720,
) : Closeable {

    data class Frame(
        val bitmap: Bitmap?,
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
    private val lastSubmitNs = AtomicLong(0L)

    private val playerThread = HandlerThread("DigitorGpuPreviewPlayer").apply { start() }
    private val playerHandler = Handler(playerThread.looper)

    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fallbackRequests = Channel<Request>(Channel.CONFLATED)
    private val softwareFallback = SoftwarePreviewTimelineCompositor(appContext)

    @Volatile
    private var gpuDisabled = false

    // Player-thread-only state.
    private var player: CompositionPlayer? = null
    private var previewSurface: Surface? = null
    private var loadedProject: TimelineProject? = null
    private var loadedOutputSize: Pair<Int, Int>? = null
    private var lastHandledTimelineUs = -1L
    private var lastHandledNs = 0L
    private var continuousPlayback = false

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

    private val pauseWhenCursorStops = object : Runnable {
        override fun run() {
            if (closed.get() || gpuDisabled) return
            val idleMs = (System.nanoTime() - lastSubmitNs.get()) / 1_000_000L
            if (idleMs >= PLAYBACK_IDLE_PAUSE_MS) {
                val activePlayer = player ?: return
                if (activePlayer.isPlaying) activePlayer.pause()
                continuousPlayback = false
                activePlayer.setScrubbingModeEnabled(true)
            } else {
                playerHandler.postDelayed(this, PLAYBACK_IDLE_PAUSE_MS - idleMs)
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
                        maxLongEdge = min(maxPreviewLongEdge, FALLBACK_LONG_EDGE),
                    )
                }
                // Channel.CONFLATED already prevents an unbounded queue. Publishing completed work
                // is better than discarding every frame when a slow device receives 30 cursor ticks/s.
                mutableFrame.value = Frame(
                    bitmap = rendered.bitmap,
                    timelineUs = request.timelineUs,
                    activeLayerCount = rendered.layerCount,
                    renderTimeMs = (System.nanoTime() - request.startedNs) / 1_000_000L,
                )
            }
        }
    }

    /** Called by the stable SurfaceView when its display surface becomes available. */
    fun attachSurface(surface: Surface) {
        if (closed.get()) return
        playerHandler.post {
            if (closed.get() || gpuDisabled) return@post
            if (previewSurface === surface) return@post
            previewSurface?.let { old -> runCatching { player?.clearVideoSurface(old) } }
            previewSurface = surface
            val activePlayer = player
            val size = loadedOutputSize
            if (activePlayer != null && size != null && surface.isValid) {
                activePlayer.setVideoSurface(surface, Size(size.first, size.second))
                latestRequest.get()?.let { request ->
                    if (!activePlayer.isPlaying) {
                        activePlayer.setScrubbingModeEnabled(true)
                        activePlayer.seekTo(request.timelineUs / 1000L)
                    }
                }
            }
        }
    }

    /** Called once when the SurfaceView surface is actually destroyed/replaced. */
    fun detachSurface(surface: Surface) {
        if (closed.get()) return
        playerHandler.post {
            if (previewSurface !== surface) return@post
            runCatching { player?.clearVideoSurface(surface) }
            previewSurface = null
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
        lastSubmitNs.set(System.nanoTime())

        if (gpuDisabled) {
            fallbackRequests.trySend(request)
            return
        }

        pendingGpuRequest.set(request)
        playerHandler.removeCallbacks(gpuDrain)
        playerHandler.post(gpuDrain)
        playerHandler.removeCallbacks(pauseWhenCursorStops)
        playerHandler.postDelayed(pauseWhenCursorStops, PLAYBACK_IDLE_PAUSE_MS)
    }

    private fun handleGpuRequest(request: Request) {
        val targetMs = request.timelineUs / 1000L
        val outputSize = resolvePreviewOutputSize(request.project, maxPreviewLongEdge)
        val projectChanged = loadedProject != request.project
        val outputChanged = loadedOutputSize != outputSize

        if (player == null || projectChanged || outputChanged) {
            configureGpuGraph(request, outputSize)
            lastHandledTimelineUs = request.timelineUs
            lastHandledNs = System.nanoTime()
            continuousPlayback = false
            return
        }

        val activePlayer = checkNotNull(player)
        val nowNs = System.nanoTime()
        val wallDeltaUs = if (lastHandledNs == 0L) Long.MAX_VALUE else (nowNs - lastHandledNs) / 1_000L
        val timelineDeltaUs = request.timelineUs - lastHandledTimelineUs

        val forwardClockLike = wallDeltaUs in 5_000L..1_500_000L &&
            timelineDeltaUs > 0L &&
            timelineDeltaUs <= 1_500_000L &&
            timelineDeltaUs >= wallDeltaUs / 4L &&
            timelineDeltaUs <= wallDeltaUs * 4L

        val keepContinuous = continuousPlayback &&
            timelineDeltaUs > 0L &&
            timelineDeltaUs <= 1_500_000L

        if (forwardClockLike || keepContinuous) {
            continuousPlayback = true
            activePlayer.setScrubbingModeEnabled(false)
            val playerUs = activePlayer.currentPosition.coerceAtLeast(0L) * 1000L
            if (abs(playerUs - request.timelineUs) > MAX_PLAYBACK_DRIFT_US) {
                activePlayer.seekTo(targetMs)
            }
            if (!activePlayer.isPlaying) activePlayer.play()
        } else {
            continuousPlayback = false
            activePlayer.pause()
            activePlayer.setScrubbingModeEnabled(true)
            activePlayer.seekTo(targetMs)
        }

        lastHandledTimelineUs = request.timelineUs
        lastHandledNs = nowNs
    }

    private fun configureGpuGraph(request: Request, outputSize: Pair<Int, Int>) {
        val (width, height) = outputSize
        val activePlayer = player ?: CompositionPlayer.Builder(appContext)
            .setLooper(playerThread.looper)
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
            .also { created ->
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        disableGpuAndFallback(error, latestRequest.get())
                    }
                })
                created.setVideoFrameMetadataListener(
                    VideoFrameMetadataListener { presentationTimeUs, _, _: Format, _ ->
                        val requestAtRender = latestRequest.get() ?: return@VideoFrameMetadataListener
                        mutableFrame.value = Frame(
                            bitmap = null,
                            timelineUs = presentationTimeUs,
                            activeLayerCount = activeVideoLayersAt(
                                requestAtRender.project,
                                presentationTimeUs,
                            ).size,
                            renderTimeMs = ((System.nanoTime() - requestAtRender.startedNs) / 1_000_000L)
                                .coerceAtLeast(0L),
                        )
                    },
                )
                player = created
            }

        activePlayer.pause()
        activePlayer.stop()
        previewSurface?.takeIf { it.isValid }?.let { surface ->
            activePlayer.setVideoSurface(surface, Size(width, height))
        }
        activePlayer.setScrubbingModeEnabled(true)
        activePlayer.setComposition(
            compositionBuilder.buildGpuPreview(request.project, maxPreviewLongEdge),
            request.timelineUs / 1000L,
        )
        activePlayer.prepare()
        loadedProject = request.project
        loadedOutputSize = outputSize
    }

    private fun disableGpuAndFallback(error: Throwable, request: Request?) {
        if (gpuDisabled) {
            request?.let { fallbackRequests.trySend(it) }
            return
        }
        gpuDisabled = true
        continuousPlayback = false
        runCatching { player?.pause() }
        previewSurface?.let { surface -> runCatching { player?.clearVideoSurface(surface) } }
        runCatching { player?.release() }
        player = null
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
            previewSurface?.let { surface -> runCatching { player?.clearVideoSurface(surface) } }
            previewSurface = null
            runCatching { player?.release() }
            player = null
            playerThread.quitSafely()
        }
    }

    private companion object {
        const val FALLBACK_LONG_EDGE = 480
        const val PLAYBACK_IDLE_PAUSE_MS = 220L
        const val MAX_PLAYBACK_DRIFT_US = 350_000L
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
