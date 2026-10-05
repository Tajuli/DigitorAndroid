package com.tajuli.digitorandroid.editor.processing

/** Inverse of floor(index * 1_000_000 / fps), including fractional-microsecond frame grids. */
internal fun retimeFrameIndexAtOrBefore(timeUs: Long, fps: Int): Long {
    require(fps > 0)
    return ((timeUs.coerceAtLeast(0L) + 1L) * fps - 1L) / 1_000_000L
}
