package com.tajuli.digitorandroid.editor.processing

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Speech-oriented mono resampler used before whisper.cpp.
 *
 * V80 previously selected one decoded sample whenever the target 16 kHz clock advanced. That is
 * effectively unfiltered decimation for common 44.1/48 kHz sources and can alias high-frequency
 * energy back into the speech band. Whisper is noticeably less reliable on that signal, especially
 * for smaller multilingual models.
 *
 * For downsampling we integrate/average the source interval represented by each destination sample
 * (a small box low-pass). For upsampling we use linear interpolation. The implementation is pure
 * Kotlin so its behavior can be unit tested without Android codecs.
 */
internal fun resampleMonoForWhisperV83(
    input: FloatArray,
    inputRate: Int,
    outputRate: Int = 16_000,
): FloatArray {
    require(inputRate > 0 && outputRate > 0)
    if (input.isEmpty()) return FloatArray(0)
    if (inputRate == outputRate) return input.copyOf()

    val outputSize = ((input.size.toLong() * outputRate.toLong()) / inputRate.toLong())
        .coerceAtLeast(1L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val output = FloatArray(outputSize)
    val sourcePerOutput = inputRate.toDouble() / outputRate.toDouble()

    if (inputRate > outputRate) {
        for (outIndex in output.indices) {
            val start = outIndex * sourcePerOutput
            val end = minOf((outIndex + 1) * sourcePerOutput, input.size.toDouble())
            val first = floor(start).toInt().coerceIn(0, input.lastIndex)
            val lastExclusive = ceil(end).toInt().coerceIn(first + 1, input.size)
            var weighted = 0.0
            var totalWeight = 0.0
            for (sourceIndex in first until lastExclusive) {
                val overlapStart = maxOf(start, sourceIndex.toDouble())
                val overlapEnd = minOf(end, sourceIndex + 1.0)
                val weight = (overlapEnd - overlapStart).coerceAtLeast(0.0)
                if (weight > 0.0) {
                    weighted += input[sourceIndex].toDouble() * weight
                    totalWeight += weight
                }
            }
            output[outIndex] = if (totalWeight > 0.0) {
                (weighted / totalWeight).toFloat().coerceIn(-1f, 1f)
            } else {
                input[first]
            }
        }
    } else {
        for (outIndex in output.indices) {
            val position = outIndex * sourcePerOutput
            val left = floor(position).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - left.toDouble()).toFloat()
            output[outIndex] = (input[left] + (input[right] - input[left]) * fraction).coerceIn(-1f, 1f)
        }
    }

    return output
}
