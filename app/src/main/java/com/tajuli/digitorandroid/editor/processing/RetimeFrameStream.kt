package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import android.graphics.Bitmap
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** One bounded decode/interpolation worker. Closing makes all subsequently produced frames stale. */
internal class RetimeFrameStream(
    context: Context,
    clip: TimelineClip,
    fps: Int,
    longEdge: Int,
    startOutputUs: Long,
    endOutputUs: Long = clip.durationUs,
) : Closeable {
    data class Frame(val bitmap: Bitmap, val localUs: Long)
    private val stopped = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val minimumTimeUs = AtomicLong(0L)
    private val failure = AtomicReference<Throwable?>(null)
    private val frames = ArrayBlockingQueue<Frame>(2)
    private val worker = Thread({
        try {
            SmoothRetimeFrameProducer(context).produce(clip, fps, longEdge, stopped::get,
                startOutputUs, endOutputUs, minimumTimeUs::get) { bitmap, time ->
                // Producer owns its scratch bitmap; stream transfers this copy to its consumer.
                val owned = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                var accepted = false
                try {
                    while (!stopped.get() && !accepted) {
                        accepted = frames.offer(Frame(owned, time), 20, TimeUnit.MILLISECONDS)
                    }
                } finally { if (!accepted) owned.recycle() }
                if (stopped.get()) throw CancellationException()
            }
        } catch (_: CancellationException) {
            // Explicit stop is not a decode failure.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!stopped.get()) failure.set(error)
        } finally {
            finished.set(true)
            if (stopped.get()) discardFrames()
        }
    }, "DigitorRetimeFrames").apply { isDaemon = true; start() }

    fun advanceTo(localUs: Long) { minimumTimeUs.set(localUs.coerceAtLeast(0L)) }
    fun peek(): Frame? = frames.peek()
    fun poll(): Frame? = frames.poll()
    fun error(): Throwable? = failure.get()
    fun isFinished(): Boolean = finished.get() && frames.isEmpty()

    override fun close() {
        if (stopped.getAndSet(true)) return
        worker.interrupt()
        if (Thread.currentThread() !== worker) worker.join(250L)
        discardFrames()
    }

    private fun discardFrames() {
        while (true) (frames.poll() ?: break).bitmap.recycle()
    }
}
