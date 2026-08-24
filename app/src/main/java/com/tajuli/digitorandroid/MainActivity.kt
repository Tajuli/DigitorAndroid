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
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tajuli.digitorandroid.ui.editor.DigitorEditorScreenV5
import com.tajuli.digitorandroid.ui.theme.DigitorTheme

class MainActivity : ComponentActivity() {
    private var recoverPreviewOnResume = false
    private var previewRecoveryGeneration = 0

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
        // External activities such as the system document picker can tear down the preview
        // SurfaceView while Compose and ExoPlayer themselves stay alive.
        recoverPreviewOnResume = true
        previewRecoveryGeneration++ // invalidate any recovery loop from an older resume
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!recoverPreviewOnResume) return
        recoverPreviewOnResume = false

        // Do not use a fixed delay here. Real devices can take >2 seconds to recreate the
        // SurfaceView after returning from DocumentsUI. Retry until the replacement video surface
        // is actually attached, visible and valid, then rebind the player exactly once.
        val generation = ++previewRecoveryGeneration
        schedulePreviewRecovery(generation, attempt = 0)
    }

    private fun schedulePreviewRecovery(generation: Int, attempt: Int) {
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
                if (attempt < MAX_PREVIEW_RECOVERY_ATTEMPTS) {
                    schedulePreviewRecovery(generation, attempt + 1)
                } else {
                    Log.w(
                        PREVIEW_RECOVERY_TAG,
                        "Timed out waiting for a valid preview surface; playerView=${playerView != null}, " +
                            "mediaItems=${player?.mediaItemCount ?: 0}, focus=${window.decorView.hasWindowFocus()}",
                    )
                }
                return@postDelayed
            }

            recoverVideoDecoder(playerView)
        }, if (attempt == 0) 0L else PREVIEW_RECOVERY_RETRY_MS)
    }

    private fun isVideoSurfaceReady(playerView: PlayerView): Boolean {
        return when (val surface = playerView.videoSurfaceView) {
            is SurfaceView -> surface.isAttachedToWindow && surface.holder.surface.isValid
            is TextureView -> surface.isAttachedToWindow && surface.isAvailable
            null -> false
            else -> surface.isAttachedToWindow && surface.isShown
        }
    }

    private fun recoverVideoDecoder(playerView: PlayerView) {
        val player = playerView.player ?: return
        if (player.mediaItemCount == 0) return

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val resumePlayback = player.playWhenReady

        Log.d(
            PREVIEW_RECOVERY_TAG,
            "Recovering video on valid replacement surface at ${positionMs}ms; " +
                "state=${player.playbackState}, playing=$resumePlayback",
        )

        // Explicitly detach/reattach PlayerView first so ExoPlayer receives the new Surface object,
        // then restart the codec while preserving the exact timeline frame and play/pause state.
        player.pause()
        playerView.player = null
        playerView.player = player
        player.stop()
        player.prepare()
        player.seekTo(positionMs)
        if (resumePlayback) player.play()
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
        const val PREVIEW_RECOVERY_RETRY_MS = 100L
        const val MAX_PREVIEW_RECOVERY_ATTEMPTS = 60 // up to about six seconds on slow devices
    }
}
