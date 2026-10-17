package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.CompositeTemplateFrameV2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * CPU fallback for the signature Video/Image Effect.
 * Uses the same output-time frame schedule, zoom, wave front and double-shake
 * impulses as the GPU shader. Kept under its historical name for callers.
 */
internal class CpuMotionTemplateV1 private constructor(
    private val frame: CompositeTemplateFrameV2,
) {
    val active: Boolean get() = frame.active
    val blurReveal: Float get() = frame.blurReveal
    val echo: Float get() = 0f // Legacy fallback compositor callers.

    private val shakeX = sin(frame.elapsedSeconds.toDouble() * 98.0).toFloat() *
        .026f * frame.shakeStrength
    private val shakeY = cos(frame.elapsedSeconds.toDouble() * 83.0).toFloat() *
        .020f * frame.shakeStrength

    fun sampleIndex(px: Int, py: Int, width: Int, height: Int): Int {
        if (!frame.active) return py * width + px
        val u = (px + .5f) / width
        val v = (py + .5f) / height
        var mappedU = (u - .5f) / frame.zoomScale + .5f + shakeX
        var mappedV = (v - .5f) / frame.zoomScale + .5f + shakeY

        if (frame.waveStrength > .001f) {
            val diagonal = u + v - 2f * frame.waveProgress
            val band = exp((-38f * diagonal * diagonal).toDouble()).toFloat()
            val ripple = sin((diagonal * 29f).toDouble()).toFloat() * band *
                .026f * frame.waveStrength
            mappedU += ripple
            mappedV -= ripple
        }
        val sx = (mappedU.coerceIn(.001f, .999f) * width).toInt().coerceIn(0, width - 1)
        val sy = (mappedV.coerceIn(.001f, .999f) * height).toInt().coerceIn(0, height - 1)
        return sy * width + sx
    }

    fun grade(rgb: FloatArray, px: Int, py: Int, width: Int, height: Int) {
        if (frame.waveStrength <= .001f) return
        val u = (px + .5f) / width
        val v = (py + .5f) / height
        val diagonal = u + v - 2f * frame.waveProgress
        val band = exp((-48f * diagonal * diagonal).toDouble()).toFloat()
        val shimmer = .035f * band * frame.waveStrength
        for (channel in 0..2) rgb[channel] += shimmer
    }

    companion object {
        fun from(frame: CompositeTemplateFrameV2): CpuMotionTemplateV1 = CpuMotionTemplateV1(frame)
    }
}
