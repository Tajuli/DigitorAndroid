package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.tajuli.digitorandroid.editor.model.PreviewClipState
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import com.tajuli.digitorandroid.editor.render.SharedVideoPipeline
import kotlin.math.abs

/**
 * Export-quality final-output preview backed by Media3's official [CompositionPlayer].
 *
 * The first PR #33 implementation drove [MultipleInputVideoGraph] directly with hand-wired
 * ExoPlayers. It could produce final-frame callbacks while some devices still displayed a black
 * Surface because that bypassed Media3's playback frame-release/surface-presentation layer.
 *
 * This engine keeps the same export-quality GPU graph, but lets CompositionPlayer own decoder
 * surfaces, stream transitions, frame release, scrubbing and final Surface presentation. The input
 * Composition mirrors export: one sequence per Digitor V track, export-resolution 33^3 LUT/node
 * processing and Resolve compositor geometry/opacity. Ordinary visual edits are published through
 * [PreviewClipState] and refresh the already-prepared player by seeking the current frame; only
 * source/timing/shader-topology changes rebuild the Composition.
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

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val compositionBuilder = Media3CompositionBuilder()

    private var outputTarget: OutputTarget? = null
    private var pendingState: PendingState? = null
    private var compositionKey: String? = null
    private var liveStateKey: Int = Int.MIN_VALUE
    private var prepared = false
    private var playing = false
    private var lastRequestedTimelineUs = 0L
    private var latestActiveLayerCount = 0
    private var refreshRunnable: Runnable? = null
    private var closed = false

    private val player = CompositionPlayer.Builder(appContext)
        // CompositionPlayer's PlaybackVideoGraphWrapper is the important part here: it owns frame
        // release and Surface presentation. MultipleInputVideoGraph is used only behind that official
        // wrapper, never driven directly by Digitor.
        .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        .build()
        .apply {
            volume = 0f
            addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (!closed) listener.onReady(latestActiveLayerCount)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!closed) {
                            listener.onError(
                                "Final GPU preview: ${error.message ?: error.errorCodeName}",
                            )
                        }
                    }
                },
            )
        }

    fun setOutputSurface(surface: Surface?, width: Int, height: Int) {
        if (closed) return

        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null

        val target = surface?.takeIf { it.isValid }?.let {
            OutputTarget(
                surface = it,
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
            )
        }
        outputTarget = target

        if (target == null) {
            runCatching { player.pause() }
            playing = false
            runCatching { player.clearVideoSurface() }
            return
        }

        runCatching {
            player.setVideoSurface(
                target.surface,
                Size(target.width, target.height),
            )
        }.onFailure { error ->
            listener.onError("Final GPU preview surface: ${error.message ?: "unavailable"}")
            return
        }

        pendingState?.let { pending ->
            update(
                project = pending.project,
                timelineUs = pending.timelineUs,
                playing = pending.playing,
                forceSeek = true,
            )
        }
    }

    fun update(
        project: TimelineProject,
        timelineUs: Long,
        playing: Boolean,
        forceSeek: Boolean = false,
    ) {
        if (closed) return

        val safeTimelineUs = timelineUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))
        pendingState = PendingState(project, safeTimelineUs, playing)
        latestActiveLayerCount = activeLayerCount(project, safeTimelineUs)

        val visibleVideoClips = visibleVideoClips(project)
        PreviewClipState.updateAll(visibleVideoClips)

        if (visibleVideoClips.isEmpty()) {
            retireComposition()
            listener.onReady(0)
            return
        }
        if (outputTarget == null) return

        val nextCompositionKey = compositionKey(project)
        if (!prepared || compositionKey != nextCompositionKey) {
            rebuildComposition(project, safeTimelineUs, playing, nextCompositionKey)
            return
        }

        val nextLiveStateKey = liveStateKey(project)
        val liveChanged = liveStateKey != nextLiveStateKey
        liveStateKey = nextLiveStateKey

        if (this.playing != playing) {
            this.playing = playing
            runCatching { player.setScrubbingModeEnabled(!playing) }
            runCatching {
                if (playing) player.play() else player.pause()
            }
        }

        val cursorChanged = abs(safeTimelineUs - lastRequestedTimelineUs) >= CURSOR_CHANGE_US
        lastRequestedTimelineUs = safeTimelineUs

        if (!playing) {
            when {
                cursorChanged || forceSeek -> seekToTimeline(safeTimelineUs)
                liveChanged -> schedulePausedRefresh(safeTimelineUs)
            }
            return
        }

        if (forceSeek) {
            seekToTimeline(safeTimelineUs)
            return
        }

        // Mixed audio remains the editor's master clock. Correct the muted video CompositionPlayer
        // only when it has meaningfully drifted, instead of seeking it on every 33 ms UI tick.
        val playerUs = player.currentPosition.coerceAtLeast(0L) * 1000L
        if (abs(playerUs - safeTimelineUs) > MAX_PLAYBACK_DRIFT_US) {
            seekToTimeline(safeTimelineUs)
        }
    }

    private fun rebuildComposition(
        project: TimelineProject,
        timelineUs: Long,
        playing: Boolean,
        nextCompositionKey: String,
    ) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null

        runCatching { player.pause() }
        runCatching { player.stop() }

        PreviewClipState.clear()
        PreviewClipState.updateAll(visibleVideoClips(project))

        try {
            val composition = compositionBuilder.buildFinalVideoPreview(project)
            val maxStartUs = (project.durationUs - 1L).coerceAtLeast(0L)
            val startMs = timelineUs.coerceIn(0L, maxStartUs) / 1000L

            player.setComposition(composition, startMs)
            player.setScrubbingModeEnabled(!playing)
            player.prepare()
            if (playing) player.play()

            prepared = true
            this.playing = playing
            compositionKey = nextCompositionKey
            liveStateKey = liveStateKey(project)
            lastRequestedTimelineUs = timelineUs
        } catch (error: Throwable) {
            prepared = false
            compositionKey = null
            listener.onError(
                "Final GPU preview: ${error.message ?: "unable to prepare CompositionPlayer"}",
            )
        }
    }

    private fun schedulePausedRefresh(timelineUs: Long) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (closed || !prepared || outputTarget == null || playing) return@Runnable
            seekToTimeline(timelineUs)
        }
        refreshRunnable = runnable
        mainHandler.postDelayed(runnable, PAUSED_EDIT_REFRESH_MS)
    }

    private fun seekToTimeline(timelineUs: Long) {
        if (!prepared) return
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        runCatching { player.seekTo(timelineUs.coerceAtLeast(0L) / 1000L) }
            .onFailure { error ->
                listener.onError("Final GPU preview seek: ${error.message ?: "failed"}")
            }
    }

    private fun retireComposition() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        if (prepared) {
            runCatching { player.pause() }
            runCatching { player.stop() }
        }
        prepared = false
        playing = false
        compositionKey = null
        liveStateKey = Int.MIN_VALUE
        PreviewClipState.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        pendingState = null
        outputTarget = null
        PreviewClipState.clear()
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
    }

    private fun visibleVideoClips(project: TimelineProject): List<TimelineClip> =
        project.tracks
            .asSequence()
            .filter { track -> track.kind == TrackKind.VIDEO && !track.muted }
            .flatMap { track -> track.clips.asSequence() }
            .toList()

    private fun activeLayerCount(project: TimelineProject, timelineUs: Long): Int =
        project.tracks.count { track ->
            track.kind == TrackKind.VIDEO &&
                !track.muted &&
                track.clips.any { clip -> timelineUs in clip.timelineStartUs until clip.timelineEndUs }
        }

    /**
     * Only changes that alter media streams, sequence timing or immutable shader topology rebuild
     * CompositionPlayer. Transform/opacity/color values intentionally stay out of this key.
     */
    private fun compositionKey(project: TimelineProject): String = buildString {
        append(project.width).append('x').append(project.height).append('|')
        append(project.durationUs).append('|')
        append(project.frameRate).append('|')
        project.tracks
            .filter { track -> track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty() }
            .forEach { track ->
                append(track.id).append('{')
                track.clips.sortedBy { clip -> clip.timelineStartUs }.forEach { clip ->
                    append(clip.id).append(':')
                    append(clip.uri).append(':')
                    append(clip.timelineStartUs).append(':')
                    append(clip.sourceInUs).append(':')
                    append(clip.sourceOutUs).append(':')
                    append(SharedVideoPipeline.finalOutputPreviewPipelineKey(clip)).append(';')
                }
                append('}')
            }
    }

    private fun liveStateKey(project: TimelineProject): Int =
        project.tracks
            .filter { track -> track.kind == TrackKind.VIDEO && !track.muted }
            .map { track -> track.clips.hashCode() }
            .hashCode()

    private companion object {
        const val CURSOR_CHANGE_US = 1_000L
        const val MAX_PLAYBACK_DRIFT_US = 140_000L
        const val PAUSED_EDIT_REFRESH_MS = 24L
    }
}
