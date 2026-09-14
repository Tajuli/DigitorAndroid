package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.argmaxinc.whisperkit.ExperimentalWhisperKit
import com.argmaxinc.whisperkit.TranscriptionResult
import com.argmaxinc.whisperkit.WhisperKit
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val AUTO_CC_ID_PREFIX_V77 = "auto-cc-v77-"
private const val AUTO_CC_TRACK_NAME_V77 = "CC"
private const val TARGET_SAMPLE_RATE_V77 = 16_000
private const val MIN_CAPTION_US_V77 = 180_000L
private const val MAX_CAPTION_CHARS_V77 = 42

/** Creator-facing choice: tiny is the default because mobile turnaround matters more than benchmark WER. */
enum class AutoCaptionQualityV77(val label: String, val detail: String) {
    FAST("Fast", "Tiny multilingual · fastest GPU path"),
    ACCURATE("Accurate", "Base multilingual · better accuracy"),
}

data class AutoCaptionProgressV77(
    val fraction: Float,
    val message: String,
)

data class AutoCaptionDraftV77(
    val text: String,
    val timelineStartUs: Long,
    val timelineEndUs: Long,
)

data class AutoCaptionResultV77(
    val captions: List<AutoCaptionDraftV77>,
    val backend: String,
    val model: String,
)

private data class StreamMapV77(
    val streamStartUs: Long,
    val streamEndUs: Long,
    val timelineStartUs: Long,
)

private data class ParsedWhisperSegmentV77(
    val text: String,
    val streamStartUs: Long,
    val streamEndUs: Long,
)

/**
 * GPU-first, on-device ASR for the editor.
 *
 * The pinned WhisperKit Android runtime uses the generic LiteRT/TFLite GPU delegate on supported
 * Android devices. Audio never leaves the device; only the selected speech model is downloaded on
 * first use and then cached in app storage. If the GPU runtime throws a recoverable Java exception,
 * the same cached model is retried on CPU so a driver quirk does not destroy the edit session.
 *
 * WhisperKit currently exposes timestamp markers only in the raw callback text. We intentionally
 * parse those markers here instead of throwing them away, which gives Auto CC real segment timing
 * instead of evenly-spaced guessed captions.
 */
@OptIn(ExperimentalWhisperKit::class)
class WhisperGpuAutoCaptionEngineV77(private val context: Context) {

    companion object {
        fun supportedOnThisDevice(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        check(supportedOnThisDevice()) {
            "Auto CC requires Android 8.0+ on a 64-bit ARM phone"
        }
        val track = project.track(audioTrackId)
            ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }

        val model = when (quality) {
            AutoCaptionQualityV77.FAST -> WhisperKit.Builder.OPENAI_TINY
            AutoCaptionQualityV77.ACCURATE -> WhisperKit.Builder.OPENAI_BASE
        }

        return try {
            runBackend(
                clips = clips,
                model = model,
                backend = WhisperKit.Builder.CPU_AND_GPU,
                backendLabel = "GPU",
                onProgress = onProgress,
            )
        } catch (gpuError: Throwable) {
            currentCoroutineContext().ensureActive()
            onProgress(AutoCaptionProgressV77(.03f, "GPU unavailable · retrying safe CPU path"))
            runCatching {
                runBackend(
                    clips = clips,
                    model = model,
                    backend = WhisperKit.Builder.CPU_ONLY,
                    backendLabel = "CPU fallback",
                    onProgress = onProgress,
                )
            }.getOrElse { cpuError ->
                throw IllegalStateException(
                    "Auto CC failed on GPU (${gpuError.message ?: "unknown"}) and CPU (${cpuError.message ?: "unknown"})",
                    cpuError,
                )
            }
        }
    }

    private suspend fun runBackend(
        clips: List<TimelineClip>,
        model: String,
        backend: Int,
        backendLabel: String,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): AutoCaptionResultV77 {
        var latestResult: TranscriptionResult? = null
        val callbackLock = Any()
        val whisper = WhisperKit.Builder()
            .setModel(model)
            .setApplicationContext(context.applicationContext)
            .setEncoderBackend(backend)
            .setDecoderBackend(backend)
            .setCallback { what, result ->
                if (
                    what == WhisperKit.TextOutputCallback.MSG_TEXT_OUT ||
                    what == WhisperKit.TextOutputCallback.MSG_CLOSE
                ) {
                    synchronized(callbackLock) { latestResult = result }
                }
            }
            .build()

        var initialized = false
        try {
            onProgress(AutoCaptionProgressV77(.01f, "Preparing speech model · $backendLabel"))
            whisper.loadModel().collect { download ->
                val p = (.01f + download.fractionCompleted.coerceIn(0f, 1f) * .14f).coerceAtMost(.15f)
                onProgress(AutoCaptionProgressV77(p, "Speech model ${((download.fractionCompleted * 100f).roundToInt()).coerceIn(0, 100)}%"))
            }
            currentCoroutineContext().ensureActive()
            whisper.init(frequency = TARGET_SAMPLE_RATE_V77, channels = 1, duration = 0L)
            initialized = true

            val plannedUs = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var decodedUs = 0L
            var streamCursorUs = 0L
            val maps = mutableListOf<StreamMapV77>()

            clips.forEachIndexed { index, clip ->
                currentCoroutineContext().ensureActive()
                val beforeSamples = streamCursorUs * TARGET_SAMPLE_RATE_V77 / US_PER_SECOND
                val fedSamples = decodeClipToWhisperV77(clip) { pcm16Mono ->
                    if (pcm16Mono.isNotEmpty()) {
                        whisper.transcribe(pcm16Mono)
                    }
                }
                val actualUs = fedSamples * US_PER_SECOND / TARGET_SAMPLE_RATE_V77
                if (actualUs > 0L) {
                    maps += StreamMapV77(
                        streamStartUs = streamCursorUs,
                        streamEndUs = streamCursorUs + actualUs,
                        timelineStartUs = clip.timelineStartUs,
                    )
                    streamCursorUs += actualUs
                }
                decodedUs += min(clip.durationUs, actualUs.coerceAtLeast(0L))
                val mediaFraction = (decodedUs.toDouble() / plannedUs.toDouble()).toFloat().coerceIn(0f, 1f)
                onProgress(
                    AutoCaptionProgressV77(
                        fraction = .15f + mediaFraction * .82f,
                        message = "${backendLabel} transcribing · ${index + 1}/${clips.size}",
                    ),
                )
                @Suppress("UNUSED_VARIABLE")
                val ignoredForReadableDebug = beforeSamples
            }

            currentCoroutineContext().ensureActive()
            onProgress(AutoCaptionProgressV77(.98f, "Finalizing caption timing"))
            whisper.deinitialize()
            initialized = false

            val finalResult = synchronized(callbackLock) { latestResult }
                ?: error("Speech model returned no transcription")
            val parsed = parseWhisperTimestampTextV77(finalResult.text)
            val drafts = parsed
                .mapNotNull { it.toTimelineDraftV77(maps) }
                .flatMap(::splitCaptionForReadabilityV77)
                .normalizeCaptionOrderV77()

            require(drafts.isNotEmpty()) {
                "No speech was detected on the selected audio track"
            }
            onProgress(AutoCaptionProgressV77(1f, "${drafts.size} captions ready · $backendLabel"))
            return AutoCaptionResultV77(
                captions = drafts,
                backend = backendLabel,
                model = if (qualityNameV77(model).contains("tiny")) "Whisper Tiny multilingual" else "Whisper Base multilingual",
            )
        } finally {
            if (initialized) runCatching { whisper.deinitialize() }
        }
    }

    /**
     * Decode one timeline clip with Android's platform decoder, down-mix to mono, resample to 16 kHz
     * PCM16, and stream blocks straight into WhisperKit. Nothing is rendered and no temporary WAV is
     * created, which keeps long-form caption generation memory-friendly.
     */
    private suspend fun decodeClipToWhisperV77(
        clip: TimelineClip,
        consume: (ByteArray) -> Unit,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var producedTargetSamples = 0L
        try {
            val sourceUri = Uri.parse(clip.uri)
            if (sourceUri.scheme.isNullOrBlank()) {
                extractor.setDataSource(clip.uri)
            } else {
                extractor.setDataSource(context, sourceUri, null)
            }

            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = candidate
                    break
                }
            }
            require(audioTrack >= 0 && inputFormat != null) { "${clip.label}: no decodable audio stream" }

            extractor.selectTrack(audioTrack)
            extractor.seekTo(clip.sourceInUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("${clip.label}: audio MIME missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            var outputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer missing")
                        inputBuffer.clear()
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= clip.sourceOutUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (
                            outputBuffer != null && info.size > 0 &&
                            info.presentationTimeUs >= clip.sourceInUs &&
                            info.presentationTimeUs < clip.sourceOutUs
                        ) {
                            val safeEnd = (info.offset + info.size).coerceAtMost(outputBuffer.capacity())
                            val bytes = outputBuffer.duplicate().apply {
                                position(info.offset.coerceAtLeast(0))
                                limit(safeEnd)
                            }.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val mono = bytes.toMonoPcm16V77(outputChannels, outputEncoding)
                            val target = resampleMonoV77(mono, outputRate, TARGET_SAMPLE_RATE_V77)
                            if (target.isNotEmpty()) {
                                val pcm = target.toLittleEndianBytesV77()
                                consume(pcm)
                                producedTargetSamples += target.size
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
            codec?.let { decoder ->
                if (codecStarted) runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }
        return producedTargetSamples
    }
}

private fun qualityNameV77(model: String): String = model.substringAfterLast('/').lowercase()

private fun ByteBuffer.toMonoPcm16V77(channels: Int, encoding: Int): ShortArray {
    val ch = channels.coerceAtLeast(1)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val sampleCount = remaining() / 4
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0f
                repeat(ch) { channel -> sum += getFloat((frame * ch + channel) * 4) }
                ((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            val sampleCount = remaining()
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0
                repeat(ch) { channel -> sum += (get(frame * ch + channel).toInt() and 0xFF) - 128 }
                ((sum / ch) shl 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        else -> {
            val sampleCount = remaining() / 2
            val frameCount = sampleCount / ch
            ShortArray(frameCount) { frame ->
                var sum = 0L
                repeat(ch) { channel -> sum += getShort((frame * ch + channel) * 2).toLong() }
                (sum / ch).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
        }
    }
}

private fun resampleMonoV77(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return ShortArray(0)
    if (sourceRate == targetRate) return input
    val outputCount = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
    val ratio = sourceRate.toDouble() / targetRate.toDouble()
    return ShortArray(outputCount) { index ->
        val sourcePosition = index * ratio
        val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
        val right = min(left + 1, input.lastIndex)
        val t = sourcePosition - left
        (input[left] + (input[right] - input[left]) * t)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun ShortArray.toLittleEndianBytesV77(): ByteArray {
    val output = ByteArray(size * 2)
    var offset = 0
    forEach { sample ->
        val value = sample.toInt()
        output[offset++] = (value and 0xFF).toByte()
        output[offset++] = ((value ushr 8) and 0xFF).toByte()
    }
    return output
}

private val WHISPER_TIMESTAMP_V77 = "<\\|(\\d+(?:\\.\\d+)?)\\|>".toRegex()
private val WHISPER_CONTROL_TOKEN_V77 = "<\\|[^>]+\\|>".toRegex()

private fun parseWhisperTimestampTextV77(raw: String): List<ParsedWhisperSegmentV77> {
    val matches = WHISPER_TIMESTAMP_V77.findAll(raw).toList()
    if (matches.size < 2) return emptyList()
    val output = mutableListOf<ParsedWhisperSegmentV77>()
    for (i in 0 until matches.lastIndex) {
        val left = matches[i]
        val right = matches[i + 1]
        val text = raw.substring(left.range.last + 1, right.range.first)
            .replace(WHISPER_CONTROL_TOKEN_V77, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) continue
        val startSeconds = left.groupValues[1].toDoubleOrNull() ?: continue
        val endSeconds = right.groupValues[1].toDoubleOrNull() ?: continue
        if (endSeconds <= startSeconds) continue
        output += ParsedWhisperSegmentV77(
            text = text,
            streamStartUs = (startSeconds * US_PER_SECOND).toLong(),
            streamEndUs = (endSeconds * US_PER_SECOND).toLong(),
        )
    }
    return output.distinctBy { Triple(it.streamStartUs, it.streamEndUs, it.text) }
}

private fun ParsedWhisperSegmentV77.toTimelineDraftV77(maps: List<StreamMapV77>): AutoCaptionDraftV77? {
    val map = maps.firstOrNull { streamStartUs >= it.streamStartUs && streamStartUs < it.streamEndUs }
        ?: maps.lastOrNull { streamStartUs >= it.streamStartUs }
        ?: return null
    val localStart = (streamStartUs - map.streamStartUs).coerceIn(0L, map.streamEndUs - map.streamStartUs)
    val localEnd = (streamEndUs - map.streamStartUs).coerceIn(localStart, map.streamEndUs - map.streamStartUs)
    val start = map.timelineStartUs + localStart
    val end = map.timelineStartUs + localEnd
    if (end - start < MIN_CAPTION_US_V77 || text.isBlank()) return null
    return AutoCaptionDraftV77(text = text.trim(), timelineStartUs = start, timelineEndUs = end)
}

private fun splitCaptionForReadabilityV77(source: AutoCaptionDraftV77): List<AutoCaptionDraftV77> {
    if (source.text.length <= MAX_CAPTION_CHARS_V77) return listOf(source)
    val words = source.text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size <= 1) {
        val chunks = source.text.chunked(MAX_CAPTION_CHARS_V77)
        return distributeCaptionChunksV77(source, chunks)
    }
    val chunks = mutableListOf<String>()
    var current = StringBuilder()
    words.forEach { word ->
        val candidateLength = current.length + if (current.isEmpty()) 0 else 1 + word.length
        if (candidateLength > MAX_CAPTION_CHARS_V77 && current.isNotEmpty()) {
            chunks += current.toString()
            current = StringBuilder(word)
        } else {
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return distributeCaptionChunksV77(source, chunks)
}

private fun distributeCaptionChunksV77(
    source: AutoCaptionDraftV77,
    chunks: List<String>,
): List<AutoCaptionDraftV77> {
    if (chunks.size <= 1) return listOf(source.copy(text = chunks.firstOrNull() ?: source.text))
    val totalWeight = chunks.sumOf { max(1, it.length) }.toLong()
    var cursor = source.timelineStartUs
    return chunks.mapIndexed { index, text ->
        val remaining = source.timelineEndUs - cursor
        val duration = if (index == chunks.lastIndex) {
            remaining
        } else {
            max(MIN_CAPTION_US_V77, source.durationShareV77(text.length, totalWeight))
                .coerceAtMost(remaining)
        }
        val end = if (index == chunks.lastIndex) source.timelineEndUs else (cursor + duration).coerceAtMost(source.timelineEndUs)
        AutoCaptionDraftV77(text.trim(), cursor, end).also { cursor = end }
    }.filter { it.timelineEndUs - it.timelineStartUs >= MIN_CAPTION_US_V77 }
}

private fun AutoCaptionDraftV77.durationShareV77(weight: Int, totalWeight: Long): Long =
    ((timelineEndUs - timelineStartUs).coerceAtLeast(1L) * max(1, weight) / totalWeight.coerceAtLeast(1L))

private fun List<AutoCaptionDraftV77>.normalizeCaptionOrderV77(): List<AutoCaptionDraftV77> {
    if (isEmpty()) return emptyList()
    val sorted = sortedWith(compareBy<AutoCaptionDraftV77> { it.timelineStartUs }.thenBy { it.timelineEndUs })
    val result = mutableListOf<AutoCaptionDraftV77>()
    var previousEnd = -1L
    sorted.forEach { item ->
        val start = max(item.timelineStartUs, previousEnd)
        val end = max(start + MIN_CAPTION_US_V77, item.timelineEndUs)
        if (end > start && item.text.isNotBlank()) {
            result += item.copy(timelineStartUs = start, timelineEndUs = end)
            previousEnd = end
        }
    }
    return result
}

/** Install/replace only captions generated by Auto CC; manual titles remain untouched and editable. */
fun TimelineProject.withAutoCaptionsV77(captions: List<AutoCaptionDraftV77>): TimelineProject {
    val existingCcTrack = tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.name == AUTO_CC_TRACK_NAME_V77 }
    val ccTrack = existingCcTrack ?: TimelineTrack(name = AUTO_CC_TRACK_NAME_V77, kind = TrackKind.VIDEO)
    val nextTracks = if (existingCcTrack == null) listOf(ccTrack) + tracks else tracks
    val retained = textOverlays.filterNot { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) }
    val style = TextStyleV2(
        colorArgb = 0xFFFFFFFFL,
        strokeWidth = 4f,
        strokeArgb = 0xFF000000L,
        shadowEnabled = true,
        shadowArgb = 0xC0000000L,
        shadowRadius = 5f,
        shadowDx = 1.5f,
        shadowDy = 2f,
        backgroundEnabled = false,
        alignment = TextAlignmentV2.CENTER,
    )
    val generated = captions.map { caption ->
        TextOverlayClip(
            id = "$AUTO_CC_ID_PREFIX_V77${UUID.randomUUID()}",
            text = caption.text,
            timelineStartUs = caption.timelineStartUs,
            timelineEndUs = caption.timelineEndUs,
            positionX = 0f,
            positionY = .72f,
            sizeScale = .88f,
            argb = 0xFFFFFFFFL,
            bold = true,
            background = false,
            styleV2 = style,
            videoTrackIdV3 = ccTrack.id,
        )
    }
    return copy(tracks = nextTracks, textOverlays = retained + generated)
}

fun TimelineProject.clearAutoCaptionsV77(): TimelineProject =
    copy(textOverlays = textOverlays.filterNot { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) })

fun TimelineProject.autoCaptionCountV77(): Int = textOverlays.count { it.id.startsWith(AUTO_CC_ID_PREFIX_V77) }
