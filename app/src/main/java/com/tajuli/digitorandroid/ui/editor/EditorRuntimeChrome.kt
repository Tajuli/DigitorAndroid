package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine

/** Canonical runtime names for the proven editor chrome implementation. */
@Composable
internal fun EditorTopBar(
    title: String,
    status: String,
    exportFraction: Float?,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    onHome: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) = TopBarV7(
    title = title,
    status = status,
    exportFraction = exportFraction,
    canUndo = canUndo,
    canRedo = canRedo,
    onUndo = onUndo,
    onRedo = onRedo,
    onSaveProject = onSaveProject,
    onLoadProject = onLoadProject,
    onHome = onHome,
    onImport = onImport,
    onExport = onExport,
)

@Composable
internal fun EditorProjectActionsBar(
    canUndo: Boolean,
    canRedo: Boolean,
    exporting: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
) = ProjectActionsBarV7(
    canUndo = canUndo,
    canRedo = canRedo,
    exporting = exporting,
    onUndo = onUndo,
    onRedo = onRedo,
    onSaveProject = onSaveProject,
    onLoadProject = onLoadProject,
)

@Composable
internal fun EditorFramePreview(
    project: TimelineProject,
    previewEngine: DavinciFramePreviewEngine,
    frame: DavinciFramePreviewEngine.Frame?,
    hasVideo: Boolean,
    activeVideoClip: TimelineClip?,
    activeLayerCount: Int,
    visualOverlays: List<VisualOverlayClipV19>,
    textOverlays: List<TextOverlayClip>,
    timelineUs: Long,
    onImport: () -> Unit,
    qualifierPickerActive: Boolean,
    onPickColor: (Float, Float, Float) -> Unit,
    modifier: Modifier,
) = FramePreviewV7(
    project = project,
    previewEngine = previewEngine,
    frame = frame,
    hasVideo = hasVideo,
    activeVideoClip = activeVideoClip,
    activeLayerCount = activeLayerCount,
    visualOverlays = visualOverlays,
    textOverlays = textOverlays,
    timelineUs = timelineUs,
    onImport = onImport,
    qualifierPickerActive = qualifierPickerActive,
    onPickColor = onPickColor,
    modifier = modifier,
)

@Composable
internal fun EditorTransportControls(
    enabled: Boolean,
    isPlaying: Boolean,
    cursorUs: Long,
    durationUs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) = TransportV7(
    enabled = enabled,
    isPlaying = isPlaying,
    cursorUs = cursorUs,
    durationUs = durationUs,
    onBack = onBack,
    onPlayPause = onPlayPause,
    onForward = onForward,
)

internal fun formatTimelineTime(us: Long): String = timeV7(us)
