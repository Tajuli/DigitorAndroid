package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextAnimationSpecV2
import com.tajuli.digitorandroid.editor.model.TextAnimationV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.US_PER_SECOND
import com.tajuli.digitorandroid.editor.model.audioSelection
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.model.resolvedEntryAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedExitAnimationV2
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43
import kotlin.math.min

private val C8Panel = Color(0xFF0B0B0F)
private val C8Raised = Color(0xFF17171C)
private val C8Divider = Color(0xFF292930)
private val C8Muted = Color(0xFF909098)
private val C8Accent = Color(0xFF30E0C3)

private val TextPaletteV2 = listOf(
    0xFFFFFFFFL,
    0xFF000000L,
    0xFFFF5A5FL,
    0xFFFFD54FL,
    0xFF4DD0E1L,
    0xFF66BB6AL,
    0xFFAB86FFL,
    0xFFFF8BCBL,
)

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
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Creator tools", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                busyOperation ?: "Text V2 · Cutout V43 · Transition V22 · Retime",
                fontSize = 8.sp,
                color = if (busyOperation == null) C8Muted else C8Accent,
            )
        }
        HorizontalDivider(color = C8Divider)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCardV8("Text / Captions V2") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = false) }) {
                        Text("+ Text", fontSize = 8.sp)
                    }
                    FilledTonalButton(onClick = { vm.addTextAt(cursorUs, caption = true) }) {
                        Text("+ Caption", fontSize = 8.sp)
                    }
                }

                if (project.textOverlays.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        project.textOverlays.forEach { item ->
                            Text(
                                item.text.ifBlank { "Text" }.take(18),
                                fontSize = 8.sp,
                                color = if (item.id == selectedTextId) C8Accent else Color.White.copy(alpha = .72f),
                                modifier = Modifier
                                    .background(C8Raised, RoundedCornerShape(5.dp))
                                    .clickable { vm.selectTextOverlay(item.id) }
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                if (selectedText != null) {
                    TextEditorV8(selectedText, vm)
                }
            }

            SectionCardV8("Cutout V43") {
                if (!selectedIsVideo) {
                    Text("Select a video or image clip", fontSize = 8.sp, color = C8Muted)
                } else {
                    CutoutEditorV43(selectedClip!!, vm)
                }
            }

            SectionCardV8("Transition") {
                if (!selectedIsVideo) {
                    Text("Select the clip after a cut", fontSize = 8.sp, color = C8Muted)
                } else {
                    TransitionPickerV22(project, selectedClip!!, vm)
                }
            }

            SectionCardV8("Speed / Reverse / Freeze") {
                if (!selectedIsVideo) {
                    Text("Select a video clip", fontSize = 8.sp, color = C8Muted)
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        listOf(.5f, .75f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                            FilledTonalButton(
                                enabled = busyOperation == null,
                                onClick = { vm.bakeSelectedSpeed(speed) },
                            ) {
                                Text("${speed}x", fontSize = 8.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(enabled = busyOperation == null, onClick = vm::reverseSelectedVideo) {
                            Text("Reverse", fontSize = 8.sp)
                        }
                        Button(
                            enabled = busyOperation == null,
                            onClick = { vm.freezeSelectedAt(cursorUs, 2_000_000L) },
                        ) {
                            Text("Freeze 2s", fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CutoutEditorV43(clip: TimelineClip, vm: EditorViewModelV4) {
    val context = LocalContext.current
    val settings = clip.resolvedCutoutV43()
    val personReady = PersonCutoutMaskStoreV43.hasAny(context.applicationContext, clip)

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        V2ChoiceButton("Off", settings.mode == CutoutModeV43.NONE) {
            vm.setSelectedCutoutV43(settings.copy(mode = CutoutModeV43.NONE), status = "Cutout off", coalesce = false)
        }
        V2ChoiceButton("Auto Cutout", settings.mode == CutoutModeV43.PERSON) {
            vm.enablePersonCutoutV43(settings)
        }
        V2ChoiceButton("Chroma Key", settings.mode == CutoutModeV43.CHROMA_KEY) {
            vm.setSelectedCutoutV43(
                settings.copy(mode = CutoutModeV43.CHROMA_KEY),
                status = "Chroma Key enabled",
                coalesce = false,
            )
        }
    }

    when (settings.mode) {
        CutoutModeV43.NONE -> {
            Text(
                "Auto Cutout removes a person background without green screen. Chroma Key is best for controlled green/blue-screen footage.",
                fontSize = 8.sp,
                color = C8Muted,
            )
        }
        CutoutModeV43.PERSON -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (personReady) "Semantic matte cached" else "Semantic matte needs analysis",
                    fontSize = 8.sp,
                    color = if (personReady) C8Accent else C8Muted,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = vm::analyzeSelectedPersonCutoutV43) {
                    Text(if (personReady) "Refresh matte" else "Analyze", fontSize = 8.sp)
                }
            }
            CommitSliderV2("Threshold", settings.personThreshold, .05f..0.95f) { value ->
                vm.setSelectedCutoutV43(settings.copy(personThreshold = value), status = "Auto Cutout edge updated")
            }
            CommitSliderV2("Feather", settings.personFeather, .005f..0.45f) { value ->
                vm.setSelectedCutoutV43(settings.copy(personFeather = value), status = "Auto Cutout feather updated")
            }
            Text(
                "Soft foreground confidence is interpolated between cached semantic frames. Put the replacement background on a lower V track.",
                fontSize = 8.sp,
                color = C8Muted,
            )
        }
        CutoutModeV43.CHROMA_KEY -> {
            val keyColor = Color(settings.keyRed, settings.keyGreen, settings.keyBlue, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(24.dp)
                        .background(keyColor, CircleShape)
                        .border(1.dp, Color.White.copy(alpha = .35f), CircleShape),
                )
                Text("Key color", fontSize = 8.sp, color = C8Muted)
                V2ChoiceButton("Green", false) {
                    vm.setSelectedCutoutV43(
                        settings.copy(keyRed = 0f, keyGreen = 1f, keyBlue = 0f),
                        status = "Green screen key selected",
                        coalesce = false,
                    )
                }
                V2ChoiceButton("Blue", false) {
                    vm.setSelectedCutoutV43(
                        settings.copy(keyRed = 0f, keyGreen = .12f, keyBlue = 1f),
                        status = "Blue screen key selected",
                        coalesce = false,
                    )
                }
            }
            CreatorSliderV8("Key R", settings.keyRed, 0f..1f) { value ->
                vm.setSelectedCutoutV43(settings.copy(keyRed = value), status = "Chroma key color updated")
            }
            CreatorSliderV8("Key G", settings.keyGreen, 0f..1f) { value ->
                vm.setSelectedCutoutV43(settings.copy(keyGreen = value), status = "Chroma key color updated")
            }
            CreatorSliderV8("Key B", settings.keyBlue, 0f..1f) { value ->
                vm.setSelectedCutoutV43(settings.copy(keyBlue = value), status = "Chroma key color updated")
            }
            CommitSliderV2("Similarity", settings.chromaSimilarity, .01f..0.40f) { value ->
                vm.setSelectedCutoutV43(settings.copy(chromaSimilarity = value), status = "Chroma similarity updated")
            }
            CommitSliderV2("Softness", settings.chromaSoftness, .005f..0.30f) { value ->
                vm.setSelectedCutoutV43(settings.copy(chromaSoftness = value), status = "Chroma softness updated")
            }
            CommitSliderV2("Spill", settings.spillSuppression, 0f..1f) { value ->
                vm.setSelectedCutoutV43(settings.copy(spillSuppression = value), status = "Spill suppression updated")
            }
            Text(
                "Cb/Cr keying ignores much of the screen brightness variation; Softness protects hair/motion edges and Spill neutralizes reflected key color.",
                fontSize = 8.sp,
                color = C8Muted,
            )
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
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Audio", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (selected.size > 1) "${selected.size} linked clips" else clip?.label ?: "No selection",
                fontSize = 8.sp,
                color = C8Muted,
            )
        }
        HorizontalDivider(color = C8Divider)

        if (clip == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select an audio clip or linked video clip", fontSize = 9.sp, color = C8Muted)
            }
            return@Column
        }

        val maxFadeUs = min(clip.durationUs, 5_000_000L).coerceAtLeast(1L)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCardV8("Volume") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(clip.audioMix.volume * 100).toInt()}%",
                        fontSize = 8.sp,
                        color = C8Accent,
                        modifier = Modifier.width(42.dp),
                    )
                    Slider(
                        value = clip.audioMix.volume.coerceIn(0f, 1f),
                        onValueChange = vm::setSelectedAudioVolume,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SectionCardV8("Fades") {
                DurationSliderV8("Fade in", clip.audioMix.fadeInUs, maxFadeUs, vm::setSelectedAudioFadeIn)
                DurationSliderV8("Fade out", clip.audioMix.fadeOutUs, maxFadeUs, vm::setSelectedAudioFadeOut)
            }
        }
    }
}

@Composable
private fun TextEditorV8(item: TextOverlayClip, vm: EditorViewModelV4) {
    val style = item.resolvedTextStyleV2()
    val entry = item.resolvedEntryAnimationV2()
    val exit = item.resolvedExitAnimationV2()

    OutlinedTextField(
        value = item.text,
        onValueChange = { vm.updateSelectedText(text = it) },
        label = { Text("Text", fontSize = 8.sp) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    Text("Font", fontSize = 8.sp, color = C8Muted)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TextFontV2.entries.forEach { font ->
            V2ChoiceButton(font.name.lowercase().replaceFirstChar { it.uppercase() }, style.font == font) {
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(font = font)))
            }
        }
    }

    Text("Text color", fontSize = 8.sp, color = C8Muted)
    ColorEditorV2(style.colorArgb) { color ->
        vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(colorArgb = color)))
    }

    Text("Stroke", fontSize = 8.sp, color = C8Muted)
    CommitSliderV2("Width", style.strokeWidth, 0f..8f) { value ->
        vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(strokeWidth = value)))
    }
    if (style.strokeWidth > 0f) {
        ColorEditorV2(style.strokeArgb) { color ->
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
        Text("Shadow", fontSize = 8.sp)
    }
    if (style.shadowEnabled) {
        ColorEditorV2(style.shadowArgb) { color ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowArgb = color)))
        }
        CommitSliderV2("Blur", style.shadowRadius, 0f..18f) { value ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowRadius = value)))
        }
        CommitSliderV2("Shadow X", style.shadowDx, -12f..12f) { value ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(shadowDx = value)))
        }
        CommitSliderV2("Shadow Y", style.shadowDy, -12f..12f) { value ->
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
        Text("Background", fontSize = 8.sp)
    }
    if (style.backgroundEnabled) {
        ColorEditorV2(style.backgroundArgb) { color ->
            vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(backgroundArgb = color)))
        }
    }

    Text("Alignment", fontSize = 8.sp, color = C8Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TextAlignmentV2.entries.forEach { alignment ->
            V2ChoiceButton(
                alignment.name.lowercase().replaceFirstChar { it.uppercase() },
                style.alignment == alignment,
            ) {
                vm.commitTextOverlayV2(item.copy(styleV2 = style.copy(alignment = alignment)))
            }
        }
    }

    Text("Entry animation", fontSize = 8.sp, color = C8Muted)
    AnimationEditorV2(item, entry, isEntry = true, vm = vm)
    Text("Exit animation", fontSize = 8.sp, color = C8Muted)
    AnimationEditorV2(item, exit, isEntry = false, vm = vm)

    CreatorSliderV8("X", item.positionX, -1f..1f) { vm.updateSelectedText(positionX = it) }
    CreatorSliderV8("Y", item.positionY, -1f..1f) { vm.updateSelectedText(positionY = it) }
    CreatorSliderV8("Size", item.sizeScale, .35f..2.5f) { vm.updateSelectedText(sizeScale = it) }
    DurationSliderV8("Duration", item.durationUs, 10_000_000L) { vm.setSelectedTextDuration(it) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::deleteSelectedText) {
            Text("Delete", fontSize = 8.sp, color = Color(0xFFFF6B6B))
        }
    }
}

@Composable
private fun AnimationEditorV2(
    item: TextOverlayClip,
    spec: TextAnimationSpecV2,
    isEntry: Boolean,
    vm: EditorViewModelV4,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            TextAnimationV2.NONE to "None",
            TextAnimationV2.FADE to "Fade",
            TextAnimationV2.SLIDE_UP to "Up",
            TextAnimationV2.SLIDE_DOWN to "Down",
            TextAnimationV2.SLIDE_LEFT to "Left",
            TextAnimationV2.SLIDE_RIGHT to "Right",
        ).forEach { (kind, label) ->
            V2ChoiceButton(label, spec.kind == kind) {
                val nextSpec = spec.copy(kind = kind)
                vm.commitTextOverlayV2(
                    if (isEntry) item.copy(entryAnimationV2 = nextSpec)
                    else item.copy(exitAnimationV2 = nextSpec),
                )
            }
        }
    }

    if (spec.kind != TextAnimationV2.NONE) {
        CommitSliderV2("Time", spec.durationUs / US_PER_SECOND.toFloat(), .1f..2f) { seconds ->
            val nextSpec = spec.copy(durationUs = (seconds * US_PER_SECOND).toLong())
            vm.commitTextOverlayV2(
                if (isEntry) item.copy(entryAnimationV2 = nextSpec)
                else item.copy(exitAnimationV2 = nextSpec),
            )
        }
    }
}

@Composable
private fun ColorEditorV2(value: Long, onColor: (Long) -> Unit) {
    var hex by remember(value) { mutableStateOf("%08X".format(value and 0xFFFFFFFFL)) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TextPaletteV2.forEach { argb ->
            Box(
                Modifier
                    .size(23.dp)
                    // Stored values are ordinary 32-bit Android ARGB, never packed Compose ColorLong.
                    .background(Color(argb.toInt()), CircleShape)
                    .border(
                        if (argb == value) 2.dp else 1.dp,
                        if (argb == value) C8Accent else C8Divider,
                        CircleShape,
                    )
                    .clickable { onColor(argb) },
            )
        }

        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                hex = input
                    .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    .take(8)
                    .uppercase()
            },
            label = { Text("ARGB", fontSize = 7.sp) },
            singleLine = true,
            modifier = Modifier.width(108.dp),
        )
        TextButton(
            enabled = hex.length == 8,
            onClick = { hex.toLongOrNull(16)?.let(onColor) },
        ) {
            Text("Apply", fontSize = 7.sp)
        }
    }
}

@Composable
private fun V2ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            if (selected) "✓ $label" else label,
            fontSize = 7.sp,
            color = if (selected) C8Accent else Color.White.copy(alpha = .72f),
        )
    }
}

@Composable
private fun CommitSliderV2(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(58.dp))
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.2f", draft), fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun SectionCardV8(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(C8Raised, RoundedCornerShape(7.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .82f))
        content()
    }
}

@Composable
private fun CreatorSliderV8(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(44.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(String.format("%.2f", value), fontSize = 7.sp, color = C8Muted, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun DurationSliderV8(
    label: String,
    valueUs: Long,
    maxUs: Long,
    onChange: (Long) -> Unit,
) {
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
