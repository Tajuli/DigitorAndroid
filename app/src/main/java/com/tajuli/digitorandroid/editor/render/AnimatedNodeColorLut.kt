package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * Timestamp-aware 3D LUT used for Correction and Color.
 *
 * In preview mode the long-lived GL effect resolves the latest clip snapshot on every frame. This
 * makes correction/color controls visible immediately without stop/setComposition/prepare. Export
 * stays deterministic and snapshot-based.
 */
@UnstableApi
internal class AnimatedNodeColorLut(
    private val clip: TimelineClip,
    private val size: Int,
    private val preview: Boolean,
) : ColorLut {
    private var textureId = Format.NO_VALUE
    private var lastSourceUs = Long.MIN_VALUE
    private var lastVisualRevision = Long.MIN_VALUE
    private var previewRevision = Long.MIN_VALUE
    private var previewAnchorPresentationUs = 0L
    private var previewAnchorSourceUs = clip.sourceInUs

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val currentClip = currentClip()
        val sourceUs = sourceTimeUs(currentClip, presentationTimeUs)
        val visualRevision = visualRevision(currentClip)
        val timeChanged = currentClip.nodeAnimations.hasColorAnimation && sourceUs != lastSourceUs
        if (textureId == Format.NO_VALUE || visualRevision != lastVisualRevision || timeChanged) {
            val bitmap = cubeBitmap(
                SharedColorPipeline.buildCubeAtSourceTime(currentClip, size, sourceUs),
            )
            try {
                if (textureId == Format.NO_VALUE) {
                    textureId = GlUtil.createTexture(bitmap)
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    GlUtil.checkGlError()
                }
            } finally {
                bitmap.recycle()
            }
            lastSourceUs = sourceUs
            lastVisualRevision = visualRevision
        }
        return textureId
    }

    override fun getLength(presentationTimeUs: Long): Int = size

    override fun release() {
        if (textureId != Format.NO_VALUE) {
            GlUtil.deleteTexture(textureId)
            textureId = Format.NO_VALUE
        }
    }

    private fun currentClip(): TimelineClip =
        if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip

    private fun visualRevision(current: TimelineClip): Long {
        // Immutable data classes make hashCode a cheap stable change token for static grades. Keep
        // animation revision separate so keyed updates also invalidate the LUT.
        var result = current.nodeGraph.hashCode().toLong()
        result = result * 31L + current.colorGrade.hashCode().toLong()
        result = result * 31L + current.nodeAnimations.revision
        return result
    }

    private fun sourceTimeUs(current: TimelineClip, presentationTimeUs: Long): Long {
        val minSource = current.sourceInUs.coerceAtLeast(0L)
        val maxSource = current.sourceOutUs.coerceAtLeast(minSource)
        if (!preview) {
            return (current.sourceInUs + presentationTimeUs.coerceAtLeast(0L))
                .coerceIn(minSource, maxSource)
        }

        val snapshot = PreviewTransformClock.snapshotFor(current.id)
        if (snapshot == null) {
            return presentationTimeUs.coerceIn(minSource, maxSource)
        }
        if (snapshot.revision != previewRevision) {
            previewRevision = snapshot.revision
            previewAnchorPresentationUs = presentationTimeUs
            previewAnchorSourceUs = current.sourceInUs + snapshot.localUs
        }
        return (previewAnchorSourceUs + (presentationTimeUs - previewAnchorPresentationUs))
            .coerceIn(minSource, maxSource)
    }

    private fun cubeBitmap(cube: Array<Array<IntArray>>): Bitmap {
        val n = cube.size
        val colors = IntArray(n * n * n)
        for (r in 0 until n) {
            for (g in 0 until n) {
                for (b in 0 until n) {
                    colors[b + n * (g + n * r)] = cube[r][g][b]
                }
            }
        }
        return Bitmap.createBitmap(colors, n, n * n, Bitmap.Config.ARGB_8888)
    }
}
