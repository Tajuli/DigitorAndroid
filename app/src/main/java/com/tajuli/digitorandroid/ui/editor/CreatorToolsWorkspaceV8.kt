package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollState as rememberVerticalScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.audioSelection
import kotlin.math.min

private val C8Panel = Color(0xFF0B0B0F)
private val C8Raised = Color(0xFF17171C)
private val C8Divider = Color(0xFF292930)
private val C8Muted = Color(0xFF909098)
private val C8Accent = Color(0xFF30E0C3)

@Composable
fun CreatorMediaWorkspaceV8(
    project: TimelineProject,
    selectedClip: TimelineClip?,
    selectedTextId: String?,
    cursorUs: Long,
    busyOperation: String?,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val selectedIsVideo = selectedClip != null && project.trackContaining(selectedClip.id)?.kind == TrackKind.VIDEO
    val selectedText = project.textOverlays.firstOrNull { it.id == selectedTextId }
    Column(modifier.background(C8Panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Creator tools", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(busyOperation ?: "Text · Transition · Retime", fontSize = 8.sp, color = if (busyOperation == null) C8Muted else C8Accent)
        }
        HorizontalDivider(color = C8Divider)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberVerticalScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCardV8("Text / Captions") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = false) }) { Text("+ Text", fontSize = 8.sp) }
                    FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = true) }) { Text("+ Caption", fontSize = 8.sp) }
                }
                if (project.textOverlays.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        project.textOverlays.forEach { item ->
                            Text(
                                item.text.ifBlank { "Text" }.take(18),
                                fontSize = 8.sp,
                                color = if (item.id == selectedTextId) C8Accent else Color.White.copy(alpha = .72f),
                                modifier = Modifier.background(C8Raised, RoundedCornerShape(5.dp)).clickable { vm.selectTextOverlay(item.id) }
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                if (selectedText != null) TextEditorV8(selectedText, vm)
            }

            SectionCardV8("Transition") {
                if (!selectedIsVideo) {
                    Text("Select a video clip", fontSize = 8.sp, color = C8Muted)
                } else {
                    val clip = selectedClip!!
                    val maxFadeUs = min(clip.durationUs / 2L, 3_000_000L).coerceAtLeast(1L)
                    DurationSliderV8("Fade in", clip.transition.fadeInUs, maxFadeUs) { value ->
                        vm.setSelectedTransition(value, clip.transition.fadeOutUs)
                    }
                    DurationSliderV8("Fade out", clip.transition.fadeOutUs, maxFadeUs) { value ->
                        vm.setSelectedTransition(clip.transition.fadeInUs, value)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { vm.setSelectedTransition(0L, 0L) }) { Text("None", fontSize = 8.sp) }
                        Text("Compositor-native fade", fontSize = 7.sp, color = C8Muted, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }

            SectionCardV8("Speed / Reverse / Freeze") {
                if (!selectedIsVideo) {
                    Text("Select a video clip", fontSize = 8.sp, color = C8Muted)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(.5f, .75f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                            FilledTonalButton(enabled = busyOperation == null, onClick = { vm.bakeSelectedSpeed(speed) }) {
                                Text("${speed}x", fontSize = 8.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(enabled = busyOperation == null, onClick = vm::reverseSelectedVideo) { Text("Reverse", fontSize = 8.sp) }
                        Button(enabled = busyOperation == null, onClick = { vm.freezeSelectedAt(cursorUs, 2_000_000L) }) { Text("Freeze 2s", fontSize = 8.sp) }
                    }
                    Text("Speed is baked with Media3; Reverse/Freeze become ordinary derived MP4 clips.", fontSize = 7.sp, color = C8Muted)
                }
            }
        }
    }
}

@Composable
fun CreatorAudioWorkspaceV8(
    project: TimelineProject,
    selectedClipId: String?,
    selectedClipIds: Set<String>,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val selected = project.audioSelection(selectedClipId, selectedClipIds)
    val clip = selected.firstOrNull()
    Column(modifier.background(C8Panel)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Audio", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (selected.size > 1) "${selected.size} linked clips" else clip?.label ?: "No selection", fontSize = 8.sp, color = C8Muted)
        }
        HorizontalDivider(color = C8Divider)
        if (clip == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select an audio clip or linked video clip", fontSize = 9.sp, color = C8Muted)
            }
            return@Column
        }
        val maxFadeUs = min(clip.durationUs, 5_000_000L).coerceAtLeast(1L)
        Column(Modifier.fillMaxSize().verticalScroll(rememberVerticalScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionCardV8("Volume") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(clip.audioMix.volume * 100).toInt()}%", fontSize = 8.sp, color = C8Accent, modifier = Modifier.width(42.dp))
                    Slider(value = clip.audioMix.volume.coerceIn(0f, 1f), onValueChange = vm::setSelectedAudioVolume, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { vm.setSelectedAudioVolume(0f) }) { Text("Mute", fontSize = 8.sp) }
                    TextButton(onClick = { vm.setSelectedAudioVolume(1f) }) { Text("100%", fontSize = 8.sp) }
                }
            }
            SectionCardV8("Fades") {
                DurationSliderV8("Fade in", clip.audioMix.fadeInUs, maxFadeUs, vm::setSelectedAudioFadeIn)
                DurationSliderV8("Fade out", clip.audioMix.fadeOutUs, maxFadeUs, vm::setSelectedAudioFadeOut)
                Text("Gain automation is applied in realtime audio preview and export.", fontSize = 7.sp, color = C8Muted)
            }
        }
    }
}

@Composable
private fun TextEditorV8(item: TextOverlayClip, vm: EditorViewModelV4) {
    OutlinedTextField(
        value = item.text,
        onValueChange = { vm.updateSelectedText(text = it) },
        label = { Text("Text", fontSize = 8.sp) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    CreatorSliderV8("X", item.positionX, -1f..1f) { vm.updateSelectedText(positionX = it) }
    CreatorSliderV8("Y", item.positionY, -1f..1f) { vm.updateSelectedText(positionY = it) }
    CreatorSliderV8("Size", item.sizeScale, .35f..2.5f) { vm.updateSelectedText(sizeScale = it) }
    DurationSliderV8("Duration", item.durationUs, 10_000_000L) { vm.setSelectedTextDuration(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.background, onCheckedChange = { vm.updateSelectedText(background = it) })
        Text("Caption background", fontSize = 8.sp, color = Color.White.copy(alpha = .72f))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::deleteSelectedText) { Text("Delete", fontSize = 8.sp, color = Color(0xFFFF6B6B)) }
    }
}

@Composable
private fun SectionCardV8(title: String, content: @Composable Column.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(C8Raised, RoundedCornerShape(7.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .82f))
        content()
    }
}

@Composable
private fun CreatorSliderV8(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(44.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.2f", value), fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun DurationSliderV8(label: String, valueUs: Long, maxUs: Long, onChange: (Long) -> Unit) {
    val safeMax = maxUs.coerceAtLeast(1L)
    val seconds = valueUs.coerceAtLeast(0L) / US_PER_SECOND.toFloat()
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(58.dp))
        Slider(
            value = (valueUs.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f),
            onValueChange = { onChange((it * safeMax).toLong()) },
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.1fs", seconds), fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(38.dp))
    }
}
