package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import com.tajuli.digitorandroid.editor.model.PreviewClipState
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Timestamp-aware 3D LUT used for Correction and Color keyframes.
 *
 * Export maps Media3 item-local timestamps to the original source timeline. Preview uses the same
 * playhead clock as transform keyframes so seeks/effect rebuilds cannot restart the animation at
 * zero. In persistent preview, the LUT also reads the newest clip snapshot so ordinary grading
 * changes update the existing GL texture instead of rebuilding MediaCodec/VideoGraph.
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
    private var lastGraphHash = Int.MIN_VALUE
    private var previewRevision = Long.MIN_VALUE
    private var previewAnchorPresentationUs = 0L
    private var previewAnchorSourceUs = clip.sourceInUs

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val currentClip = if (preview) PreviewClipState.snapshot(clip.id) ?: clip else clip
        val sourceUs = sourceTimeUs(currentClip, presentationTimeUs)
        val animationRevision = currentClip.nodeAnimations.revision
        val graphHash = currentClip.nodeGraph.hashCode()
        val timeChanged = currentClip.nodeAnimations.hasColorAnimation && sourceUs != lastSourceUs
        val parametersChanged = graphHash != lastGraphHash || animationRevision != lastRevision
        if (textureId == Format.NO_VALUE || parametersChanged || timeChanged) {
            val bitmap = cubeBitmap(SharedColorPipeline.buildCubeAtSourceTime(currentClip, size, sourceUs))
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
            lastGraphHash = graphHash
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

    private fun sourceTimeUs(currentClip: TimelineClip, presentationTimeUs: Long): Long {
        val minSource = currentClip.sourceInUs.coerceAtLeast(0L)
        val maxSource = currentClip.sourceOutUs.coerceAtLeast(minSource)
        if (!preview) {
            return (currentClip.sourceInUs + presentationTimeUs.coerceAtLeast(0L))
                .coerceIn(minSource, maxSource)
        }

        val snapshot = PreviewTransformClock.snapshotFor(clip.id)
        if (snapshot == null) {
            return presentationTimeUs.coerceIn(minSource, maxSource)
        }
        if (snapshot.revision != previewRevision) {
            previewRevision = snapshot.revision
            previewAnchorPresentationUs = presentationTimeUs
            previewAnchorSourceUs = currentClip.sourceInUs + snapshot.localUs
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
