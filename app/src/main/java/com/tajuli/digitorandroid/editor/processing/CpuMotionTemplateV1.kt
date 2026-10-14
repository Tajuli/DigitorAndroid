package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.MotionTemplateFrameV1
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lightweight CPU fallback for the video/image motion family. Geometry is
 * pre-evaluated once per frame, so the per-pixel path only remaps coordinates.
 * This prevents a successful fallback MP4 from silently losing motion.
 */
internal class CpuMotionTemplateV1 private constructor(
    val style: Int,
    private val amount: Float,
    private val zoom: Float,
    private val cosAngle: Float,
    private val sinAngle: Float,
    private val shiftX: Float,
    private val shiftY: Float,
    val monochrome: Float,
    val flash: Float,
    val echo: Float,
) {
    val active: Boolean get() = style != 0 && amount > 0f
    private val collage get() = style == 7 || style == 17

    fun sampleIndex(px: Int, py: Int, width: Int, height: Int): Int {
        if (!active) return py * width + px
        val u = (px + .5f) / width
        val v = (py + .5f) / height
        val x: Float
        val y: Float
        if (collage) {
            val tileU = (u * 2f) % 1f
            val tileV = (v * 2f) % 1f
            x = tileU - .5f
            y = tileV - .5f
        } else {
            x = u - .5f
            y = v - .5f
        }
        val destU = if (collage) x / (1.1f + .12f * amount) + .5f
            else (cosAngle * x + sinAngle * y) / zoom + .5f + shiftX
        val destV = if (collage) y / (1.1f + .12f * amount) + .5f
            else (-sinAngle * x + cosAngle * y) / zoom + .5f + shiftY
        val sampledU = (u + (destU - u) * amount).coerceIn(.001f, .999f)
        val sampledV = (v + (destV - v) * amount).coerceIn(.001f, .999f)
        val sx = (sampledU * width).toInt().coerceIn(0, width - 1)
        val sy = (sampledV * height).toInt().coerceIn(0, height - 1)
        return sy * width + sx
    }

    fun grade(rgb: FloatArray, px: Int, py: Int, width: Int, height: Int) {
        if (!active) return
        if (monochrome > 0f) {
            val gray = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f
            for (channel in 0..2) rgb[channel] += (gray - rgb[channel]) * monochrome
        }
        if (flash > 0f) {
            for (channel in 0..2) rgb[channel] += flash
        }
        if (collage &&
            (kotlin.math.abs(px - width / 2) <= 1 || kotlin.math.abs(py - height / 2) <= 1)
        ) {
            for (channel in 0..2) rgb[channel] += (.06f - rgb[channel]) * amount
        }
    }

    companion object {
        fun from(frame: MotionTemplateFrameV1): CpuMotionTemplateV1 {
            val a = frame.amount.coerceIn(0f, 1f)
            val t = frame.progress.coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val seconds = frame.elapsedSeconds
            fun wave(speed: Float) = sin(seconds.toDouble() * 2.0 * PI * speed).toFloat()
            fun pulse(speed: Float) = max(0f, wave(speed)).pow(8)
            var zoom = 1f
            var angle = 0f
            var dx = 0f
            var dy = 0f
            var mono = 0f
            var flash = 0f
            var echo = 0f
            when (frame.style) {
                1 -> zoom = 1.02f + a * (.08f + .27f * pulse(1.8f))
                2 -> {
                    dx = a * wave(1.35f) * .115f
                    zoom = 1f + a * (.17f + .07f * kotlin.math.abs(wave(1.35f)))
                }
                3 -> {
                    val shake = pulse(1.55f)
                    dx = a * shake * wave(92f / (2f * PI.toFloat())) * .035f
                    dy = a * shake * cos(seconds.toDouble() * 71.0).toFloat() * .029f
                    angle = a * shake * sin(seconds.toDouble() * 83.0).toFloat() * .042f
                    zoom = 1f + a * .15f
                }
                4 -> {
                    zoom = 1f + a * (.06f + .17f * pulse(1.35f))
                    flash = a * .65f * pulse(1.35f)
                }
                5 -> {
                    zoom = 1f + a * (.07f + .04f * sin(seconds.toDouble() * 4.0).toFloat())
                    echo = a * .46f
                }
                6 -> {
                    zoom = 1f + a * .045f
                    val x = ((t - .28f) / (.69f - .28f)).coerceIn(0f, 1f)
                    mono = a * (1f - x * x * (3f - 2f * x))
                }
                7 -> zoom = 1f + a * (.04f + .06f * pulse(1.8f))
                11 -> {
                    zoom = 1f + a * (.10f + .24f * smooth)
                    dx = a * (-.034f + .068f * smooth)
                    dy = a * (.020f - .040f * smooth)
                }
                12 -> zoom = 1f + a * .32f * smooth
                13 -> {
                    zoom = 1f + a * .13f
                    dx = a * sin(smooth.toDouble() * 2.0 * PI).toFloat() * .035f
                    dy = a * cos(smooth.toDouble() * 2.0 * PI).toFloat() * .027f
                }
                14 -> {
                    zoom = 1f + a * .18f
                    angle = a * (smooth - .5f) * .13f
                }
                15 -> {
                    zoom = 1f + a * (.045f + .16f * pulse(1.25f))
                    dy = a * .016f * wave(1.25f)
                }
                16 -> {
                    zoom = 1f + a * (.045f + .15f * pulse(1f))
                    flash = a * .65f * pulse(1f)
                }
                17 -> zoom = 1f + a * (.04f + .12f * smooth)
            }
            return CpuMotionTemplateV1(
                frame.style, a, zoom,
                cos(angle.toDouble()).toFloat(), sin(angle.toDouble()).toFloat(),
                dx, dy, mono, flash, echo,
            )
        }
    }
}
