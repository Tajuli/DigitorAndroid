package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.tajuli.digitorandroid.editor.model.EyePose
import com.tajuli.digitorandroid.editor.model.EyeSample
import com.tajuli.digitorandroid.editor.model.EyeTrack
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
        val result = runCatching {
            gson.fromJson(file(context, clip).readText(), EyeTrack::class.java)
        }.getOrNull()?.takeIf { it.covers(clip) } ?: return null
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
 * Full-clip face motion tracking on the same ncnn Vulkan runtime as PP-MattingV2.
 *
 * The decoded working frame is capped at 512 px only to preserve enough source detail for ROI
 * sampling. Native inference is much smaller: BlazeFace reacquisition runs at 128x128 and the
 * persistent Face Mesh ROI runs at 192x192. Between reacquisitions the native engine follows the
 * landmarks-to-ROI crop, so the full frame is not sent through a landmark network.
 */
class EyeTrackingAnalyzer(private val context: Context) {
    private fun downscaleDecodeFrame(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= DECODE_LONG_EDGE) return bitmap
        val scale = DECODE_LONG_EDGE.toFloat() / edge
        val result = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
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
        return downscaleDecodeFrame(bitmap)
    }

    private fun stabilize(previous: EyePose?, fresh: EyePose?): EyePose? {
        fresh ?: return null
        previous ?: return fresh
        if (
            hypot(
                fresh.left.x - previous.left.x,
                fresh.left.y - previous.left.y,
            ) >= max(fresh.left.radius, .01f) * .20f
        ) {
            return fresh
        }
        return fresh.copy(
            left = previous.left.interpolate(fresh.left, .80f).copy(open = fresh.left.open),
            right = previous.right.interpolate(fresh.right, .80f).copy(open = fresh.right.open),
        )
    }

    suspend fun analyze(
        clip: TimelineClip,
        onBackend: (gpuAccelerated: Boolean) -> Unit = {},
        onBackendFailure: (String?) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): EyeTrack {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "DigitorFaceTrackingNcnn").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()

        return try {
            withContext(dispatcher) {
                analysisMutex.withLock {
                    EyeTrackStore.load(context, clip)?.let {
                        onProgress(100)
                        return@withLock it
                    }

                    var lease: PreviewExportCoordinator.AnalysisLease? = null
                    var tracker: NcnnVulkanFaceTrackerV103? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        // Give ncnn Vulkan the same exclusive GPU/codec handoff used by PP-MattingV2.
                        lease = PreviewExportCoordinator.acquireAnalysisLease("Face Tracking")
                        currentCoroutineContext().ensureActive()

                        val activeTracker = NcnnVulkanFaceTrackerV103.create(context)
                        tracker = activeTracker
                        onBackend(activeTracker.gpuAccelerated)
                        if (!activeTracker.gpuAccelerated) {
                            onBackendFailure(
                                "ncnn Vulkan was unavailable; tracking is using ncnn CPU fallback",
                            )
                        }

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
                                        DECODE_LONG_EDGE.toFloat() /
                                            max(info.size.width, info.size.height),
                                    )
                                    decoder.setTargetSize(
                                        max(1, (info.size.width * scale).roundToInt()),
                                        max(1, (info.size.height * scale).roundToInt()),
                                    )
                                }
                            } else {
                                decodeLegacyImage(Uri.parse(clip.uri))
                            }
                            try {
                                val pose = activeTracker.detect(bitmap)
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
                                DECODE_LONG_EDGE.toFloat() / max(width, height),
                            )
                            val decodeWidth = max(1, (width * scale).roundToInt())
                            val decodeHeight = max(1, (height * scale).roundToInt())
                            val duration =
                                (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1L)

                            var previousPose: EyePose? = null
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
                                    )?.let { downscaleDecodeFrame(it) }
                                }

                                val rawPose = try {
                                    bitmap?.let(activeTracker::detect)
                                } finally {
                                    bitmap?.recycle()
                                }
                                val pose = stabilize(previousPose, rawPose)
                                previousPose = pose
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
                                    )?.let { downscaleDecodeFrame(it) }
                                }
                                val rawPose = try {
                                    bitmap?.let(activeTracker::detect)
                                } finally {
                                    bitmap?.recycle()
                                }
                                samples += EyeSample(
                                    clip.sourceOutUs,
                                    stabilize(previousPose, rawPose),
                                )
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
                            tracker?.close()
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
        private const val DECODE_LONG_EDGE = 512
        private const val SAMPLE_INTERVAL_US = 83_333L
        private val analysisMutex = Mutex()
    }
}
