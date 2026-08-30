package com.tajuli.digitorandroid.editor.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry

/**
 * Timestamp-aware 3D LUT used for Correction and Color.
 *
 * In preview mode the long-lived GL effect resolves the latest clip snapshot on every frame. This
 * makes correction/color controls and camera input-profile changes visible immediately without
 * stop/setComposition/prepare. Export stays deterministic and snapshot-based. Both modes use the
 * exact same composition-time to source-time mapping from [ParityRenderContract].
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

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        val currentClip = currentClip()
        val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
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
        // animation revision separate so keyed updates also invalidate the LUT. Input profile is
        // part of the visual transform and therefore must invalidate the cached cube as well.
        var result = current.nodeGraph.hashCode().toLong()
        result = result * 31L + current.colorGrade.hashCode().toLong()
        result = result * 31L + (current.inputColorProfileV1?.hashCode()?.toLong() ?: 0L)
        result = result * 31L + current.nodeAnimations.revision
        return result
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
