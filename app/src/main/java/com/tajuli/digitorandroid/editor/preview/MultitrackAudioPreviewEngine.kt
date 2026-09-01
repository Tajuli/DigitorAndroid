package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.render.Media3CompositionBuilder
import java.io.Closeable
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Realtime audio mixer for the editor preview.
 *
 * CompositionPlayer is reliable when each instance owns a single sequence, but multisequence
 * preview is still an evolving Media3 path. Digitor therefore gives every non-muted A track one
 * audio-only CompositionPlayer. Android's system audio mixer mixes the player outputs, while this
 * class keeps all followers aligned to the first A track (the master clock).
 *
 * Export is unaffected and still uses Transformer/Composition audio mixing.
 */
@UnstableApi
class MultitrackAudioPreviewEngine(
    context: Context,
    private val compositionBuilder: Media3CompositionBuilder = Media3CompositionBuilder(),
) : Closeable {

    data class State(
        val ready: Boolean = false,
        val activeTrackCount: Int = 0,
        val error: String? = null,
    )

    private data class TrackComposition(
        val track: TimelineTrack,
        val composition: Composition,
    )

    private data class TrackPlayer(
        val trackId: String,
        val player: CompositionPlayer,
    )

    private val appContext = context.applicationContext
    private val players = mutableListOf<TrackPlayer>()
    private val mutableState = MutableStateFlow(State())
    private var lastKnownPositionMs = 0L
    private var pendingSeekPositionMs: Long? = null
    private var lastFollowerSyncMs = 0L
    private var closed = false

    val state: StateFlow<State> = mutableState.asStateFlow()

    /**
     * Rebuilds only audio preview resources. Composition construction happens off the main thread;
     * CompositionPlayer creation/control stays on the main application thread as required by Media3.
     */
    suspend fun rebuild(
        project: TimelineProject,
        startPositionMs: Long,
        resumePlayback: Boolean,
    ) {
        ensureMainThread()
        if (closed) return

        val tracks = project.tracks.filter { track ->
            track.kind == TrackKind.AUDIO && !track.muted && track.clips.isNotEmpty()
        }
        if (tracks.isEmpty()) {
            clear()
            return
        }

        val prepared = withContext(Dispatchers.Default) {
            tracks.map { track ->
                TrackComposition(
                    track = track,
                    composition = compositionBuilder.buildAudioTrackPreview(project, track.id),
                )
            }
        }

        if (closed) return
        releasePlayers()
        lastKnownPositionMs = startPositionMs.coerceAtLeast(0L)
        pendingSeekPositionMs = lastKnownPositionMs
        mutableState.value = State(ready = false, activeTrackCount = prepared.size)

        try {
            prepared.forEach { item ->
                val player = CompositionPlayer.Builder(appContext).build()
                try {
                    player.setComposition(item.composition, lastKnownPositionMs)
                    player.prepare()
                    players += TrackPlayer(item.track.id, player)
                } catch (error: Throwable) {
                    runCatching { player.release() }
                    throw error
                }
            }
            mutableState.value = State(ready = players.isNotEmpty(), activeTrackCount = players.size)
            if (resumePlayback) play()
        } catch (error: Throwable) {
            releasePlayers()
            mutableState.value = State(
                ready = false,
                activeTrackCount = 0,
                error = error.message ?: error::class.java.simpleName,
            )
            throw error
        }
    }

    /** Start every A-track from the last requested editor playhead, never from a stale player clock. */
    fun play() {
        ensureMainThread()
        if (closed || players.isEmpty()) return
        val targetMs = (pendingSeekPositionMs ?: lastKnownPositionMs).coerceAtLeast(0L)
        lastKnownPositionMs = targetMs
        players.forEach { trackPlayer ->
            runCatching { trackPlayer.player.seekTo(targetMs) }
        }
        lastFollowerSyncMs = SystemClock.elapsedRealtime()
        players.forEach { trackPlayer ->
            runCatching { trackPlayer.player.play() }
        }
    }

    fun pause() {
        ensureMainThread()
        if (closed) return
        lastKnownPositionMs = currentPositionMs()
        players.forEach { trackPlayer ->
            runCatching { trackPlayer.player.pause() }
        }
    }

    fun seekTo(positionMs: Long) {
        ensureMainThread()
        if (closed) return
        val targetMs = positionMs.coerceAtLeast(0L)
        lastKnownPositionMs = targetMs
        pendingSeekPositionMs = targetMs
        players.forEach { trackPlayer ->
            runCatching { trackPlayer.player.seekTo(targetMs) }
        }
        lastFollowerSyncMs = SystemClock.elapsedRealtime()
    }

    /** Master A-track position drives the editor transport clock. */
    fun currentPositionMs(): Long {
        ensureMainThread()
        val master = players.firstOrNull()?.player
        val position = runCatching { master?.currentPosition }.getOrNull()
        val pending = pendingSeekPositionMs
        if (pending != null) {
            if (position != null && position >= 0L && abs(position - pending) <= SEEK_SETTLE_TOLERANCE_MS) {
                pendingSeekPositionMs = null
                lastKnownPositionMs = position
            } else {
                return pending
            }
        } else if (position != null && position >= 0L) {
            lastKnownPositionMs = position
        }
        return lastKnownPositionMs.coerceAtLeast(0L)
    }

    /**
     * Keeps A2/A3/... close to the A1 master without seeking on every UI tick. A correction is only
     * issued every few hundred milliseconds and only when drift is large enough to be audible.
     */
    fun syncFollowers() {
        ensureMainThread()
        if (closed || players.size < 2) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFollowerSyncMs < SYNC_INTERVAL_MS) return
        lastFollowerSyncMs = nowMs
        alignFollowers(currentPositionMs(), force = false)
    }

    /** Release audio decoders before export; the UI rebuilds them afterward at the same playhead. */
    fun suspendForExternalWork() {
        ensureMainThread()
        if (closed) return
        lastKnownPositionMs = currentPositionMs()
        pendingSeekPositionMs = lastKnownPositionMs
        releasePlayers()
        mutableState.value = State()
    }

    /** Clears current track players while keeping the engine reusable for later project changes. */
    fun clear() {
        ensureMainThread()
        if (closed) return
        releasePlayers()
        pendingSeekPositionMs = null
        mutableState.value = State()
    }

    private fun alignFollowers(targetMs: Long, force: Boolean) {
        players.drop(1).forEach { trackPlayer ->
            val player = trackPlayer.player
            val positionMs = runCatching { player.currentPosition }.getOrDefault(targetMs)
            if (force || abs(positionMs - targetMs) > DRIFT_TOLERANCE_MS) {
                runCatching { player.seekTo(targetMs) }
            }
        }
    }

    private fun releasePlayers() {
        players.forEach { trackPlayer ->
            runCatching { trackPlayer.player.pause() }
            runCatching { trackPlayer.player.release() }
        }
        players.clear()
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "MultitrackAudioPreviewEngine must be controlled from the main thread"
        }
    }

    override fun close() {
        ensureMainThread()
        if (closed) return
        closed = true
        releasePlayers()
        pendingSeekPositionMs = null
        mutableState.value = State()
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 250L
        const val DRIFT_TOLERANCE_MS = 80L
        const val SEEK_SETTLE_TOLERANCE_MS = 250L
    }
}
