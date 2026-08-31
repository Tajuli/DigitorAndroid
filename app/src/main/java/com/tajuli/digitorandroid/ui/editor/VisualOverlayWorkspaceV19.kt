package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.ShapePresetV19
import com.tajuli.digitorandroid.editor.model.StickerPresetV19
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19

private val VO19Panel = Color(0xFF0B0B0F)
private val VO19Raised = Color(0xFF17171C)
private val VO19Divider = Color(0xFF292930)
private val VO19Muted = Color(0xFF909098)
private val VO19Accent = Color(0xFF30E0C3)
private val VO19Danger = Color(0xFFFF7474)

private val VisualPaletteV19 = listOf(
    0xFFFFFFFFL,
    0xFF111111L,
    0xFFFFD54FL,
    0xFFFF5A5FL,
    0xFF4DD0E1L,
    0xFF66BB6AL,
    0xFFAB86FFL,
    0xFFFF8BCBL,
)

/**
 * Overlay is composition graphics only. User photos/still images are V-track TimelineClip media and
 * are imported from the regular media Import action, exactly like video clips.
 */
@Composable
fun VisualOverlayWorkspaceV19(
    project: TimelineProject,
    cursorUs: Long,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedId by VisualOverlaySelectionBusV19.selectedId.collectAsState()
    val overlays = project.resolvedVisualOverlaysV19().filter { it.kind != VisualOverlayKindV19.IMAGE }
    val selected = overlays.firstOrNull { it.id == selectedId }
    val targetTrack = vm.selectedVideoTrackForVisualV19()

    LaunchedEffect(selectedId, overlays) {
        if (selectedId != null && overlays.none { it.id == selectedId }) {
            VisualOverlaySelectionBusV19.clear()
        } else if (selectedId == null && overlays.isNotEmpty()) {
            vm.selectVisualOverlayV19(overlays.last().id)
        }
    }

    Column(modifier.background(VO19Panel)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Overlay", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text("Stickers & shapes · ${targetTrack?.name ?: "select a V track"}", fontSize = 7.sp, color = VO19Muted)
            }
            Spacer(Modifier.weight(1f))
            Text("Images: use Import", fontSize = 7.sp, color = VO19Accent)
        }
        HorizontalDivider(color = VO19Divider)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StickerPresetV19.entries.forEach { preset ->
                SmallOverlayActionV19("Sticker · ${preset.prettyV19()}", targetTrack != null) {
                    vm.addStickerOverlayV19(preset, cursorUs)
                }
            }
            ShapePresetV19.entries.forEach { preset ->
                SmallOverlayActionV19("Shape · ${preset.prettyV19()}", targetTrack != null) {
                    vm.addShapeOverlayV19(preset, cursorUs)
                }
            }
        }

        if (overlays.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                overlays.sortedBy { it.timelineStartUs }.forEach { item ->
                    val trackName = project.track(item.resolvedVideoTrackIdV19(project))?.name ?: "V?"
                    val active = item.id == selectedId
                    Text(
                        "$trackName · ${item.label.take(15)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 8.sp,
                        color = if (active) VO19Accent else Color.White.copy(alpha = .72f),
                        modifier = Modifier
                            .background(VO19Raised, RoundedCornerShape(5.dp))
                            .border(if (active) 1.dp else .5.dp, if (active) VO19Accent else VO19Divider, RoundedCornerShape(5.dp))
                            .clickable {
                                vm.selectVisualOverlayV19(item.id)
                                onSeek(item.timelineStartUs)
                            }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = VO19Divider)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected == null) {
                Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                    Text("Add a sticker or shape", fontSize = 9.sp, color = VO19Muted)
                }
                return@Column
            }

            Text("${selected.kind.prettyV19()} · ${selected.label}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text("Video track", fontSize = 8.sp, color = VO19Muted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                project.tracks.filter { it.kind == TrackKind.VIDEO }.forEach { track ->
                    val active = selected.resolvedVideoTrackIdV19(project) == track.id
                    SmallOverlayActionV19(track.name, true, active) {
                        vm.moveVisualOverlayToTrackV19(selected.id, track.id)
                    }
                }
            }

            OverlaySliderV19("X", selected.positionX, -1f..1f) { vm.updateSelectedVisualV19(positionX = it) }
            OverlaySliderV19("Y", selected.positionY, -1f..1f) { vm.updateSelectedVisualV19(positionY = it) }
            OverlaySliderV19("Scale", selected.scale, .03f..1.5f) { vm.updateSelectedVisualV19(scale = it) }
            OverlaySliderV19("Rotation", selected.rotationDegrees, 0f..360f) { vm.updateSelectedVisualV19(rotationDegrees = it) }
            OverlaySliderV19("Opacity", selected.opacity, 0f..1f) { vm.updateSelectedVisualV19(opacity = it) }
            OverlaySliderV19("Duration", selected.durationUs / 1_000_000f, .1f..60f) {
                vm.setSelectedVisualDurationV19((it * 1_000_000L).toLong())
            }

            Text("Color", fontSize = 8.sp, color = VO19Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VisualPaletteV19.forEach { color ->
                    val active = color == selected.colorArgb
                    Box(
                        Modifier.size(24.dp).background(Color(color), CircleShape)
                            .border(if (active) 2.dp else 1.dp, if (active) VO19Accent else Color.White.copy(alpha = .25f), CircleShape)
                            .clickable { vm.updateSelectedVisualV19(colorArgb = color) },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${"%.1f".format(selected.durationUs / 1_000_000f)}s · ${project.track(selected.resolvedVideoTrackIdV19(project))?.name ?: "V?"}",
                    fontSize = 7.sp,
                    color = VO19Muted,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::deleteSelectedVisualV19) {
                    Text("Delete", fontSize = 8.sp, color = VO19Danger)
                }
            }
        }
    }
}

@Composable
private fun SmallOverlayActionV19(
    label: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 8.sp,
        color = when {
            !enabled -> VO19Muted.copy(alpha = .5f)
            selected -> VO19Accent
            else -> Color.White.copy(alpha = .78f)
        },
        modifier = Modifier
            .background(VO19Raised, RoundedCornerShape(5.dp))
            .border(if (selected) 1.dp else .5.dp, if (selected) VO19Accent else VO19Divider, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun OverlaySliderV19(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), Modifier.width(42.dp), fontSize = 7.sp, color = VO19Muted)
    }
}

private fun StickerPresetV19.prettyV19(): String = name.lowercase().replaceFirstChar { it.uppercase() }
private fun ShapePresetV19.prettyV19(): String = name.lowercase().replaceFirstChar { it.uppercase() }
private fun VisualOverlayKindV19.prettyV19(): String = name.lowercase().replaceFirstChar { it.uppercase() }
