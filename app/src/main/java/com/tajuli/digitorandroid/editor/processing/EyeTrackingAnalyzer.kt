package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.os.Build
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.tajuli.digitorandroid.editor.model.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Complete tracks only: cancelling never publishes partial coverage or replaces a good track. */
object EyeTrackStore {
    private val cache = ConcurrentHashMap<String, EyeTrack>()
    private val gson = Gson()
    private val checked = ConcurrentHashMap.newKeySet<String>()
    private fun key(clip: TimelineClip) = MessageDigest.getInstance("SHA-256")
        .digest("${clip.uri}|${clip.sourceInUs}|${clip.sourceOutUs}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun file(context: Context, clip: TimelineClip) = File(context.filesDir, "eye_tracks_v1/${key(clip)}.json")
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
        } finally { temp.delete() }
    }
}

/** Accurate 24 Hz source-time contours, separate from the sparse beauty cache. */
class EyeTrackingAnalyzer(private val context: Context) {
    private fun downscale(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= 720) return bitmap
        val scale = 720f / edge
        val result = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width*scale).toInt()),
            max(1, (bitmap.height*scale).toInt()), true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }
    private fun decodeLegacyImage(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1440) options.inSampleSize *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode image")
        return downscale(bitmap)
    }
    companion object { private val analysisMutex = Mutex() }

    suspend fun analyze(clip: TimelineClip, onProgress: (Int) -> Unit = {}): EyeTrack =
        withContext(Dispatchers.Default) { analysisMutex.withLock {
            EyeTrackStore.load(context, clip)?.let { onProgress(100); return@withLock it }
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(.08f).enableTracking().build()
            val detector = FaceDetection.getClient(options)
            val retriever = MediaMetadataRetriever()
            var selectedIdentity: Int? = null
            var previous: EyePose? = null
            suspend fun detect(bitmap: Bitmap): EyePose? {
                // Await the native task to completion before releasing detector/bitmap on cancel.
                val faces = withContext(NonCancellable) {
                    kotlin.coroutines.suspendCoroutine<List<Face>> { continuation ->
                        detector.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resumeWithException(it) }
                            .addOnCanceledListener { continuation.resumeWithException(CancellationException("Face detection cancelled")) }
                    }
                }
                currentCoroutineContext().ensureActive()
                val face = if (selectedIdentity == null) faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    else faces.firstOrNull { it.trackingId == selectedIdentity }
                if (face == null) { selectedIdentity = null; previous = null; return null }
                selectedIdentity = face.trackingId
                fun eye(type: Int, probability: Float?): TrackedEye? {
                    val points = face.getContour(type)?.points ?: return null
                    if (points.size < 8) return null
                    val a = points[0]; val b = points[points.size / 2]
                    val length = hypot(b.x-a.x, b.y-a.y)
                    if (length < 3f) return null
                    var angle = atan2(b.y-a.y, b.x-a.x)
                    if (cos(angle) < 0) angle += PI.toFloat()
                    val cx = (a.x+b.x)*.5f; val cy = (a.y+b.y)*.5f
                    val aperture = points.maxOf { abs(-(it.x-cx)*sin(angle)+(it.y-cy)*cos(angle)) } / length
                    val openness = min(((aperture-.035f)/.10f).coerceIn(0f, 1f), probability ?: 1f)
                    return TrackedEye(cx/bitmap.width, cy/bitmap.height, length*.5f/bitmap.width, angle, openness)
                }
                val left = eye(FaceContour.LEFT_EYE, face.leftEyeOpenProbability) ?: return null
                val right = eye(FaceContour.RIGHT_EYE, face.rightEyeOpenProbability) ?: return null
                val fresh = EyePose(left, right, selectedIdentity)
                // Mild stabilization only for sub-eye-width jitter; preserve fast motion and blinks.
                val old = previous
                val stable = if (old != null && old.identity == fresh.identity &&
                    hypot(left.x-old.left.x, left.y-old.left.y) < left.radius*.20f) {
                    fresh.copy(left = old.left.interpolate(left, .8f).copy(open = left.open),
                        right = old.right.interpolate(right, .8f).copy(open = right.open))
                } else fresh
                previous = stable
                return stable
            }
            var lease: PreviewExportCoordinator.AnalysisLease? = null
            try {
                if (!clip.isImageV21) lease = PreviewExportCoordinator.acquireAnalysisLease()
                currentCoroutineContext().ensureActive()
                val samples = ArrayList<EyeSample>()
                if (clip.isImageV21) {
                    val bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(clip.uri))) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = min(1f, 720f / max(info.size.width, info.size.height))
                        decoder.setTargetSize(max(1, (info.size.width*scale).toInt()), max(1, (info.size.height*scale).toInt()))
                    }
                    else decodeLegacyImage(Uri.parse(clip.uri))
                    try {
                        val pose = detect(bitmap)
                        // Still-image poses are repeated at the same cadence for ordinary interpolation.
                        var time = clip.sourceInUs
                        while (time < clip.sourceOutUs) { samples += EyeSample(time, pose); time += 41_667L }
                        samples += EyeSample(clip.sourceOutUs, pose)
                    } finally { bitmap.recycle() }
                    onProgress(100)
                } else {
                    retriever.setDataSource(context, Uri.parse(clip.uri))
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                    val scale = min(1f, 720f/max(width, height))
                    var time = clip.sourceInUs
                    while (time < clip.sourceOutUs) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST,
                            max(1, (width*scale).toInt()), max(1, (height*scale).toInt()))
                        else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)?.let { downscale(it) }
                        val pose = try { bitmap?.let { detect(it) } } finally { bitmap?.recycle() }
                        samples += EyeSample(time, pose)
                        onProgress(((time-clip.sourceInUs)*100/(clip.sourceOutUs-clip.sourceInUs).coerceAtLeast(1)).toInt())
                        time += 41_667L
                    }
                }
                currentCoroutineContext().ensureActive()
                val track = EyeTrack(clip.uri, clip.sourceInUs, clip.sourceOutUs, samples)
                check(samples.any { it.pose != null }) { "No clear eyes found. Use a visible, front-facing face." }
                EyeTrackStore.save(context, clip, track)
                onProgress(100)
                track
            } finally {
                try { runCatching { retriever.release() }; detector.close() } finally { lease?.close() }
            }
        } }
}
