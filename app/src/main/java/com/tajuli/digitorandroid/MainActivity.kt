package com.tajuli.digitorandroid

import android.os.Bundle
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
        // External activities such as the system document picker can destroy the SurfaceView while
        // keeping the Compose/ExoPlayer state alive. Remember that the next resume needs a codec
        // re-bind even when the visible timeline clip itself did not change.
        recoverPreviewOnResume = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!recoverPreviewOnResume) return
        recoverPreviewOnResume = false

        // Let Compose/AndroidView attach the replacement PlayerView/SurfaceView first. On some
        // Unisoc devices ExoPlayer stays READY against the old, disconnected producer otherwise,
        // leaving a permanently black preview until the media item changes.
        window.decorView.postDelayed({
            val playerView = findPlayerView(window.decorView) ?: return@postDelayed
            recoverVideoDecoder(playerView.player)
        }, PREVIEW_SURFACE_RECOVERY_DELAY_MS)
    }

    private fun recoverVideoDecoder(player: Player?) {
        player ?: return
        if (player.mediaItemCount == 0) return

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val resumePlayback = player.playWhenReady

        // Force MediaCodec to drop the stale output surface and bind to PlayerView's newly-created
        // surface. stop() keeps the playlist; prepare()+seek restores the exact preview frame.
        player.pause()
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
        const val PREVIEW_SURFACE_RECOVERY_DELAY_MS = 180L
    }
}
