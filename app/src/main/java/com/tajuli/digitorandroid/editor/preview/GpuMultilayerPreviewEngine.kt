package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.SurfaceInfo
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
 * Device-safe realtime multilayer GPU preview.
 *
 * CompositionPlayer's multi-video path is intentionally not used here. Each visible V layer is
 * decoded by its own MediaCodec-backed ExoPlayer directly into a MultipleInputVideoGraph input
 * surface. The graph applies the same per-layer effects and ResolveVideoCompositorSettings used by
 * Transformer export, then renders one composited output Surface for the editor viewer.
 *
 * Only clips that are active at the current playhead are registered as graph inputs. This avoids
 * timeline gap/sequence state in CompositionPlayer and lets topology changes (V2 add/remove,
 * split/delete, clip boundaries) rebuild a small graph without wedging the existing decoder.
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
                            listener.onError("GPU preview: ${exception.message ?: "video graph failed"}")
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
                var streamRegistered = false

                val metadataListener = VideoFrameMetadataListener { _, _, inputFormat, _ ->
                    try {
                        if (!streamRegistered) {
                            val graphFormat = safeGraphFormat(inputFormat, project)
                            createdGraph.registerInputStream(
                                index,
                                VideoFrameProcessor.INPUT_TYPE_SURFACE,
                                graphFormat,
                                effects,
                                clip.timelineStartUs - clip.sourceInUs,
                            )
                            streamRegistered = true
                        }
                        createdGraph.registerInputFrame(index)
                    } catch (error: Throwable) {
                        mainHandler.post {
                            if (session?.generation == generation) {
                                listener.onError("GPU preview layer ${index + 1}: ${error.message ?: "frame registration failed"}")
                            }
                        }
                    }
                }

                val player = ExoPlayer.Builder(appContext)
                    .setDetachSurfaceTimeoutMs(DETACH_SURFACE_TIMEOUT_MS)
                    .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
                    .build()
                    .apply {
                        volume = 0f
                        setVideoSurface(createdGraph.getInputSurface(index))
                        setVideoFrameMetadataListener(metadataListener)
                        setMediaItem(MediaItem.fromUri(clip.uri))
                        prepare()
                        seekTo(sourcePositionUs(clip, timelineUs) / 1000L)
                        if (playing) play()
                    }
                decoders += LayerDecoder(layer, player, metadataListener)
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
            listener.onError("GPU preview: ${error.message ?: "unable to create multilayer graph"}")
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
            listener.onError("GPU preview surface: ${error.message ?: "unavailable"}")
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
        runCatching { decoder.player.setVideoSurface(null) }
        runCatching { decoder.player.release() }
    }

    override fun close() {
        if (closed) return
        closed = true
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
    }
}
