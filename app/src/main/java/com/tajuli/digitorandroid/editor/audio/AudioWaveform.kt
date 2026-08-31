package com.tajuli.digitorandroid.editor.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Source-level waveform. Peaks are normalized 0..1 and cover the whole decoded audio stream. */
data class AudioWaveform(
    val durationUs: Long,
    val peaks: FloatArray,
) {
    init {
        require(durationUs > 0L)
        require(peaks.isNotEmpty())
    }

    fun peakAt(sourceTimeUs: Long): Float {
        val index = ((sourceTimeUs.coerceIn(0L, durationUs - 1L).toDouble() / durationUs) * peaks.size)
            .toInt()
            .coerceIn(0, peaks.lastIndex)
        return peaks[index].coerceIn(0f, 1f)
    }

    /** Returns the loudest source peak inside a visible timeline column. */
    fun peakBetween(startUs: Long, endUs: Long): Float {
        val safeStart = startUs.coerceIn(0L, durationUs - 1L)
        val safeEnd = endUs.coerceIn(safeStart + 1L, durationUs)
        val first = ((safeStart.toDouble() / durationUs) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
        val lastExclusive = ceil((safeEnd.toDouble() / durationUs) * peaks.size)
            .toInt()
            .coerceIn(first + 1, peaks.size)
        var value = 0f
        for (index in first until lastExclusive) value = max(value, peaks[index])
        return value.coerceIn(0f, 1f)
    }
}

/**
 * Decodes source audio to a compact peak envelope once, then shares it across split/trimmed clips.
 * Cache files live under cacheDir so projects never depend on generated waveform data for validity.
 */
class AudioWaveformRepository private constructor(private val appContext: Context) {
    private val memory = object : android.util.LruCache<String, AudioWaveform>(MEMORY_ENTRIES) {}
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cacheDir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }

    suspend fun load(uriString: String): AudioWaveform? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val key = fingerprint(uri)
        memory.get(key)?.let { return@withContext it }
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            lock.withLock {
                memory.get(key)?.let { return@withLock it }
                readDisk(key)?.let { cached ->
                    memory.put(key, cached)
                    return@withLock cached
                }
                val decoded = runCatching { decode(uri) }.getOrNull() ?: return@withLock null
                memory.put(key, decoded)
                runCatching { writeDisk(key, decoded) }
                decoded
            }
        } finally {
            if (!lock.isLocked) locks.remove(key, lock)
        }
    }

    private fun decode(uri: Uri): AudioWaveform {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME unavailable")
            val declaredDurationUs = inputFormat.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0L }
            val initialBucketUs = declaredDurationUs
                ?.let { ceil(it.toDouble() / MAX_PEAKS).toLong() }
                ?.coerceAtLeast(MIN_BUCKET_US)
                ?: MIN_BUCKET_US
            val accumulator = PeakAccumulator(initialBucketUs)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            var outputFormat = inputFormat
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input unavailable")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        codec.getOutputBuffer(outputIndex)?.let { buffer ->
                            if (info.size > 0) {
                                accumulatePcm(
                                    buffer = buffer,
                                    offset = info.offset,
                                    size = info.size,
                                    presentationTimeUs = info.presentationTimeUs.coerceAtLeast(0L),
                                    format = outputFormat,
                                    accumulator = accumulator,
                                )
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val durationUs = max(declaredDurationUs ?: 0L, accumulator.maxTimeUs.coerceAtLeast(1L))
            return AudioWaveform(durationUs, normalize(accumulator.finish(durationUs)))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun accumulatePcm(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        format: MediaFormat,
        accumulator: PeakAccumulator,
    ) {
        val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.coerceAtLeast(1) ?: 48_000
        val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.coerceAtLeast(1) ?: 1
        val encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = bytesPerSample(encoding)
        if (bytesPerSample <= 0) return
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return

        val pcm = buffer.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(offset.coerceIn(0, pcm.limit()))
        pcm.limit((offset + size).coerceIn(pcm.position(), pcm.capacity()))
        val frames = pcm.remaining() / frameBytes
        for (frame in 0 until frames) {
            var peak = 0f
            repeat(channels) {
                peak = max(peak, readSample(pcm, encoding))
            }
            val timeUs = presentationTimeUs + frame.toLong() * 1_000_000L / sampleRate
            accumulator.add(timeUs, peak)
        }
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> abs(((buffer.get().toInt() and 0xFF) - 128) / 128f)
        AudioFormat.ENCODING_PCM_FLOAT -> abs(buffer.float).coerceAtMost(1f)
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            val signed = b0 or (b1 shl 8) or (b2 shl 16)
            abs(signed / 8_388_608f).coerceAtMost(1f)
        }
        AudioFormat.ENCODING_PCM_32BIT -> abs(buffer.int / 2_147_483_648f).coerceAtMost(1f)
        else -> abs(buffer.short / 32_768f).coerceAtMost(1f)
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }

    private fun normalize(input: FloatArray): FloatArray {
        val maxPeak = input.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        if (maxPeak <= SILENCE_FLOOR) return input
        val gain = 1f / maxPeak
        return FloatArray(input.size) { index -> (input[index] * gain).coerceIn(0f, 1f) }
    }

    private fun fingerprint(uri: Uri): String {
        var length = -1L
        var modified = -1L
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.let { file ->
                length = file.length()
                modified = file.lastModified()
            }
        } else {
            length = runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            modified = runCatching {
                appContext.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
            }.getOrDefault(-1L)
        }
        return sha256("${uri}|$length|$modified")
    }

    private fun readDisk(key: String): AudioWaveform? {
        val file = File(cacheDir, "$key.dwf")
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) return@use null
                val durationUs = input.readLong()
                val count = input.readInt()
                if (durationUs <= 0L || count !in 1..MAX_PEAKS) return@use null
                val peaks = FloatArray(count) { input.readFloat().coerceIn(0f, 1f) }
                AudioWaveform(durationUs, peaks)
            }
        }.getOrNull()
    }

    private fun writeDisk(key: String, waveform: AudioWaveform) {
        val target = File(cacheDir, "$key.dwf")
        val temp = File(cacheDir, "$key.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { output ->
            output.writeInt(CACHE_MAGIC)
            output.writeInt(CACHE_VERSION)
            output.writeLong(waveform.durationUs)
            output.writeInt(waveform.peaks.size)
            waveform.peaks.forEach(output::writeFloat)
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun MediaFormat.intOrNull(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.longOrNull(key: String): Long? = runCatching { getLong(key) }.getOrNull()

    internal class PeakAccumulator(initialBucketUs: Long) {
        var bucketUs: Long = initialBucketUs.coerceAtLeast(1L)
            private set
        private val values = FloatArray(MAX_PEAKS)
        var maxTimeUs: Long = 0L
            private set

        fun add(timeUs: Long, amplitude: Float) {
            val safeTime = timeUs.coerceAtLeast(0L)
            while (safeTime / bucketUs >= MAX_PEAKS) compact()
            val index = (safeTime / bucketUs).toInt().coerceIn(0, MAX_PEAKS - 1)
            values[index] = max(values[index], amplitude.coerceIn(0f, 1f))
            maxTimeUs = max(maxTimeUs, safeTime + 1L)
        }

        private fun compact() {
            for (index in 0 until MAX_PEAKS / 2) {
                values[index] = max(values[index * 2], values[index * 2 + 1])
            }
            java.util.Arrays.fill(values, MAX_PEAKS / 2, MAX_PEAKS, 0f)
            bucketUs *= 2L
        }

        fun finish(durationUs: Long): FloatArray {
            val count = ceil(durationUs.coerceAtLeast(1L).toDouble() / bucketUs)
                .toInt()
                .coerceIn(1, MAX_PEAKS)
            return values.copyOf(count)
        }
    }

    companion object {
        private const val MEMORY_ENTRIES = 16
        private const val CACHE_DIR = "audio_waveforms_v1"
        private const val CACHE_MAGIC = 0x44574631 // DWF1
        private const val CACHE_VERSION = 1
        private const val MAX_PEAKS = 8192
        private const val MIN_BUCKET_US = 20_000L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val SILENCE_FLOOR = 0.0001f

        @Volatile private var instance: AudioWaveformRepository? = null

        fun get(context: Context): AudioWaveformRepository = instance ?: synchronized(this) {
            instance ?: AudioWaveformRepository(context.applicationContext).also { instance = it }
        }
    }
}
