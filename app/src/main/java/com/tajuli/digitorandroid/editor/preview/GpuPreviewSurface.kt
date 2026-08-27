package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Direct display surface for the editor GPU preview.
 *
 * MediaCodec frames are composited by DigitorRenderCore/OpenGL straight into this SurfaceView.
 * There is no ImageReader, GPU-to-CPU pixel copy, Bitmap upload, or Compose texture upload in the
 * normal preview path.
 *
 * The SurfaceView itself is center-fitted to the project/canvas aspect ratio. This is essential:
 * letting AndroidView fill an arbitrary editor panel would stretch the project frame and make clip
 * scale/position look different from export even when the GPU render pixels were correct.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    modifier: Modifier = Modifier,
) {
    val project by PreviewProjectRegistry.flow.collectAsState()
    val projectAspect = project
        ?.let { it.width.toFloat() / it.height.coerceAtLeast(1).toFloat() }
        ?.takeIf { it.isFinite() && it > 0f }
        ?: (16f / 9f)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val availableAspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            projectAspect
        }
        val fittedModifier = if (availableAspect > projectAspect) {
            Modifier.fillMaxHeight().aspectRatio(projectAspect)
        } else {
            Modifier.fillMaxWidth().aspectRatio(projectAspect)
        }

        AndroidView(
            modifier = fittedModifier,
            factory = { context -> DigitorPreviewSurfaceView(context, engine) },
            update = { view -> view.bind(engine) },
        )
    }
}

private class DigitorPreviewSurfaceView(
    context: Context,
    initialEngine: DavinciFramePreviewEngine,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var engine: DavinciFramePreviewEngine = initialEngine
    private var attachedSurface: android.view.Surface? = null

    init {
        holder.addCallback(this)
        // Keep this SurfaceView in the normal hierarchy so Compose controls/overlays can remain
        // above it. We intentionally do not use setZOrderOnTop(true).
    }

    fun bind(next: DavinciFramePreviewEngine) {
        if (engine === next) return
        attachedSurface?.let { engine.detachSurface(it) }
        engine = next
        attachedSurface?.let { engine.attachSurface(it) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachedSurface = holder.surface
        engine.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attachedSurface !== holder.surface) {
            attachedSurface?.let { engine.detachSurface(it) }
            attachedSurface = holder.surface
        }
        engine.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attachedSurface?.let { engine.detachSurface(it) }
        attachedSurface = null
    }
}
