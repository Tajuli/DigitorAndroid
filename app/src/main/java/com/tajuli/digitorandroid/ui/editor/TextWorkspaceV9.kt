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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.resolvedEntryAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedExitAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedManualAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.model.textManualFrameV2
import kotlin.math.max

private val TX9Panel = Color(0xFF0B0B0F)
private val TX9Raised = Color(0xFF17171C)
private val TX9Divider = Color(0xFF292930)
private val TX9Muted = Color(0xFF909098)
private val TX9Accent = Color(0xFF30E0C3)
private val TX9Key = Color(0xFFFFC857)

private enum class TextPageV9(val label: String) {
    INSPECTOR("Inspector"),
    ANIMATE("Animate"),
    TEMPLATES("Templates"),
}

private data class TextTemplateV9(
    val label: String,
    val style: TextStyleV2,
    val bold: Boolean = true,
    val positionY: Float = 0f,
    val sizeScale: Float = 1f,
    val entry: TextAnimationSpecV2 = TextAnimationSpecV2(),
    val exit: TextAnimationSpecV2 = TextAnimationSpecV2(),
)

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

private val TextTemplatesV9 = listOf(
    TextTemplateV9(
        label = "Clean",
        style = TextStyleV2(font = TextFontV2.SANS, colorArgb = 0xFFFFFFFFL),
    ),
    TextTemplateV9(
        label = "Subtitle",
        style = TextStyleV2(
            font = TextFontV2.SANS,
            colorArgb = 0xFFFFFFFFL,
            backgroundEnabled = true,
            backgroundArgb = 0xB0000000L,
        ),
        positionY = .72f,
        sizeScale = .78f,
        entry = TextAnimationSpecV2(TextAnimationV2.FADE, 220_000L),
        exit = TextAnimationSpecV2(TextAnimationV2.FADE, 220_000L),
    ),
    TextTemplateV9(
        label = "Bold Pop",
        style = TextStyleV2(
            font = TextFontV2.SANS,
            colorArgb = 0xFFFFD54FL,
            strokeWidth = 3.2f,
            strokeArgb = 0xFF101010L,
            shadowEnabled = true,
            shadowArgb = 0x90000000L,
            shadowRadius = 7f,
            shadowDy = 4f,
        ),
        sizeScale = 1.12f,
        entry = TextAnimationSpecV2(TextAnimationV2.SLIDE_UP, 320_000L),
        exit = TextAnimationSpecV2(TextAnimationV2.FADE, 260_000L),
    ),
    TextTemplateV9(
        label = "Neon",
        style = TextStyleV2(
            font = TextFontV2.SANS,
            colorArgb = 0xFF65F5FFL,
            strokeWidth = 1.4f,
            strokeArgb = 0xFF12333CL,
            shadowEnabled = true,
            shadowArgb = 0xCC32E9FFL,
            shadowRadius = 12f,
        ),
        entry = TextAnimationSpecV2(TextAnimationV2.SLIDE_LEFT, 380_000L),
        exit = TextAnimationSpecV2(TextAnimationV2.FADE, 300_000L),
    ),
    TextTemplateV9(
        label = "Creator",
        style = TextStyleV2(
            font = TextFontV2.CURSIVE,
            colorArgb = 0xFFFFFFFFL,
            strokeWidth = 2f,
            strokeArgb = 0xFF171717L,
            shadowEnabled = true,
            shadowArgb = 0xA0000000L,
            shadowRadius = 5f,
            shadowDy = 2f,
        ),
        sizeScale = 1.08f,
        entry = TextAnimationSpecV2(TextAnimationV2.SLIDE_RIGHT, 340_000L),
        exit = TextAnimationSpecV2(TextAnimationV2.FADE, 260_000L),
    ),
    TextTemplateV9(
        label = "Cinematic",
        style = TextStyleV2(
            font = TextFontV2.SERIF,
            colorArgb = 0xFFF4F0E8L,
            shadowEnabled = true,
            shadowArgb = 0xA0000000L,
            shadowRadius = 8f,
        ),
        bold = false,
        sizeScale = .92f,
        entry = TextAnimationSpecV2(TextAnimationV2.FADE, 650_000L),
        exit = TextAnimationSpecV2(TextAnimationV2.FADE, 650_000L),
    ),
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
    val selected = project.textOverlays.firstOrNull { it.id == selectedTextId }
    var page by remember { mutableStateOf(TextPageV9.INSPECTOR) }

    Column(modifier.background(TX9Panel)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Text", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = false) }, modifier = Modifier.height(30.dp)) {
                Text("+ Text", fontSize = 8.sp)
            }
            Spacer(Modifier.width(5.dp))
            FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = true) }, modifier = Modifier.height(30.dp)) {
                Text("+ Caption", fontSize = 8.sp)
            }
        }
        HorizontalDivider(color = TX9Divider)

        if (project.textOverlays.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                project.textOverlays.forEach { item ->
                    Text(
                        item.text.ifBlank { "Text" }.take(18),
                        fontSize = 8.sp,
                        color = if (item.id == selectedTextId) TX9Accent else Color.White.copy(alpha = .7f),
                        modifier = Modifier
                            .background(TX9Raised, RoundedCornerShape(5.dp))
                            .clickable { vm.selectTextOverlay(item.id) }
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

        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add or select a text clip", fontSize = 9.sp, color = TX9Muted)
            }
            return@Column
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            when (page) {
                TextPageV9.INSPECTOR -> TextInspectorV9(selected, vm)
                TextPageV9.ANIMATE -> TextAnimateV9(selected, cursorUs, frameRate, vm, onSeek)
                TextPageV9.TEMPLATES -> TextTemplatesPanelV9(selected, vm)
            }
        }
    }
}

@Composable
private fun TextInspectorV9(item: TextOverlayClip, vm: EditorViewModelV4) {
    val style = item.resolvedTextStyleV2()

    OutlinedTextField(
        value = item.text,
        onValueChange = { vm.updateSelectedText(text = it) },
        label = { Text("Text", fontSize = 8.sp) },
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
        Checkbox(
            checked = item.bold,
            onCheckedChange = { vm.commitTextOverlayV2(item.copy(bold = it)) },
        )
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
    DurationSliderV9("Duration", item.durationUs, 10_000_000L) { vm.setSelectedTextDuration(it) }

    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::deleteSelectedText) {
            Text("Delete text", fontSize = 8.sp, color = Color(0xFFFF7474))
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

    Text("Manual keyframes", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Move the playhead, add a diamond, then change X / Y / Size / Opacity. Values interpolate between keyframes.",
        fontSize = 7.sp,
        color = TX9Muted,
    )

    if (!inside) {
        Button(onClick = { onSeek(item.timelineStartUs) }, modifier = Modifier.height(32.dp)) {
            Text("Go to text clip", fontSize = 8.sp)
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
                Text(if (currentKey != null) "◆ Remove" else "◇ Add keyframe", fontSize = 8.sp, color = if (currentKey != null) TX9Key else Color.Unspecified)
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
        CommitSliderV9("Size ◆", evaluated.sizeScale, .35f..2.5f) { commitFrame(evaluated.copy(sizeScale = it)) }
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
    Text("Optional preset motion can be combined with manual keyframes.", fontSize = 7.sp, color = TX9Muted)
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
private fun TextTemplatesPanelV9(item: TextOverlayClip, vm: EditorViewModelV4) {
    Text("Ready text templates", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "One-tap social templates. Apply a template, then fine-tune it in Inspector or Animate.",
        fontSize = 7.sp,
        color = TX9Muted,
    )

    TextTemplatesV9.forEach { template ->
        Row(
            Modifier.fillMaxWidth().background(TX9Raised, RoundedCornerShape(7.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(template.label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${template.style.font.name.lowercase()} · ${template.entry.kind.name.lowercase().replace('_', ' ')}",
                    fontSize = 7.sp,
                    color = TX9Muted,
                )
            }
            Button(
                onClick = {
                    vm.commitTextOverlayV2(
                        item.copy(
                            styleV2 = template.style,
                            bold = template.bold,
                            positionY = template.positionY,
                            sizeScale = template.sizeScale,
                            entryAnimationV2 = template.entry,
                            exitAnimationV2 = template.exit,
                            manualAnimationV2 = null,
                        ),
                    )
                },
                modifier = Modifier.height(31.dp),
            ) {
                Text("Apply", fontSize = 8.sp)
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
        Text(label, fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(64.dp))
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
    val safeMax = maxUs.coerceAtLeast(1L)
    val seconds = valueUs.coerceAtLeast(0L) / US_PER_SECOND.toFloat()
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(58.dp))
        Slider(
            value = (valueUs.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f),
            onValueChange = { onChange((it * safeMax).toLong()) },
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.1fs", seconds), fontSize = 7.sp, color = TX9Muted, modifier = Modifier.width(38.dp))
    }
}

private fun formatSecondsV9(us: Long): String = String.format("%.2fs", us.coerceAtLeast(0L) / US_PER_SECOND.toFloat())
