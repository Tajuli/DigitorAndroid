package com.tajuli.digitorandroid.editor.render

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.cos
import kotlin.math.sin

/**
 * Time-aware clip transform shared by ExoPlayer preview and Transformer export.
 *
 * Position is stored in normalized canvas units: X/Y = +/-1 moves the clip by half a frame.
 * UI Y grows downward, while Media3's normalized-device Y grows upward, so Y is inverted here.
 */
@UnstableApi
object ClipTransformEffect {
    fun forPreview(clip: TimelineClip): Effect? =
        create(clip, presentationTimeOffsetUs = clip.sourceInUs)

    fun forExport(clip: TimelineClip): Effect? =
        create(clip, presentationTimeOffsetUs = 0L)

    private fun create(clip: TimelineClip, presentationTimeOffsetUs: Long): Effect? {
        if (clip.transform.isStaticIdentity) return null

        return MatrixTransformation { presentationTimeUs ->
            val localUs = (presentationTimeUs - presentationTimeOffsetUs)
                .coerceIn(0L, clip.durationUs.coerceAtLeast(0L))
            val value = clip.transform.evaluate(localUs)
            val radians = Math.toRadians(value.rotationDegrees.toDouble())
            val c = cos(radians).toFloat() * value.scale
            val s = sin(radians).toFloat() * value.scale
            val tx = value.positionX
            val ty = -value.positionY

            Matrix().apply {
                setValues(
                    floatArrayOf(
                        c, -s, tx,
                        s, c, ty,
                        0f, 0f, 1f,
                    ),
                )
            }
        }
    }
}
