package com.tajuli.digitorandroid.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Direct display surface for the editor GPU preview.
 *
 * MediaCodec frames are composited by DigitorRenderCore/OpenGL straight into this SurfaceView.
 * There is no ImageReader, Bitmap readback, Compose texture upload or per-tick player seek in the
 * healthy preview path. PixelCopy is used only for an explicit HSL-picker tap and copies a tiny
 * neighborhood from the already-rendered Surface; it never opens another decoder.
 *
 * Realtime editing uses a 720p-long-edge final Surface buffer while the render core keeps the full
 * project compositor canvas. This avoids changing Media3 overlay geometry while still reducing the
 * final display surface. Source decoding and the shared LUT/spatial pipeline remain source-accurate.
 *
 * Some vendor codecs can decode a valid camera Log file but take too long to deliver a requested GPU
 * frame. Fallback activation is timestamp-aware: an old successful GPU frame does not count as the
 * frame for a new playhead seek. On a large stale seek we immediately expose the fallback path. Its
 * first decode uses the closest sync frame for fast visibility, then replaces it with the exact
 * requested frame while the GPU path keeps working in parallel.
 *
 * Export owns the device video resources exclusively. While an export lease is active the software
 * fallback is disabled as well as the GPU preview, preventing a hidden MediaMetadataRetriever decode
 * from racing Transformer and crashing fragile vendor codec stacks.
 */
@Composable
fun GpuPreviewSurface(
    engine: DavinciFramePreviewEngine,
    qualifierPickerActive: Boolean = false,
    onQualifierColorSample: ((Float, Float, Float) -> Unit)? = null,
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

    val fallbackClip = project?.clip(previewClock.clipId)
    val requestedTimelineUs = fallbackClip?.let { clip ->
        (clip.timelineStartUs + previewClock.localUs)
            .coerceIn(clip.timelineStartUs, clip.timelineEndUs.coerceAtLeast(clip.timelineStartUs))
    }
    val gpuDeltaUs = if (gpuFrame != null && requestedTimelineUs != null) {
        abs(gpuFrame!!.timelineUs - requestedTimelineUs)
    } else {
        Long.MAX_VALUE
    }
    val gpuFrameFresh = gpuFrame != null &&
        (requestedTimelineUs == null || gpuDeltaUs <= GPU_FRAME_FRESH_TOLERANCE_US)
    val staleRequestedFrame = gpuFrame != null &&
        requestedTimelineUs != null &&
        gpuDeltaUs > STALE_FRAME_FALLBACK_US

    var softwareFallbackActive by remember(engine) { mutableStateOf(false) }
    var previewView by remember(engine) { mutableStateOf<DigitorPreviewSurfaceView?>(null) }

    // First-ever frame: allow a very short GPU grace period, then show fallback rather than black.
    LaunchedEffect(engine, hasVideo, gpuFrame?.timelineUs, exportActive) {
        when {
            exportActive -> softwareFallbackActive = false
            !hasVideo -> softwareFallbackActive = false
            gpuFrame == null -> {
                delay(GPU_FIRST_FRAME_GRACE_MS)
                if (!PreviewExportCoordinator.exportActive.value && engine.frame.value == null) {
                    softwareFallbackActive = true
                }
            }
        }
    }

    // Seeking after a GPU frame already exists used to remain black because `gpuFrame != null` was
    // treated as ready even when that frame belonged to the old cursor position. Large timestamp
    // mismatch now activates fallback immediately; a fresh GPU frame turns it off again.
    LaunchedEffect(
        staleRequestedFrame,
        requestedTimelineUs,
        gpuFrame?.timelineUs,
        gpuFrameFresh,
        exportActive,
    ) {
        when {
            exportActive -> softwareFallbackActive = false
            gpuFrameFresh -> softwareFallbackActive = false
            staleRequestedFrame -> softwareFallbackActive = true
        }
    }

    // Follow project frame cadence, capped at 30 fps for thermal safety on devices that need the
    // software fallback. It still uses the shared color LUT for each displayed frame.
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

        // A freshly activated fallback should not make the user wait for a long GOP walk. Show a
        // small nearest-keyframe placeholder first. `produceState` keeps that value visible while
        // the exact OPTION_CLOSEST decode below is still running.
        if (value == null) {
            val fastFrame = withContext(Dispatchers.Default) {
                SoftwarePreviewRenderer.render(
                    context = context.applicationContext,
                    clip = clip,
                    sourceTimeUs = sourceUs,
                    maxLongEdge = FAST_PLACEHOLDER_LONG_EDGE,
                    closestSyncOnly = true,
                )
            }
            if (fastFrame != null) value = fastFrame
        }

        val exactFrame = withContext(Dispatchers.Default) {
            SoftwarePreviewRenderer.render(
                context = context.applicationContext,
                clip = clip,
                sourceTimeUs = sourceUs,
                maxLongEdge = SOFTWARE_PREVIEW_LONG_EDGE,
                closestSyncOnly = false,
            )
        }
        if (exactFrame != null) value = exactFrame
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
                ).also { previewView = it }
            },
            update = { view ->
                if (previewView !== view) previewView = view
                view.bind(
                    nextEngine = engine,
                    nextBufferWidth = renderWidth,
                    nextBufferHeight = renderHeight,
                )
            },
        )

        val softwareFrame = fallbackBitmap
        val showingSoftwareFrame =
            !exportActive &&
                softwareFallbackActive &&
                !gpuFrameFresh &&
                softwareFrame != null &&
                !softwareFrame.isRecycled

        if (showingSoftwareFrame && softwareFrame != null) {
            Image(
                bitmap = softwareFrame.asImageBitmap(),
                contentDescription = "Software fallback preview",
                modifier = fittedModifier,
                contentScale = ContentScale.Fit,
            )
        }

        // HSL qualification samples the pixels the user can already see. When fallback is on, read
        // its in-memory bitmap. Otherwise PixelCopy reads a tiny Surface neighborhood asynchronously.
        // Neither route opens MediaMetadataRetriever or another MediaCodec decoder.
        if (qualifierPickerActive && onQualifierColorSample != null && !exportActive) {
            Box(
                fittedModifier.pointerInput(
                    previewView,
                    softwareFrame,
                    showingSoftwareFrame,
                    renderWidth,
                    renderHeight,
                ) {
                    detectTapGestures { pos ->
                        val callback = onQualifierColorSample
                        if (callback == null || size.width <= 0 || size.height <= 0) return@detectTapGestures

                        if (showingSoftwareFrame && softwareFrame != null && !softwareFrame.isRecycled) {
                            sampleBitmapFitColor(
                                bitmap = softwareFrame,
                                tapX = pos.x,
                                tapY = pos.y,
                                viewWidth = size.width.toFloat(),
                                viewHeight = size.height.toFloat(),
                            )?.let { rgb -> callback(rgb[0], rgb[1], rgb[2]) }
                        } else {
                            previewView?.sampleVisibleColor(
                                normalizedX = (pos.x / size.width.toFloat()).coerceIn(0f, 1f),
                                normalizedY = (pos.y / size.height.toFloat()).coerceIn(0f, 1f),
                                onColor = callback,
                            )
                        }
                    }
                },
            ) {}
        }
    }
}

private const val GPU_FIRST_FRAME_GRACE_MS = 80L
private const val GPU_FRAME_FRESH_TOLERANCE_US = 180_000L
private const val STALE_FRAME_FALLBACK_US = 250_000L
private const val FAST_PLACEHOLDER_LONG_EDGE = 480
private const val SOFTWARE_PREVIEW_LONG_EDGE = REALTIME_PREVIEW_LONG_EDGE
private val PREVIEW_PASTEBOARD_GRAY = Color(0xFF222226)

private fun sampleBitmapFitColor(
    bitmap: Bitmap,
    tapX: Float,
    tapY: Float,
    viewWidth: Float,
    viewHeight: Float,
): FloatArray? {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null

    val scale = min(viewWidth / bitmap.width.toFloat(), viewHeight / bitmap.height.toFloat())
    val shownWidth = bitmap.width * scale
    val shownHeight = bitmap.height * scale
    val left = (viewWidth - shownWidth) * .5f
    val top = (viewHeight - shownHeight) * .5f
    if (tapX < left || tapX >= left + shownWidth || tapY < top || tapY >= top + shownHeight) return null

    val centerX = (((tapX - left) / shownWidth) * bitmap.width)
        .toInt()
        .coerceIn(0, bitmap.width - 1)
    val centerY = (((tapY - top) / shownHeight) * bitmap.height)
        .toInt()
        .coerceIn(0, bitmap.height - 1)
    return averageBitmapNeighborhood(bitmap, centerX, centerY)
}

private fun averageBitmapNeighborhood(bitmap: Bitmap, centerX: Int, centerY: Int): FloatArray {
    var rr = 0f
    var gg = 0f
    var bb = 0f
    var count = 0
    for (dy in -1..1) {
        for (dx in -1..1) {
            val x = (centerX + dx).coerceIn(0, bitmap.width - 1)
            val y = (centerY + dy).coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            rr += ((color ushr 16) and 0xFF) / 255f
            gg += ((color ushr 8) and 0xFF) / 255f
            bb += (color and 0xFF) / 255f
            count++
        }
    }
    return floatArrayOf(rr / count, gg / count, bb / count)
}

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
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun sampleVisibleColor(
        normalizedX: Float,
        normalizedY: Float,
        onColor: (Float, Float, Float) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val surface = attachedSurface?.takeIf { it.isValid } ?: holder.surface.takeIf { it.isValid } ?: return

        val centerX = (normalizedX.coerceIn(0f, 1f) * (bufferWidth - 1))
            .toInt()
            .coerceIn(0, bufferWidth - 1)
        val centerY = (normalizedY.coerceIn(0f, 1f) * (bufferHeight - 1))
            .toInt()
            .coerceIn(0, bufferHeight - 1)
        val sourceRect = Rect(
            (centerX - 1).coerceAtLeast(0),
            (centerY - 1).coerceAtLeast(0),
            (centerX + 2).coerceAtMost(bufferWidth),
            (centerY + 2).coerceAtMost(bufferHeight),
        )
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return

        val sample = Bitmap.createBitmap(sourceRect.width(), sourceRect.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                surface,
                sourceRect,
                sample,
                { result ->
                    try {
                        if (result == PixelCopy.SUCCESS && !sample.isRecycled) {
                            val rgb = averageBitmapNeighborhood(
                                sample,
                                sample.width / 2,
                                sample.height / 2,
                            )
                            onColor(rgb[0], rgb[1], rgb[2])
                        }
                    } finally {
                        if (!sample.isRecycled) sample.recycle()
                    }
                },
                mainHandler,
            )
        } catch (_: Throwable) {
            if (!sample.isRecycled) sample.recycle()
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
