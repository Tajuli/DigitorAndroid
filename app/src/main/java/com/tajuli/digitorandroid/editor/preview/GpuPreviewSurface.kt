package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tajuli.digitorandroid.editor.model.PreviewTransformClock
import com.tajuli.digitorandroid.editor.model.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Direct display surface for the editor GPU preview.
 *
 * MediaCodec frames are composited by DigitorRenderCore/OpenGL straight into this SurfaceView.
 * There is no ImageReader, GPU-to-CPU pixel copy, Bitmap upload, or Compose texture upload in the
 * healthy preview path.
 *
 * Some vendor codecs can decode a valid camera Log file but never complete the Surface handoff into
 * Media3's multi-input GL graph. In that case a Resolve-style editor must still show the footage as
 * a flat/raw image instead of leaving the viewer black. If the GPU path has not produced its first
 * frame after a short grace period, this host enables [SoftwarePreviewRenderer]. That fallback
 * decodes a lower-resolution playhead frame and samples the same color LUT on CPU, so the clip stays
 * editable and Input Color / color grading changes remain visible while the GPU path is unavailable.
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
 * The workspace outside the fitted project canvas is deliberately blackish gray. The project
 * canvas itself remains the rendered Surface, so scaling a clip below 100% exposes the canvas
 * around it while the outer pasteboard keeps the canvas boundary visible.
 *
 * A Surface can legally lose its displayed buffer while the editor is paused, after an Android
 * lifecycle transition, or while preview GPU resources are rebuilt. A paused decoder has no pump
 * that would naturally repaint it, so this host explicitly asks the engine to re-submit the last
 * visible playhead frame after Surface/lifecycle/topology changes.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    modifier: Modifier = Modifier,
) {
    val project by PreviewProjectRegistry.flow.collectAsState()
    val gpuFrame by engine.frame.collectAsState()
    val previewClock by PreviewTransformClock.flow.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val renderWidth = project?.width?.coerceAtLeast(2) ?: 1920
    val renderHeight = project?.height?.coerceAtLeast(2) ?: 1080
    val projectAspect = renderWidth.toFloat() / renderHeight.toFloat()
    val hasVideo = project?.tracks?.any { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    } == true

    // Once a device proves that its GPU Surface path is stalled, keep the software path active for
    // the current editor session. If a real GPU frame ever arrives we immediately hand display back
    // to the exact realtime pipeline.
    var softwareFallbackActive by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, hasVideo, gpuFrame?.timelineUs, gpuFrame?.bitmap) {
        when {
            !hasVideo -> softwareFallbackActive = false
            gpuFrame != null -> softwareFallbackActive = false
            !softwareFallbackActive -> {
                delay(GPU_FIRST_FRAME_GRACE_MS)
                if (engine.frame.value == null) softwareFallbackActive = true
            }
        }
    }

    val fallbackClip = project?.clip(previewClock.clipId)
    // During playback cap software fallback decode to ~10 fps. Paused grading still refreshes as
    // soon as the immutable project snapshot changes, even when playhead time does not move.
    val fallbackLocalUs = if (softwareFallbackActive) {
        (previewClock.localUs / SOFTWARE_FRAME_STEP_US) * SOFTWARE_FRAME_STEP_US
    } else {
        0L
    }
    val fallbackBitmap by produceState<Bitmap?>(
        initialValue = null,
        softwareFallbackActive,
        project,
        fallbackClip?.id,
        fallbackLocalUs,
    ) {
        val clip = fallbackClip
        if (!softwareFallbackActive || clip == null) {
            value = null
            return@produceState
        }
        val sourceUs = (clip.sourceInUs + fallbackLocalUs)
            .coerceIn(clip.sourceInUs, clip.sourceOutUs.coerceAtLeast(clip.sourceInUs))
        value = withContext(Dispatchers.Default) {
            SoftwarePreviewRenderer.render(
                context = context.applicationContext,
                clip = clip,
                sourceTimeUs = sourceUs,
                maxLongEdge = 720,
            )
        }
    }

    // Recycle only after Compose has switched away from the old fallback bitmap.
    DisposableEffect(fallbackBitmap) {
        val bitmap = fallbackBitmap
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    // Only structural video changes participate in this key. Color/transform slider snapshots do
    // not cause a second decode, but importing/moving/trimming a clip (and therefore rebuilding the
    // decoder/GL session topology) gets a one-shot self-healing paused-frame refresh.
    val videoTopologyKey = project?.tracks
        ?.filter { track -> track.kind == TrackKind.VIDEO && !track.muted }
        ?.map { track ->
            track.id to track.clips.map { clip ->
                listOf(
                    clip.id,
                    clip.uri,
                    clip.timelineStartUs.toString(),
                    clip.sourceInUs.toString(),
                    clip.sourceOutUs.toString(),
                )
            }
        }

    LaunchedEffect(engine, videoTopologyKey) {
        if (project != null) engine.scheduleCurrentFrameRefresh(140L)
    }

    // Some devices preserve the SurfaceView object while discarding its last buffer when the app
    // is backgrounded, so surfaceCreated() is not guaranteed to fire on return. ON_RESUME is the
    // second repaint trigger for that case.
    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                engine.scheduleCurrentFrameRefresh(80L)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            factory = { viewContext ->
                DigitorPreviewSurfaceView(
                    context = viewContext,
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

        val softwareFrame = fallbackBitmap
        if (softwareFallbackActive && softwareFrame != null && !softwareFrame.isRecycled) {
            Image(
                bitmap = softwareFrame.asImageBitmap(),
                contentDescription = "Software fallback preview",
                modifier = fittedModifier,
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val GPU_FIRST_FRAME_GRACE_MS = 700L
private const val SOFTWARE_FRAME_STEP_US = 100_000L
private val PREVIEW_PASTEBOARD_GRAY = Color(0xFF222226)

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
            attachedSurface?.takeIf { it.isValid }?.let {
                engine.attachSurface(it)
                engine.scheduleCurrentFrameRefresh(80L)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachedSurface = holder.surface
        engine.attachSurface(holder.surface)
        engine.scheduleCurrentFrameRefresh(80L)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attachedSurface !== holder.surface) {
            attachedSurface?.let { engine.detachSurface(it) }
            attachedSurface = holder.surface
        }
        engine.attachSurface(holder.surface)
        engine.scheduleCurrentFrameRefresh(80L)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attachedSurface?.let { engine.detachSurface(it) }
        attachedSurface = null
    }
}
