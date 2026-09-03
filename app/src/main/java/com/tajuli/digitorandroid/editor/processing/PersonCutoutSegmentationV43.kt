package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Google's dedicated portrait segmentation model is a better fit for background replacement than
// SelfieMulticlass. MediaPipe's official Android confidence-mask sample consumes index 0 for this
// model; unlike the multiclass model there is not a separate background/person mask pair to index.
private const val PERSON_MODEL_ASSET_V43 = "selfie_segmenter.tflite"
private const val PERSON_CONFIDENCE_MASK_INDEX_V43 = 0
private const val PERSON_ANALYSIS_LONG_EDGE_V43 = 384
private const val MIN_PERSON_ANCHORS_V43 = 8
private const val MAX_PERSON_ANCHORS_V43 = 120
private const val PERSON_ANCHORS_PER_SECOND_V43 = 2L

data class PersonCutoutMaskFrameV43(
    val sourceTimeUs: Long,
    val file: File,
)

data class PersonCutoutMaskTrackV43(
    val sourceUri: String,
    val frames: List<PersonCutoutMaskFrameV43>,
) {
    fun nearest(sourceTimeUs: Long): PersonCutoutMaskFrameV43? {
        if (frames.isEmpty()) return null
        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = frames[mid].sourceTimeUs
            when {
                value < sourceTimeUs -> low = mid + 1
                value > sourceTimeUs -> high = mid - 1
                else -> return frames[mid]
            }
        }
        val right = frames.getOrNull(low)
        val left = frames.getOrNull(low - 1)
        return when {
            left == null -> right
            right == null -> left
            abs(sourceTimeUs - left.sourceTimeUs) <= abs(right.sourceTimeUs - sourceTimeUs) -> left
            else -> right
        }
    }
}

object PersonCutoutMaskStoreV43 {
    private val indexCache = ConcurrentHashMap<String, PersonCutoutMaskTrackV43>()

    fun index(context: Context, clip: TimelineClip): PersonCutoutMaskTrackV43 {
        val key = cacheKey(clip.uri)
        return indexCache[key] ?: loadIndex(context, clip.uri).also { indexCache[key] = it }
    }

    fun hasAny(context: Context, clip: TimelineClip): Boolean =
        index(context, clip).frames.any { it.file.isFile }

    fun save(context: Context, sourceUri: String, sourceTimeUs: Long, mask: Bitmap): File {
        val dir = sourceDir(context, sourceUri).apply { mkdirs() }
        val target = File(dir, "$sourceTimeUs.png")
        val temp = File(dir, "$sourceTimeUs.png.tmp")
        temp.outputStream().buffered().use { stream ->
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not encode person cutout confidence mask"
            }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not install person cutout confidence mask" }
        indexCache.remove(cacheKey(sourceUri))
        return target
    }

    private fun loadIndex(context: Context, sourceUri: String): PersonCutoutMaskTrackV43 {
        val frames = sourceDir(context, sourceUri).listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            .mapNotNull { file ->
                file.nameWithoutExtension.toLongOrNull()?.let { time -> PersonCutoutMaskFrameV43(time, file) }
            }
            .sortedBy { it.sourceTimeUs }
            .toList()
        return PersonCutoutMaskTrackV43(sourceUri, frames)
    }

    private fun sourceDir(context: Context, sourceUri: String): File =
        File(File(context.filesDir, "person_cutout_masks_v43_binary_person_256"), cacheKey(sourceUri))

    private fun cacheKey(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sourceUri.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
}

class PersonCutoutSegmenterV43(context: Context) : AutoCloseable {
    private val segmenter: ImageSegmenter

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(PERSON_MODEL_ASSET_V43)
            .setDelegate(Delegate.CPU)
            .build()
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build()
        segmenter = ImageSegmenter.createFromOptions(context.applicationContext, options)
    }

    fun segmentAndStore(context: Context, clip: TimelineClip, bitmap: Bitmap, sourceTimeUs: Long): Boolean {
        val mediaPipeBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Could not convert decoded frame to ARGB_8888 for Auto Cutout")
        }

        try {
            val result = segmenter.segment(BitmapImageBuilder(mediaPipeBitmap).build())
            val masks = result.confidenceMasks().orElse(emptyList())
            val person = masks.getOrNull(PERSON_CONFIDENCE_MASK_INDEX_V43)
                ?: error(
                    "Selfie Segmenter returned ${masks.size} confidence mask(s); expected mask index 0",
                )
            val width = person.width.coerceAtLeast(1)
            val height = person.height.coerceAtLeast(1)
            val confidences = ByteBufferExtractor.extract(person).asFloatBuffer()
            confidences.rewind()
            check(confidences.remaining() >= width * height) {
                "Selfie Segmenter confidence mask was incomplete"
            }

            val pixels = IntArray(width * height)
            for (index in pixels.indices) {
                val foreground = confidences.get().coerceIn(0f, 1f)
                val value = (foreground * 255f).roundToInt().coerceIn(0, 255)
                pixels[index] = Color.argb(255, value, value, value)
            }
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            mask.setPixels(pixels, 0, width, 0, 0, width, height)
            try {
                PersonCutoutMaskStoreV43.save(context.applicationContext, clip.uri, sourceTimeUs, mask)
            } finally {
                mask.recycle()
            }
            return true
        } finally {
            if (mediaPipeBitmap !== bitmap && !mediaPipeBitmap.isRecycled) {
                mediaPipeBitmap.recycle()
            }
        }
    }

    override fun close() {
        segmenter.close()
    }
}

class PersonCutoutAnalyzerV43(private val context: Context) {
    fun analyzeAndStore(
        clip: TimelineClip,
        prioritySourceUs: Long? = null,
        onAnchorStored: ((completedAnchors: Int) -> Unit)? = null,
    ): PersonCutoutMaskTrackV43 {
        PersonCutoutSegmenterV43(context).use { segmenter ->
            if (clip.isImageV21) {
                analyzeImage(clip, segmenter, onAnchorStored)
            } else {
                analyzeVideo(clip, segmenter, prioritySourceUs, onAnchorStored)
            }
        }
        return PersonCutoutMaskStoreV43.index(context, clip)
    }

    private fun analyzeImage(
        clip: TimelineClip,
        segmenter: PersonCutoutSegmenterV43,
        onAnchorStored: ((Int) -> Unit)?,
    ) {
        val bitmap = decodeImage(Uri.parse(clip.uri)) ?: error("Could not decode image for Auto Cutout")
        try {
            check(segmenter.segmentAndStore(context, clip, bitmap, clip.sourceInUs)) {
                "Person segmentation returned no confidence mask"
            }
            onAnchorStored?.invoke(1)
        } finally {
            bitmap.recycle()
        }
    }

    private fun analyzeVideo(
        clip: TimelineClip,
        segmenter: PersonCutoutSegmenterV43,
        prioritySourceUs: Long?,
        onAnchorStored: ((Int) -> Unit)?,
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            val start = clip.sourceInUs.coerceAtLeast(0L)
            val end = clip.sourceOutUs.coerceAtLeast(start + 1L)
            val durationUs = (end - start).coerceAtLeast(1L)
            val target = ((durationUs * PERSON_ANCHORS_PER_SECOND_V43) / 1_000_000L).toInt() + 2
            val count = target.coerceIn(MIN_PERSON_ANCHORS_V43, MAX_PERSON_ANCHORS_V43)
            val regularTimes = evenlySpacedTimes(start, end, count)
            val priority = prioritySourceUs?.coerceIn(start, (end - 1L).coerceAtLeast(start))
            val times = buildList {
                priority?.let(::add)
                addAll(regularTimes)
            }.distinct()

            var completed = 0
            var decoded = 0
            for (sourceUs in times) {
                val frame = scaledFrameAtTime(retriever, sourceUs) ?: continue
                decoded++
                try {
                    // A model/output contract failure must abort on the first decodable frame. The
                    // previous implementation silently returned false for every frame and could make
                    // the user wait several minutes before reporting completed=0.
                    if (segmenter.segmentAndStore(context, clip, frame, sourceUs)) {
                        completed++
                        onAnchorStored?.invoke(completed)
                    }
                } finally {
                    frame.recycle()
                }
            }
            check(decoded > 0) { "Could not decode any video frame for Auto Cutout" }
            check(completed > 0) { "Could not analyze any video frame for Auto Cutout" }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun evenlySpacedTimes(start: Long, end: Long, count: Int): List<Long> {
        val last = (end - 1L).coerceAtLeast(start)
        val safeCount = count.coerceAtLeast(2)
        if (last <= start) return listOf(start)
        return (0 until safeCount).map { index ->
            if (index == safeCount - 1) last
            else start + ((last - start) * index.toLong()) / (safeCount - 1).toLong()
        }.distinct()
    }

    private fun scaledFrameAtTime(retriever: MediaMetadataRetriever, sourceUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) {
                val longEdge = max(width, height)
                val scale = if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V43) 1f
                else PERSON_ANALYSIS_LONG_EDGE_V43 / longEdge.toFloat()
                val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
                retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight,
                )?.let { return it }
            }
        }
        val raw = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val longEdge = max(raw.width, raw.height)
        if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V43) return raw
        val scale = PERSON_ANALYSIS_LONG_EDGE_V43 / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).roundToInt().coerceAtLeast(1),
            (raw.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        raw.recycle()
        return scaled
    }

    private fun decodeImage(uri: Uri): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > PERSON_ANALYSIS_LONG_EDGE_V43) {
                    val scale = PERSON_ANALYSIS_LONG_EDGE_V43 / longEdge.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        } ?: return@runCatching null

        val longEdge = max(raw.width, raw.height)
        if (longEdge <= PERSON_ANALYSIS_LONG_EDGE_V43) raw else {
            val scale = PERSON_ANALYSIS_LONG_EDGE_V43 / longEdge.toFloat()
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).roundToInt().coerceAtLeast(1),
                (raw.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()
}
