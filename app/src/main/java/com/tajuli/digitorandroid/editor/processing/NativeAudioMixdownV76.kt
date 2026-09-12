package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result metadata for the fully native audio branch of the V76 exporter. */
internal data class NativeAudioMixResultV76(
    val mixedClipCount: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int,
)

internal fun TimelineProject.hasActiveNativeAudioV76(): Boolean =
    tracks.any { track -> track.kind == TrackKind.AUDIO && !track.muted && track.clips.isNotEmpty() }

/**
 * Offline multitrack audio mixdown implemented only with Android platform media APIs.
 *
 * Each active A-track clip is decoded with MediaExtractor + MediaCodec to PCM, resampled to the
 * common 48 kHz stereo timeline, mixed into a bounded on-disk PCM buffer with the same volume and
 * fade envelope used by preview/export, then encoded to AAC-LC with MediaCodec. The resulting
 * audio-only MP4 is remuxed with the native video render by [remuxNativeVideoAndAudioV76].
 *
 * Keeping the mix buffer on disk avoids retaining a long project's entire PCM timeline in RAM.
 */
internal class NativeAudioMixdownV76(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun encode(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): NativeAudioMixResultV76 {
        val clips = project.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted }
            .flatMap { it.clips }
            .sortedBy { it.timelineStartUs }
        require(clips.isNotEmpty()) { "Native audio mix requested without active audio clips" }
        require(project.durationUs > 0L) { "Native audio mix requires a positive timeline duration" }

        val totalFrames = ceil(
            project.durationUs.toDouble() * TARGET_SAMPLE_RATE.toDouble() / 1_000_000.0,
        ).toLong().coerceAtLeast(1L)
        val pcmBytes = Math.multiplyExact(totalFrames, TARGET_FRAME_BYTES.toLong())
        val pcmFile = File.createTempFile("digitor-native-mix-", ".pcm", appContext.cacheDir)
        try {
            RandomAccessFile(pcmFile, "rw").use { mixFile ->
                mixFile.setLength(pcmBytes)
                clips.forEachIndexed { index, clip ->
                    val fraction = index.toFloat() / clips.size.toFloat()
                    onProgress(
                        ExportProgress.Stage(
                            "Native audio decode + mix ${index + 1}/${clips.size}",
                            (0.05f + fraction * 0.25f).coerceIn(0.05f, 0.30f),
                        ),
                    )
                    mixClipIntoTimelineV76(
                        clip = clip,
                        output = mixFile,
                        totalTargetFrames = totalFrames,
                    )
                }
            }
            onProgress(ExportProgress.Stage("Native AAC hardware encode", 0.32f))
            encodePcmToAacMp4V76(pcmFile, totalFrames, output)
            return NativeAudioMixResultV76(
                mixedClipCount = clips.size,
                sampleRate = TARGET_SAMPLE_RATE,
                channelCount = TARGET_CHANNELS,
                bitrate = TARGET_AAC_BITRATE,
            )
        } finally {
            runCatching { pcmFile.delete() }
        }
    }

    private fun mixClipIntoTimelineV76(
        clip: TimelineClip,
        output: RandomAccessFile,
        totalTargetFrames: Long,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, Uri.parse(clip.uri), null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            var mime: String? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val candidateMime = candidate.getString(MediaFormat.KEY_MIME)
                if (candidateMime?.startsWith("audio/") == true) {
                    audioTrackIndex = index
                    inputFormat = candidate
                    mime = candidateMime
                    break
                }
            }
            require(audioTrackIndex >= 0 && inputFormat != null && mime != null) {
                "No audio track in ${clip.label}"
            }
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(clip.sourceInUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val createdDecoder = MediaCodec.createDecoderByType(mime).also { codec ->
                codec.configure(inputFormat, null, null, 0)
                codec.start()
            }
            decoder = createdDecoder

            var outputFormat = inputFormat
            var sampleRate = outputFormat.intValueV76(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
                .coerceAtLeast(1)
            var channelCount = outputFormat.intValueV76(MediaFormat.KEY_CHANNEL_COUNT, 2)
                .coerceAtLeast(1)
            var pcmEncoding = outputFormat.intValueV76(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastTargetFrameExclusive = 0L
            var idleLoops = 0

            while (!outputEnded) {
                var didWork = false
                if (!inputEnded) {
                    for (attempt in 0 until 8) {
                        val index = createdDecoder.dequeueInputBuffer(0L)
                        if (index < 0) break
                        val input = createdDecoder.getInputBuffer(index)
                            ?: error("Native audio decoder input buffer unavailable")
                        input.clear()
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs >= clip.sourceOutUs) {
                            createdDecoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                clip.sourceOutUs.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                            didWork = true
                            break
                        }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            createdDecoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                sampleTimeUs.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            createdDecoder.queueInputBuffer(index, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                        didWork = true
                    }
                }

                for (attempt in 0 until 12) {
                    val index = createdDecoder.dequeueOutputBuffer(info, 0L)
                    when {
                        index >= 0 -> {
                            if (info.size > 0) {
                                val decoded = createdDecoder.getOutputBuffer(index)
                                    ?: error("Native audio decoder output buffer unavailable")
                                lastTargetFrameExclusive = mixDecodedBufferV76(
                                    clip = clip,
                                    decoded = decoded,
                                    info = info,
                                    sampleRate = sampleRate,
                                    channelCount = channelCount,
                                    pcmEncoding = pcmEncoding,
                                    output = output,
                                    totalTargetFrames = totalTargetFrames,
                                    lastTargetFrameExclusive = lastTargetFrameExclusive,
                                )
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                            createdDecoder.releaseOutputBuffer(index, false)
                            didWork = true
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = createdDecoder.outputFormat
                            sampleRate = outputFormat.intValueV76(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceAtLeast(1)
                            channelCount = outputFormat.intValueV76(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceAtLeast(1)
                            pcmEncoding = outputFormat.intValueV76(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                            didWork = true
                        }
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    }
                }

                if (didWork) {
                    idleLoops = 0
                } else {
                    idleLoops++
                    if (idleLoops > AUDIO_DECODER_IDLE_LIMIT) error("Native audio decoder stalled for ${clip.label}")
                    Thread.sleep(1L)
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun mixDecodedBufferV76(
        clip: TimelineClip,
        decoded: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        output: RandomAccessFile,
        totalTargetFrames: Long,
        lastTargetFrameExclusive: Long,
    ): Long {
        val bytesPerSample = pcmBytesPerSampleV76(pcmEncoding)
        val sourceFrameBytes = bytesPerSample * channelCount
        if (sourceFrameBytes <= 0 || info.size < sourceFrameBytes) return lastTargetFrameExclusive
        val sourceFrameCount = info.size / sourceFrameBytes
        if (sourceFrameCount <= 0) return lastTargetFrameExclusive

        val bufferStartUs = info.presentationTimeUs
        val bufferDurationUs = sourceFrameCount.toLong() * 1_000_000L / sampleRate.toLong()
        val bufferEndUs = bufferStartUs + bufferDurationUs
        val usableStartUs = max(bufferStartUs, clip.sourceInUs)
        val usableEndUs = min(bufferEndUs, clip.sourceOutUs)
        if (usableEndUs <= usableStartUs) return lastTargetFrameExclusive

        val timelineStartUs = clip.timelineStartUs + (usableStartUs - clip.sourceInUs)
        val timelineEndUs = clip.timelineStartUs + (usableEndUs - clip.sourceInUs)
        var targetStartFrame = ceil(
            timelineStartUs.toDouble() * TARGET_SAMPLE_RATE.toDouble() / 1_000_000.0,
        ).toLong()
        var targetEndFrame = ceil(
            timelineEndUs.toDouble() * TARGET_SAMPLE_RATE.toDouble() / 1_000_000.0,
        ).toLong()
        targetStartFrame = max(targetStartFrame, lastTargetFrameExclusive).coerceIn(0L, totalTargetFrames)
        targetEndFrame = targetEndFrame.coerceIn(targetStartFrame, totalTargetFrames)
        val targetCountLong = targetEndFrame - targetStartFrame
        if (targetCountLong <= 0L) return max(lastTargetFrameExclusive, targetEndFrame)
        require(targetCountLong <= Int.MAX_VALUE / TARGET_FRAME_BYTES) { "Audio decode buffer is unexpectedly large" }
        val targetCount = targetCountLong.toInt()
        val existing = ByteArray(targetCount * TARGET_FRAME_BYTES)
        output.seek(targetStartFrame * TARGET_FRAME_BYTES.toLong())
        val read = output.read(existing)
        if (read in 0 until existing.size) {
            java.util.Arrays.fill(existing, read.coerceAtLeast(0), existing.size, 0.toByte())
        }

        val source = decoded.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        val mix = clip.audioMix.normalizedFor(clip.durationUs)
        val targetBuffer = ByteBuffer.wrap(existing).order(ByteOrder.LITTLE_ENDIAN)

        for (targetOffset in 0 until targetCount) {
            val targetFrame = targetStartFrame + targetOffset
            val timelineUs = targetFrame * 1_000_000L / TARGET_SAMPLE_RATE.toLong()
            val sourceUs = clip.sourceInUs + (timelineUs - clip.timelineStartUs)
            val relativeUs = (sourceUs - bufferStartUs).coerceAtLeast(0L)
            val sourceFrame = floor(
                relativeUs.toDouble() * sampleRate.toDouble() / 1_000_000.0,
            ).toInt().coerceIn(0, sourceFrameCount - 1)
            val frameOffset = info.offset + sourceFrame * sourceFrameBytes
            val left = readPcmSampleV76(source, frameOffset, pcmEncoding)
            val right = if (channelCount == 1) {
                left
            } else {
                readPcmSampleV76(source, frameOffset + bytesPerSample, pcmEncoding)
            }
            val localUs = (sourceUs - clip.sourceInUs).coerceIn(0L, clip.durationUs)
            var gain = mix.volume
            if (mix.fadeInUs > 0L) {
                gain *= (localUs.toDouble() / mix.fadeInUs.toDouble()).toFloat().coerceIn(0f, 1f)
            }
            if (mix.fadeOutUs > 0L) {
                val remainingUs = (clip.durationUs - localUs).coerceAtLeast(0L)
                gain *= (remainingUs.toDouble() / mix.fadeOutUs.toDouble()).toFloat().coerceIn(0f, 1f)
            }

            val byteOffset = targetOffset * TARGET_FRAME_BYTES
            val existingLeft = targetBuffer.getShort(byteOffset).toInt()
            val existingRight = targetBuffer.getShort(byteOffset + 2).toInt()
            val mixedLeft = (existingLeft + (left * gain * Short.MAX_VALUE).roundToInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val mixedRight = (existingRight + (right * gain * Short.MAX_VALUE).roundToInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            targetBuffer.putShort(byteOffset, mixedLeft.toShort())
            targetBuffer.putShort(byteOffset + 2, mixedRight.toShort())
        }

        output.seek(targetStartFrame * TARGET_FRAME_BYTES.toLong())
        output.write(existing)
        return max(lastTargetFrameExclusive, targetEndFrame)
    }

    private fun encodePcmToAacMp4V76(pcmFile: File, totalFrames: Long, output: File) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_INPUT_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val createdMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = createdMuxer
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var framesQueued = 0L

            FileInputStream(pcmFile).use { inputFile ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000L)
                        if (index >= 0) {
                            val input = codec.getInputBuffer(index)
                                ?: error("Native AAC encoder input buffer unavailable")
                            input.clear()
                            val capacity = input.remaining() - (input.remaining() % TARGET_FRAME_BYTES)
                            val request = min(capacity, AAC_INPUT_BYTES)
                            val bytes = ByteArray(request)
                            val read = inputFile.read(bytes)
                            if (read < 0 || framesQueued >= totalFrames) {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    framesQueued * 1_000_000L / TARGET_SAMPLE_RATE.toLong(),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                val alignedRead = read - (read % TARGET_FRAME_BYTES)
                                if (alignedRead <= 0) {
                                    codec.queueInputBuffer(
                                        index,
                                        0,
                                        0,
                                        framesQueued * 1_000_000L / TARGET_SAMPLE_RATE.toLong(),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputEnded = true
                                } else {
                                    input.put(bytes, 0, alignedRead)
                                    val ptsUs = framesQueued * 1_000_000L / TARGET_SAMPLE_RATE.toLong()
                                    codec.queueInputBuffer(index, 0, alignedRead, ptsUs, 0)
                                    framesQueued += alignedRead / TARGET_FRAME_BYTES
                                }
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outputIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outputIndex)
                                ?: error("Native AAC encoder output buffer unavailable")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(started) { "AAC muxer did not receive encoder format" }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                createdMuxer.writeSampleData(0, encoded, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!started) { "Native AAC output format changed twice" }
                            createdMuxer.addTrack(codec.outputFormat)
                            createdMuxer.start()
                            started = true
                        }
                    }
                }
            }
            check(started && output.length() > 0L) { "Native AAC encoder produced no MP4 audio track" }
        } catch (error: Throwable) {
            runCatching { if (output.exists()) output.delete() }
            throw error
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 48_000
        const val TARGET_CHANNELS = 2
        const val TARGET_FRAME_BYTES = TARGET_CHANNELS * 2
        const val TARGET_AAC_BITRATE = 192_000
        const val AAC_INPUT_BYTES = 32 * 1024
        const val AUDIO_DECODER_IDLE_LIMIT = 30_000
    }
}

/** Final native MP4 remux: H.264 video track + AAC-LC mixed audio track. */
internal fun remuxNativeVideoAndAudioV76(videoMp4: File, audioMp4: File, output: File) {
    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        videoExtractor.setDataSource(videoMp4.absolutePath)
        audioExtractor.setDataSource(audioMp4.absolutePath)
        val videoTrack = videoExtractor.firstTrackV76("video/")
        val audioTrack = audioExtractor.firstTrackV76("audio/")
        require(videoTrack >= 0) { "Native video temporary file contains no video track" }
        require(audioTrack >= 0) { "Native audio temporary file contains no audio track" }

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val createdMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = createdMuxer
        val outVideoTrack = createdMuxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
        val outAudioTrack = createdMuxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
        createdMuxer.start()
        started = true
        copyTrackV76(videoExtractor, videoTrack, createdMuxer, outVideoTrack)
        copyTrackV76(audioExtractor, audioTrack, createdMuxer, outAudioTrack)
        check(output.length() > 0L) { "Native AV remux produced an empty MP4" }
    } catch (error: Throwable) {
        runCatching { if (output.exists()) output.delete() }
        throw error
    } finally {
        runCatching { videoExtractor.release() }
        runCatching { audioExtractor.release() }
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
}

private fun MediaExtractor.firstTrackV76(prefix: String): Int {
    for (index in 0 until trackCount) {
        val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith(prefix) == true) return index
    }
    return -1
}

private fun copyTrackV76(
    extractor: MediaExtractor,
    inputTrack: Int,
    muxer: MediaMuxer,
    outputTrack: Int,
) {
    val format = extractor.getTrackFormat(inputTrack)
    val advertised = format.intValueV76(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
    val capacity = max(advertised, if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
        16 * 1024 * 1024
    } else {
        1024 * 1024
    })
    val buffer = ByteBuffer.allocateDirect(capacity)
    val info = MediaCodec.BufferInfo()
    extractor.selectTrack(inputTrack)
    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    while (true) {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        val timeUs = extractor.sampleTime
        if (timeUs < 0L) break
        info.set(0, size, timeUs, extractor.sampleFlags)
        buffer.position(0)
        buffer.limit(size)
        muxer.writeSampleData(outputTrack, buffer, info)
        if (!extractor.advance()) break
    }
    extractor.unselectTrack(inputTrack)
}

private fun pcmBytesPerSampleV76(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_FLOAT,
    AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
    else -> 2
}

private fun readPcmSampleV76(buffer: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> (((buffer.get(offset).toInt() and 0xFF) - 128) / 128f).coerceIn(-1f, 1f)
    AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        val b2 = buffer.get(offset + 2).toInt()
        val signed = b0 or (b1 shl 8) or (b2 shl 16)
        (signed / 8_388_608f).coerceIn(-1f, 1f)
    }
    AudioFormat.ENCODING_PCM_32BIT -> (buffer.getInt(offset).toDouble() / 2_147_483_648.0).toFloat().coerceIn(-1f, 1f)
    else -> (buffer.getShort(offset).toInt() / 32768f).coerceIn(-1f, 1f)
}

private fun MediaFormat.intValueV76(key: String, fallback: Int): Int =
    if (!containsKey(key)) fallback else runCatching { getInteger(key) }.getOrDefault(fallback)
