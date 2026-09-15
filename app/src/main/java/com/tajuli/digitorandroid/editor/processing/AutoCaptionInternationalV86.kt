package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val V86_RATE = 16_000
private const val V86_FEED_SAMPLES = 8_000
private const val V86_TAIL_SAMPLES = 6_400
private const val V86_FRAME_SAMPLES = 320
private const val V86_FORCE_SEGMENT_SAMPLES = V86_RATE * 10
private const val V86_MIN_SPLIT_SAMPLES = V86_RATE * 2
private const val V86_MIN_SILENCE_FRAMES = 12
private const val V86_MIN_CAPTION_US = 180_000L
private const val V86_MAX_CAPTION_CHARS = 42
private const val V86_MAX_CAPTION_SPAN_US = 4_000_000L
private const val V86_CACHE_ROOT = "auto_cc_models_v79"

/**
 * International Auto CC language selector.
 *
 * AUTO_BN_EN is intentionally limited to the two proven legacy packs. Digitor does not fan out a
 * clip through every installed model because that multiplies CPU/RAM use as users install more
 * languages. All other international languages are explicit selections.
 */
enum class AutoCaptionLanguageV86(
    val label: String,
    val detail: String,
    val localeCode: String,
) {
    BANGLA("বাংলা", "Bengali Zipformer2", "bn"),
    ENGLISH("English", "English Zipformer INT8", "en"),
    CHINESE("中文", "Chinese Zipformer 14M INT8", "zh"),
    KOREAN("한국어", "Korean streaming Zipformer INT8", "ko"),
    FRENCH("Français", "French streaming Zipformer INT8", "fr"),
    AUTO_BN_EN("Auto · বাংলা + English", "Detect between installed Bangla and English packs", "auto"),
    ;

    val downloadable: Boolean get() = this != AUTO_BN_EN
}

internal data class AutoCaptionPackFileV86(
    val remotePath: String,
    val localName: String,
    val minimumBytes: Long,
)

internal data class AutoCaptionPackSpecV86(
    val language: AutoCaptionLanguageV86,
    val directoryName: String,
    val baseUrl: String,
    val displayName: String,
    val modelType: String,
    val dither: Float,
    val approximateDownloadMb: Int,
    val license: String,
    val files: List<AutoCaptionPackFileV86>,
)

private val V86_BANGLA = AutoCaptionPackSpecV86(
    language = AutoCaptionLanguageV86.BANGLA,
    directoryName = "bn-zipformer2-2026-02-09",
    baseUrl = "https://huggingface.co/alphacep/vosk-model-small-streaming-bn/resolve/dfabeea5eee1f33d81436826d0575d8cfd64bd1d",
    displayName = "Bengali Zipformer2 0.60",
    modelType = "zipformer2",
    dither = 3e-5f,
    approximateDownloadMb = 84,
    license = "Apache-2.0",
    files = listOf(
        AutoCaptionPackFileV86("am-onnx/encoder.onnx", "encoder.onnx", 80_000_000L),
        AutoCaptionPackFileV86("am-onnx/decoder.onnx", "decoder.onnx", 1_500_000L),
        AutoCaptionPackFileV86("am-onnx/joiner.onnx", "joiner.onnx", 700_000L),
        AutoCaptionPackFileV86("lang/tokens.txt", "tokens.txt", 4_000L),
    ),
)

private val V86_ENGLISH = AutoCaptionPackSpecV86(
    language = AutoCaptionLanguageV86.ENGLISH,
    directoryName = "en-zipformer-20m-int8-2023-02-17",
    baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
    displayName = "English Zipformer 20M INT8",
    modelType = "zipformer",
    dither = 0f,
    approximateDownloadMb = 38,
    license = "Apache-2.0",
    files = listOf(
        AutoCaptionPackFileV86("encoder-epoch-99-avg-1.int8.onnx", "encoder.onnx", 35_000_000L),
        AutoCaptionPackFileV86("decoder-epoch-99-avg-1.int8.onnx", "decoder.onnx", 400_000L),
        AutoCaptionPackFileV86("joiner-epoch-99-avg-1.int8.onnx", "joiner.onnx", 200_000L),
        AutoCaptionPackFileV86("tokens.txt", "tokens.txt", 4_000L),
    ),
)

private val V86_CHINESE = AutoCaptionPackSpecV86(
    language = AutoCaptionLanguageV86.CHINESE,
    directoryName = "zh-zipformer-14m-int8-2023-02-23",
    baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/204ad334e2e683fd295359930cc16fc0432a23ac",
    displayName = "Chinese Zipformer 14M INT8",
    modelType = "zipformer",
    dither = 0f,
    approximateDownloadMb = 32,
    license = "Apache-2.0",
    files = listOf(
        AutoCaptionPackFileV86("encoder-epoch-99-avg-1.int8.onnx", "encoder.onnx", 20_000_000L),
        AutoCaptionPackFileV86("decoder-epoch-99-avg-1.onnx", "decoder.onnx", 7_000_000L),
        AutoCaptionPackFileV86("joiner-epoch-99-avg-1.int8.onnx", "joiner.onnx", 1_500_000L),
        AutoCaptionPackFileV86("tokens.txt", "tokens.txt", 40_000L),
    ),
)

private val V86_KOREAN = AutoCaptionPackSpecV86(
    language = AutoCaptionLanguageV86.KOREAN,
    directoryName = "ko-streaming-zipformer-int8-2024-06-16",
    baseUrl = "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/023c8279ae8ac55719b07eee0ad8bc59889973fb",
    displayName = "Korean streaming Zipformer INT8",
    modelType = "zipformer",
    dither = 0f,
    approximateDownloadMb = 142,
    license = "Apache-2.0",
    files = listOf(
        AutoCaptionPackFileV86("encoder-epoch-99-avg-1.int8.onnx", "encoder.onnx", 120_000_000L),
        // Upstream's documented INT8 recipe keeps the decoder in fp32.
        AutoCaptionPackFileV86("decoder-epoch-99-avg-1.onnx", "decoder.onnx", 10_000_000L),
        AutoCaptionPackFileV86("joiner-epoch-99-avg-1.int8.onnx", "joiner.onnx", 2_000_000L),
        AutoCaptionPackFileV86("tokens.txt", "tokens.txt", 45_000L),
    ),
)

private val V86_FRENCH = AutoCaptionPackSpecV86(
    language = AutoCaptionLanguageV86.FRENCH,
    directoryName = "fr-streaming-zipformer-int8-2023-04-14",
    baseUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73",
    displayName = "French streaming Zipformer INT8",
    modelType = "zipformer",
    dither = 0f,
    approximateDownloadMb = 131,
    license = "Apache-2.0",
    files = listOf(
        AutoCaptionPackFileV86("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", "encoder.onnx", 120_000_000L),
        // Upstream's documented INT8 recipe keeps the decoder in fp32.
        AutoCaptionPackFileV86("decoder-epoch-29-avg-9-with-averaged-model.onnx", "decoder.onnx", 1_500_000L),
        AutoCaptionPackFileV86("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", "joiner.onnx", 200_000L),
        AutoCaptionPackFileV86("tokens.txt", "tokens.txt", 3_000L),
    ),
)

internal val AUTO_CAPTION_PACKS_V86: List<AutoCaptionPackSpecV86> = listOf(
    V86_BANGLA,
    V86_ENGLISH,
    V86_CHINESE,
    V86_KOREAN,
    V86_FRENCH,
)

internal fun autoCaptionPackSpecV86(language: AutoCaptionLanguageV86): AutoCaptionPackSpecV86 =
    AUTO_CAPTION_PACKS_V86.firstOrNull { it.language == language }
        ?: error("${language.label} is not a downloadable language pack")

/**
 * Explicit, user-driven language-pack manager. Generate never invokes this downloader. This keeps
 * international launch storage predictable: users pay only for the languages they choose.
 */
class AutoCaptionLanguagePackManagerV86(private val context: Context) {
    fun isInstalled(language: AutoCaptionLanguageV86): Boolean = when (language) {
        AutoCaptionLanguageV86.AUTO_BN_EN ->
            isInstalled(AutoCaptionLanguageV86.BANGLA) && isInstalled(AutoCaptionLanguageV86.ENGLISH)
        else -> {
            val spec = autoCaptionPackSpecV86(language)
            val dir = modelDir(spec)
            spec.files.all { file ->
                val target = File(dir, file.localName)
                target.isFile && target.length() > file.minimumBytes
            }
        }
    }

    fun installedLanguages(): List<AutoCaptionLanguageV86> =
        AUTO_CAPTION_PACKS_V86.map { it.language }.filter(::isInstalled)

    fun approximateDownloadMb(language: AutoCaptionLanguageV86): Int =
        autoCaptionPackSpecV86(language).approximateDownloadMb

    fun modelDisplayName(language: AutoCaptionLanguageV86): String =
        autoCaptionPackSpecV86(language).displayName

    fun modelDir(language: AutoCaptionLanguageV86): File = modelDir(autoCaptionPackSpecV86(language))

    suspend fun download(
        language: AutoCaptionLanguageV86,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(language.downloadable) { "Auto mode is composed from the Bangla and English packs" }
        val spec = autoCaptionPackSpecV86(language)
        val dir = modelDir(spec).apply { mkdirs() }
        spec.files.forEachIndexed { index, remote ->
            currentCoroutineContext().ensureActive()
            val target = File(dir, remote.localName)
            if (target.isFile && target.length() > remote.minimumBytes) {
                val overall = (index + 1f) / spec.files.size
                onProgress(AutoCaptionProgressV77(overall, "${language.label} · ${(overall * 100).roundToInt()}%"))
                return@forEachIndexed
            }
            downloadFile(spec, remote, target, index, onProgress)
        }
        check(isInstalled(language)) { "${language.label} language pack is incomplete" }
        onProgress(AutoCaptionProgressV77(1f, "${language.label} language pack ready"))
    }

    fun delete(language: AutoCaptionLanguageV86): Boolean {
        if (!language.downloadable) return false
        return modelDir(autoCaptionPackSpecV86(language)).deleteRecursively()
    }

    private fun modelDir(spec: AutoCaptionPackSpecV86): File =
        File(context.filesDir, "$V86_CACHE_ROOT/${spec.directoryName}")

    private suspend fun downloadFile(
        spec: AutoCaptionPackSpecV86,
        remote: AutoCaptionPackFileV86,
        target: File,
        index: Int,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ) {
        val temp = File(target.parentFile, "${target.name}.download")
        val url = "${spec.baseUrl}/${remote.remotePath}?download=true"
        var last: Throwable? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            temp.delete()
            var connection: HttpURLConnection? = null
            try {
                connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.setRequestProperty("User-Agent", "DigitorAndroid-AutoCC/2.0")
                connection.setRequestProperty("Accept", "application/octet-stream,*/*")
                require(connection.responseCode in 200..299) {
                    "${spec.language.label} download returned HTTP ${connection.responseCode}"
                }
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
                            val fileRatio = if (expected != null) {
                                (copied.toDouble() / expected).toFloat().coerceIn(0f, 1f)
                            } else {
                                (copied.toDouble() / max(remote.minimumBytes.toDouble(), 1.0)).toFloat().coerceIn(0f, .98f)
                            }
                            val overall = (index + fileRatio) / spec.files.size.toFloat()
                            onProgress(
                                AutoCaptionProgressV77(
                                    overall.coerceIn(0f, 1f),
                                    "Downloading ${spec.language.label} · ${(overall * 100).roundToInt()}%",
                                ),
                            )
                        }
                    }
                }
                require(temp.length() > remote.minimumBytes) { "Downloaded ${target.name} is incomplete" }
                target.delete()
                check(temp.renameTo(target)) { "Could not install ${target.name}" }
                return
            } catch (error: Throwable) {
                temp.delete()
                if (error is CancellationException) throw error
                last = error
                if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Could not download ${spec.language.label} (${target.name})", last)
    }
}

/**
 * V86 keeps the proven V83 Bengali/English recognizer untouched and routes additional international
 * packs through the same sherpa-onnx streaming transducer API. This isolates launch expansion from
 * the already phone-tested Bangla/English path.
 */
class GlobalZipformerAutoCaptionEngineV86(private val context: Context) {
    private val legacy = ZipformerAutoCaptionEngineV83(context)
    private val packs = AutoCaptionLanguagePackManagerV86(context)
    private val international = InternationalZipformerRecognizerV86(context, packs)

    companion object {
        fun supportedOnThisDevice(): Boolean = ZipformerAutoCaptionEngineV83.supportedOnThisDevice()
    }

    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV86,
        onProgress: (AutoCaptionProgressV77) -> Unit = {},
    ): AutoCaptionResultV77 {
        require(packs.isInstalled(language)) {
            when (language) {
                AutoCaptionLanguageV86.AUTO_BN_EN -> "Install both বাংলা and English language packs first"
                else -> "Download the ${language.label} language pack first"
            }
        }
        return when (language) {
            AutoCaptionLanguageV86.BANGLA -> legacy.generate(
                project,
                audioTrackId,
                quality,
                AutoCaptionLanguageV79.BANGLA,
                onProgress,
            )
            AutoCaptionLanguageV86.ENGLISH -> legacy.generate(
                project,
                audioTrackId,
                quality,
                AutoCaptionLanguageV79.ENGLISH,
                onProgress,
            )
            AutoCaptionLanguageV86.AUTO_BN_EN -> legacy.generate(
                project,
                audioTrackId,
                quality,
                AutoCaptionLanguageV79.AUTO,
                onProgress,
            )
            else -> international.generate(project, audioTrackId, quality, language, onProgress)
        }
    }
}

private data class V86AudioSegment(val start: Int, val end: Int)

private class InternationalZipformerRecognizerV86(
    private val context: Context,
    private val packs: AutoCaptionLanguagePackManagerV86,
) {
    suspend fun generate(
        project: TimelineProject,
        audioTrackId: String,
        quality: AutoCaptionQualityV77,
        language: AutoCaptionLanguageV86,
        onProgress: (AutoCaptionProgressV77) -> Unit,
    ): AutoCaptionResultV77 {
        require(language in setOf(AutoCaptionLanguageV86.CHINESE, AutoCaptionLanguageV86.KOREAN, AutoCaptionLanguageV86.FRENCH))
        val track = project.track(audioTrackId)?.takeIf { it.kind == TrackKind.AUDIO && !it.muted }
            ?: error("Select an unmuted audio track")
        val clips = track.sortedClips()
        require(clips.isNotEmpty()) { "${track.name} has no audio clips" }
        val spec = autoCaptionPackSpecV86(language)
        val recognizer = createRecognizer(spec, quality)
        return try {
            val drafts = mutableListOf<AutoCaptionDraftV77>()
            val totalUs = clips.sumOf { it.durationUs }.coerceAtLeast(1L)
            var completedUs = 0L
            clips.forEachIndexed { clipIndex, clip ->
                currentCoroutineContext().ensureActive()
                onProgress(
                    AutoCaptionProgressV77(
                        .04f + .90f * (completedUs.toFloat() / totalUs).coerceIn(0f, 1f),
                        "${language.label} · speech ${clipIndex + 1}/${clips.size}",
                    ),
                )
                val decoded = decodeClip(clip)
                if (decoded.isNotEmpty()) {
                    val prepared = if (quality == AutoCaptionQualityV77.ACCURATE) normalizeSpeechLevel(decoded) else decoded
                    val segments = if (quality == AutoCaptionQualityV77.ACCURATE) {
                        speechSegments(prepared)
                    } else {
                        listOf(V86AudioSegment(0, prepared.size))
                    }
                    segments.forEach { segment ->
                        currentCoroutineContext().ensureActive()
                        if (segment.end <= segment.start) return@forEach
                        val result = recognize(recognizer, prepared.copyOfRange(segment.start, segment.end))
                        val localStartUs = segment.start.toLong() * 1_000_000L / V86_RATE
                        val localEndUs = segment.end.toLong() * 1_000_000L / V86_RATE
                        val segmentClip = clip.copy(
                            timelineStartUs = clip.timelineStartUs + localStartUs,
                            sourceInUs = clip.sourceInUs + localStartUs,
                            sourceOutUs = min(clip.sourceOutUs, clip.sourceInUs + localEndUs),
                        )
                        drafts += result.toInternationalDraftsV86(segmentClip, language)
                    }
                }
                completedUs += clip.durationUs
            }
            val normalized = drafts.normalizeInternationalDraftsV86()
            require(normalized.isNotEmpty()) { "No speech was detected for ${language.label}" }
            onProgress(AutoCaptionProgressV77(1f, "${normalized.size} captions ready · ${language.label}"))
            AutoCaptionResultV77(
                captions = normalized,
                backend = if (quality == AutoCaptionQualityV77.ACCURATE) {
                    "sherpa-onnx CPU · international Zipformer tuned"
                } else {
                    "sherpa-onnx CPU · international Zipformer fast"
                },
                model = spec.displayName,
            )
        } finally {
            runCatching { recognizer.release() }
        }
    }

    private fun createRecognizer(
        spec: AutoCaptionPackSpecV86,
        quality: AutoCaptionQualityV77,
    ): OnlineRecognizer {
        val dir = packs.modelDir(spec.language)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = V86_RATE, featureDim = 80, dither = spec.dither),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder.onnx").absolutePath,
                        decoder = File(dir, "decoder.onnx").absolutePath,
                        joiner = File(dir, "joiner.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                    modelType = spec.modelType,
                ),
                enableEndpoint = false,
                decodingMethod = if (quality == AutoCaptionQualityV77.ACCURATE) "modified_beam_search" else "greedy_search",
                maxActivePaths = if (quality == AutoCaptionQualityV77.ACCURATE) 24 else 4,
            ),
        )
    }

    private fun recognize(recognizer: OnlineRecognizer, audio: FloatArray): OnlineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            var offset = 0
            while (offset < audio.size) {
                val end = min(audio.size, offset + V86_FEED_SAMPLES)
                stream.acceptWaveform(audio.copyOfRange(offset, end), V86_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                offset = end
            }
            stream.acceptWaveform(FloatArray(V86_TAIL_SAMPLES), V86_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun normalizeSpeechLevel(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input
        val mean = input.sumOf { it.toDouble() }.toFloat() / input.size
        val centered = FloatArray(input.size) { (input[it] - mean).coerceIn(-1f, 1f) }
        val frameRms = frameRms(centered)
        if (frameRms.isEmpty()) return centered
        val sorted = frameRms.sorted()
        val noise = sorted[(sorted.size * .2f).toInt().coerceIn(0, sorted.lastIndex)]
        val speechThreshold = max(.008f, noise * 2.2f)
        var sumSq = 0.0
        var count = 0
        frameRms.forEachIndexed { frame, rms ->
            if (rms >= speechThreshold) {
                val start = frame * V86_FRAME_SAMPLES
                val end = min(centered.size, start + V86_FRAME_SAMPLES)
                for (i in start until end) {
                    sumSq += centered[i] * centered[i]
                    count++
                }
            }
        }
        if (count == 0) return centered
        val speechRms = sqrt(sumSq / count).toFloat().coerceAtLeast(.001f)
        val gain = (.10f / speechRms).coerceIn(.75f, 2.5f)
        return FloatArray(centered.size) { (centered[it] * gain).coerceIn(-.98f, .98f) }
    }

    private fun speechSegments(audio: FloatArray): List<V86AudioSegment> {
        if (audio.size <= V86_FORCE_SEGMENT_SAMPLES) return listOf(V86AudioSegment(0, audio.size))
        val rms = frameRms(audio)
        if (rms.isEmpty()) return listOf(V86AudioSegment(0, audio.size))
        val sorted = rms.sorted()
        val noise = sorted[(sorted.size * .2f).toInt().coerceIn(0, sorted.lastIndex)]
        val silenceThreshold = max(.006f, noise * 1.8f)
        val boundaries = mutableListOf(0)
        var segmentStartSample = 0
        var silenceStartFrame = -1
        for (frame in rms.indices) {
            val sample = frame * V86_FRAME_SAMPLES
            val silent = rms[frame] < silenceThreshold
            if (silent && silenceStartFrame < 0) silenceStartFrame = frame
            if (!silent && silenceStartFrame >= 0) {
                val silenceFrames = frame - silenceStartFrame
                val silenceMidSample = ((silenceStartFrame + frame) / 2) * V86_FRAME_SAMPLES
                if (silenceFrames >= V86_MIN_SILENCE_FRAMES && silenceMidSample - segmentStartSample >= V86_MIN_SPLIT_SAMPLES) {
                    boundaries += silenceMidSample.coerceIn(segmentStartSample + 1, audio.size)
                    segmentStartSample = boundaries.last()
                }
                silenceStartFrame = -1
            }
            if (sample - segmentStartSample >= V86_FORCE_SEGMENT_SAMPLES) {
                boundaries += sample.coerceIn(segmentStartSample + 1, audio.size)
                segmentStartSample = boundaries.last()
                silenceStartFrame = -1
            }
        }
        if (boundaries.last() != audio.size) boundaries += audio.size
        val raw = boundaries.zipWithNext().map { (a, b) -> V86AudioSegment(a, b) }.filter { it.end > it.start }
        if (raw.size <= 1) return raw
        val merged = mutableListOf<V86AudioSegment>()
        raw.forEach { segment ->
            if (segment.end - segment.start < V86_RATE / 2 && merged.isNotEmpty()) {
                val previous = merged.removeAt(merged.lastIndex)
                merged += V86AudioSegment(previous.start, segment.end)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun frameRms(audio: FloatArray): List<Float> {
        if (audio.isEmpty()) return emptyList()
        val count = (audio.size + V86_FRAME_SAMPLES - 1) / V86_FRAME_SAMPLES
        return List(count) { frame ->
            val start = frame * V86_FRAME_SAMPLES
            val end = min(audio.size, start + V86_FRAME_SAMPLES)
            var sum = 0.0
            for (i in start until end) sum += audio[i] * audio[i]
            sqrt(sum / max(1, end - start)).toFloat()
        }
    }

    private suspend fun decodeClip(clip: TimelineClip): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        val chunks = mutableListOf<ShortArray>()
        var total = 0
        try {
            val uri = Uri.parse(clip.uri)
            if (uri.scheme.isNullOrBlank()) extractor.setDataSource(clip.uri) else extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            require(audioTrack >= 0 && format != null) { "${clip.label}: no decodable audio stream" }
            extractor.selectTrack(audioTrack)
            extractor.seekTo(clip.sourceInUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME missing")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Audio input buffer missing")
                        input.clear()
                        val time = extractor.sampleTime
                        if (time < 0 || time >= clip.sourceOutUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && info.presentationTimeUs >= clip.sourceInUs && info.presentationTimeUs < clip.sourceOutUs) {
                            val end = (info.offset + info.size).coerceAtMost(buffer.capacity())
                            val bytes = buffer.duplicate().apply {
                                position(info.offset.coerceAtLeast(0))
                                limit(end)
                            }.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val mono = bytes.toMonoV86(channels, encoding)
                            val resampled = resampleV86(mono, rate, V86_RATE)
                            if (resampled.isNotEmpty()) {
                                chunks += resampled
                                total += resampled.size
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
            codec?.let {
                if (started) runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
        val output = FloatArray(total)
        var position = 0
        chunks.forEach { chunk ->
            chunk.forEach { sample -> output[position++] = (sample.toInt() / 32768f).coerceIn(-1f, 1f) }
        }
        return output
    }
}

internal fun internationalLanguageChoicesV86(): List<AutoCaptionLanguageV86> =
    AUTO_CAPTION_PACKS_V86.map { it.language }

internal fun autoLanguageAvailableV86(installed: Collection<AutoCaptionLanguageV86>): Boolean =
    AutoCaptionLanguageV86.BANGLA in installed && AutoCaptionLanguageV86.ENGLISH in installed

private fun OnlineRecognizerResult.toInternationalDraftsV86(
    clip: TimelineClip,
    language: AutoCaptionLanguageV86,
): List<AutoCaptionDraftV77> {
    val clean = text.cleanInternationalTextV86()
    if (clean.isBlank()) return emptyList()
    val cjkCompact = language == AutoCaptionLanguageV86.CHINESE
    val units = if (cjkCompact) {
        clean.filterNot { it.isWhitespace() }.map { it.toString() }
    } else {
        clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    if (units.isEmpty()) return emptyList()
    val times = timestamps.filter { it.isFinite() && it >= 0f }
        .map { (it * 1_000_000f).toLong().coerceIn(0, clip.durationUs) }

    fun timeFor(index: Int): Long {
        if (times.isEmpty()) return clip.durationUs * index / units.size.coerceAtLeast(1)
        if (units.size == 1) return times.first()
        val mapped = ((index.toDouble() / (units.size - 1)) * (times.size - 1))
            .roundToInt()
            .coerceIn(0, times.lastIndex)
        return times[mapped]
    }

    val groups = mutableListOf<Pair<List<String>, Long>>()
    var current = mutableListOf<String>()
    var start = timeFor(0)
    units.forEachIndexed { index, unit ->
        val timestampUs = timeFor(index)
        val chars = current.sumOf { it.length } + current.size + unit.length
        if (
            current.isNotEmpty() &&
            (chars > V86_MAX_CAPTION_CHARS || timestampUs - start > V86_MAX_CAPTION_SPAN_US || current.last().endsSentenceV86())
        ) {
            groups += current.toList() to start
            current = mutableListOf()
            start = timestampUs
        }
        if (current.isEmpty()) start = timestampUs
        current += unit
    }
    if (current.isNotEmpty()) groups += current.toList() to start

    return groups.mapIndexedNotNull { index, (group, localStart) ->
        val next = groups.getOrNull(index + 1)?.second
        val end = (
            next ?: min(
                clip.durationUs,
                max(localStart + 900_000L, (times.lastOrNull() ?: localStart) + 1_000_000L),
            )
        ).coerceAtLeast(localStart + V86_MIN_CAPTION_US).coerceAtMost(clip.durationUs)
        if (end <= localStart) {
            null
        } else {
            val captionText = if (cjkCompact) group.joinToString("") else group.joinToString(" ")
            AutoCaptionDraftV77(
                captionText.cleanInternationalTextV86(),
                clip.timelineStartUs + localStart,
                clip.timelineStartUs + end,
            )
        }
    }
}

private fun String.cleanInternationalTextV86(): String =
    replace('▁', ' ').replace(Regex("\\s+"), " ").trim()

private fun String.endsSentenceV86(): Boolean =
    endsWith('.') || endsWith('?') || endsWith('!') || endsWith('।') ||
        endsWith(';') || endsWith(':') || endsWith('。') || endsWith('？') || endsWith('！')

private fun ByteBuffer.toMonoV86(channels: Int, encoding: Int): ShortArray {
    val channelCount = channels.coerceAtLeast(1)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val frames = remaining() / 4 / channelCount
            ShortArray(frames) { frame ->
                var sum = 0f
                repeat(channelCount) { channel -> sum += getFloat((frame * channelCount + channel) * 4) }
                ((sum / channelCount).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        AudioFormat.ENCODING_PCM_8BIT -> {
            val frames = remaining() / channelCount
            ShortArray(frames) { frame ->
                var sum = 0
                repeat(channelCount) { channel -> sum += (get(frame * channelCount + channel).toInt() and 0xff) - 128 }
                ((sum / channelCount) shl 8)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        else -> {
            val frames = remaining() / 2 / channelCount
            ShortArray(frames) { frame ->
                var sum = 0L
                repeat(channelCount) { channel -> sum += getShort((frame * channelCount + channel) * 2) }
                (sum / channelCount)
                    .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                    .toShort()
            }
        }
    }
}

private fun resampleV86(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return ShortArray(0)
    if (sourceRate == targetRate) return input
    val count = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
    val ratio = sourceRate.toDouble() / targetRate
    return ShortArray(count) { index ->
        val position = index * ratio
        val left = floor(position).toInt().coerceIn(0, input.lastIndex)
        val right = min(left + 1, input.lastIndex)
        val fraction = position - left
        (input[left] + (input[right] - input[left]) * fraction)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun List<AutoCaptionDraftV77>.normalizeInternationalDraftsV86(): List<AutoCaptionDraftV77> {
    val sorted = sortedWith(compareBy<AutoCaptionDraftV77> { it.timelineStartUs }.thenBy { it.timelineEndUs })
    val output = mutableListOf<AutoCaptionDraftV77>()
    var previousEnd = -1L
    sorted.forEach { item ->
        val start = max(item.timelineStartUs, previousEnd)
        val end = max(start + V86_MIN_CAPTION_US, item.timelineEndUs)
        if (item.text.isNotBlank() && end > start) {
            output += item.copy(timelineStartUs = start, timelineEndUs = end)
            previousEnd = end
        }
    }
    return output
}
