package com.tajuli.digitorandroid.editor.processing

internal object SmoothFrameInterpolator {
    fun blendArgb(
        first: IntArray,
        second: IntArray,
        amount: Float,
        output: IntArray = IntArray(minOf(first.size, second.size)),
    ): IntArray {
        val count = minOf(first.size, second.size, output.size)
        val t = amount.coerceIn(0f, 1f)
        if (t <= .0001f) {
            first.copyInto(output, endIndex = count)
            return output
        }
        if (t >= .9999f) {
            second.copyInto(output, endIndex = count)
            return output
        }
        val inv = 1f - t
        for (index in 0 until count) {
            val a = first[index]
            val b = second[index]
            val aa = (((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t).toInt()
            val rr = (((a ushr 16) and 0xFF) * inv + ((b ushr 16) and 0xFF) * t).toInt()
            val gg = (((a ushr 8) and 0xFF) * inv + ((b ushr 8) and 0xFF) * t).toInt()
            val bb = ((a and 0xFF) * inv + (b and 0xFF) * t).toInt()
            output[index] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return output
    }
}
