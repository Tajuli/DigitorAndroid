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
 * Preview keeps one GL LUT texture alive and updates it only when color-relevant data changes.
 * V36 intentionally excludes NodeEffect metadata from the visual revision token: creator filter and
 * beauty markers are evaluated by their own persistent high-precision stages and must not trigger a
 * 33^3 cube rebuild on every tap/slider tick. This is a major part of CapCut-class filter response.
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
        var result = current.nodeGraph.edges.hashCode().toLong()
        current.nodeGraph.nodes.forEach { node ->
            // Node effects deliberately do not participate. LUT generation does not read them.
            result = result * 31L + node.id.hashCode().toLong()
            result = result * 31L + node.kind.hashCode().toLong()
            result = result * 31L + node.corrections.hashCode().toLong()
            result = result * 31L + node.advancedColor.hashCode().toLong()
        }
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
