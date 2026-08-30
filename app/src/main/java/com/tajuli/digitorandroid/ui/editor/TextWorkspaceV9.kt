package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextAnimationSpecV2
import com.tajuli.digitorandroid.editor.model.TextAnimationV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextManualAnimationV2
import com.tajuli.digitorandroid.editor.model.TextManualFrameV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TextStyleV2
import com.tajuli.digitorandroid.editor.model.TextTransformKeyframeV2
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.resolvedEntryAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedExitAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedManualAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.model.resolvedVideoTrackIdV3
import com.tajuli.digitorandroid.editor.model.textManualFrameV2
import kotlin.math.max

private val TX9Panel = Color(0xFF0B0B0F)
private val TX9Raised = Color(0xFF17171C)
private val TX9Divider = Color(0xFF292930)
private val TX9Muted = Color(0xFF909098)
private val TX9Accent = Color(0xFF30E0C3)
private val TX9Key = Color(0xFFFFC857)
private val TX9Danger = Color(0xFFFF7474)

private enum class TextPageV9(val label: String) {
    INSPECTOR("Inspector"),
    ANIMATE("Keyframes"),
    TEMPLATES("Templates"),
}

private val TextPaletteV9 = listOf(
    0xFFFFFFFFL,
    0xFF000000L,
    0xFFFFD54FL,
    0xFFFF5A5FL,
    0xFF4DD0E1L,
    0xFF66BB6AL,
    0xFFAB86FFL,
    0xFFFF8BCBL,
)

@Composable
fun TextWorkspaceV9(
    project: TimelineProject,
    selectedTextId: String?,
    cursorUs: Long,
    frameRate: Int,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timelineSelectedTextId by TimelineTextSelectionBusV10.selectedTextId.collectAsState()
    val selected = project.textOverlays.firstOrNull { it.id == selectedTextId }
    val targetTrack = vm.selectedVideoTrackForTextV10()
    var page by remember { mutableStateOf(TextPageV9.INSPECTOR) }

    LaunchedEffect(timelineSelectedTextId, project.textOverlays) {
        val pending = timelineSelectedTextId
        if (pending != null && pending != selectedTextId && project.textOverlays.any { it.id == pending }) {
            vm.selectTextOverlay(pending)
        }
    }

    Column(modifier.background(TX9Panel)) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Text", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Add to ${targetTrack?.name ?: "select a V track"}",
                    fontSize = 7.sp,
                    color = TX9Muted,
                )
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { vm.addTextAtSelectedVideoTrackV10(cursorUs, caption = false) },
                enabled = targetTrack != null,
                modifier = Modifier.height(30.dp),
            ) { Text("+ Text", fontSize = 8.sp) }
            Spacer(Modifier.width(5.dp))
            FilledTonalButton(
                onClick = { vm.addTextAtSelectedVideoTrackV10(cursorUs, caption = true) },
                enabled = targetTrack != null,
                modifier = Modifier.height(30.dp),
            ) { Text("+ Caption", fontSize = 8.sp) }
        }
        HorizontalDivider(color = TX9Divider)

        if (project.textOverlays.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                project.textOverlays.sortedBy { it.timelineStartUs }.forEach { item ->
                    val trackName = project.track(item.resolvedVideoTrackIdV3(project))?.name ?: "V?"
                    val selectedChip = item.id == selectedTextId
                    Text(
                        "$trackName · ${item.text.ifBlank { "Text" }.take(15)}",
                        fontSize = 8.sp,
                        color = if (selectedChip) TX9Accent else Color.White.copy(alpha = .72f),
                        modifier = Modifier
                            .background(TX9Raised, RoundedCornerShape(5.dp))
                            .border(
                                if (selectedChip) 1.dp else .5.dp,
                                if (selectedChip) TX9Accent else TX9Divider,
                                RoundedCornerShape(5.dp),
                            )
                            .clickable {
                                TimelineTextSelectionBusV10.select(item.id)
                                vm.selectTextOverlay(item.id)
                                onSeek(item.timelineStartUs)
                            }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextPageV9.entries.forEach { item ->
                TextButton(onClick = { page = item }, modifier = Modifier.height(32.dp)) {
                    Text(item.label, fontSize = 8.sp, color = if (page == item) TX9Accent else TX9Muted)
                }
            }
        }
        HorizontalDivider(color = TX9Divider)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (page) {
                TextPageV9.INSPECTOR -> {
                    if (selected == null) EmptyTextV9("Select a title in the timeline or add a new one")
                    else TextInspectorV9(project, selected, vm)
                }
                TextPageV9.ANIMATE -> {
                    if (selected == null) EmptyTextV9("Select a title to animate")
                    else TextAnimateV9(selected, cursorUs, frameRate, vm, onSeek)
                }
                TextPageV9.TEMPLATES -> TextTemplatesPanelV10(
                    selected = selected,
                    targetTrackName = targetTrack?.name,
                    cursorUs = cursorUs,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun EmptyTextV9(message: String) {
    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 9.sp, color = TX9Muted)
    }
}

@Composable
private fun TextInspectorV9(project: TimelineProject, item: TextOverlayClip, vm: EditorViewModelV4) {
    val style = item.resolvedTextStyleV2()
    val assignedTrackId = item.resolvedVideoTrackIdV3(project)

    Text("Video track", fontSize = 8.sp, color = TX9Muted)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        project.tracks.filter { it.kind == TrackKind.VIDEO }.forEach { track ->
            ChoiceV9(track.name, assignedTrackId == track.id) {
                vm.moveSelectedTextToVideoTrackV10(track.id)
            }
        }
    }

    OutlinedTextField(
        value = item.text,
        onValueChange = { vm.updateSelectedText(text = it) },
        label = { Text("Selected text", fontSize = 8.sp) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    Text("Font", fontSize = 8.sp, color = TX9Muted)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TextFontV2.entries.forEach { font ->
            ChoiceV9(font.name.lowercase().replaceFirstChar { it.uppercase() }, style.font == font) {
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(font = font)))
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.bold, onCheckedChange = { vm.commitTextOverlayV2(item.copy(bold = it)) })
        Text("Bold", fontSize = 8.sp, color = Color.White.copy(alpha = .75f))
    }

    Text("Text color", fontSize = 8.sp, color = TX9Muted)
    ColorEditorV9(style.colorArgb) { color ->
        vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(colorArgb = color)))
    }

    Text("Stroke", fontSize = 8.sp, color = TX9Muted)
    CommitSliderV9("Width", style.strokeWidth, 0f..8f) { value ->
        vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(strokeWidth = value)))
    }
    if (style.strokeWidth > 0f) {
        ColorEditorV9(style.strokeArgb) { color ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(strokeArgb = color)))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = style.shadowEnabled,
            onCheckedChange = { enabled ->
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowEnabled = enabled)))
            },
        )
        Text("Shadow", fontSize = 8.sp, color = Color.White.copy(alpha = .75f))
    }
    if (style.shadowEnabled) {
        CommitSliderV9("Blur", style.shadowRadius, 0f..18f) { value ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowRadius = value)))
        }
        CommitSliderV9("Shadow X", style.shadowDx, -12f..12f) { value ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowDx = value)))
        }
        CommitSliderV9("Shadow Y", style.shadowDy, -12f..12f) { value ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowDy = value)))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = style.backgroundEnabled,
            onCheckedChange = { enabled ->
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(backgroundEnabled = enabled)))
            },
        )
        Text("Background", fontSize = 8.sp, color = Color.White.copy(alpha = .75f))
    }
    if (style.backgroundEnabled) {
        ColorEditorV9(style.backgroundArgb) { color ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(backgroundArgb = color)))
        }
    }

    Text("Alignment", fontSize = 8.sp, color = TX9Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TextAlignmentV2.entries.forEach { alignment ->
            ChoiceV9(alignment.name.lowercase().replaceFirstChar { it.uppercase() }, style.alignment == alignment) {
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(alignment = alignment)))
            }
        }
    }

    CreatorSliderV9("X", item.positionX, -1f..1f) { vm.updateSelectedText(positionX = it) }
    CreatorSliderV9("Y", item.positionY, -1f..1f) { vm.updateSelectedText(positionY = it) }
    CreatorSliderV9("Size", item.sizeScale, .35f..2.5f) { vm.updateSelectedText(sizeScale = it) }
    DurationSliderV9("Duration", item.durationUs, 15_000_000L) { vm.setSelectedTextDuration(it) }

    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
            TimelineTextSelectionBusV10.clear(item.id)
            vm.deleteSelectedText()
        }) {
            Text("Delete text", fontSize = 8.sp, color = TX9Danger)
        }
    }
}

@Composable
private fun TextAnimateV9(
    item: TextOverlayClip,
    cursorUs: Long,
    frameRate: Int,
    vm: EditorViewModelV4,
    onSeek: (Long) -> Unit,
) {
    val inside = cursorUs in item.timelineStartUs until item.timelineEndUs
    val localUs = (cursorUs - item.timelineStartUs).coerceIn(0L, item.durationUs)
    val frameUs = (US_PER_SECOND / frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
    val toleranceUs = max(1L, frameUs / 2L)
    val animation = item.resolvedManualAnimationV2()
    val evaluated = if (inside) item.textManualFrameV2(cursorUs) else TextManualFrameV2(
        item.positionX,
        item.positionY,
        item.sizeScale,
        1f,
    )
    val currentKey = animation.keyframeNear(localUs, toleranceUs)

    Text("DaVinci-style manual keyframes", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Place the playhead anywhere inside this title. Changing X, Y, Size or Opacity creates/updates a diamond at that frame; values interpolate between diamonds.",
        fontSize = 7.sp,
        color = TX9Muted,
    )

    if (!inside) {
        Button(onClick = { onSeek(item.timelineStartUs) }, modifier = Modifier.height(32.dp)) {
            Text("Go to title", fontSize = 8.sp)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatSecondsV9(localUs)} / ${formatSecondsV9(item.durationUs)}",
                fontSize = 8.sp,
                color = TX9Muted,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    val next = if (currentKey != null) {
                        animation.withoutKeyframeNear(localUs, toleranceUs)
                    } else {
                        animation.withKeyframe(
                            TextTransformKeyframeV2(
                                localUs = localUs,
                                positionX = evaluated.positionX,
                                positionY = evaluated.positionY,
                                sizeScale = evaluated.sizeScale,
                                alpha = evaluated.alpha,
                            ),
                            item.durationUs,
                            toleranceUs,
                        )
                    }
                    vm.commitTextOverlayV2(item.copy(manualAnimationV2 = next))
                },
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    if (currentKey != null) "◆ Remove" else "◇ Add keyframe",
                    fontSize = 8.sp,
                    color = if (currentKey != null) TX9Key else Color.Unspecified,
                )
            }
        }

        fun commitFrame(frame: TextManualFrameV2) {
            val nextAnimation = animation.withKeyframe(
                TextTransformKeyframeV2(
                    localUs = localUs,
                    positionX = frame.positionX,
                    positionY = frame.positionY,
                    sizeScale = frame.sizeScale,
                    alpha = frame.alpha,
                ),
                item.durationUs,
                toleranceUs,
            )
            vm.commitTextOverlayV2(item.copy(manualAnimationV2 = nextAnimation))
        }

        CommitSliderV9("X ◆", evaluated.positionX, -1f..1f) { commitFrame(evaluated.copy(positionX = it)) }
        CommitSliderV9("Y ◆", evaluated.positionY, -1f..1f) { commitFrame(evaluated.copy(positionY = it)) }
        CommitSliderV9("Size ◆", evaluated.sizeScale, .35f..3f) { commitFrame(evaluated.copy(sizeScale = it)) }
        CommitSliderV9("Opacity ◆", evaluated.alpha, 0f..1f) { commitFrame(evaluated.copy(alpha = it)) }
    }

    if (animation.keyframes.isNotEmpty()) {
        Text("Keyframes", fontSize = 8.sp, color = TX9Muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            animation.keyframes.forEach { key ->
                Text(
                    "◆ ${formatSecondsV9(key.localUs)}",
                    fontSize = 7.sp,
                    color = TX9Key,
                    modifier = Modifier
                        .background(TX9Raised, RoundedCornerShape(5.dp))
                        .clickable { onSeek(item.timelineStartUs + key.localUs) }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
            }
        }
        TextButton(onClick = { vm.commitTextOverlayV2(item.copy(manualAnimationV2 = null)) }) {
            Text("Clear manual animation", fontSize = 7.sp, color = TX9Muted)
        }
    }

    HorizontalDivider(color = TX9Divider)
    Text("Quick in / out", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    Text("Preset motion can be combined with manual keyframes.", fontSize = 7.sp, color = TX9Muted)
    QuickAnimationV9("Entry", item, item.resolvedEntryAnimationV2(), true, vm)
    QuickAnimationV9("Exit", item, item.resolvedExitAnimationV2(), false, vm)
}

@Composable
private fun QuickAnimationV9(
    title: String,
    item: TextOverlayClip,
    spec: TextAnimationSpecV2,
    entry: Boolean,
    vm: EditorViewModelV4,
) {
    Text(title, fontSize = 8.sp, color = TX9Muted)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            TextAnimationV2.NONE to "None",
            TextAnimationV2.FADE to "Fade",
            TextAnimationV2.SLIDE_UP to "Up",
            TextAnimationV2.SLIDE_DOWN to "Down",
            TextAnimationV2.SLIDE_LEFT to "Left",
            TextAnimationV2.SLIDE_RIGHT to "Right",
        ).forEach { (kind, label) ->
            ChoiceV9(label, spec.kind == kind) {
                val next = spec.copy(kind = kind)
                vm.commitTextOverlayV2(
                    if (entry) item.copy(entryAnimationV2 = next) else item.copy(exitAnimationV2 = next),
                )
            }
        }
    }
    if (spec.kind != TextAnimationV2.NONE) {
        CommitSliderV9("Time", spec.durationUs / US_PER_SECOND.toFloat(), .1f..2f) { seconds ->
            val next = spec.copy(durationUs = (seconds * US_PER_SECOND).toLong())
            vm.commitTextOverlayV2(
                if (entry) item.copy(entryAnimationV2 = next) else item.copy(exitAnimationV2 = next),
            )
        }
    }
}

@Composable
private fun TextTemplatesPanelV10(
    selected: TextOverlayClip?,
    targetTrackName: String?,
    cursorUs: Long,
    vm: EditorViewModelV4,
) {
    var category by remember { mutableStateOf("All") }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val filtered = remember(category) {
        if (category == "All") TextTemplateCatalogV10 else TextTemplateCatalogV10.filter { it.category == category }
    }

    Text("Animated title templates", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Tap Apply to style the selected title. Hold and drag any card, then release to drop a NEW title on ${targetTrackName ?: "the selected V track"} at the playhead.",
        fontSize = 7.sp,
        color = TX9Muted,
    )

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextTemplateCategoriesV10.forEach { item ->
            ChoiceV9(item, category == item) { category = item }
        }
    }

    if (draggingId != null) {
        Surface(
            modifier = Modifier.fillMaxWidth().border(1.dp, TX9Accent, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            color = TX9Accent.copy(alpha = .09f),
        ) {
            Text(
                "Release → ${targetTrackName ?: "V track"} · ${formatSecondsV9(cursorUs)}",
                modifier = Modifier.padding(10.dp),
                fontSize = 9.sp,
                color = TX9Accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    filtered.forEach { template ->
        val dragging = draggingId == template.id
        Row(
            Modifier.fillMaxWidth()
                .alpha(if (dragging) .62f else 1f)
                .background(TX9Raised, RoundedCornerShape(8.dp))
                .border(if (dragging) 1.dp else .5.dp, if (dragging) TX9Accent else TX9Divider, RoundedCornerShape(8.dp))
                .pointerInput(template.id, targetTrackName, cursorUs) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggingId = template.id },
                        onDragEnd = {
                            draggingId = null
                            if (targetTrackName != null) {
                                vm.addTextAtSelectedVideoTrackV10(cursorUs, template = template)
                            }
                        },
                        onDragCancel = { draggingId = null },
                    ) { change, _ -> change.consume() }
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(Color(template.style.backgroundArgb.toInt()).copy(alpha = if (template.style.backgroundEnabled) 1f else .12f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aa", fontSize = 11.sp, color = Color(template.style.colorArgb.toInt()), fontWeight = if (template.bold) FontWeight.Bold else FontWeight.Normal)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(template.label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${template.category} · ${if (template.manualKeyframes.isNotEmpty()) "keyframed" else template.entry.kind.name.lowercase().replace('_', ' ')}",
                    fontSize = 7.sp,
                    color = TX9Muted,
                )
                Text("hold + drag to insert", fontSize = 6.sp, color = TX9Accent.copy(alpha = .8f))
            }
            Button(
                onClick = {
                    if (selected != null) vm.applyTemplateToSelectedTextV10(template)
                    else if (targetTrackName != null) vm.addTextAtSelectedVideoTrackV10(cursorUs, template = template)
                },
                enabled = selected != null || targetTrackName != null,
                modifier = Modifier.height(31.dp),
            ) {
                Text(if (selected != null) "Apply" else "Add", fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun ColorEditorV9(value: Long, onColor: (Long) -> Unit) {
    var hex by remember(value) { mutableStateOf("%08X".format(value and 0xFFFFFFFFL)) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TextPaletteV9.forEach { argb ->
            Box(
                Modifier.size(23.dp)
                    .background(Color(argb.toInt()), CircleShape)
                    .border(if (argb == value) 2.dp else 1.dp, if (argb == value) TX9Accent else TX9Divider, CircleShape)
                    .clickable { onColor(argb) },
            )
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                hex = input.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(8).uppercase()
            },
            label = { Text("ARGB", fontSize = 7.sp) },
            singleLine = true,
            modifier = Modifier.width(108.dp),
        )
        TextButton(enabled = hex.length == 8, onClick = { hex.toLongOrNull(16)?.let(onColor) }) {
            Text("Apply", fontSize = 7.sp)
        }
    }
}

@Composable
private fun ChoiceV9(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.height(30.dp), shape = RoundedCornerShape(6.dp)) {
        Text(if (selected) "✓ $label" else label, fontSize = 7.sp, color = if (selected) TX9Accent else Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun CommitSliderV9(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(70.dp))
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.2f", draft), fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun CreatorSliderV9(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(44.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.2f", value), fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun DurationSliderV9(label: String, valueUs: Long, maxUs: Long, onChange: (Long) -> Unit) {
    val maxSeconds = (maxUs / US_PER_SECOND.toFloat()).coerceAtLeast(.2f)
    val seconds = (valueUs / US_PER_SECOND.toFloat()).coerceIn(.1f, maxSeconds)
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(64.dp))
        Slider(
            value = seconds,
            onValueChange = { onChange((it * US_PER_SECOND).toLong()) },
            valueRange = .1f..maxSeconds,
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.1fs", seconds), fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(42.dp))
    }
}

private fun formatSecondsV9(us: Long): String = String.format("%.2fs", us.coerceAtLeast(0L) / US_PER_SECOND.toFloat())
