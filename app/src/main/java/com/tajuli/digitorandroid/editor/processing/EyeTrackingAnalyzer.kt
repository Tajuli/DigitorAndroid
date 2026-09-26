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
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.EyePose
import com.tajuli.digitorandroid.editor.model.EyeSample
import com.tajuli.digitorandroid.editor.model.EyeTrack
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TrackedEye
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

private data class FaceRoiV103(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

private data class InferenceFrameV103(
    val bitmap: Bitmap,
    val region: FaceRoiV103,
)

/**
 * Fast full-clip face tracking.
 *
 * Preview GPU/codec resources are released before MediaPipe is created so the GPU delegate gets the
 * first chance at the device GPU. Decoded frames use a 512 px working edge only as a crop source;
 * landmark inference itself is limited to a 320 px input. After acquisition, a padded motion-safe
 * face ROI is tracked and periodically reacquired from the full frame.
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

    private fun fullFrameRegion(bitmap: Bitmap): FaceRoiV103 =
        FaceRoiV103(0, 0, bitmap.width, bitmap.height)

    private fun motionSafeRoi(pose: EyePose, width: Int, height: Int): FaceRoiV103? {
        val face = pose.face ?: return null
        if (width <= 1 || height <= 1) return null

        val faceWidth = ((face.right - face.left) * width).coerceAtLeast(1f)
        val faceHeight = ((face.bottom - face.top) * height).coerceAtLeast(1f)
        val maxSquare = min(width, height).coerceAtLeast(1)
        val side = max(
            ROI_MIN_SIDE_PX.toFloat(),
            max(faceWidth, faceHeight) * ROI_EXPANSION,
        ).roundToInt().coerceAtMost(maxSquare)

        val centerX = ((face.left + face.right) * .5f * width).roundToInt()
        val centerY = ((face.top + face.bottom) * .5f * height).roundToInt()
        val left = (centerX - side / 2).coerceIn(0, (width - side).coerceAtLeast(0))
        val top = (centerY - side / 2).coerceIn(0, (height - side).coerceAtLeast(0))
        return FaceRoiV103(left, top, left + side, top + side)
    }

    private fun prepareInferenceFrame(
        frame: Bitmap,
        roi: FaceRoiV103?,
    ): InferenceFrameV103 {
        val region = roi ?: fullFrameRegion(frame)
        val cropped = if (
            region.left == 0 &&
            region.top == 0 &&
            region.right == frame.width &&
            region.bottom == frame.height
        ) {
            frame
        } else {
            Bitmap.createBitmap(
                frame,
                region.left,
                region.top,
                region.width,
                region.height,
            )
        }

        val scale = LANDMARK_INPUT_LONG_EDGE.toFloat() /
            max(cropped.width, cropped.height).coerceAtLeast(1)
        val targetWidth = max(1, (cropped.width * scale).roundToInt())
        val targetHeight = max(1, (cropped.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)

        if (cropped !== frame && scaled !== cropped) cropped.recycle()
        if (scaled === frame) {
            // The decoded working frame is intentionally owned by the caller. This branch is only
            // possible when a tiny source already matches 320 px, so make a separate inference copy.
            return InferenceFrameV103(
                frame.copy(Bitmap.Config.ARGB_8888, false),
                region,
            )
        }
        return InferenceFrameV103(scaled, region)
    }

    private fun mapRect(
        rect: BeautyRectV28?,
        region: FaceRoiV103,
        frameWidth: Int,
        frameHeight: Int,
    ): BeautyRectV28? {
        rect ?: return null
        fun x(value: Float) =
            (region.left + value * region.width) / frameWidth.toFloat()
        fun y(value: Float) =
            (region.top + value * region.height) / frameHeight.toFloat()
        return BeautyRectV28(
            x(rect.left),
            y(rect.top),
            x(rect.right),
            y(rect.bottom),
        ).normalized()
    }

    private fun mapEye(
        eye: TrackedEye,
        region: FaceRoiV103,
        frameWidth: Int,
        frameHeight: Int,
    ): TrackedEye {
        return eye.copy(
            x = (region.left + eye.x * region.width) / frameWidth.toFloat(),
            y = (region.top + eye.y * region.height) / frameHeight.toFloat(),
            radius = eye.radius * region.width / frameWidth.toFloat(),
        )
    }

    private fun remapPose(
        pose: EyePose,
        region: FaceRoiV103,
        frameWidth: Int,
        frameHeight: Int,
    ): EyePose = EyePose(
        left = mapEye(pose.left, region, frameWidth, frameHeight),
        right = mapEye(pose.right, region, frameWidth, frameHeight),
        identity = pose.identity,
        face = mapRect(pose.face, region, frameWidth, frameHeight),
        mouth = mapRect(pose.mouth, region, frameWidth, frameHeight),
    )

    private fun stabilizeGlobal(previous: EyePose?, fresh: EyePose?): EyePose? {
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

    private fun detectFrame(
        detector: EyeLandmarkDetector,
        frame: Bitmap,
        timeUs: Long,
        roi: FaceRoiV103?,
    ): EyePose? {
        val input = prepareInferenceFrame(frame, roi)
        return try {
            detector.detect(
                bitmap = input.bitmap,
                timeUs = timeUs,
                stabilizeInInputSpace = false,
            )?.let {
                remapPose(
                    pose = it,
                    region = input.region,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                )
            }
        } finally {
            input.bitmap.recycle()
        }
    }

    private fun detectTrackedFrame(
        detector: EyeLandmarkDetector,
        frame: Bitmap,
        timeUs: Long,
        preferredRoi: FaceRoiV103?,
    ): EyePose? {
        val first = detectFrame(detector, frame, timeUs, preferredRoi)
        if (first != null || preferredRoi == null) return first

        // If the face outruns the padded ROI, reacquire from the whole frame immediately. VIDEO mode
        // requires increasing timestamps, so the retry advances MediaPipe's timestamp by 1 ms only.
        return detectFrame(
            detector = activeDetector,
            frame = frame,
            timeUs = timeUs + ROI_RETRY_TIMESTAMP_US,
            roi = null,
        )
    }

    suspend fun analyze(
        clip: TimelineClip,
        onBackend: (gpuAccelerated: Boolean) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): EyeTrack {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "DigitorFaceTrackingGPU").apply {
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
                    var detector: EyeLandmarkDetector? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        // GPU/codec handoff must happen before MediaPipe GPU delegate creation.
                        lease = PreviewExportCoordinator.acquireAnalysisLease("Face Tracking")
                        currentCoroutineContext().ensureActive()

                        val activeDetector = EyeLandmarkDetector(
                            context = context,
                            runningMode = RunningMode.VIDEO,
                            preferGpu = true,
                        )
                        detector = activeDetector
                        onBackend(activeDetector.gpuAccelerated)

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
                                val pose = detectFrame(
                                    detector = activeDetector,
                                    frame = bitmap,
                                    timeUs = clip.sourceInUs,
                                    roi = null,
                                )
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
                            val decodeScale = min(
                                1f,
                                DECODE_LONG_EDGE.toFloat() / max(width, height),
                            )
                            val decodeWidth =
                                max(1, (width * decodeScale).roundToInt())
                            val decodeHeight =
                                max(1, (height * decodeScale).roundToInt())
                            val duration =
                                (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1L)

                            var time = clip.sourceInUs
                            var sampleIndex = 0
                            var activeRoi: FaceRoiV103? = null
                            var previousPose: EyePose? = null

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
                                    bitmap?.let { frame ->
                                        val periodicReacquire =
                                            activeRoi == null ||
                                                sampleIndex % FULL_REACQUIRE_SAMPLES == 0
                                        detectTrackedFrame(
                                            detector = activeDetector,
                                            frame = frame,
                                            timeUs = time,
                                            preferredRoi =
                                                if (periodicReacquire) null else activeRoi,
                                        ).also { pose ->
                                            activeRoi = pose?.let {
                                                motionSafeRoi(it, frame.width, frame.height)
                                            }
                                        }
                                    }
                                } finally {
                                    bitmap?.recycle()
                                }

                                val pose = stabilizeGlobal(previousPose, rawPose)
                                previousPose = pose
                                samples += EyeSample(time, pose)
                                onProgress(
                                    (((time - clip.sourceInUs) * 100L) / duration)
                                        .toInt()
                                        .coerceIn(0, 99),
                                )
                                sampleIndex += 1
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
                                    bitmap?.let { frame ->
                                        detectTrackedFrame(
                                            detector = activeDetector,
                                            frame = frame,
                                            timeUs = clip.sourceOutUs,
                                            preferredRoi = activeRoi,
                                        )
                                    }
                                } finally {
                                    bitmap?.recycle()
                                }
                                val pose = stabilizeGlobal(previousPose, rawPose)
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
                            detector?.close()
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
        private const val LANDMARK_INPUT_LONG_EDGE = 320
        private const val ROI_MIN_SIDE_PX = 112
        private const val ROI_EXPANSION = 2.55f
        private const val FULL_REACQUIRE_SAMPLES = 6
        private const val ROI_RETRY_TIMESTAMP_US = 1_000L
        private const val SAMPLE_INTERVAL_US = 83_333L
        private val analysisMutex = Mutex()
    }
}
