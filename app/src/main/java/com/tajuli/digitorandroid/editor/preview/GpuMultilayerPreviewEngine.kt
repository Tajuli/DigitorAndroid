package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.Tracks
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.exoplayer.ExoPlayer
import com.tajuli.digitorandroid.editor.model.PreviewClipState
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.render.PreviewResolveVideoCompositorSettings
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Persistent final-output / Export-node preview.
 *
 * Every active V layer is decoded by a MediaCodec-backed ExoPlayer into one direct
 * [MultipleInputVideoGraph]. Per-layer color/spatial processing uses the export-quality GPU path and
 * the final graph uses the same Resolve compositor math as Transformer export. The viewer therefore
 * displays the final pre-encoder frame instead of a separate CPU approximation.
 *
 * The graph is never started before a valid viewer Surface exists. This is important on slower OEM
 * devices: a first frame produced before SurfaceView creation is otherwise consumed by the automatic
 * output renderer and the later-attached Surface stays black until another decoder frame arrives.
 *
 * MultipleInputVideoGraph does not implement redraw(), even with replayable cache enabled. Paused
 * visual edits therefore refresh the current frame by seeking the already-alive decoder. MediaCodec,
 * the GPU graph and output Surface remain persistent; only source/timing or immutable shader topology
 * changes rebuild the small active-layer graph.
 */
@UnstableApi
class GpuMultilayerPreviewEngine(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        fun onReady(activeLayerCount: Int)
        fun onError(message: String)
    }

    private data class ActiveLayer(
        val track: TimelineTrack,
        val clip: TimelineClip,
    )

    private data class OutputTarget(
        val surface: Surface,
        val width: Int,
        val height: Int,
    )

    private data class PendingState(
        val project: TimelineProject,
        val timelineUs: Long,
        val playing: Boolean,
    )

    private data class LayerDecoder(
        val layer: ActiveLayer,
        val player: ExoPlayer,
        val playerListener: Player.Listener,
    )

    private data class Session(
        val generation: Long,
        val key: String,
        val graph: MultipleInputVideoGraph,
        val decoders: List<LayerDecoder>,
        var playing: Boolean,
        var lastSyncCursorUs: Long,
        var lastRequestedTimelineUs: Long,
        var liveStateKey: Int,
        var firstOutputReported: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }
    private val graphFactory = MultipleInputVideoGraph.Factory()

    private var outputTarget: OutputTarget? = null
    private var pendingState: PendingState? = null
    private var session: Session? = null
    private var sessionGeneration = 0L
    private var refreshRunnable: Runnable? = null
    private var closed = false

    fun setOutputSurface(surface: Surface?, width: Int, height: Int) {
        if (closed) return

        outputTarget = surface?.takeIf { it.isValid }?.let {
            OutputTarget(
                surface = it,
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
            )
        }

        val current = session
        applyOutputTarget(current?.graph)

        if (outputTarget == null) {
            // A detached Surface must not keep video clocks running invisibly. Mark the session
            // paused so the pending editor state can resume it when a new Surface becomes valid.
            current?.let { active ->
                active.decoders.forEach { decoder -> runCatching { decoder.player.pause() } }
                active.playing = false
            }
            return
        }

        // Initial Compose composition often submits the timeline before AndroidView creates its
        // SurfaceView. Build/re-seek only after the valid output target has been installed.
        val pending = pendingState
        if (pending != null) {
            update(
                project = pending.project,
                timelineUs = pending.timelineUs,
                playing = pending.playing,
                forceSeek = true,
            )
        } else if (current != null) {
            scheduleFrameRefresh(current, current.lastRequestedTimelineUs, immediate = true)
        }
    }

    fun update(project: TimelineProject, timelineUs: Long, playing: Boolean, forceSeek: Boolean = false) {
        if (closed) return

        val safeTimelineUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        pendingState = PendingState(project, safeTimelineUs, playing)
        val activeLayers = activeLayers(project, safeTimelineUs)
        PreviewClipState.updateAll(activeLayers.map { layer -> layer.clip })

        // Do not let the first processed frame disappear before SurfaceView exists. setOutputSurface
        // will replay this exact pending state as soon as Android gives us a valid Surface.
        if (outputTarget == null) return

        val key = sessionKey(project, activeLayers)
        val current = session
        if (current == null || current.key != key) {
            rebuildSession(project, activeLayers, safeTimelineUs, playing)
            return
        }

        val nextLiveStateKey = liveStateKey(activeLayers)
        val liveStateChanged = current.liveStateKey != nextLiveStateKey
        current.liveStateKey = nextLiveStateKey

        if (current.playing != playing) {
            current.playing = playing
            current.decoders.forEach { decoder ->
                runCatching { decoder.player.setScrubbingModeEnabled(!playing) }
                runCatching {
                    if (playing) decoder.player.play() else decoder.player.pause()
                }
            }
        }

        val cursorChanged = abs(safeTimelineUs - current.lastRequestedTimelineUs) >= CURSOR_CHANGE_US
        current.lastRequestedTimelineUs = safeTimelineUs

        if (!playing || forceSeek) {
            if (cursorChanged || liveStateChanged || forceSeek) {
                // MultipleInputVideoGraph.redraw() is intentionally unsupported in Media3 1.11.
                // Refresh through the persistent decoder instead of recreating MediaCodec/GL state.
                scheduleFrameRefresh(
                    current,
                    safeTimelineUs,
                    immediate = cursorChanged || forceSeek,
                )
            }
            current.lastSyncCursorUs = safeTimelineUs
            return
        }

        if (abs(safeTimelineUs - current.lastSyncCursorUs) >= DRIFT_CHECK_INTERVAL_US) {
            current.decoders.forEach { decoder ->
                val desiredUs = sourcePositionUs(decoder.layer.clip, safeTimelineUs)
                val actualUs = decoder.player.currentPosition.coerceAtLeast(0L) * 1000L
                if (abs(actualUs - desiredUs) > MAX_LAYER_DRIFT_US) {
                    runCatching { decoder.player.seekTo(desiredUs / 1000L) }
                }
            }
            current.lastSyncCursorUs = safeTimelineUs
        }
    }

    private fun rebuildSession(
        project: TimelineProject,
        activeLayers: List<ActiveLayer>,
        timelineUs: Long,
        playing: Boolean,
    ) {
        retireSession()

        if (activeLayers.isEmpty()) {
            listener.onReady(0)
            return
        }
        if (outputTarget == null) return

        PreviewClipState.updateAll(activeLayers.map { layer -> layer.clip })
        val expectedKey = sessionKey(project, activeLayers)
        val generation = ++sessionGeneration
        var graph: MultipleInputVideoGraph? = null
        val decoders = mutableListOf<LayerDecoder>()

        try {
            val createdGraph = graphFactory.create(
                appContext,
                ColorInfo.SDR_BT709_LIMITED,
                DebugViewProvider.NONE,
                object : VideoGraph.Listener {
                    override fun onOutputFrameAvailableForRendering(
                        framePresentationTimeUs: Long,
                        isRedrawnFrame: Boolean,
                    ) {
                        val activeSession = session
                        val target = outputTarget
                        if (activeSession != null &&
                            activeSession.generation == generation &&
                            activeSession.key == expectedKey &&
                            target != null && target.surface.isValid &&
                            !activeSession.firstOutputReported
                        ) {
                            activeSession.firstOutputReported = true
                            listener.onReady(activeSession.decoders.size)
                        }
                    }

                    override fun onError(exception: VideoFrameProcessingException) {
                        if (session?.generation == generation) {
                            listener.onError("Final GPU preview: ${exception.message ?: "video graph failed"}")
                        }
                    }
                },
                mainExecutor,
                0L,
                true,
            )
            graph = createdGraph
            createdGraph.initialize()
            createdGraph.setCompositorSettings(
                PreviewResolveVideoCompositorSettings(
                    outputWidth = project.width,
                    outputHeight = project.height,
                    fallbackClips = activeLayers.map { layer -> layer.clip },
                ),
            )
            activeLayers.indices.forEach(createdGraph::registerInput)

            // Install the output Surface before any decoder is prepared, so the first automatic
            // output render is guaranteed to have somewhere visible to go.
            applyOutputTarget(createdGraph)

            activeLayers.forEachIndexed { index, layer ->
                val clip = layer.clip
                val effects = SharedVideoPipeline.finalOutputPreviewEffectsFor(clip)
                val requestedSourcePositionMs = sourcePositionUs(clip, timelineUs) / 1000L
                var streamRegistered = false
                lateinit var player: ExoPlayer

                val playerListener = object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        if (streamRegistered || closed || generation != sessionGeneration) return
                        val videoGroup = tracks.groups.firstOrNull { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                        } ?: return
                        val selectedIndex = (0 until videoGroup.length)
                            .firstOrNull { trackIndex -> videoGroup.isTrackSelected(trackIndex) }
                            ?: 0
                        val selectedFormat = videoGroup.getTrackFormat(selectedIndex)

                        try {
                            // Media3 1.11 can register MediaCodec SurfaceTexture frames itself. This
                            // avoids the manual first-frame registration race that could leave the
                            // direct graph alive but visually black on slower devices.
                            createdGraph.setOnInputSurfaceReadyListener(index) {
                                mainHandler.post {
                                    if (closed || generation != sessionGeneration) return@post
                                    runCatching {
                                        player.setVideoSurface(createdGraph.getInputSurface(index))
                                        player.seekTo(requestedSourcePositionMs)
                                        player.setScrubbingModeEnabled(!playing)
                                        if (playing) player.play() else player.pause()
                                    }.onFailure { error ->
                                        if (session?.generation == generation) {
                                            listener.onError(
                                                "Final GPU preview layer ${index + 1}: ${error.message ?: "decoder surface attach failed"}",
                                            )
                                        }
                                    }
                                }
                            }
                            createdGraph.registerInputStream(
                                index,
                                VideoFrameProcessor.INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION,
                                safeGraphFormat(selectedFormat, project),
                                effects,
                                clip.timelineStartUs - clip.sourceInUs,
                            )
                            streamRegistered = true
                        } catch (error: Throwable) {
                            if (session?.generation == generation) {
                                listener.onError(
                                    "Final GPU preview layer ${index + 1}: ${error.message ?: "stream registration failed"}",
                                )
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (session?.generation == generation) {
                            listener.onError(
                                "Final GPU preview layer ${index + 1}: ${error.message ?: error.errorCodeName}",
                            )
                        }
                    }
                }

                player = ExoPlayer.Builder(appContext)
                    .setDetachSurfaceTimeoutMs(DETACH_SURFACE_TIMEOUT_MS)
                    .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
                    .build()
                    .apply {
                        volume = 0f
                        addListener(playerListener)
                        setMediaItem(MediaItem.fromUri(clip.uri))
                        prepare()
                    }
                decoders += LayerDecoder(layer, player, playerListener)
            }

            session = Session(
                generation = generation,
                key = expectedKey,
                graph = createdGraph,
                decoders = decoders,
                playing = playing,
                lastSyncCursorUs = timelineUs,
                lastRequestedTimelineUs = timelineUs,
                liveStateKey = liveStateKey(activeLayers),
            )
        } catch (error: Throwable) {
            decoders.forEach(::releaseDecoder)
            runCatching { graph?.setOutputSurfaceInfo(null) }
            runCatching { graph?.release() }
            activeLayers.forEach { layer -> PreviewClipState.remove(layer.clip.id) }
            listener.onError("Final GPU preview: ${error.message ?: "unable to create final output graph"}")
        }
    }

    private fun applyOutputTarget(graph: MultipleInputVideoGraph?) {
        graph ?: return
        val target = outputTarget
        runCatching {
            graph.setOutputSurfaceInfo(
                target?.let { SurfaceInfo(it.surface, it.width, it.height) },
            )
        }.onFailure { error ->
            listener.onError("Final GPU preview surface: ${error.message ?: "unavailable"}")
        }
    }

    private fun scheduleFrameRefresh(session: Session, timelineUs: Long, immediate: Boolean) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        val generation = session.generation
        val runnable = Runnable {
            if (closed || outputTarget == null || this.session?.generation != generation) return@Runnable
            seekDecoders(session, timelineUs, force = true)
        }
        refreshRunnable = runnable
        if (immediate) mainHandler.post(runnable)
        else mainHandler.postDelayed(runnable, PAUSED_EDIT_REFRESH_MS)
    }

    private fun seekDecoders(session: Session, timelineUs: Long, force: Boolean = false) {
        session.decoders.forEach { decoder ->
            val desiredUs = sourcePositionUs(decoder.layer.clip, timelineUs)
            val actualUs = decoder.player.currentPosition.coerceAtLeast(0L) * 1000L
            if (force || abs(actualUs - desiredUs) >= SEEK_TOLERANCE_US) {
                runCatching { decoder.player.seekTo(desiredUs / 1000L) }
            }
        }
    }

    private fun retireSession() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null

        val old = session ?: return
        session = null
        old.decoders.forEach { decoder ->
            runCatching { decoder.player.pause() }
            runCatching { decoder.player.setVideoSurface(null) }
        }
        runCatching { old.graph.setOutputSurfaceInfo(null) }
        old.decoders.forEach(::releaseDecoder)
        runCatching { old.graph.release() }
        old.decoders.forEach { decoder -> PreviewClipState.remove(decoder.layer.clip.id) }
    }

    private fun releaseDecoder(decoder: LayerDecoder) {
        runCatching { decoder.player.removeListener(decoder.playerListener) }
        runCatching { decoder.player.setVideoSurface(null) }
        runCatching { decoder.player.release() }
    }

    override fun close() {
        if (closed) return
        closed = true
        sessionGeneration += 1L
        retireSession()
        PreviewClipState.clear()
        pendingState = null
        outputTarget = null
    }

    private fun activeLayers(project: TimelineProject, timelineUs: Long): List<ActiveLayer> =
        project.tracks
            .asSequence()
            .filter { track -> track.kind == TrackKind.VIDEO && !track.muted }
            .mapNotNull { track ->
                track.clips.firstOrNull { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }
                    ?.let { clip -> ActiveLayer(track, clip) }
            }
            .toList()

    /** Rebuild only for source/timing/shader-topology changes, never ordinary parameter edits. */
    private fun sessionKey(project: TimelineProject, layers: List<ActiveLayer>): String = buildString {
        append(project.width).append('x').append(project.height).append('|')
        layers.forEach { layer ->
            val clip = layer.clip
            append(layer.track.id).append(':')
            append(clip.id).append(':')
            append(clip.uri).append(':')
            append(clip.timelineStartUs).append(':')
            append(clip.sourceInUs).append(':')
            append(clip.sourceOutUs).append(':')
            append(SharedVideoPipeline.finalOutputPreviewPipelineKey(clip)).append(';')
        }
    }

    /** Detect live visual changes so a paused frame is refreshed without rebuilding the session. */
    private fun liveStateKey(layers: List<ActiveLayer>): Int =
        layers.map { layer -> layer.clip.hashCode() }.hashCode()

    private fun safeGraphFormat(format: Format, project: TimelineProject): Format =
        format.buildUpon()
            .setWidth(if (format.width > 0) format.width else project.width.coerceAtLeast(1))
            .setHeight(if (format.height > 0) format.height else project.height.coerceAtLeast(1))
            .setPixelWidthHeightRatio(format.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f)
            .setColorInfo(format.colorInfo ?: ColorInfo.SDR_BT709_LIMITED)
            .build()

    private fun sourcePositionUs(clip: TimelineClip, timelineUs: Long): Long {
        val maxLocalUs = (clip.durationUs - 1L).coerceAtLeast(0L)
        val localUs = (timelineUs - clip.timelineStartUs).coerceIn(0L, maxLocalUs)
        return (clip.sourceInUs + localUs).coerceAtLeast(0L)
    }

    private companion object {
        const val CURSOR_CHANGE_US = 1_000L
        const val SEEK_TOLERANCE_US = 20_000L
        const val DRIFT_CHECK_INTERVAL_US = 250_000L
        const val MAX_LAYER_DRIFT_US = 150_000L
        const val DETACH_SURFACE_TIMEOUT_MS = 350L
        const val RELEASE_TIMEOUT_MS = 500L
        const val PAUSED_EDIT_REFRESH_MS = 16L
    }
}
