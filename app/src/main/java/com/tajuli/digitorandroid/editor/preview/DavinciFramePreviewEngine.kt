package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.TransitionPairV22
import com.tajuli.digitorandroid.editor.model.transitionPairsV22
import com.tajuli.digitorandroid.editor.render.DigitorRenderCore
import com.tajuli.digitorandroid.editor.render.transitionGhostIdV22
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Resolve-style playhead preview with custom Android transport and one shared GPU render core.
 *
 * MediaExtractor + MediaCodec own decode/play/scrub. Decoded frames go directly to
 * [DigitorRenderCore] input Surfaces, then through the same color/spatial/compositor code used by
 * export and straight into the viewer SurfaceView. CompositionPlayer is intentionally not involved
 * in video preview scheduling anymore.
 *
 * There is no ImageReader, Bitmap readback, Compose texture upload or per-tick player seek in the
 * healthy path. [maxPreviewLongEdge] is retained for source compatibility with older callers but is
 * intentionally ignored: source-pixel processing stays exact and the render core owns final preview
 * downsampling without changing grading/effect math.
 */
@UnstableApi
class DavinciFramePreviewEngine(
    context: Context,
    @Suppress("UNUSED_PARAMETER") maxPreviewLongEdge: Int = 720,
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
        val isPlaying: Boolean,
        val revision: Long,
        val startedNs: Long = System.nanoTime(),
    )

    internal data class ActiveLayer(
        val track: TimelineTrack,
        val clip: TimelineClip,
    )

    internal data class SessionKey(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val layers: List<LayerKey>,
    )

    internal data class LayerKey(
        val trackId: String,
        val clipId: String,
        val uri: String,
        val timelineStartUs: Long,
        val sourceInUs: Long,
        val sourceOutUs: Long,
        val staticSpatialHash: Int,
    )

    private data class PreparedLayer(
        val layer: ActiveLayer,
        val extractor: MediaExtractor,
        val platformFormat: MediaFormat,
        val media3Format: Format,
        val mime: String,
    )

    private data class HeldOutput(
        val index: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private data class AroundTarget(
        val atOrBefore: HeldOutput?,
        val after: HeldOutput?,
    )

    private data class PendingPausedOutput(
        val source: DecoderSource,
        val output: HeldOutput,
    )

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val exportSuspended = AtomicBoolean(false)
    private val revision = AtomicLong(0L)
    private val pendingRequest = AtomicReference<Request?>(null)
    private val mutableFrame = MutableStateFlow<Frame?>(null)
    private val resumeProject = AtomicReference<TimelineProject?>(null)
    private val resumeTimelineUs = AtomicLong(0L)

    private val legacyLastSubmitNs = AtomicLong(0L)
    private val legacyLastTimelineUs = AtomicLong(Long.MIN_VALUE)
    private val legacyPlaying = AtomicBoolean(false)
    private val legacyProject = AtomicReference<TimelineProject?>(null)

    private val renderThread = HandlerThread("DigitorMediaCodecPreview").apply { start() }
    private val handler = Handler(renderThread.looper)
    private val renderExecutor = Executor { runnable -> handler.post(runnable) }

    private var previewSurface: Surface? = null
    private var session: PreviewSession? = null
    private var sessionGeneration = 0L
    private var playing = false
    private var playAnchorTimelineUs = 0L
    private var playAnchorNs = 0L
    private var lastRequestedTimelineUs = Long.MIN_VALUE
    private var lastProjectRef: TimelineProject? = null
    private var latestRequestStartedNs = 0L

    val frame: StateFlow<Frame?> = mutableFrame.asStateFlow()

    init {
        PreviewExportCoordinator.register(this)
    }

    private val requestDrain = object : Runnable {
        override fun run() {
            if (closed.get() || exportSuspended.get()) return
            val request = pendingRequest.getAndSet(null) ?: return
            runCatching { handleRequest(request) }
                .onFailure { failPreview("request", it) }
            if (pendingRequest.get() != null && !closed.get() && !exportSuspended.get()) {
                handler.post(this)
            }
        }
    }

    private val playbackPump = object : Runnable {
        override fun run() {
            if (closed.get() || exportSuspended.get() || !playing) return
            val active = session ?: return
            runCatching {
                active.pumpPlayback(currentPlaybackTimelineUs())
            }.onFailure {
                failPreview("playback", it)
                return
            }
            if (playing && !closed.get() && !exportSuspended.get()) {
                handler.postDelayed(this, PLAYBACK_PUMP_MS)
            }
        }
    }

    private val legacyPauseWatchdog = object : Runnable {
        override fun run() {
            if (closed.get() || exportSuspended.get() || !legacyPlaying.get()) return
            val idleMs = (System.nanoTime() - legacyLastSubmitNs.get()) / 1_000_000L
            if (idleMs < LEGACY_IDLE_PAUSE_MS) {
                handler.postDelayed(this, LEGACY_IDLE_PAUSE_MS - idleMs)
                return
            }
            if (!legacyPlaying.compareAndSet(true, false)) return
            val project = legacyProject.get() ?: return
            val timelineUs = legacyLastTimelineUs.get().coerceAtLeast(0L)
            submit(project, timelineUs, false)
        }
    }

    fun attachSurface(surface: Surface) {
        if (closed.get()) return
        handler.post {
            if (closed.get()) return@post
            if (previewSurface === surface) {
                session?.retryPausedSubmission()
                return@post
            }
            previewSurface = surface.takeIf { it.isValid }
            session?.core?.setOutputSurface(previewSurface)
            session?.retryPausedSubmission()
        }
    }

    fun detachSurface(surface: Surface) {
        if (closed.get()) return
        handler.post {
            if (previewSurface !== surface) return@post
            previewSurface = null
            session?.core?.setOutputSurface(null)
        }
    }

    /**
     * Synchronously gives MediaCodec/GL resources back to the device before another heavy GPU job
     * starts. Export must not start until the release action has actually finished.
     */
    internal fun suspendForExternalGpuWork(): Boolean {
        if (closed.get()) return false
        if (!exportSuspended.compareAndSet(false, true)) return true

        pendingRequest.set(null)
        legacyPlaying.set(false)
        handler.removeCallbacks(requestDrain)
        handler.removeCallbacks(playbackPump)
        handler.removeCallbacks(legacyPauseWatchdog)

        val latch = CountDownLatch(1)
        val releaseAction = Runnable {
            try {
                lastProjectRef?.let(resumeProject::set)
                if (lastRequestedTimelineUs != Long.MIN_VALUE) {
                    resumeTimelineUs.set(lastRequestedTimelineUs.coerceAtLeast(0L))
                }
                stopPlayback()
                replaceSession(null)
                lastProjectRef = null
                lastRequestedTimelineUs = Long.MIN_VALUE
            } finally {
                latch.countDown()
            }
        }

        return if (Looper.myLooper() == renderThread.looper) {
            releaseAction.run()
            true
        } else {
            handler.post(releaseAction)
            runCatching {
                latch.await(EXPORT_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        }
    }

    internal fun resumeAfterExternalGpuWork() {
        if (closed.get() || !exportSuspended.compareAndSet(true, false)) return
        val project = resumeProject.get() ?: legacyProject.get() ?: return
        val safeTimelineUs = resumeTimelineUs.get()
            .coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        submit(project, safeTimelineUs, false)
    }

    fun submit(project: TimelineProject, timelineUs: Long) {
        if (closed.get()) return
        val safeTimelineUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        val nowNs = System.nanoTime()
        val previousNs = legacyLastSubmitNs.getAndSet(nowNs)
        val previousTimelineUs = legacyLastTimelineUs.getAndSet(safeTimelineUs)
        legacyProject.set(project)
        resumeProject.set(project)
        resumeTimelineUs.set(safeTimelineUs)

        if (previousNs != 0L && previousTimelineUs != Long.MIN_VALUE) {
            val wallDeltaUs = (nowNs - previousNs) / 1_000L
            val timelineDeltaUs = safeTimelineUs - previousTimelineUs
            val forwardRealtime = wallDeltaUs in 5_000L..750_000L &&
                timelineDeltaUs > 0L &&
                timelineDeltaUs <= 750_000L &&
                timelineDeltaUs >= wallDeltaUs / 4L &&
                timelineDeltaUs <= wallDeltaUs * 4L
            when {
                forwardRealtime -> legacyPlaying.set(true)
                timelineDeltaUs < 0L || abs(timelineDeltaUs) > 1_500_000L -> legacyPlaying.set(false)
            }
        }

        submit(project, safeTimelineUs, legacyPlaying.get())
        handler.removeCallbacks(legacyPauseWatchdog)
        if (legacyPlaying.get() && !exportSuspended.get()) {
            handler.postDelayed(legacyPauseWatchdog, LEGACY_IDLE_PAUSE_MS)
        }
    }

    fun submit(project: TimelineProject, timelineUs: Long, isPlaying: Boolean) {
        if (closed.get()) return
        val safeTimelineUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        PreviewProjectRegistry.update(project)
        resumeProject.set(project)
        resumeTimelineUs.set(safeTimelineUs)
        if (exportSuspended.get()) return

        val request = Request(
            project = project,
            timelineUs = safeTimelineUs,
            isPlaying = isPlaying,
            revision = revision.incrementAndGet(),
        )
        pendingRequest.set(request)
        handler.removeCallbacks(requestDrain)
        handler.post(requestDrain)
    }

    private fun handleRequest(request: Request) {
        if (exportSuspended.get()) return
        latestRequestStartedNs = request.startedNs
        val layers = activeLayerSpecsAt(request.project, request.timelineUs)
        if (layers.isEmpty()) {
            stopPlayback()
            replaceSession(null)
            lastRequestedTimelineUs = request.timelineUs
            lastProjectRef = request.project
            return
        }

        val wantedKey = sessionKey(request.project, layers)
        val sessionChanged = session?.key != wantedKey
        if (sessionChanged) {
            replaceSession(buildSession(request.project, layers, wantedKey))
        }
        val active = session ?: return

        val timelineChanged = request.timelineUs != lastRequestedTimelineUs
        val projectChanged = lastProjectRef !== request.project

        if (request.isPlaying) {
            val wasPlaying = playing
            val transportDriftUs = if (wasPlaying) {
                abs(currentPlaybackTimelineUs() - request.timelineUs)
            } else {
                Long.MAX_VALUE
            }

            playAnchorTimelineUs = request.timelineUs
            playAnchorNs = System.nanoTime()

            if (!wasPlaying || sessionChanged || transportDriftUs > HARD_RESYNC_US) {
                active.resetForPlayback(request.timelineUs)
            }
            playing = true
            handler.removeCallbacks(playbackPump)
            handler.post(playbackPump)
        } else {
            val wasPlaying = playing
            stopPlayback()
            when {
                sessionChanged || wasPlaying || timelineChanged -> active.seekAndRender(request.timelineUs)
                // MultipleInputVideoGraph.redraw() re-presents its already-processed output frame;
                // it does not run a changed LUT/spatial shader over the held decoder texture again.
                // Re-submit the same playhead frame so paused slider/effect changes are visible now.
                projectChanged -> active.seekAndRender(request.timelineUs)
            }
        }

        lastRequestedTimelineUs = request.timelineUs
        lastProjectRef = request.project
    }

    private fun currentPlaybackTimelineUs(): Long {
        if (playAnchorNs == 0L) return playAnchorTimelineUs
        return playAnchorTimelineUs + (System.nanoTime() - playAnchorNs) / 1_000L
    }

    private fun stopPlayback() {
        playing = false
        handler.removeCallbacks(playbackPump)
    }

    private fun buildSession(
        project: TimelineProject,
        layers: List<ActiveLayer>,
        key: SessionKey,
    ): PreviewSession {
        val prepared = mutableListOf<PreparedLayer>()
        var core: DigitorRenderCore? = null
        try {
            layers.forEach { layer -> prepared += prepareLayer(layer) }
            val generation = ++sessionGeneration
            core = DigitorRenderCore(
                context = appContext,
                project = project,
                layers = prepared.map { item ->
                    DigitorRenderCore.Layer(
                        track = item.layer.track,
                        clip = item.layer.clip,
                        format = item.media3Format,
                    )
                },
                listenerExecutor = renderExecutor,
                listener = object : DigitorRenderCore.Listener {
                    override fun onFrameRendered(timelineUs: Long) {
                        if (session?.generation != generation) return
                        mutableFrame.value = Frame(
                            bitmap = null,
                            timelineUs = timelineUs,
                            activeLayerCount = session?.sources?.size ?: layers.size,
                            renderTimeMs = ((System.nanoTime() - latestRequestStartedNs) / 1_000_000L)
                                .coerceAtLeast(0L),
                        )
                    }

                    override fun onError(error: Throwable) {
                        if (session?.generation == generation) failPreview("render core", error)
                    }
                },
            )
            previewSurface?.takeIf { it.isValid }?.let(core::setOutputSurface)

            val sources = prepared.mapIndexed { index, item ->
                val codec = createConfiguredPreviewDecoder(
                    mime = item.mime,
                    format = item.platformFormat,
                    outputSurface = core.inputSurface(index),
                )
                DecoderSource(
                    inputIndex = index,
                    clip = item.layer.clip,
                    extractor = item.extractor,
                    codec = codec,
                )
            }
            return PreviewSession(
                generation = generation,
                key = key,
                core = core,
                sources = sources,
            )
        } catch (error: Throwable) {
            prepared.forEach { item -> runCatching { item.extractor.release() } }
            runCatching { core?.close() }
            throw error
        }
    }

    /**
     * Device policy: always try the platform-default decoder first. On Android devices that normally
     * resolves to the vendor hardware decoder, giving the fastest/lowest-power preview. We do not
     * blacklist a chipset or camera profile up front. If codec creation/configuration/start actually
     * fails, retry the same stream with Media3's software-priority decoder list. Runtime stalls are
     * still covered by GpuPreviewSurface's first-frame fallback, so healthy devices stay on hardware.
     */
    private fun createConfiguredPreviewDecoder(
        mime: String,
        format: MediaFormat,
        outputSurface: Surface,
    ): MediaCodec {
        var primary: MediaCodec? = null
        var primaryName: String? = null
        try {
            primary = MediaCodec.createDecoderByType(mime)
            primaryName = runCatching { primary.name }.getOrNull()
            primary.configure(format, outputSurface, null, 0)
            primary.start()
            Log.i(TAG, "Preview decoder primary: ${primaryName ?: mime}")
            return primary
        } catch (primaryError: Throwable) {
            runCatching { primary?.stop() }
            runCatching { primary?.release() }

            val softwareName = runCatching {
                MediaCodecSelector.PREFER_SOFTWARE
                    .getDecoderInfos(mime, false, false)
                    .map { it.name }
                    .firstOrNull { candidate ->
                        primaryName == null || !candidate.equals(primaryName, ignoreCase = true)
                    }
            }.getOrNull()

            if (softwareName.isNullOrBlank()) throw primaryError

            val fallback = MediaCodec.createByCodecName(softwareName)
            try {
                fallback.configure(format, outputSurface, null, 0)
                fallback.start()
                Log.w(
                    TAG,
                    "Preview decoder fallback: ${primaryName ?: mime} -> $softwareName",
                    primaryError,
                )
                return fallback
            } catch (fallbackError: Throwable) {
                runCatching { fallback.stop() }
                runCatching { fallback.release() }
                fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private fun prepareLayer(layer: ActiveLayer): PreparedLayer {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, Uri.parse(layer.clip.uri), null)
            var videoTrack = -1
            var platformFormat: MediaFormat? = null
            var mime: String? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME)
                if (candidateMime?.startsWith("video/") == true) {
                    videoTrack = index
                    platformFormat = candidate
                    mime = candidateMime
                    break
                }
            }
            require(videoTrack >= 0 && platformFormat != null && mime != null) {
                "No video track in ${layer.clip.label}"
            }
            extractor.selectTrack(videoTrack)

            val width = platformFormat.intValue(MediaFormat.KEY_WIDTH, 1).coerceAtLeast(1)
            val height = platformFormat.intValue(MediaFormat.KEY_HEIGHT, 1).coerceAtLeast(1)
            val rotation = platformFormat.intValue(MediaFormat.KEY_ROTATION, 0)
            val frameRate = platformFormat.numberValue(MediaFormat.KEY_FRAME_RATE)?.toFloat()

            val formatBuilder = Format.Builder()
                .setSampleMimeType(mime)
                .setWidth(width)
                .setHeight(height)
                .setPixelWidthHeightRatio(1f)
                .setRotationDegrees(rotation)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            if (frameRate != null && frameRate > 0f) formatBuilder.setFrameRate(frameRate)

            return PreparedLayer(
                layer = layer,
                extractor = extractor,
                platformFormat = platformFormat,
                media3Format = formatBuilder.build(),
                mime = mime,
            )
        } catch (error: Throwable) {
            runCatching { extractor.release() }
            throw error
        }
    }

    private fun replaceSession(next: PreviewSession?) {
        val previous = session
        session = next
        runCatching { previous?.close() }
    }

    private fun failPreview(stage: String, error: Throwable) {
        Log.e(TAG, "Shared preview $stage failed", error)
        stopPlayback()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PreviewExportCoordinator.unregister(this)
        pendingRequest.set(null)
        PreviewProjectRegistry.clear()
        handler.removeCallbacksAndMessages(null)
        handler.post {
            stopPlayback()
            replaceSession(null)
            previewSurface = null
            renderThread.quitSafely()
        }
    }

    private inner class PreviewSession(
        val generation: Long,
        val key: SessionKey,
        val core: DigitorRenderCore,
        val sources: List<DecoderSource>,
    ) : Closeable {

        private val pendingPausedOutputs = mutableListOf<PendingPausedOutput>()
        private var pausedRetryPosted = false

        private val pausedRetry = Runnable {
            pausedRetryPosted = false
            if (session !== this || closed.get() || exportSuspended.get() || playing) return@Runnable
            runCatching { drainPausedOutputs() }
                .onFailure { failPreview("paused frame submit", it) }
        }

        fun resetForPlayback(timelineUs: Long) {
            clearPausedOutputs()
            core.flush()
            sources.forEach { source -> source.resetToTimeline(timelineUs) }
        }

        fun pumpPlayback(timelineUs: Long) {
            sources.forEach { source -> source.feedInput(MAX_INPUT_PER_PUMP) }
            sources.forEach { source -> source.drainPlayback(timelineUs, core) }
        }

        fun seekAndRender(timelineUs: Long) {
            clearPausedOutputs()
            core.flush()
            sources.forEach { source -> source.resetToTimeline(timelineUs) }

            sources.forEachIndexed { index, source ->
                val around = source.decodeAroundTarget()
                val before = around.atOrBefore
                val after = around.after
                when {
                    before != null -> {
                        pendingPausedOutputs += PendingPausedOutput(source, before)
                        if (index != 0 && after != null) {
                            pendingPausedOutputs += PendingPausedOutput(source, after)
                        } else if (after != null) {
                            source.releaseWithoutRendering(after)
                        }
                    }
                    after != null -> pendingPausedOutputs += PendingPausedOutput(source, after)
                }
            }
            drainPausedOutputs()
        }

        fun retryPausedSubmission() {
            if (pendingPausedOutputs.isEmpty() || playing || pausedRetryPosted) return
            pausedRetryPosted = true
            handler.post(pausedRetry)
        }

        private fun drainPausedOutputs() {
            if (pendingPausedOutputs.isEmpty()) return

            // A paused target frame must not be consumed before the viewer Surface is attached.
            // Otherwise the graph can process it successfully into a null output and there is no
            // playback pump to submit another frame, leaving "Preparing GPU preview" forever.
            if (previewSurface?.isValid != true) {
                schedulePausedRetry()
                return
            }

            val iterator = pendingPausedOutputs.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.source.tryReleaseToGraph(pending.output, core)) {
                    iterator.remove()
                }
            }

            if (pendingPausedOutputs.isNotEmpty()) schedulePausedRetry()
        }

        private fun schedulePausedRetry() {
            if (pausedRetryPosted) return
            pausedRetryPosted = true
            handler.postDelayed(pausedRetry, PAUSED_FRAME_RETRY_MS)
        }

        private fun clearPausedOutputs() {
            handler.removeCallbacks(pausedRetry)
            pausedRetryPosted = false
            pendingPausedOutputs.forEach { pending ->
                pending.source.releaseWithoutRendering(pending.output)
            }
            pendingPausedOutputs.clear()
        }

        override fun close() {
            clearPausedOutputs()
            sources.forEach { source -> runCatching { source.close() } }
            runCatching { core.close() }
        }
    }

    private class DecoderSource(
        val inputIndex: Int,
        val clip: TimelineClip,
        val extractor: MediaExtractor,
        val codec: MediaCodec,
    ) : Closeable {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var inputEos = false
        private var outputEos = false
        private var playbackFloorSourceUs = clip.sourceInUs
        private var heldPlaybackOutput: HeldOutput? = null

        fun resetToTimeline(timelineUs: Long) {
            heldPlaybackOutput?.let(::releaseWithoutRendering)
            heldPlaybackOutput = null
            codec.flush()
            val targetSourceUs = timelineToSourceUs(clip, timelineUs)
            extractor.seekTo(targetSourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputEos = false
            outputEos = false
            playbackFloorSourceUs = targetSourceUs
        }

        fun feedInput(limit: Int): Boolean {
            if (inputEos) return false
            var didWork = false
            for (attempt in 0 until limit) {
                if (inputEos) break
                val inputIndex = codec.dequeueInputBuffer(0L)
                if (inputIndex < 0) break
                val input = codec.getInputBuffer(inputIndex)
                    ?: error("Decoder input buffer unavailable")
                input.clear()
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L || sampleTimeUs >= clip.sourceOutUs) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        clip.sourceOutUs.coerceAtLeast(0L),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputEos = true
                    didWork = true
                    break
                }
                val size = extractor.readSampleData(input, 0)
                if (size < 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        sampleTimeUs.coerceAtLeast(0L),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    inputEos = true
                } else {
                    codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0)
                    extractor.advance()
                }
                didWork = true
            }
            return didWork
        }

        fun drainPlayback(timelineUs: Long, core: DigitorRenderCore) {
            heldPlaybackOutput?.let { held ->
                val heldTimelineUs = sourceToTimelineUs(clip, held.presentationTimeUs)
                if (heldTimelineUs <= timelineUs + PLAYBACK_LEAD_US &&
                    core.pendingInputFrames(inputIndex) < MAX_GRAPH_PENDING_FRAMES &&
                    core.registerInputFrame(inputIndex)
                ) {
                    codec.releaseOutputBuffer(held.index, true)
                    heldPlaybackOutput = null
                } else {
                    return
                }
            }

            for (attempt in 0 until MAX_OUTPUT_PER_PUMP) {
                if (outputEos || heldPlaybackOutput != null) return
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                when {
                    outputIndex >= 0 -> {
                        val output = HeldOutput(
                            index = outputIndex,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags,
                        )
                        val isEos = output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val sourceUs = output.presentationTimeUs
                        if (sourceUs < playbackFloorSourceUs || sourceUs < clip.sourceInUs) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (isEos) outputEos = true
                            continue
                        }
                        if (sourceUs >= clip.sourceOutUs) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (isEos) outputEos = true
                            continue
                        }

                        val outputTimelineUs = sourceToTimelineUs(clip, sourceUs)
                        if (outputTimelineUs > timelineUs + PLAYBACK_LEAD_US ||
                            core.pendingInputFrames(inputIndex) >= MAX_GRAPH_PENDING_FRAMES ||
                            !core.registerInputFrame(inputIndex)
                        ) {
                            heldPlaybackOutput = output
                            return
                        }

                        codec.releaseOutputBuffer(outputIndex, true)
                        playbackFloorSourceUs = Long.MIN_VALUE
                        if (isEos) outputEos = true
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        }

        fun decodeAroundTarget(): AroundTarget {
            val targetSourceUs = playbackFloorSourceUs
            var candidate: HeldOutput? = null
            var future: HeldOutput? = null

            for (step in 0 until MAX_SCRUB_STEPS) {
                feedInput(2)
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, SCRUB_DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val output = HeldOutput(
                            index = outputIndex,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags,
                        )
                        val sourceUs = output.presentationTimeUs
                        val isEos = output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            sourceUs < clip.sourceInUs -> releaseWithoutRendering(output)
                            sourceUs <= targetSourceUs && sourceUs < clip.sourceOutUs -> {
                                candidate?.let(::releaseWithoutRendering)
                                candidate = output
                            }
                            sourceUs < clip.sourceOutUs -> future = output
                            else -> releaseWithoutRendering(output)
                        }
                        if (isEos) outputEos = true
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
                if (future != null || (inputEos && outputEos)) break
            }
            return AroundTarget(candidate, future)
        }

        fun tryReleaseToGraph(output: HeldOutput, core: DigitorRenderCore): Boolean {
            if (!core.registerInputFrame(inputIndex)) return false
            codec.releaseOutputBuffer(output.index, true)
            return true
        }

        fun releaseWithoutRendering(output: HeldOutput) {
            runCatching { codec.releaseOutputBuffer(output.index, false) }
        }

        override fun close() {
            heldPlaybackOutput?.let(::releaseWithoutRendering)
            heldPlaybackOutput = null
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "DigitorSharedPreview"
        const val PLAYBACK_PUMP_MS = 4L
        const val PAUSED_FRAME_RETRY_MS = 8L
        const val PLAYBACK_LEAD_US = 120_000L
        const val HARD_RESYNC_US = 500_000L
        const val MAX_GRAPH_PENDING_FRAMES = 4
        const val MAX_INPUT_PER_PUMP = 8
        const val MAX_OUTPUT_PER_PUMP = 12
        const val MAX_SCRUB_STEPS = 280
        const val SCRUB_DEQUEUE_TIMEOUT_US = 1_000L
        const val LEGACY_IDLE_PAUSE_MS = 180L
        const val EXPORT_RELEASE_TIMEOUT_MS = 5_000L
    }
}

/** Visible preview layers, including the virtual outgoing tail while a V22 video transition is active. */
internal fun activeVideoLayersAt(project: TimelineProject, timeUs: Long): List<TimelineClip> =
    activeLayerSpecsAt(project, timeUs).map { it.clip }

private fun activeLayerSpecsAt(
    project: TimelineProject,
    timeUs: Long,
): List<DavinciFramePreviewEngine.ActiveLayer> =
    project.tracks
        .withIndex()
        .filter { (_, track) -> track.kind == TrackKind.VIDEO && !track.muted }
        .sortedByDescending { (trackIndex, _) -> trackIndex }
        .flatMap { (_, track) ->
            val activeClip = track.clips
                .firstOrNull { clip -> timeUs in clip.timelineStartUs until clip.timelineEndUs }
            val activeTransition = track.transitionPairsV22().firstOrNull { pair ->
                timeUs in pair.startUs until pair.endUs &&
                    !pair.outgoing.isImageV21 &&
                    !pair.incoming.isImageV21
            }
            buildList {
                if (activeTransition != null) {
                    add(
                        DavinciFramePreviewEngine.ActiveLayer(
                            track = track,
                            clip = previewTransitionGhostClipV22(activeTransition),
                        ),
                    )
                }
                if (activeClip != null) {
                    add(DavinciFramePreviewEngine.ActiveLayer(track, activeClip))
                }
            }
        }

private fun previewTransitionGhostClipV22(pair: TransitionPairV22): TimelineClip {
    val outgoing = pair.outgoing
    val sourceOutUs = outgoing.sourceOutUs
    val sourceInUs = (sourceOutUs - pair.durationUs).coerceAtLeast(outgoing.sourceInUs)
    return outgoing.copy(
        id = transitionGhostIdV22(pair),
        label = "${outgoing.label} · transition tail",
        timelineStartUs = pair.startUs,
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        linkGroupId = null,
        transition = pair.incoming.transition.copy(durationUsV22 = pair.durationUs),
    )
}

private fun sessionKey(
    project: TimelineProject,
    layers: List<DavinciFramePreviewEngine.ActiveLayer>,
): DavinciFramePreviewEngine.SessionKey = DavinciFramePreviewEngine.SessionKey(
    width = project.width,
    height = project.height,
    frameRate = project.frameRate,
    layers = layers.map { layer ->
        DavinciFramePreviewEngine.LayerKey(
            trackId = layer.track.id,
            clipId = layer.clip.id,
            uri = layer.clip.uri,
            timelineStartUs = layer.clip.timelineStartUs,
            sourceInUs = layer.clip.sourceInUs,
            sourceOutUs = layer.clip.sourceOutUs,
            staticSpatialHash = staticSpatialHash(layer.clip),
        )
    },
)

private fun staticSpatialHash(clip: TimelineClip): Int {
    var result = clip.nodeGraph.edges.hashCode()
    result = 31 * result + clip.transition.hashCode()
    clip.nodeGraph.nodes.forEach { node ->
        result = 31 * result + node.id.hashCode()
        result = 31 * result + node.kind.hashCode()
        result = 31 * result + node.advancedColor.qualifier.hashCode()
        result = 31 * result + clip.nodeAnimations
            .track(node.id, com.tajuli.digitorandroid.editor.model.NodeAnimationDomain.COLOR)
            .keyframes
            .map { key -> key.sourceTimeUs to key.node.advancedColor.qualifier }
            .hashCode()
    }
    return result
}

internal fun timelineToSourceUs(clip: TimelineClip, timelineUs: Long): Long =
    (clip.sourceInUs + (timelineUs - clip.timelineStartUs))
        .coerceIn(clip.sourceInUs, clip.sourceOutUs.coerceAtLeast(clip.sourceInUs))

internal fun sourceToTimelineUs(clip: TimelineClip, sourceUs: Long): Long =
    clip.timelineStartUs + (sourceUs - clip.sourceInUs)

private fun MediaFormat.intValue(key: String, fallback: Int): Int =
    if (!containsKey(key)) fallback else runCatching { getInteger(key) }.getOrDefault(fallback)

private fun MediaFormat.numberValue(key: String): Number? {
    if (!containsKey(key)) return null
    return runCatching { getNumber(key) }.getOrNull()
}
