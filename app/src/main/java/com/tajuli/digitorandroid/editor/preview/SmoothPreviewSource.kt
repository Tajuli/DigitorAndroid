package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.processing.RetimeFrameStream
import com.tajuli.digitorandroid.editor.render.DigitorRenderCore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds retimed bitmaps into the existing per-layer GPU effects/compositor input. */
@UnstableApi
internal class SmoothPreviewSource(
    private val context: Context,
    private val inputIndex: Int,
    private val original: TimelineClip,
    private val fps: Int,
) : Closeable {
    private var clip = original
    private var stream: RetimeFrameStream? = null
    private var inFlight: UploadTimestamp? = null
    private var paused = false
    private var submittedPaused = false

    fun reset(timelineUs: Long, playing: Boolean) {
        stream?.close()
        clip = PreviewProjectRegistry.clip(original.id) ?: original
        val local = (timelineUs - clip.timelineStartUs).coerceIn(0L, (clip.durationUs - 1).coerceAtLeast(0L))
        // Paused requests use the frame at/before the playhead; playback starts at the same cadence.
        val frameIndex = local * fps / 1_000_000L
        val start = frameIndex * 1_000_000L / fps
        val end = if (playing) clip.durationUs else minOf(clip.durationUs, (frameIndex + 1) * 1_000_000L / fps)
        stream = RetimeFrameStream(context, clip, fps, 480, start, end)
        inFlight = null
        paused = !playing
        submittedPaused = false
    }

    /** Nonblocking. Returns whether a paused request still needs another pump. */
    fun pump(timelineUs: Long, core: DigitorRenderCore): Boolean {
        val current = PreviewProjectRegistry.clip(original.id) ?: original
        if (current.retime != clip.retime) reset(timelineUs, playing = !paused)
        val frames = stream ?: return false
        frames.error()?.let { throw IllegalStateException("Smooth preview decode failed", it) }
        val pending = inFlight
        if (pending != null) {
            if (!pending.uploaded.get()) return true
            inFlight = null
            if (paused && submittedPaused) return false
        }
        if (!paused) {
            val floorUs = (timelineUs - clip.timelineStartUs - 1_000_000L / fps).coerceAtLeast(0L)
            frames.advanceTo(floorUs)
            while (frames.peek()?.localUs?.let { it < floorUs } == true) frames.poll()?.bitmap?.recycle()
        }
        val next = frames.peek() ?: return !frames.isFinished()
        val presentationUs = clip.timelineStartUs + next.localUs
        if (!paused && presentationUs > timelineUs + 50_000L) return true
        val timestamp = UploadTimestamp(presentationUs)
        if (!core.queueSmoothBitmap(inputIndex, next.bitmap, timestamp)) return true
        check(frames.poll() === next)
        // Media3 owns/recycles accepted bitmap input, including during teardown.
        inFlight = timestamp
        submittedPaused = true
        return true
    }

    override fun close() {
        stream?.close()
        stream = null
        inFlight = null
    }

    private class UploadTimestamp(
        private val timeUs: Long,
        val uploaded: AtomicBoolean = AtomicBoolean(false),
    ) : TimestampIterator {
        private var consumed = false
        override fun hasNext(): Boolean {
            if (consumed) uploaded.set(true)
            return !consumed
        }
        override fun next(): Long { check(!consumed); consumed = true; return timeUs }
        override fun copyOf(): TimestampIterator = UploadTimestamp(timeUs, uploaded)
        override fun getLastTimestampUs() = timeUs
    }
}
