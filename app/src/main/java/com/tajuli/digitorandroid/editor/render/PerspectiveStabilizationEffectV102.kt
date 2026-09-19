package com.tajuli.digitorandroid.editor.render

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.evaluatePerspectiveV102
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * V102 Resolve-style Perspective stabilization.
 *
 * This is a real 3x3 projective transform in Media3's NDC vertex shader, not a Perspective label
 * mapped to affine rotation/scale. The effect runs before the ordinary clip transform/compositor so
 * projective stabilization has preview/export parity on both single-input and multitrack routes.
 */
@UnstableApi
object PerspectiveStabilizationEffectV102 {
    fun forPreview(clip: TimelineClip): Effect? =
        create(
            clip = clip,
            presentationTimeOffsetUs = clip.sourceInUs,
            usePreviewClock = true,
            liveProject = true,
        )

    fun forExport(clip: TimelineClip): Effect? =
        create(
            clip = clip,
            presentationTimeOffsetUs = 0L,
            usePreviewClock = false,
            liveProject = false,
        )

    /** Composition item effects are item-local; the compositor handles timeline placement later. */
    fun forCompositedItem(clip: TimelineClip): Effect? =
        create(
            clip = clip,
            presentationTimeOffsetUs = 0L,
            usePreviewClock = false,
            liveProject = false,
        )

    fun forCompositedPreview(clip: TimelineClip): Effect? =
        create(
            clip = clip,
            presentationTimeOffsetUs = 0L,
            usePreviewClock = false,
            liveProject = true,
        )

    private fun create(
        clip: TimelineClip,
        presentationTimeOffsetUs: Long,
        usePreviewClock: Boolean,
        liveProject: Boolean,
    ): Effect? {
        val seed = clip.stabilizationV90?.normalized() ?: return null
        if (
            seed.analysisVersionV93 < 102 ||
            !seed.hasAnalysis ||
            seed.samples.none { it.perspectivePathV102 != null }
        ) {
            return null
        }

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

            val liveClip = if (liveProject) {
                PreviewProjectRegistry.project()?.clip(clip.id) ?: clip
            } else {
                clip
            }
            val stabilization = liveClip.stabilizationV90?.normalized()
            val sourceTimeUs = liveClip.sourceInUs + localUs
            val transform = stabilization?.evaluatePerspectiveV102(sourceTimeUs)
            Matrix().apply {
                if (transform == null) {
                    reset()
                } else {
                    setValues(transform.matrixValues)
                }
            }
        }
    }
}
