package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.tajuli.digitorandroid.editor.model.EyeSample
import com.tajuli.digitorandroid.editor.model.EyeTrack
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Complete tracks only: cancelling never publishes partial coverage or replaces a good track. */
object EyeTrackStore {
    private val cache = ConcurrentHashMap<String, EyeTrack>()
    private val gson = Gson()
    private val checked = ConcurrentHashMap.newKeySet<String>()

    private fun key(clip: TimelineClip) = MessageDigest.getInstance("SHA-256")
        .digest("${clip.uri}|${clip.sourceInUs}|${clip.sourceOutUs}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun file(context: Context, clip: TimelineClip) =
        File(context.filesDir, "eye_tracks_v2/${key(clip)}.json")

    fun load(context: Context, clip: TimelineClip): EyeTrack? {
        val key = key(clip)
        cache[key]?.let { return it }
        if (!checked.add(key)) return null
        val result = runCatching { gson.fromJson(file(context, clip).readText(), EyeTrack::class.java) }
            .getOrNull()?.takeIf { it.covers(clip) } ?: return null
        cache[key] = result
        return result
    }

    fun save(context: Context, clip: TimelineClip, track: EyeTrack) {
        require(track.covers(clip))
        val target = file(context, clip)
        target.parentFile!!.mkdirs()
        val temp = File.createTempFile("eyes", ".tmp", target.parentFile)
        try {
            temp.writeText(gson.toJson(track))
            check(temp.renameTo(target)) { "Could not save eye tracking" }
            cache[key(clip)] = track
            checked += key(clip)
        } finally {
            temp.delete()
        }
    }
}

/**
 * Fast full-clip face tracking.
 *
 * A dedicated single thread keeps MediaPipe's GPU delegate on the thread where it was created.
 * VIDEO mode reuses MediaPipe temporal tracking between samples. Sampling at 12 Hz stays below the
 * 100 ms interpolation ceiling in EyeTrack while cutting inference work roughly in half versus the
 * old 24 Hz ML Kit scan.
 */
class EyeTrackingAnalyzer(private val context: Context) {
    private fun downscale(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= ANALYSIS_LONG_EDGE) return bitmap
        val scale = ANALYSIS_LONG_EDGE.toFloat() / edge
        val result = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    private fun decodeLegacyImage(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
            options.inSampleSize *= 2
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Could not decode image")
        return downscale(bitmap)
    }

    suspend fun analyze(
        clip: TimelineClip,
        onProgress: (Int) -> Unit = {},
        onBackend: (gpuAccelerated: Boolean) -> Unit = {},
    ): EyeTrack {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "DigitorFaceTrackingGPU").apply { priority = Thread.NORM_PRIORITY }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                analysisMutex.withLock {
                    EyeTrackStore.load(context, clip)?.let {
                        onProgress(100)
                        return@withLock it
                    }

                    val detector = EyeLandmarkDetector(
                        context = context,
                        runningMode = RunningMode.VIDEO,
                        preferGpu = true,
                    )
                    onBackend(detector.gpuAccelerated)

                    val retriever = MediaMetadataRetriever()
                    var lease: PreviewExportCoordinator.AnalysisLease? = null
                    try {
                        if (!clip.isImageV21) {
                            lease = PreviewExportCoordinator.acquireAnalysisLease()
                        }
                        currentCoroutineContext().ensureActive()
                        val samples = ArrayList<EyeSample>()

                        if (clip.isImageV21) {
                            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                                ImageDecoder.decodeBitmap(
                                    ImageDecoder.createSource(
                                        context.contentResolver,
                                        Uri.parse(clip.uri),
                                    ),
                                ) { decoder, info, _ ->
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                    val scale = min(
                                        1f,
                                        ANALYSIS_LONG_EDGE.toFloat() /
                                            max(info.size.width, info.size.height),
                                    )
                                    decoder.setTargetSize(
                                        max(1, (info.size.width * scale).toInt()),
                                        max(1, (info.size.height * scale).toInt()),
                                    )
                                }
                            } else {
                                decodeLegacyImage(Uri.parse(clip.uri))
                            }
                            try {
                                val pose = detector.detect(bitmap, clip.sourceInUs)
                                var time = clip.sourceInUs
                                while (time < clip.sourceOutUs) {
                                    samples += EyeSample(time, pose)
                                    time += SAMPLE_INTERVAL_US
                                }
                                samples += EyeSample(clip.sourceOutUs, pose)
                            } finally {
                                bitmap.recycle()
                            }
                            onProgress(100)
                        } else {
                            retriever.setDataSource(context, Uri.parse(clip.uri))
                            val width = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                ?.toIntOrNull() ?: 720
                            val height = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                ?.toIntOrNull() ?: 720
                            val scale = min(
                                1f,
                                ANALYSIS_LONG_EDGE.toFloat() / max(width, height),
                            )
                            val decodeWidth = max(1, (width * scale).toInt())
                            val decodeHeight = max(1, (height * scale).toInt())
                            val duration = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1L)

                            var time = clip.sourceInUs
                            while (time < clip.sourceOutUs) {
                                currentCoroutineContext().ensureActive()
                                val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                                    retriever.getScaledFrameAtTime(
                                        time,
                                        MediaMetadataRetriever.OPTION_CLOSEST,
                                        decodeWidth,
                                        decodeHeight,
                                    )
                                } else {
                                    retriever.getFrameAtTime(
                                        time,
                                        MediaMetadataRetriever.OPTION_CLOSEST,
                                    )?.let { downscale(it) }
                                }
                                val pose = try {
                                    bitmap?.let { detector.detect(it, time) }
                                } finally {
                                    bitmap?.recycle()
                                }
                                samples += EyeSample(time, pose)
                                onProgress(
                                    (((time - clip.sourceInUs) * 100L) / duration)
                                        .toInt()
                                        .coerceIn(0, 99),
                                )
                                time += SAMPLE_INTERVAL_US
                            }
                            if (samples.lastOrNull()?.timeUs != clip.sourceOutUs) {
                                currentCoroutineContext().ensureActive()
                                val bitmap = if (Build.VERSION.SDK_INT >= 27) {
                                    retriever.getScaledFrameAtTime(
                                        clip.sourceOutUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST,
                                        decodeWidth,
                                        decodeHeight,
                                    )
                                } else {
                                    retriever.getFrameAtTime(
                                        clip.sourceOutUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST,
                                    )?.let { downscale(it) }
                                }
                                val pose = try {
                                    bitmap?.let { detector.detect(it, clip.sourceOutUs) }
                                } finally {
                                    bitmap?.recycle()
                                }
                                samples += EyeSample(clip.sourceOutUs, pose)
                            }
                        }

                        currentCoroutineContext().ensureActive()
                        check(samples.any { it.pose != null }) {
                            "No clear face found. Keep one face clearly visible and retry."
                        }
                        val track = EyeTrack(
                            clip.uri,
                            clip.sourceInUs,
                            clip.sourceOutUs,
                            samples,
                        )
                        EyeTrackStore.save(context, clip, track)
                        onProgress(100)
                        track
                    } finally {
                        try {
                            runCatching { retriever.release() }
                            detector.close()
                        } finally {
                            lease?.close()
                        }
                    }
                }
            }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
    }

    companion object {
        private const val ANALYSIS_LONG_EDGE = 512
        private const val SAMPLE_INTERVAL_US = 83_333L
        private val analysisMutex = Mutex()
    }
}
