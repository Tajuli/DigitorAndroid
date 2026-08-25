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

/**
 * Timestamp-aware 3D LUT used for Correction and Color keyframes.
 *
 * Export maps Media3 item-local timestamps to the original source timeline. Preview uses the same
 * playhead clock as transform keyframes so seeks/effect rebuilds cannot restart the animation at
 * zero. Static grades build only once; animated grades update the existing GL texture per frame.
 */
@UnstableApi
internal class AnimatedNodeColorLut(
    private val clip: TimelineClip,
    private val size: Int,
    private val preview: Boolean,
) : ColorLut {
    private var textureId = Format.NO_VALUE
    private var lastSourceUs = Long.MIN_VALUE
    private var lastRevision = Long.MIN_VALUE
    private var previewRevision = Long.MIN_VALUE
    private var previewAnchorPresentationUs = 0L
    private var previewAnchorSourceUs = clip.sourceInUs

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val sourceUs = sourceTimeUs(presentationTimeUs)
        val animationRevision = clip.nodeAnimations.revision
        val timeChanged = clip.nodeAnimations.hasColorAnimation && sourceUs != lastSourceUs
        if (textureId == Format.NO_VALUE || animationRevision != lastRevision || timeChanged) {
            val bitmap = cubeBitmap(SharedColorPipeline.buildCubeAtSourceTime(clip, size, sourceUs))
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
            lastRevision = animationRevision
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

    private fun sourceTimeUs(presentationTimeUs: Long): Long {
        val minSource = clip.sourceInUs.coerceAtLeast(0L)
        val maxSource = clip.sourceOutUs.coerceAtLeast(minSource)
        if (!preview) {
            return (clip.sourceInUs + presentationTimeUs.coerceAtLeast(0L)).coerceIn(minSource, maxSource)
        }

        val snapshot = PreviewTransformClock.snapshotFor(clip.id)
        if (snapshot == null) {
            return presentationTimeUs.coerceIn(minSource, maxSource)
        }
        if (snapshot.revision != previewRevision) {
            previewRevision = snapshot.revision
            previewAnchorPresentationUs = presentationTimeUs
            previewAnchorSourceUs = clip.sourceInUs + snapshot.localUs
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
