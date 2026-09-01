package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionPresetV24
import kotlin.math.PI
import kotlin.math.sin

/** Preset-specific geometry layered on top of the V22 two-source compositor. */
internal object TransitionPresetMotionV24 {
    fun incoming(base: ResolveOverlayState, presetId: String?, progress: Float): ResolveOverlayState? {
        val p = smooth(progress)
        val pulse = sin((PI * p).toFloat()).coerceAtLeast(0f)
        return when (presetId) {
            TransitionPresetV24.PULL_IN -> base.copy(scaleX = base.scaleX * (.62f + .38f * p), scaleY = base.scaleY * (.62f + .38f * p))
            TransitionPresetV24.PULL_OUT -> base.copy(scaleX = base.scaleX * (1.45f - .45f * p), scaleY = base.scaleY * (1.45f - .45f * p))
            TransitionPresetV24.ZOOM_LENS -> base.copy(scaleX = base.scaleX * (.70f + .30f * p), scaleY = base.scaleY * (.70f + .30f * p), rotationDegrees = base.rotationDegrees - 2.5f * (1f - p))
            TransitionPresetV24.CAMERA_ZOOM -> base.copy(scaleX = base.scaleX * (.82f + .18f * p), scaleY = base.scaleY * (.82f + .18f * p))

            TransitionPresetV24.SWIPE_LEFT, TransitionPresetV24.SLIDE_LEFT -> base.copy(backgroundX = base.backgroundX + 2f * (1f - p))
            TransitionPresetV24.SWIPE_RIGHT, TransitionPresetV24.SLIDE_RIGHT -> base.copy(backgroundX = base.backgroundX - 2f * (1f - p))
            TransitionPresetV24.SWIPE_UP, TransitionPresetV24.SLIDE_UP -> base.copy(backgroundY = base.backgroundY - 2f * (1f - p))
            TransitionPresetV24.SWIPE_DOWN, TransitionPresetV24.SLIDE_DOWN -> base.copy(backgroundY = base.backgroundY + 2f * (1f - p))

            TransitionPresetV24.ROTATE -> base.copy(rotationDegrees = base.rotationDegrees - 180f * (1f - p), scaleX = base.scaleX * (.90f + .10f * p), scaleY = base.scaleY * (.90f + .10f * p))
            TransitionPresetV24.SHAKE -> base.copy(backgroundX = base.backgroundX + shake(p, 7f) * .08f, backgroundY = base.backgroundY + shake(p + .17f, 9f) * .05f)
            TransitionPresetV24.CAMERA_SHAKE -> base.copy(backgroundX = base.backgroundX + shake(p, 11f) * .12f, backgroundY = base.backgroundY + shake(p + .31f, 13f) * .08f, rotationDegrees = base.rotationDegrees + shake(p + .08f, 8f) * 2.2f)
            TransitionPresetV24.STRETCH -> base.copy(scaleX = base.scaleX * (.55f + .45f * p), scaleY = base.scaleY * (1.28f - .28f * p))
            TransitionPresetV24.WARP -> base.copy(scaleX = base.scaleX * (.76f + .24f * p + pulse * .10f), scaleY = base.scaleY * (1.20f - .20f * p - pulse * .08f), rotationDegrees = base.rotationDegrees - 8f * (1f - p))
            TransitionPresetV24.CUBE_3D -> base.copy(backgroundX = base.backgroundX + 1.65f * (1f - p), scaleX = base.scaleX * (.68f + .32f * p), rotationDegrees = base.rotationDegrees - 76f * (1f - p))
            TransitionPresetV24.PAGE_TURN -> base.copy(backgroundX = base.backgroundX + .70f * (1f - p), scaleX = base.scaleX * (.82f + .18f * p), rotationDegrees = base.rotationDegrees - 42f * (1f - p))
            TransitionPresetV24.VELOCITY -> base.copy(backgroundX = base.backgroundX + 2.35f * (1f - p), scaleX = base.scaleX * (.90f + .10f * p), rotationDegrees = base.rotationDegrees + 2.8f * (1f - p))

            else -> null
        }
    }

    fun outgoing(base: ResolveOverlayState, presetId: String?, progress: Float): ResolveOverlayState? {
        val p = smooth(progress)
        val fade = (1f - p).coerceIn(0f, 1f)
        val pulse = sin((PI * p).toFloat()).coerceAtLeast(0f)
        return when (presetId) {
            TransitionPresetV24.PULL_IN -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f + .34f * p), scaleY = base.scaleY * (1f + .34f * p))
            TransitionPresetV24.PULL_OUT -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f - .34f * p), scaleY = base.scaleY * (1f - .34f * p))
            TransitionPresetV24.ZOOM_LENS -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f + .22f * p), scaleY = base.scaleY * (1f + .22f * p), rotationDegrees = base.rotationDegrees + 2.5f * p)
            TransitionPresetV24.CAMERA_ZOOM -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f + .12f * p), scaleY = base.scaleY * (1f + .12f * p))

            TransitionPresetV24.SWIPE_LEFT, TransitionPresetV24.SLIDE_LEFT -> base.copy(backgroundX = base.backgroundX - 2f * p)
            TransitionPresetV24.SWIPE_RIGHT, TransitionPresetV24.SLIDE_RIGHT -> base.copy(backgroundX = base.backgroundX + 2f * p)
            TransitionPresetV24.SWIPE_UP, TransitionPresetV24.SLIDE_UP -> base.copy(backgroundY = base.backgroundY + 2f * p)
            TransitionPresetV24.SWIPE_DOWN, TransitionPresetV24.SLIDE_DOWN -> base.copy(backgroundY = base.backgroundY - 2f * p)

            TransitionPresetV24.ROTATE -> base.copy(alphaScale = base.alphaScale * fade, rotationDegrees = base.rotationDegrees + 180f * p, scaleX = base.scaleX * (1f - .10f * p), scaleY = base.scaleY * (1f - .10f * p))
            TransitionPresetV24.SHAKE -> base.copy(alphaScale = base.alphaScale * fade, backgroundX = base.backgroundX + shake(p, 7f) * .08f, backgroundY = base.backgroundY + shake(p + .17f, 9f) * .05f)
            TransitionPresetV24.CAMERA_SHAKE -> base.copy(alphaScale = base.alphaScale * fade, backgroundX = base.backgroundX + shake(p, 11f) * .12f, backgroundY = base.backgroundY + shake(p + .31f, 13f) * .08f, rotationDegrees = base.rotationDegrees + shake(p + .08f, 8f) * 2.2f)
            TransitionPresetV24.STRETCH -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f + .45f * p), scaleY = base.scaleY * (1f - .22f * p))
            TransitionPresetV24.WARP -> base.copy(alphaScale = base.alphaScale * fade, scaleX = base.scaleX * (1f + .20f * p + pulse * .10f), scaleY = base.scaleY * (1f - .12f * p - pulse * .08f), rotationDegrees = base.rotationDegrees + 8f * p)
            TransitionPresetV24.CUBE_3D -> base.copy(alphaScale = base.alphaScale * fade, backgroundX = base.backgroundX - 1.65f * p, scaleX = base.scaleX * (1f - .32f * p), rotationDegrees = base.rotationDegrees + 76f * p)
            TransitionPresetV24.PAGE_TURN -> base.copy(alphaScale = base.alphaScale * fade, backgroundX = base.backgroundX - .70f * p, scaleX = base.scaleX * (1f - .18f * p), rotationDegrees = base.rotationDegrees + 42f * p)
            TransitionPresetV24.VELOCITY -> base.copy(alphaScale = base.alphaScale * fade, backgroundX = base.backgroundX - 2.35f * p, scaleX = base.scaleX * (1f + .08f * p), rotationDegrees = base.rotationDegrees - 2.8f * p)

            else -> null
        }
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun shake(value: Float, frequency: Float): Float =
        sin((value.coerceIn(0f, 1f) * frequency * PI * 2.0).toFloat()) * (1f - value.coerceIn(0f, 1f))
}
