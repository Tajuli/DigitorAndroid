package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Direct display surface for the editor GPU preview.
 *
 * MediaCodec frames are composited by DigitorRenderCore/OpenGL straight into this SurfaceView.
 * There is no ImageReader, GPU-to-CPU pixel copy, Bitmap upload, or Compose texture upload in the
 * normal preview path.
 *
 * Two sizes intentionally exist here:
 * - the Compose/SurfaceView size is the physical on-screen viewer size;
 * - the Surface buffer size is fixed to the project canvas resolution.
 *
 * DigitorRenderCore renders project-resolution pixels and its SurfaceInfo also describes that same
 * project size. Keeping the actual Surface buffer at that exact size prevents an EGL viewport that
 * is larger than the native Surface buffer, which otherwise shows only a cropped/zoomed portion of
 * the rendered frame on smaller phone displays. SurfaceFlinger then scales the completed project
 * frame down to the center-fitted viewer without changing its geometry.
 *
 * The workspace outside the fitted project canvas is deliberately gray. The project canvas itself
 * remains the rendered Surface, so scaling a clip below 100% exposes the canvas around it while the
 * outer gray pasteboard makes the canvas boundary obvious, similar to a desktop NLE viewer.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    modifier: Modifier = Modifier,
) {
    val project by PreviewProjectRegistry.flow.collectAsState()
    val renderWidth = project?.width?.coerceAtLeast(2) ?: 1920
    val renderHeight = project?.height?.coerceAtLeast(2) ?: 1080
    val projectAspect = renderWidth.toFloat() / renderHeight.toFloat()

    BoxWithConstraints(
        modifier = modifier.background(PREVIEW_PASTEBOARD_GRAY),
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
            factory = { context ->
                DigitorPreviewSurfaceView(
                    context = context,
                    initialEngine = engine,
                    initialBufferWidth = renderWidth,
                    initialBufferHeight = renderHeight,
                )
            },
            update = { view ->
                view.bind(
                    nextEngine = engine,
                    nextBufferWidth = renderWidth,
                    nextBufferHeight = renderHeight,
                )
            },
        )
    }
}

private val PREVIEW_PASTEBOARD_GRAY = Color(0xFF424247)

private class DigitorPreviewSurfaceView(
    context: Context,
    initialEngine: DavinciFramePreviewEngine,
    initialBufferWidth: Int,
    initialBufferHeight: Int,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var engine: DavinciFramePreviewEngine = initialEngine
    private var attachedSurface: android.view.Surface? = null
    private var bufferWidth = initialBufferWidth.coerceAtLeast(2)
    private var bufferHeight = initialBufferHeight.coerceAtLeast(2)

    init {
        // The render core advertises project.width/project.height in SurfaceInfo. Make the native
        // Surface buffer the same size so OpenGL's output viewport maps 1:1 to the project frame.
        holder.setFixedSize(bufferWidth, bufferHeight)
        holder.addCallback(this)
        // Keep this SurfaceView in the normal hierarchy so Compose controls/overlays can remain
        // above it. We intentionally do not use setZOrderOnTop(true).
    }

    fun bind(
        nextEngine: DavinciFramePreviewEngine,
        nextBufferWidth: Int,
        nextBufferHeight: Int,
    ) {
        val safeWidth = nextBufferWidth.coerceAtLeast(2)
        val safeHeight = nextBufferHeight.coerceAtLeast(2)
        val engineChanged = engine !== nextEngine

        if (engineChanged) {
            attachedSurface?.let { engine.detachSurface(it) }
            engine = nextEngine
        }

        if (bufferWidth != safeWidth || bufferHeight != safeHeight) {
            bufferWidth = safeWidth
            bufferHeight = safeHeight
            holder.setFixedSize(bufferWidth, bufferHeight)
        }

        if (engineChanged) {
            attachedSurface?.takeIf { it.isValid }?.let { engine.attachSurface(it) }
        }
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
