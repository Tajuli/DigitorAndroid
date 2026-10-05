package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tajuli.digitorandroid.editor.model.*
import java.util.concurrent.CancellationException

/** Sequential decode, at most two source pixel arrays and one output buffer. No derived media. */
internal class SmoothRetimeFrameProducer(private val context: Context) {
    fun produce(clip: TimelineClip, fps: Int, longEdge: Int, cancelled: () -> Boolean,
                startOutputUs: Long = 0L, endOutputUs: Long = clip.durationUs,
                minimumOutputUs: () -> Long = { 0L },
                emit: (Bitmap, Long) -> Unit) {
        require(fps > 0)
        val interpolator = MotionFrameInterpolator()
        var previous: IntArray? = null
        var previousUs = clip.sourceInUs
        var outputIndex = (startOutputUs.coerceAtLeast(0L) * fps + 999_999L) / 1_000_000L
        val endUs = endOutputUs.coerceIn(0L, clip.durationUs)
        var width = 0; var height = 0
        var scratch: IntArray? = null
        fun outputTime() = outputIndex * 1_000_000L / fps
        fun submit(pixels: IntArray) {
            if (cancelled()) throw CancellationException()
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try { emit(bitmap, outputTime()) } finally { bitmap.recycle() }
            outputIndex++
        }
        try {
            GpuSequentialCutoutDecoderV47(context, longEdge.coerceIn(256, 4096)).decodeTargets(
                Uri.parse(clip.uri),
                clip.sourceTimeForOutput(startOutputUs),
                clip.sourceOutUs,
                emptyList(), emitEveryFrame = true, cancelled = cancelled, includePreroll = true,
            ) { time, bitmap ->
                if (cancelled()) { bitmap.recycle(); throw CancellationException() }
                try {
                    if (previous == null) {
                        width = bitmap.width; height = bitmap.height
                        scratch = IntArray(width * height)
                    }
                    val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else
                        Bitmap.createScaledBitmap(bitmap, width, height, true)
                    val current = IntArray(width * height)
                    scaled.getPixels(current, 0, width, 0, 0, width, height)
                    if (scaled !== bitmap) scaled.recycle()
                    outputIndex = maxOf(outputIndex, minimumOutputUs().coerceAtLeast(0L) * fps / 1_000_000L)
                    val before = previous
                    var motion: MotionFrameInterpolator.Pair? = null
                    var motionFailed = false
                    while (outputTime() < endUs && clip.sourceTimeForOutput(outputTime()) <= time) {
                        outputIndex = maxOf(outputIndex, minimumOutputUs().coerceAtLeast(0L) * fps / 1_000_000L)
                        if (outputTime() >= endUs || clip.sourceTimeForOutput(outputTime()) > time) break
                        val source = clip.sourceTimeForOutput(outputTime())
                        val amount = if (before == null || time <= previousUs) 1f else
                            ((source - previousUs).toDouble() / (time - previousUs)).toFloat().coerceIn(0f, 1f)
                        val pixels = if (before != null && amount > 0f && amount < 1f && clip.speedAtSource(source) < .999f) {
                            if (motion == null && !motionFailed) {
                                try { motion = interpolator.prepare(before, current, width, height, cancelled) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: RuntimeException) { motionFailed = true }
                            }
                            if (motion != null) motion!!.render(amount, checkNotNull(scratch), cancelled)
                            else SmoothFrameInterpolator.blendArgb(before, current, amount, checkNotNull(scratch))
                        } else if (before != null && amount < .5f) before else current
                        submit(pixels)
                    }
                    previous = current
                    previousUs = time
                    if (outputTime() >= endUs) throw FramesComplete()
                } finally { if (!bitmap.isRecycled) bitmap.recycle() }
            }
        } catch (_: FramesComplete) { /* The requested output window is complete. */ }
        val tail = checkNotNull(previous) { "No video frame could be decoded for slow motion" }
        while (outputTime() < endUs) submit(tail)
    }

    private class FramesComplete : RuntimeException()
}
