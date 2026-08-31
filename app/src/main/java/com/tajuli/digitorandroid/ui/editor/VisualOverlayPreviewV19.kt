package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.render.VisualOverlayBitmapCacheV19
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
internal fun BoxScope.VisualOverlayPreviewV19(
    project: TimelineProject,
    overlay: VisualOverlayClipV19,
    previewSize: IntSize,
) {
    if (previewSize.width <= 0 || previewSize.height <= 0) return
    PreviewOverlayLayerOrderV19.install(project)
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = overlay.kind,
        key2 = "${overlay.imageUri}|${overlay.stickerPreset}|${overlay.shapePreset}|${overlay.colorArgb}",
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { VisualOverlayBitmapCacheV19.get(context, overlay, 1024) }.getOrNull()
        }
    }
    val source = bitmap?.takeIf { !it.isRecycled } ?: return
    val fit = min(
        previewSize.width.toFloat() / project.width.coerceAtLeast(1),
        previewSize.height.toFloat() / project.height.coerceAtLeast(1),
    )
    val shownWidthPx = project.width * fit
    val shownHeightPx = project.height * fit
    val desiredWidthPx = (shownWidthPx * overlay.scale.coerceIn(.03f, 1.5f)).coerceAtLeast(2f)
    val desiredHeightPx = (desiredWidthPx * source.height / source.width.coerceAtLeast(1).toFloat()).coerceAtLeast(2f)
    val density = LocalDensity.current
    val widthDp = with(density) { desiredWidthPx.toDp() }
    val heightDp = with(density) { desiredHeightPx.toDp() }

    Image(
        bitmap = source.asImageBitmap(),
        contentDescription = overlay.label,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .align(Alignment.Center)
            .zIndex(PreviewOverlayLayerOrderV19.zFor(overlay.resolvedVideoTrackIdV19(project)))
            .width(widthDp)
            .height(heightDp)
            .graphicsLayer {
                translationX = overlay.positionX.coerceIn(-1f, 1f) * shownWidthPx * .5f
                translationY = overlay.positionY.coerceIn(-1f, 1f) * shownHeightPx * .5f
                rotationZ = overlay.rotationDegrees
                alpha = overlay.opacity.coerceIn(0f, 1f)
            },
    )
}
