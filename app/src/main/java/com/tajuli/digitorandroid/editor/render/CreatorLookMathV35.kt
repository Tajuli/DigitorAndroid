package com.tajuli.digitorandroid.editor.render

/** Small fixed-arity helper so V35 strength resolution stays allocation-free on the render thread. */
internal fun maxOf(
    a: Float,
    b: Float,
    c: Float,
    d: Float,
    e: Float,
    f: Float,
    g: Float,
    h: Float,
    i: Float,
    j: Float,
): Float = kotlin.math.max(
    kotlin.math.max(kotlin.math.max(a, b), kotlin.math.max(c, d)),
    kotlin.math.max(
        kotlin.math.max(kotlin.math.max(e, f), kotlin.math.max(g, h)),
        kotlin.math.max(i, j),
    ),
)
