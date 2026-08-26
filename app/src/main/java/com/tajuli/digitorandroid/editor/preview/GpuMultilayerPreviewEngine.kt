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
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.render.ResolveVideoCompositorSettings
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Final-output / Export-node preview.
 *
 * The editor viewer does not display a decoder surface directly. Every active V layer is decoded by
 * MediaCodec-backed ExoPlayer into a MultipleInputVideoGraph input, receives the same per-layer GPU
 * effects as export, then passes through the same ResolveVideoCompositorSettings. The single graph
 * output surface is therefore the final composited output, analogous to viewing the Render/Viewer
 * output node in a compositor.
 *
 * Multi-video CompositionPlayer is intentionally not used. The important decoder/graph handshake is:
 * discover the selected video format -> registerInputStream -> pre-register the first input frame ->
 * attach the decoder surface. This prevents the graph from receiving an unregistered first frame,
 * which previously resulted in a permanently blank preview.
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

    private data class LayerDecoder(
        val layer: ActiveLayer,
        val player: ExoPlayer,
        val playerListener: Player.Listener,
        val metadataListener: VideoFrameMetadataListener,
    )

    private data class Session(
        val generation: Long,
        val key: String,
        val graph: MultipleInputVideoGraph,
        val decoders: List<LayerDecoder>,
        var playing: Boolean,
        var lastSyncCursorUs: Long,
        var firstOutputReported: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable -> mainHandler.post(runnable) }

    private var outputTarget: OutputTarget? = null
    private var session: Session? = null
    private var sessionGeneration = 0L
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
        applyOutputTarget(session?.graph)
    }

    fun update(project: TimelineProject, timelineUs: Long, playing: Boolean, forceSeek: Boolean = false) {
        if (closed) return
        val safeTimelineUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        val activeLayers = activeLayers(project, safeTimelineUs)
        val key = sessionKey(project, activeLayers)
        val current = session

        if (current == null || current.key != key) {
            rebuildSession(project, activeLayers, safeTimelineUs, playing)
            return
        }

        if (current.playing != playing) {
            current.playing = playing
            current.decoders.forEach { decoder ->
                runCatching {
                    if (playing) decoder.player.play() else decoder.player.pause()
                }
            }
        }

        if (!playing || forceSeek) {
            seekDecoders(current, safeTimelineUs)
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

        val expectedKey = sessionKey(project, activeLayers)
        val generation = ++sessionGeneration
        var graph: MultipleInputVideoGraph? = null
        val decoders = mutableListOf<LayerDecoder>()
        try {
            val createdGraph = MultipleInputVideoGraph.Factory().create(
                appContext,
                ColorInfo.SDR_BT709_LIMITED,
                DebugViewProvider.NONE,
                object : VideoGraph.Listener {
                    override fun onOutputFrameAvailableForRendering(
                        framePresentationTimeUs: Long,
                        isRedrawnFrame: Boolean,
                    ) {
                        val activeSession = session
                        if (activeSession != null &&
                            activeSession.generation == generation &&
                            activeSession.key == expectedKey &&
                            !activeSession.firstOutputReported
                        ) {
                            activeSession.firstOutputReported = true
                            listener.onReady(activeSession.decoders.size)
                        }
                    }

                    override fun onError(exception: VideoFrameProcessingException) {
                        if (session?.generation == generation) {
                            listener.onError("Export-node preview: ${exception.message ?: "video graph failed"}")
                        }
                    }
                },
                mainExecutor,
                0L,
                true,
            )
            graph = createdGraph
            createdGraph.initialize()

            val compositorTracks = activeLayers.map { layer ->
                layer.track.copy(clips = listOf(layer.clip))
            }
            createdGraph.setCompositorSettings(
                ResolveVideoCompositorSettings(
                    outputWidth = project.width,
                    outputHeight = project.height,
                    videoTracks = compositorTracks,
                ),
            )

            activeLayers.indices.forEach(createdGraph::registerInput)
            applyOutputTarget(createdGraph)

            activeLayers.forEachIndexed { index, layer ->
                val clip = layer.clip
                val effects = SharedVideoPipeline.compositedExportEffectsFor(clip)
                val requestedSourcePositionMs = sourcePositionUs(clip, timelineUs) / 1000L
                var streamRegistered = false
                var firstFramePreRegistered = false
                lateinit var player: ExoPlayer

                val metadataListener = VideoFrameMetadataListener { _, _, _, _ ->
                    try {
                        if (firstFramePreRegistered) {
                            // The first frame was registered before the decoder surface was attached.
                            firstFramePreRegistered = false
                        } else if (streamRegistered) {
                            val accepted = createdGraph.registerInputFrame(index)
                            if (!accepted && session?.generation == generation) {
                                listener.onError("Export-node preview layer ${index + 1}: GPU input busy")
                            }
                        }
                    } catch (error: Throwable) {
                        mainHandler.post {
                            if (session?.generation == generation) {
                                listener.onError(
                                    "Export-node preview layer ${index + 1}: ${error.message ?: "frame registration failed"}",
                                )
                            }
                        }
                    }
                }

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
                            createdGraph.registerInputStream(
                                index,
                                VideoFrameProcessor.INPUT_TYPE_SURFACE,
                                safeGraphFormat(selectedFormat, project),
                                effects,
                                clip.timelineStartUs - clip.sourceInUs,
                            )
                            streamRegistered = true

                            // Media3 requires a surface frame to be registered before the decoder
                            // renders it. Wait until the graph accepts that first registration, then
                            // attach the MediaCodec output surface. Subsequent frames are registered
                            // by VideoFrameMetadataListener immediately before rendering.
                            val attachSurfaceWhenReady = object : Runnable {
                                var attempts = 0

                                override fun run() {
                                    if (closed || generation != sessionGeneration) return
                                    val accepted = runCatching {
                                        createdGraph.registerInputFrame(index)
                                    }.getOrElse { error ->
                                        if (session?.generation == generation) {
                                            listener.onError(
                                                "Export-node preview layer ${index + 1}: ${error.message ?: "GPU input registration failed"}",
                                            )
                                        }
                                        return
                                    }
                                    if (!accepted) {
                                        if (attempts++ < FIRST_FRAME_REGISTER_RETRIES) {
                                            mainHandler.postDelayed(this, FIRST_FRAME_REGISTER_RETRY_MS)
                                        } else if (session?.generation == generation) {
                                            listener.onError("Export-node preview layer ${index + 1}: GPU input never became ready")
                                        }
                                        return
                                    }

                                    firstFramePreRegistered = true
                                    player.setVideoFrameMetadataListener(metadataListener)
                                    player.setVideoSurface(createdGraph.getInputSurface(index))
                                    player.seekTo(requestedSourcePositionMs)
                                    if (playing) player.play() else player.pause()
                                }
                            }
                            mainHandler.post(attachSurfaceWhenReady)
                        } catch (error: Throwable) {
                            if (session?.generation == generation) {
                                listener.onError(
                                    "Export-node preview layer ${index + 1}: ${error.message ?: "stream registration failed"}",
                                )
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (session?.generation == generation) {
                            listener.onError(
                                "Export-node preview layer ${index + 1}: ${error.message ?: error.errorCodeName}",
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
                        // Prepare first with no output surface. onTracksChanged gives us the exact
                        // decoder format needed by the final GPU graph before any frame is rendered.
                        prepare()
                    }
                decoders += LayerDecoder(layer, player, playerListener, metadataListener)
            }

            session = Session(
                generation = generation,
                key = expectedKey,
                graph = createdGraph,
                decoders = decoders,
                playing = playing,
                lastSyncCursorUs = timelineUs,
            )
        } catch (error: Throwable) {
            decoders.forEach(::releaseDecoder)
            runCatching { graph?.setOutputSurfaceInfo(null) }
            runCatching { graph?.release() }
            listener.onError("Export-node preview: ${error.message ?: "unable to create final output graph"}")
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
            listener.onError("Export-node preview surface: ${error.message ?: "unavailable"}")
        }
    }

    private fun seekDecoders(session: Session, timelineUs: Long) {
        session.decoders.forEach { decoder ->
            val desiredUs = sourcePositionUs(decoder.layer.clip, timelineUs)
            val actualUs = decoder.player.currentPosition.coerceAtLeast(0L) * 1000L
            if (abs(actualUs - desiredUs) >= SEEK_TOLERANCE_US) {
                runCatching { decoder.player.seekTo(desiredUs / 1000L) }
            }
        }
    }

    private fun retireSession() {
        val old = session ?: return
        session = null
        old.decoders.forEach { decoder ->
            runCatching { decoder.player.pause() }
            runCatching { decoder.player.setVideoSurface(null) }
        }
        runCatching { old.graph.setOutputSurfaceInfo(null) }
        old.decoders.forEach(::releaseDecoder)
        runCatching { old.graph.release() }
    }

    private fun releaseDecoder(decoder: LayerDecoder) {
        runCatching { decoder.player.clearVideoFrameMetadataListener(decoder.metadataListener) }
        runCatching { decoder.player.removeListener(decoder.playerListener) }
        runCatching { decoder.player.setVideoSurface(null) }
        runCatching { decoder.player.release() }
    }

    override fun close() {
        if (closed) return
        closed = true
        sessionGeneration += 1L
        retireSession()
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

    private fun sessionKey(project: TimelineProject, layers: List<ActiveLayer>): String = buildString {
        append(project.width).append('x').append(project.height).append('|')
        layers.forEach { layer ->
            append(layer.track.id).append(':')
            append(layer.clip.hashCode()).append(';')
        }
    }

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
        const val SEEK_TOLERANCE_US = 20_000L
        const val DRIFT_CHECK_INTERVAL_US = 250_000L
        const val MAX_LAYER_DRIFT_US = 150_000L
        const val DETACH_SURFACE_TIMEOUT_MS = 350L
        const val RELEASE_TIMEOUT_MS = 500L
        const val FIRST_FRAME_REGISTER_RETRIES = 60
        const val FIRST_FRAME_REGISTER_RETRY_MS = 8L
    }
}
