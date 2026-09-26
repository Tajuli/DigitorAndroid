package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Preview-only data. Never satisfies full-clip export coverage. At most eight resident clips. */
internal object LiveSemanticFrames {
    data class Frame(val uri: String, val timeUs: Long, val pose: EyePose?, val mask: Bitmap?)
    private val frames = LinkedHashMap<String, Frame>()
    @Synchronized fun put(clip: TimelineClip, frame: Frame) {
        frames.remove(clip.id)?.mask?.recycle()
        frames[clip.id] = frame
        while (frames.size > 8) frames.remove(frames.keys.first())?.mask?.recycle()
    }
    @Synchronized fun pose(clip: TimelineClip, timeUs: Long): EyePose? = frames[clip.id]
        ?.takeIf { it.uri == clip.uri && abs(it.timeUs-timeUs) <= 250_000L }?.pose
    @Synchronized fun mask(clip: TimelineClip, timeUs: Long): Bitmap? = frames[clip.id]
        ?.takeIf { it.uri == clip.uri && abs(it.timeUs-timeUs) <= 250_000L }?.mask?.copy(Bitmap.Config.ARGB_8888, false)
}

/** Uses an already-decoded GPU frame: no second MediaCodec, full-clip scan, or preview lease. */
internal class LiveSemanticWorker(private val context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "DigitorLiveTracking") }
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var body: ImageSegmenter? = null
    @Volatile private var lastKey = ""
    fun reserve(clip: TimelineClip, timeUs: Long, eyes: Boolean, person: Boolean): Boolean {
        if (closed.get() || (!eyes && !person) || PreviewExportCoordinator.exportActive.value || CutoutAnalysisRuntimeV66.state.value.busy) return false
        val key = "${clip.id}|${clip.uri}|$timeUs|$eyes|$person"
        if (key == lastKey || !busy.compareAndSet(false, true)) return false
        lastKey = key
        return true
    }
    fun abandon() { busy.set(false) }
    fun submit(clip: TimelineClip, timeUs: Long, bitmap: Bitmap, eyes: Boolean, person: Boolean) {
        worker.execute {
            try {
                if (closed.get()) return@execute
                // Face effects use the durable ncnn Vulkan EyeTrack. This worker is retained only
                // for preview-only body semantics; never start the obsolete MediaPipe face path.
                val pose: EyePose? = null
                val mask = if (person) bodyMask(bitmap) else null
                if (closed.get()) mask?.recycle()
                else {
                    LiveSemanticFrames.put(clip, LiveSemanticFrames.Frame(clip.uri, timeUs, pose, mask))
                }
            } catch (error: Exception) {
                Log.e("DigitorLiveTracking", "Tracking frame failed", error)
            } finally {
                bitmap.recycle()
                busy.set(false)
                if(!closed.get()) PreviewExportCoordinator.refreshTrackedPreviews()
            }
        }
    }
    private fun bodyMask(bitmap: Bitmap): Bitmap? {
        val segmenter = body ?: ImageSegmenter.createFromOptions(context,
            ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_multiclass_256x256.tflite").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setOutputCategoryMask(false).setOutputConfidenceMasks(true).build()
        ).also { body = it }
        val input = BitmapImageBuilder(bitmap).build()
        try {
            val masks = segmenter.segment(input).confidenceMasks().orElse(emptyList())
            try {
                // Multiclass label 0 is background; its complement includes hair, skin and clothing.
                val background = masks.firstOrNull() ?: return null
                val floats = ByteBufferExtractor.extract(background).asFloatBuffer().apply { rewind() }
                val pixels = IntArray(background.width*background.height) {
                    val a = ((1f-floats.get()).coerceIn(0f,1f)*255f).toInt()
                    Color.rgb(a,a,a)
                }
                return Bitmap.createBitmap(pixels, background.width, background.height, Bitmap.Config.ARGB_8888)
            } finally { masks.forEach { it.close() } }
        } finally { input.close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        worker.execute { body?.close() }
        worker.shutdown()
    }
}
