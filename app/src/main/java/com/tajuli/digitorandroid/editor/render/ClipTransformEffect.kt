package com.tajuli.digitorandroid.editor.render

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.tajuli.digitorandroid.editor.model.PreviewClipState
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import kotlin.math.cos
import kotlin.math.sin

/**
 * Time-aware clip transform shared by ExoPlayer preview and Transformer export.
 *
 * Preview deliberately keeps one matrix effect attached even when the initial transform is
 * identity. The effect reads the newest clip snapshot from [PreviewClipState] every frame, so
 * position/scale/rotation edits can reach the GPU without rebuilding or re-preparing ExoPlayer.
 * Export stays immutable and deterministic.
 */
@UnstableApi
object ClipTransformEffect {
    fun forPreview(clip: TimelineClip): Effect =
        create(
            clip = clip,
            presentationTimeOffsetUs = clip.sourceInUs,
            usePreviewClock = true,
            livePreview = true,
        )

    fun forExport(clip: TimelineClip): Effect? {
        if (clip.transform.isStaticIdentity) return null
        return create(
            clip = clip,
            presentationTimeOffsetUs = 0L,
            usePreviewClock = false,
            livePreview = false,
        )
    }

    private fun create(
        clip: TimelineClip,
        presentationTimeOffsetUs: Long,
        usePreviewClock: Boolean,
        livePreview: Boolean,
    ): Effect {
        var previewRevision = Long.MIN_VALUE
        var previewAnchorPresentationUs = 0L
        var previewAnchorLocalUs = 0L

        return MatrixTransformation { presentationTimeUs ->
            val currentClip = if (livePreview) PreviewClipState.snapshot(clip.id) ?: clip else clip
            val fallbackLocalUs = (presentationTimeUs - presentationTimeOffsetUs)
                .coerceIn(0L, currentClip.durationUs.coerceAtLeast(0L))

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
                        .coerceIn(0L, currentClip.durationUs.coerceAtLeast(0L))
                }
            } else {
                fallbackLocalUs
            }

            val value = currentClip.transform.evaluate(localUs)
            val radians = Math.toRadians(value.rotationDegrees.toDouble())
            val cos = cos(radians).toFloat()
            val sin = sin(radians).toFloat()
            val tx = value.positionX
            val ty = -value.positionY

            Matrix().apply {
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
