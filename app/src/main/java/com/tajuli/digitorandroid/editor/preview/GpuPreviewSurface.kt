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
import com.tajuli.digitorandroid.editor.render.REALTIME_PREVIEW_LONG_EDGE
import com.tajuli.digitorandroid.editor.render.resolvePreviewOutputSize
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
 * Realtime editing uses a 720p-long-edge final Surface buffer while source decoding and the shared
 * per-layer LUT/spatial pipeline remain at source resolution. That keeps grading/effect math aligned
 * with full-resolution export and removes the unnecessary project-resolution final GPU surface.
 *
 * Some vendor codecs can decode a valid camera Log file but never complete the Surface handoff into
 * Media3's multi-input GL graph. In that case a Resolve-style editor must still show the footage as
 * a flat/raw image instead of leaving the viewer black. The software image appears almost
 * immediately while the GPU path is allowed to keep preparing in the background.
 *
 * Export owns the device video resources exclusively. While an export lease is active the software
 * fallback is disabled as well as the GPU preview, preventing a hidden MediaMetadataRetriever decode
 * from racing Transformer and crashing fragile vendor codec stacks.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    modifier: Modifier = Modifier,
) {
    val project by PreviewProjectRegistry.flow.collectAsState()
    val gpuFrame by engine.frame.collectAsState()
    val previewClock by PreviewTransformClock.flow.collectAsState()
    val exportActive by PreviewExportCoordinator.exportActive.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val sourceWidth = project?.width?.coerceAtLeast(2) ?: 1920
    val sourceHeight = project?.height?.coerceAtLeast(2) ?: 1080
    val previewOutputSize = project?.let {
        resolvePreviewOutputSize(it, REALTIME_PREVIEW_LONG_EDGE)
    }
    val renderWidth = previewOutputSize?.first ?: 1280
    val renderHeight = previewOutputSize?.second ?: 720
    val projectAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
    val hasVideo = project?.tracks?.any { track ->
        track.kind == TrackKind.VIDEO && !track.muted && track.clips.isNotEmpty()
    } == true

    var softwareFallbackActive by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, hasVideo, gpuFrame?.timelineUs, gpuFrame?.bitmap, exportActive) {
        when {
            exportActive -> softwareFallbackActive = false
            !hasVideo -> softwareFallbackActive = false
            gpuFrame != null -> softwareFallbackActive = false
            !softwareFallbackActive -> {
                // Do not make the user stare at a black viewer while a fragile camera codec warms
                // up. Show the flat/raw software frame quickly and let GPU preparation continue.
                delay(GPU_FIRST_FRAME_GRACE_MS)
                if (!PreviewExportCoordinator.exportActive.value && engine.frame.value == null) {
                    softwareFallbackActive = true
                }
            }
        }
    }

    val fallbackClip = project?.clip(previewClock.clipId)
    // Follow project frame cadence, capped at 30 fps for thermal safety on devices that need the
    // software fallback. It still uses the exact shared color LUT for each displayed frame.
    val fallbackPreviewFps = project?.frameRate?.coerceIn(12, 30) ?: 24
    val fallbackFrameStepUs = 1_000_000L / fallbackPreviewFps.toLong()
    val fallbackLocalUs = if (softwareFallbackActive && !exportActive) {
        (previewClock.localUs / fallbackFrameStepUs) * fallbackFrameStepUs
    } else {
        0L
    }
    val fallbackBitmap by produceState<Bitmap?>(
        initialValue = null,
        softwareFallbackActive,
        exportActive,
        project,
        fallbackClip?.id,
        fallbackLocalUs,
    ) {
        val clip = fallbackClip
        if (exportActive || !softwareFallbackActive || clip == null) {
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
                maxLongEdge = SOFTWARE_PREVIEW_LONG_EDGE,
            )
        }
    }

    DisposableEffect(fallbackBitmap) {
        val bitmap = fallbackBitmap
        onDispose {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }

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

    LaunchedEffect(engine, videoTopologyKey, exportActive) {
        if (project != null && !exportActive) engine.scheduleCurrentFrameRefresh(140L)
    }

    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !PreviewExportCoordinator.exportActive.value) {
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
        if (!exportActive && softwareFallbackActive && softwareFrame != null && !softwareFrame.isRecycled) {
            Image(
                bitmap = softwareFrame.asImageBitmap(),
                contentDescription = "Software fallback preview",
                modifier = fittedModifier,
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val GPU_FIRST_FRAME_GRACE_MS = 120L
private const val SOFTWARE_PREVIEW_LONG_EDGE = REALTIME_PREVIEW_LONG_EDGE
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
        holder.setFixedSize(bufferWidth, bufferHeight)
        holder.addCallback(this)
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
        if (!PreviewExportCoordinator.exportActive.value) engine.scheduleCurrentFrameRefresh(80L)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (attachedSurface !== holder.surface) {
            attachedSurface?.let { engine.detachSurface(it) }
            attachedSurface = holder.surface
        }
        engine.attachSurface(holder.surface)
        if (!PreviewExportCoordinator.exportActive.value) engine.scheduleCurrentFrameRefresh(80L)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attachedSurface?.let { engine.detachSurface(it) }
        attachedSurface = null
    }
}
