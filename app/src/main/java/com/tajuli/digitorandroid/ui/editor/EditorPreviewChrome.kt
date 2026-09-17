package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.VisualOverlayClipV19
import com.tajuli.digitorandroid.editor.preview.DavinciFramePreviewEngine
import com.tajuli.digitorandroid.editor.preview.GpuPreviewSurface
import kotlin.math.roundToInt

private val E7Muted = Color(0xFF909098)
private val E7Accent = Color(0xFF30E0C3)
private val E7PreviewPasteboard = Color(0xFF222226)

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
) {
    val exporting = exportFraction != null && exportFraction < 1f
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onHome, enabled = !exporting, modifier = Modifier.height(32.dp), shape = RoundedCornerShape(7.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Home, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Home", fontSize = 10.sp)
        }
        IconButton(onClick = onImport, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, "Import", modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onUndo, enabled = canUndo && !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Undo, "Undo", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onRedo, enabled = canRedo && !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Redo, "Redo", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onSaveProject, enabled = !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Save, "Save project", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onLoadProject, enabled = !exporting, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.FolderOpen, "Load project", modifier = Modifier.size(16.dp)) }
        Column(Modifier.weight(1f).padding(start = 3.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(E7Accent)); Spacer(Modifier.width(4.dp))
                Text(status, fontSize = 8.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(onClick = onExport, enabled = !exporting, modifier = Modifier.height(32.dp), shape = RoundedCornerShape(7.dp)) {
            Icon(Icons.Rounded.Share, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
            Text(if (exporting) "${((exportFraction ?: 0f) * 100).roundToInt()}%" else "Export", fontSize = 10.sp)
        }
    }
}

@Composable
internal fun EditorProjectActionsBar(
    canUndo: Boolean,
    canRedo: Boolean,
    exporting: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF0D0D11))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = onUndo, enabled = canUndo && !exporting, modifier = Modifier.height(30.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Undo, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Undo", fontSize = 9.sp)
        }
        TextButton(onClick = onRedo, enabled = canRedo && !exporting, modifier = Modifier.height(30.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Redo, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Redo", fontSize = 9.sp)
        }
        TextButton(onClick = onSaveProject, enabled = !exporting, modifier = Modifier.height(30.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.Save, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Save Project", fontSize = 9.sp)
        }
        TextButton(onClick = onLoadProject, enabled = !exporting, modifier = Modifier.height(30.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Load Project", fontSize = 9.sp)
        }
    }
}

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
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(E7PreviewPasteboard).onSizeChanged { previewSize = it }, contentAlignment = Alignment.Center) {
        if (hasVideo) {
            GpuPreviewSurface(
                engine = previewEngine,
                qualifierPickerActive = qualifierPickerActive && activeVideoClip != null,
                onQualifierColorSample = onPickColor,
                modifier = Modifier.fillMaxSize(),
            )
            val fallbackBitmap = frame?.bitmap
            if (fallbackBitmap != null && !fallbackBitmap.isRecycled) {
                Image(bitmap = fallbackBitmap.asImageBitmap(), contentDescription = "CPU fallback preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (frame == null) {
                Text("Preparing GPU preview…", color = Color.White.copy(alpha = .55f), fontSize = 11.sp)
            }
            if (activeVideoClip == null) {
                Text("No video at cursor · overlays/audio can continue", color = Color.White.copy(alpha = .55f), fontSize = 11.sp, modifier = Modifier.background(Color.Black.copy(alpha = .62f), RoundedCornerShape(5.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
            }
        } else if (visualOverlays.isEmpty() && textOverlays.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color.White.copy(alpha = .35f), modifier = Modifier.size(40.dp)); Spacer(Modifier.height(8.dp))
                Text("No video media", color = Color.White.copy(alpha = .55f), fontSize = 12.sp); TextButton(onClick = onImport) { Text("Import media") }
            }
        }

        visualOverlays.forEach { overlay ->
            VisualOverlayPreviewV19(project = project, overlay = overlay, previewSize = previewSize)
        }
        textOverlays.forEach { overlay ->
            TextOverlayPreviewV2(overlay = overlay, timelineUs = timelineUs, previewSize = previewSize)
        }

        Text("GPU Preview · $activeLayerCount ${if (activeLayerCount == 1) "layer" else "layers"}", modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 9.sp, color = Color.White.copy(alpha = .72f))

        if (qualifierPickerActive && activeVideoClip != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .05f)), contentAlignment = Alignment.TopCenter) {
                Row(Modifier.padding(top = 9.dp).background(Color.Black.copy(alpha = .76f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Colorize, null, modifier = Modifier.size(15.dp), tint = E7Accent); Spacer(Modifier.width(5.dp)); Text("Tap the color to qualify selected layer", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun EditorTransportControls(
    enabled: Boolean,
    isPlaying: Boolean,
    cursorUs: Long,
    durationUs: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF0D0D11)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(formatTimelineTime(cursorUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
        IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(18.dp), tint = Color.White) }
        IconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(38.dp)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(23.dp), tint = Color.White) }
        IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Forward10, null, modifier = Modifier.size(18.dp), tint = Color.White) }
        Text(formatTimelineTime(durationUs), color = E7Muted, fontSize = 9.sp, modifier = Modifier.width(66.dp))
    }
}

internal fun formatTimelineTime(us: Long): String {
    val totalSeconds = us.coerceAtLeast(0L) / US_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
