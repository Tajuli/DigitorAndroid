package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TimelineTrack
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val AUTO_CC_ID_PREFIX_V77 = "auto-cc-v77-"
private const val AUTO_CC_TRACK_NAME_V77 = "CC"
private const val TARGET_SAMPLE_RATE_V77 = 16_000
private const val MIN_CAPTION_US_V77 = 180_000L
private const val MAX_CAPTION_CHARS_V77 = 42
private const val WHISPER_TIME_UNIT_US_V78 = 10_000L // whisper.cpp segment times are centiseconds

enum class AutoCaptionQualityV77(val label: String, val detail: String) {
    FAST("Fast", "Tiny Q5 multilingual · fastest Vulkan path"),
    ACCURATE("Accurate", "Base Q5 multilingual · better accuracy"),
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

private data class WhisperModelSpecV78(
    val fileName: String,
    val url: String,
    val minimumBytes: Long,
    val displayName: String,
    val bestOf: Int,
)

private data class NativeSegmentV78(
    val text: String,
    val startCentiseconds: Long,
    val endCentiseconds: Long,
)

/**
 * V78 Auto CC engine.
 *
 * This deliberately does not use WhisperKit. The previous Android artifact could load only when a
 * proprietary Qualcomm QNN delegate was packaged and its multilingual path produced unreliable text
 * on the target phone. V78 uses pinned MIT-licensed whisper.cpp/ggml, requests Vulkan first, retains
 * ggml CPU fallback, lets whisper.cpp auto-detect Bangla/English, and consumes whisper.cpp's own
 * segment timestamps.
 */
class WhisperGpuAutoCaptionEngineV77(private val context: Context) {

    companion object {
        fun supportedOnThisDevice(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        check(supportedOnThisDevice()) {
            "Auto CC requires a 64-bit ARM Android phone"
        }
        val track = project.track(audioTrackId)
            ?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }

        val spec = when (quality) {
            AutoCaptionQualityV77.FAST -> WhisperModelSpecV78(
                fileName = "ggml-tiny-q5_1.bin",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
                minimumBytes = 20_000_000L,
                displayName = "Whisper Tiny Q5 multilingual",
                bestOf = 1,
            )
            AutoCaptionQualityV77.ACCURATE -> WhisperModelSpecV78(
                fileName = "ggml-base-q5_1.bin",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
                minimumBytes = 45_000_000L,
                displayName = "Whisper Base Q5 multilingual",
                bestOf = 3,
            )
        }

        val model = ensureWhisperModelV78(spec, onProgress)
        return try {
            runBackendV78(
                clips = clips,
                model = model,
                spec = spec,
                useGpu = true,
                backendLabel = "Vulkan GPU",
                onProgress = onProgress,
            )
        } catch (gpuError: Throwable) {
            currentCoroutineContext().ensureActive()
            onProgress(AutoCaptionProgressV77(.18f, "Vulkan unavailable · retrying CPU fallback"))
            runCatching {
                runBackendV78(
                    clips = clips,
                    model = model,
                    spec = spec,
                    useGpu = false,
                    backendLabel = "CPU fallback",
                    onProgress = onProgress,
                )
            }.getOrElse { cpuError ->
                throw IllegalStateException(
                    "Auto CC failed on Vulkan (${gpuError.message ?: "unknown"}) and CPU (${cpuError.message ?: "unknown"})",
                    cpuError,
                )
            }
        }
    }

    private suspend fun runBackendV78(
        clips: List<TimelineClip>,
        model: File,
        spec: WhisperModelSpecV78,
        useGpu: Boolean,
        backendLabel: String,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): AutoCaptionResultV77 = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        onProgress(AutoCaptionProgressV77(.17f, "Loading ${spec.displayName} · $backendLabel"))

        var nativeContext = 0L
        try {
            nativeContext = WhisperCppNativeV78.createContext(model.absolutePath, useGpu)
            check(nativeContext != 0L) { "whisper.cpp context initialization returned null" }

            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val drafts = mutableListOf<AutoCaptionDraftV77>()
            val totalDuration = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var completedDuration = 0L

            clips.forEachIndexed { index, clip ->
                currentCoroutineContext().ensureActive()
                onProgress(
                    AutoCaptionProgressV77(
                        .20f + .70f * (completedDuration.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f),
                        "$backendLabel · decoding ${index + 1}/${clips.size}",
                    ),
                )
                val audio = decodeClipToFloatV78(clip)
                if (audio.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val encodedSegments = WhisperCppNativeV78.transcribeSegments(
                        contextPtr = nativeContext,
                        audioData = audio,
                        threadCount = threads,
                        bestOf = spec.bestOf,
                    )
                    encodedSegments
                        .mapNotNull(::parseNativeSegmentV78)
                        .mapNotNull { segment -> segment.toTimelineDraftV78(clip) }
                        .flatMap(::splitCaptionForReadabilityV77)
                        .let(drafts::addAll)
                }
                completedDuration += clip.durationUs
                onProgress(
                    AutoCaptionProgressV77(
                        .20f + .76f * (completedDuration.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f),
                        "$backendLabel transcribing · ${index + 1}/${clips.size}",
                    ),
                )
            }

            val normalized = drafts.normalizeCaptionOrderV77()
            require(normalized.isNotEmpty()) {
                "No speech was detected on the selected audio track"
            }
            onProgress(AutoCaptionProgressV77(1f, "${normalized.size} captions ready · $backendLabel"))
            AutoCaptionResultV77(
                captions = normalized,
                backend = backendLabel,
                model = spec.displayName,
            )
        } finally {
            if (nativeContext != 0L) runCatching { WhisperCppNativeV78.freeContext(nativeContext) }
        }
    }

    private suspend fun ensureWhisperModelV78(
        spec: WhisperModelSpecV78,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "auto_cc_models_v78").apply { mkdirs() }
        val target = File(directory, spec.fileName)
        if (target.isFile && target.length() > spec.minimumBytes) {
            onProgress(AutoCaptionProgressV77(.15f, "Speech model cached · ${spec.displayName}"))
            return@withContext target
        }

        val temp = File(directory, "${spec.fileName}.download")
        if (temp.exists()) temp.delete()
        var connection: HttpURLConnection? = null
        try {
            onProgress(AutoCaptionProgressV77(.01f, "Downloading ${spec.displayName}"))
            connection = URI(spec.url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.setRequestProperty("User-Agent", "DigitorAndroid-AutoCC/1.0")
            connection.setRequestProperty("Accept", "application/octet-stream,*/*")
            val response = connection.responseCode
            require(response in 200..299) { "Speech model download returned HTTP $response" }
            val expected = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val downloadFraction = if (expected != null) {
                            (copied.toDouble() / expected.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            (copied.toDouble() / max(spec.minimumBytes, copied).toDouble()).toFloat().coerceIn(0f, 1f)
                        }
                        onProgress(
                            AutoCaptionProgressV77(
                                .01f + downloadFraction * .13f,
                                "Speech model ${(downloadFraction * 100f).roundToInt()}%",
                            ),
                        )
                    }
                }
            }
            require(temp.length() > spec.minimumBytes) {
                "Downloaded speech model is incomplete (${temp.length()} bytes)"
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Could not install ${spec.fileName}" }
            onProgress(AutoCaptionProgressV77(.15f, "Speech model ready · ${spec.displayName}"))
            target
        } finally {
            connection?.disconnect()
            if (temp.exists() && (!target.exists() || target.length() <= spec.minimumBytes)) temp.delete()
        }
    }

    /** Decode only the selected timeline range to 16 kHz mono float PCM expected by whisper.cpp. */
    private suspend fun decodeClipToFloatV78(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        val chunks = mutableListOf<ShortArray>()
        var totalSamples = 0
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
                                chunks += target
                                totalSamples += target.size
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

        if (totalSamples <= 0) return FloatArray(0)
        val output = FloatArray(totalSamples)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.forEach { sample ->
                output[offset++] = (sample.toInt() / 32768f).coerceIn(-1f, 1f)
            }
        }
        return output
    }
}

private fun parseNativeSegmentV78(encoded: String): NativeSegmentV78? {
    val parts = encoded.split('\t', limit = 3)
    if (parts.size < 3) return null
    val t0 = parts[0].toLongOrNull() ?: return null
    val t1 = parts[1].toLongOrNull() ?: return null
    val text = parts[2].replace(Regex("\\s+"), " ").trim()
    if (text.isBlank() || t1 <= t0) return null
    return NativeSegmentV78(text, t0, t1)
}

private fun NativeSegmentV78.toTimelineDraftV78(clip: TimelineClip): AutoCaptionDraftV77? {
    val localStartUs = (startCentiseconds * WHISPER_TIME_UNIT_US_V78).coerceIn(0L, clip.durationUs)
    val localEndUs = (endCentiseconds * WHISPER_TIME_UNIT_US_V78).coerceIn(localStartUs, clip.durationUs)
    if (localEndUs - localStartUs < MIN_CAPTION_US_V77 || text.isBlank()) return null
    return AutoCaptionDraftV77(
        text = text,
        timelineStartUs = clip.timelineStartUs + localStartUs,
        timelineEndUs = clip.timelineStartUs + localEndUs,
    )
}

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
