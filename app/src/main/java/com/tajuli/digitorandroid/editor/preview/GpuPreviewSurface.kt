package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Direct display surface for the editor GPU preview.
 *
 * The Media3/OpenGL graph renders straight into this SurfaceView. There is no ImageReader,
 * GPU-to-CPU pixel copy, Bitmap upload, or Compose texture upload in the normal preview path.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> DigitorPreviewSurfaceView(context, engine) },
        update = { view -> view.bind(engine) },
    )
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
