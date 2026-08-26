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
 * CompositionPlayer owns decoder surfaces, stream transitions, frame release, scrubbing and final
 * Surface presentation. The input Composition mirrors export: one sequence per Digitor V track,
 * export-resolution 33^3 LUT/node processing and Resolve compositor geometry/opacity.
 *
 * Important: Media3 scrubbing is not the same thing as a normally paused Player. A player that is
 * paused first and then put into scrubbing mode may acknowledge seek positions without decoding and
 * releasing the requested video frame. For editor-paused state we therefore keep playWhenReady=true
 * and enable scrubbing mode. Media3 suppresses timeline progression with
 * PLAYBACK_SUPPRESSION_REASON_SCRUBBING, while seekTo() still decodes/renders the target frame.
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
            // Surface detach is allowed to pause the underlying player. The desired editor transport
            // state stays in pendingState/playing and is re-armed when a valid Surface returns.
            runCatching { player.pause() }
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

        // Always verify transport mode. This also re-arms scrubbing after a Surface detach, where
        // the underlying player was intentionally paused while the editor state stayed unchanged.
        this.playing = playing
        applyEditorTransportMode(playing)

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
            player.prepare()

            prepared = true
            this.playing = playing
            compositionKey = nextCompositionKey
            liveStateKey = liveStateKey(project)
            lastRequestedTimelineUs = timelineUs

            // Arm transport only after prepare(). For a paused editor this deliberately calls play()
            // first and then enables scrubbing suppression; doing pause()->scrub makes Media3 drop
            // the seek-render work on real devices.
            applyEditorTransportMode(playing)
        } catch (error: Throwable) {
            prepared = false
            compositionKey = null
            listener.onError(
                "Final GPU preview: ${error.message ?: "unable to prepare CompositionPlayer"}",
            )
        }
    }

    /**
     * Maps Digitor's two transport states onto Media3's three relevant states.
     *
     * Editor playing  -> normal playWhenReady playback.
     * Editor paused   -> playWhenReady=true + scrubbing suppression, so timeline stays still but
     *                    seekTo() continues to decode and present requested frames.
     */
    private fun applyEditorTransportMode(editorPlaying: Boolean) {
        if (!prepared) return

        runCatching {
            if (editorPlaying) {
                if (player.isScrubbingModeEnabled) {
                    player.setScrubbingModeEnabled(false)
                }
                player.play()
            } else {
                if (player.isScrubbingModeEnabled) {
                    // A Surface detach may have called pause() while scrubbing remained enabled.
                    // Recreate the required playWhenReady=true -> scrubbing transition in that case.
                    if (!player.playWhenReady) {
                        player.setScrubbingModeEnabled(false)
                        player.play()
                        player.setScrubbingModeEnabled(true)
                    }
                } else {
                    player.play()
                    player.setScrubbingModeEnabled(true)
                }
            }
        }.onFailure { error ->
            listener.onError("Final GPU preview transport: ${error.message ?: "unavailable"}")
        }
    }

    private fun schedulePausedRefresh(timelineUs: Long) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (closed || !prepared || outputTarget == null || playing) return@Runnable
            applyEditorTransportMode(editorPlaying = false)
            seekToTimeline(timelineUs)
        }
        refreshRunnable = runnable
        mainHandler.postDelayed(runnable, PAUSED_EDIT_REFRESH_MS)
    }

    private fun seekToTimeline(timelineUs: Long) {
        if (!prepared) return
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null

        // Cursor seeks must execute with active scrubbing transport when the editor is paused.
        if (!playing) applyEditorTransportMode(editorPlaying = false)

        runCatching { player.seekTo(timelineUs.coerceAtLeast(0L) / 1000L) }
            .onFailure { error ->
                listener.onError("Final GPU preview seek: ${error.message ?: "failed"}")
            }
    }

    private fun retireComposition() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        if (prepared) {
            runCatching {
                if (player.isScrubbingModeEnabled) player.setScrubbingModeEnabled(false)
            }
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
        runCatching {
            if (player.isScrubbingModeEnabled) player.setScrubbingModeEnabled(false)
        }
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
