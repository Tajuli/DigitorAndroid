package com.tajuli.digitorandroid.editor.processing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Surface-free AVC encoder for the CPU fallback. Frames enter as ARGB and are converted to
 * YUV420 in ordinary memory, so the effect/composite path never requires OpenGL.
 */
class CpuAvcEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val output: File,
    private val bitrate: Int = (width * height * frameRate * 0.10).toInt().coerceAtLeast(2_000_000),
) : AutoCloseable {
    private data class EncoderChoice(val name: String, val colorFormat: Int)

    private val choice = chooseEncoder()
    private val codec = MediaCodec.createByCodecName(choice.name)
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var muxerTrack = -1
    private var muxerStarted = false
    private var finished = false

    init {
        require(width % 2 == 0 && height % 2 == 0) { "CPU AVC encoder requires even width/height" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun encodeFrame(argb: IntArray, presentationTimeUs: Long) {
        check(!finished)
        require(argb.size == width * height)
        val yuv = when (choice.colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> argbToNv12(argb)
            else -> argbToI420(argb)
        }

        while (true) {
            val inputIndex = codec.dequeueInputBuffer(20_000)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: error("Encoder input buffer unavailable")
                input.clear()
                require(input.remaining() >= yuv.size) { "Encoder input buffer too small" }
                input.put(yuv)
                codec.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = false)
    }

    fun finish() {
        if (finished) return
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(20_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        finished = true
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 100) return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed twice" }
                    muxerTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> if (outputIndex >= 0) {
                    val encoded = codec.getOutputBuffer(outputIndex)
                        ?: error("Encoder output buffer unavailable")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        check(muxerStarted) { "Muxer not started" }
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrack, encoded, bufferInfo)
                    }
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    private fun chooseEncoder(): EncoderChoice {
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
            for (format in preferred) {
                if (caps.colorFormats.contains(format)) return EncoderChoice(info.name, format)
            }
        }
        error("No byte-buffer AVC encoder with YUV420 support")
    }

    private fun argbToI420(argb: IntArray): ByteArray {
        val frame = width * height
        val out = ByteArray(frame + frame / 2)
        var yIndex = 0
        var uIndex = frame
        var vIndex = frame + frame / 4
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var sumU = 0
                var sumV = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val c = argb[(y + dy) * width + (x + dx)]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        out[(y + dy) * width + (x + dx)] = yy.coerceIn(0, 255).toByte()
                        sumU += uu
                        sumV += vv
                    }
                }
                out[uIndex++] = (sumU / 4).coerceIn(0, 255).toByte()
                out[vIndex++] = (sumV / 4).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private fun argbToNv12(argb: IntArray): ByteArray {
        val frame = width * height
        val out = ByteArray(frame + frame / 2)
        var uvIndex = frame
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var sumU = 0
                var sumV = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val c = argb[(y + dy) * width + (x + dx)]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        val uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        out[(y + dy) * width + (x + dx)] = yy.coerceIn(0, 255).toByte()
                        sumU += uu
                        sumV += vv
                    }
                }
                out[uvIndex++] = (sumU / 4).coerceIn(0, 255).toByte()
                out[uvIndex++] = (sumV / 4).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    override fun close() {
        runCatching { if (!finished) finish() }
        runCatching { codec.stop() }
        codec.release()
        if (muxerStarted) runCatching { muxer.stop() }
        muxer.release()
    }
}
