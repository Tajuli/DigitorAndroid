package com.tajuli.digitorandroid.editor.render

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.cos
import kotlin.math.sin

/**
 * Time-aware clip transform shared by ExoPlayer preview and Transformer export.
 *
 * Position is stored in normalized canvas units: X/Y = +/-1 moves the clip by half a frame.
 * UI Y grows downward, while Media3's normalized-device Y grows upward, so Y is inverted here.
 * Scale X and Scale Y are independent so the editor can stretch width and height separately.
 *
 * Preview timing is anchored to PreviewTransformClock instead of trusting Media3's effect timestamp
 * origin. ExoPlayer may restart that origin after a seek or after effects are rebuilt. The clock
 * provides the real clip-local playhead time, while presentationTimeUs advances smoothly between
 * clock revisions. Transformer export remains purely item-local and deterministic.
 */
@UnstableApi
object ClipTransformEffect {
    fun forPreview(clip: TimelineClip): Effect? =
        create(clip, presentationTimeOffsetUs = clip.sourceInUs, usePreviewClock = true)

    fun forExport(clip: TimelineClip): Effect? =
        create(clip, presentationTimeOffsetUs = 0L, usePreviewClock = false)

    private fun create(
        clip: TimelineClip,
        presentationTimeOffsetUs: Long,
        usePreviewClock: Boolean,
    ): Effect? {
        if (clip.transform.isStaticIdentity) return null

        var previewRevision = Long.MIN_VALUE
        var previewAnchorPresentationUs = 0L
        var previewAnchorLocalUs = 0L

        return MatrixTransformation { presentationTimeUs ->
            val fallbackLocalUs = (presentationTimeUs - presentationTimeOffsetUs)
                .coerceIn(0L, clip.durationUs.coerceAtLeast(0L))

            val localUs = if (usePreviewClock) {
                val snapshot = PreviewTransformClock.snapshotFor(clip.id)
                if (snapshot == null) {
                    fallbackLocalUs
                } else {
                    if (snapshot.revision != previewRevision) {
                        previewRevision = snapshot.revision
                        previewAnchorPresentationUs = presentationTimeUs
                        previewAnchorLocalUs = snapshot.localUs
                    }
                    (previewAnchorLocalUs + (presentationTimeUs - previewAnchorPresentationUs))
                        .coerceIn(0L, clip.durationUs.coerceAtLeast(0L))
                }
            } else {
                fallbackLocalUs
            }

            val value = clip.transform.evaluate(localUs)
            val radians = Math.toRadians(value.rotationDegrees.toDouble())
            val cos = cos(radians).toFloat()
            val sin = sin(radians).toFloat()
            val tx = value.positionX
            val ty = -value.positionY

            Matrix().apply {
                // Rotation * non-uniform scale, then translation.
                setValues(
                    floatArrayOf(
                        cos * value.scaleX, -sin * value.scaleY, tx,
                        sin * value.scaleX, cos * value.scaleY, ty,
                        0f, 0f, 1f,
                    ),
                )
            }
        }
    }
}
