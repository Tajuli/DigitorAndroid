package com.tajuli.digitorandroid

import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.ui.editor.DigitorEditorScreenV5
import com.tajuli.digitorandroid.ui.theme.DigitorTheme

class MainActivity : ComponentActivity() {
    private var recoverPreviewOnResume = false
    private var previewRecoveryGeneration = 0
    private var previewRecoveryListener: Player.Listener? = null
    private var previewRecoveryPlayer: Player? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DigitorTheme {
                DigitorEditorScreenV5()
            }
        }
    }

    override fun onPause() {
        recoverPreviewOnResume = true
        previewRecoveryGeneration++
        cancelFrameConfirmation()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!recoverPreviewOnResume) return
        recoverPreviewOnResume = false

        val generation = ++previewRecoveryGeneration
        schedulePreviewRecovery(generation, surfaceAttempt = 0, frameAttempt = 0)
    }

    private fun schedulePreviewRecovery(
        generation: Int,
        surfaceAttempt: Int,
        frameAttempt: Int,
    ) {
        if (generation != previewRecoveryGeneration || isFinishing || isDestroyed) return

        window.decorView.postDelayed({
            if (generation != previewRecoveryGeneration || isFinishing || isDestroyed) {
                return@postDelayed
            }

            val playerView = findPlayerView(window.decorView)
            val player = playerView?.player
            val ready = playerView != null &&
                player != null &&
                player.mediaItemCount > 0 &&
                window.decorView.hasWindowFocus() &&
                playerView.isAttachedToWindow &&
                playerView.isShown &&
                isVideoSurfaceReady(playerView)

            if (!ready) {
                if (surfaceAttempt < MAX_SURFACE_WAIT_ATTEMPTS) {
                    schedulePreviewRecovery(
                        generation = generation,
                        surfaceAttempt = surfaceAttempt + 1,
                        frameAttempt = frameAttempt,
                    )
                } else {
                    Log.w(
                        PREVIEW_RECOVERY_TAG,
                        "Timed out waiting for preview surface; playerView=${playerView != null}, " +
                            "mediaItems=${player?.mediaItemCount ?: 0}, focus=${window.decorView.hasWindowFocus()}",
                    )
                }
                return@postDelayed
            }

            recoverAndConfirmFirstFrame(generation, playerView, frameAttempt)
        }, if (surfaceAttempt == 0) 0L else SURFACE_WAIT_RETRY_MS)
    }

    private fun isVideoSurfaceReady(playerView: PlayerView): Boolean {
        return when (val surface = playerView.videoSurfaceView) {
            is SurfaceView -> surface.isAttachedToWindow && surface.holder.surface.isValid
            is TextureView -> surface.isAttachedToWindow && surface.isAvailable
            null -> false
            else -> surface.isAttachedToWindow && surface.isShown
        }
    }

    private fun recoverAndConfirmFirstFrame(
        generation: Int,
        playerView: PlayerView,
        frameAttempt: Int,
    ) {
        if (generation != previewRecoveryGeneration) return
        val player = playerView.player ?: return
        if (player.mediaItemCount == 0) return

        cancelFrameConfirmation()

        val rawPositionMs = player.currentPosition.coerceAtLeast(0L)
        val knownDurationMs = player.duration
        val safePositionMs = if (knownDurationMs != C.TIME_UNSET && knownDurationMs > 0L) {
            rawPositionMs.coerceAtMost((knownDurationMs - 1L).coerceAtLeast(0L))
        } else {
            rawPositionMs
        }
        val resumePlayback = player.playWhenReady

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (generation != previewRecoveryGeneration) {
                    player.removeListener(this)
                    return
                }
                Log.d(
                    PREVIEW_RECOVERY_TAG,
                    "Rendered first frame after recovery attempt=${frameAttempt + 1}; " +
                        "position=${player.currentPosition}ms state=${player.playbackState}",
                )
                if (previewRecoveryListener === this) {
                    cancelFrameConfirmation()
                } else {
                    player.removeListener(this)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    PREVIEW_RECOVERY_TAG,
                    "Preview player error during recovery attempt=${frameAttempt + 1}: " +
                        (error.message ?: error.errorCodeName),
                    error,
                )
            }
        }
        previewRecoveryPlayer = player
        previewRecoveryListener = listener
        player.addListener(listener)

        Log.d(
            PREVIEW_RECOVERY_TAG,
            "Recovering video and waiting for first frame; attempt=${frameAttempt + 1}, " +
                "position=${rawPositionMs}ms safePosition=${safePositionMs}ms, " +
                "duration=${knownDurationMs}ms state=${player.playbackState}, playing=$resumePlayback",
        )

        player.pause()
        playerView.player = null
        playerView.player = player

        // Bind the actual replacement surface explicitly. PlayerView normally does this too, but
        // direct binding avoids OEM races where the new SurfaceView is visible while the codec is
        // still attached to the producer from the old picker lifecycle.
        when (val surface = playerView.videoSurfaceView) {
            is SurfaceView -> player.setVideoSurfaceView(surface)
            is TextureView -> player.setVideoTextureView(surface)
        }

        player.stop()
        player.prepare()
        player.seekTo(safePositionMs)
        if (resumePlayback) player.play()

        window.decorView.postDelayed({
            if (generation != previewRecoveryGeneration) {
                if (previewRecoveryListener === listener) cancelFrameConfirmation()
                return@postDelayed
            }
            if (previewRecoveryListener !== listener) return@postDelayed

            cancelFrameConfirmation()
            if (frameAttempt < MAX_FRAME_RECOVERY_ATTEMPTS) {
                Log.w(
                    PREVIEW_RECOVERY_TAG,
                    "No rendered first frame after ${FIRST_FRAME_TIMEOUT_MS}ms; retrying",
                )
                // Re-resolve PlayerView and its surface because the project import may have caused
                // another Compose/PlayerView update while this attempt was waiting.
                schedulePreviewRecovery(
                    generation = generation,
                    surfaceAttempt = 0,
                    frameAttempt = frameAttempt + 1,
                )
            } else {
                Log.e(
                    PREVIEW_RECOVERY_TAG,
                    "Preview recovery failed: no rendered first frame after ${frameAttempt + 1} attempts",
                )
            }
        }, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelFrameConfirmation() {
        val listener = previewRecoveryListener
        val player = previewRecoveryPlayer
        if (listener != null && player != null) {
            runCatching { player.removeListener(listener) }
        }
        previewRecoveryListener = null
        previewRecoveryPlayer = null
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val PREVIEW_RECOVERY_TAG = "DigitorPreviewRecovery"
        const val SURFACE_WAIT_RETRY_MS = 100L
        const val MAX_SURFACE_WAIT_ATTEMPTS = 60
        const val FIRST_FRAME_TIMEOUT_MS = 700L
        const val MAX_FRAME_RECOVERY_ATTEMPTS = 6
    }
}
