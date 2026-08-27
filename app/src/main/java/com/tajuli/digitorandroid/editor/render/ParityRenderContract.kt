package com.tajuli.digitorandroid.editor.render

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.TransformerUtil
import com.tajuli.digitorandroid.editor.model.TimelineClip

/**
 * Exact preview/export contract for metadata and timestamps that sit around the shared GL stages.
 *
 * Media3 Transformer places each decoded media item on composition time before video effects run.
 * Realtime preview does the same by registering its source-timestamped decoder stream with
 * timelineStartUs - sourceInUs. Both paths therefore resolve an animated effect from the same
 * composition presentation timestamp through [sourceTimeUs].
 *
 * The output color rule mirrors VideoSampleExporter for supported SDR video: incomplete source
 * metadata is first normalized with TransformerUtil.getValidColor(), then sRGB/Gamma-2.2 graph
 * output is converted to SDR BT.709 limited. HDR remains outside the realtime parity contract.
 */
@UnstableApi
internal object ParityRenderContract {

    fun validDecoderInputFormat(format: Format): Format =
        format.buildUpon()
            .setColorInfo(TransformerUtil.getValidColor(format.colorInfo))
            .build()

    /** Mirrors VideoEncoderGraphInput.applyDecoderRotation for Surface-decoded video. */
    fun decoderOutputFormat(format: Format): Format {
        val valid = validDecoderInputFormat(format)
        return if (valid.rotationDegrees % 180 == 0) {
            valid
        } else {
            valid.buildUpon()
                .setWidth(valid.height)
                .setHeight(valid.width)
                .setRotationDegrees(0)
                .build()
        }
    }

    /** Mirrors the SDR branch of Media3 VideoSampleExporter videoGraphOutputColor selection. */
    fun videoGraphOutputColor(firstDecodedInputFormat: Format): ColorInfo {
        val valid = TransformerUtil.getValidColor(firstDecodedInputFormat.colorInfo)
        return when (valid.colorTransfer) {
            C.COLOR_TRANSFER_SRGB,
            C.COLOR_TRANSFER_GAMMA_2_2,
            -> ColorInfo.SDR_BT709_LIMITED

            else -> valid
        }
    }

    /**
     * Converts the composition timestamp seen by both preview/export GL effects to source time.
     * This is the only timestamp mapping used by animated color and spatial effects.
     */
    fun sourceTimeUs(clip: TimelineClip, presentationTimeUs: Long): Long {
        val minSource = clip.sourceInUs.coerceAtLeast(0L)
        val maxSource = clip.sourceOutUs.coerceAtLeast(minSource)
        return (clip.sourceInUs + (presentationTimeUs - clip.timelineStartUs))
            .coerceIn(minSource, maxSource)
    }
}
