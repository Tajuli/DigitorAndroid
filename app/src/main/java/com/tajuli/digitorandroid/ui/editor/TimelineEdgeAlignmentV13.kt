package com.tajuli.digitorandroid.ui.editor

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Alignment modifier used by timeline resize handles outside a BoxScope helper function.
 * It keeps the actual hit target narrow while positioning it against the left/right edge of the
 * clip-sized parent.
 */
fun Modifier.align(alignment: Alignment): Modifier = this.layout { measurable, constraints ->
    val child = measurable.measure(
        constraints.copy(
            minWidth = 0,
            minHeight = 0,
        ),
    )
    val layoutWidth = constraints.maxWidth.coerceAtLeast(child.width)
    val layoutHeight = constraints.maxHeight.coerceAtLeast(child.height)
    val x = when (alignment) {
        Alignment.CenterEnd, Alignment.TopEnd, Alignment.BottomEnd -> layoutWidth - child.width
        Alignment.Center, Alignment.TopCenter, Alignment.BottomCenter -> (layoutWidth - child.width) / 2
        else -> 0
    }.coerceAtLeast(0)
    val y = when (alignment) {
        Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd -> 0
        Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd -> layoutHeight - child.height
        else -> (layoutHeight - child.height) / 2
    }.coerceAtLeast(0)

    layout(layoutWidth, layoutHeight) {
        child.placeRelative(x, y)
    }
}
