package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43

private val V8CutoutAccent = Color(0xFF30E0C3)

/**
 * V43 discoverability wrapper.
 *
 * CreatorMediaWorkspaceV8 already owns the full creator-tools surface, but the V7 workspace bar
 * intentionally hides MEDIA. Keep V7 stable and expose Cutout as a dedicated always-visible quick
 * action instead of forcing users to discover a hidden workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    var showCutout by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)

        Button(
            onClick = { showCutout = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 10.dp, bottom = 74.dp)
                .height(38.dp),
            shape = RoundedCornerShape(9.dp),
        ) {
            Text("Cutout", fontSize = 10.sp)
        }
    }

    if (showCutout) {
        ModalBottomSheet(onDismissRequest = { showCutout = false }) {
            CutoutQuickPanelV43(vm = vm)
        }
    }
}

@Composable
private fun CutoutQuickPanelV43(vm: EditorViewModelV4) {
    val state by vm.state.collectAsState()
    val clip = state.project.clip(state.selectedClipId)
    val isVisualClip = clip != null && state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Background Remove & Chroma Key", fontSize = 17.sp)
        Text(
            "Select a video or image clip, then choose Auto Cutout or Chroma Key.",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = .62f),
        )

        if (!isVisualClip) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = .06f), RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Text("No video/image clip selected. Tap a clip on the timeline first.", fontSize = 11.sp)
            }
            return@Column
        }

        val selectedClip = clip!!
        val settings = selectedClip.resolvedCutoutV43()
        val personReady = PersonCutoutMaskStoreV43.hasAny(
            androidx.compose.ui.platform.LocalContext.current.applicationContext,
            selectedClip,
        )
        val analysisStatus = state.status.takeIf { it.startsWith("Auto Cutout") }
        val analyzing = analysisStatus?.contains("analyzing", ignoreCase = true) == true

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OutlinedButton(
                onClick = {
                    vm.setSelectedCutoutV43(
                        settings.copy(mode = CutoutModeV43.NONE),
                        status = "Cutout off",
                        coalesce = false,
                    )
                },
            ) { Text(if (settings.mode == CutoutModeV43.NONE) "✓ Off" else "Off", fontSize = 9.sp) }

            FilledTonalButton(
                onClick = { vm.enablePersonCutoutV43(settings) },
            ) { Text(if (settings.mode == CutoutModeV43.PERSON) "✓ Auto Cutout" else "Auto Cutout", fontSize = 9.sp) }

            FilledTonalButton(
                onClick = {
                    vm.setSelectedCutoutV43(
                        settings.copy(mode = CutoutModeV43.CHROMA_KEY),
                        status = "Chroma Key enabled",
                        coalesce = false,
                    )
                },
            ) { Text(if (settings.mode == CutoutModeV43.CHROMA_KEY) "✓ Chroma Key" else "Chroma Key", fontSize = 9.sp) }
        }

        when (settings.mode) {
            CutoutModeV43.NONE -> {
                Text(
                    "Auto Cutout removes a person background without a green screen. Chroma Key is for green/blue or other controlled key backgrounds.",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = .62f),
                )
            }

            CutoutModeV43.PERSON -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            analyzing && personReady -> "Preview matte ready · finishing clip…"
                            analyzing -> "Analyzing visible frame…"
                            personReady -> "Person matte ready"
                            else -> "Person matte needs analysis"
                        },
                        fontSize = 10.sp,
                        color = if (personReady) V8CutoutAccent else Color.White.copy(alpha = .62f),
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = !analyzing,
                        onClick = vm::analyzeSelectedPersonCutoutV43,
                    ) {
                        Text(
                            when {
                                analyzing -> "Analyzing…"
                                personReady -> "Refresh"
                                else -> "Analyze"
                            },
                            fontSize = 9.sp,
                        )
                    }
                }
                if (analysisStatus != null) {
                    Text(
                        analysisStatus,
                        fontSize = 9.sp,
                        color = V8CutoutAccent.copy(alpha = .82f),
                    )
                }
                CutoutSliderV43("Threshold", settings.personThreshold, .05f..0.95f) {
                    vm.setSelectedCutoutV43(settings.copy(personThreshold = it), status = "Auto Cutout edge updated")
                }
                CutoutSliderV43("Feather", settings.personFeather, .005f..0.45f) {
                    vm.setSelectedCutoutV43(settings.copy(personFeather = it), status = "Auto Cutout feather updated")
                }
                Text(
                    "The visible frame is analyzed first for a fast preview; remaining frames continue in the background. For background replacement, place the replacement clip on a lower V track.",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .55f),
                )
            }

            CutoutModeV43.CHROMA_KEY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilledTonalButton(
                        onClick = {
                            vm.setSelectedCutoutV43(
                                settings.copy(keyRed = 0f, keyGreen = 1f, keyBlue = 0f),
                                status = "Green screen key selected",
                                coalesce = false,
                            )
                        },
                    ) { Text("Green", fontSize = 9.sp) }
                    FilledTonalButton(
                        onClick = {
                            vm.setSelectedCutoutV43(
                                settings.copy(keyRed = 0f, keyGreen = .12f, keyBlue = 1f),
                                status = "Blue screen key selected",
                                coalesce = false,
                            )
                        },
                    ) { Text("Blue", fontSize = 9.sp) }
                }

                CutoutSliderV43("Key R", settings.keyRed, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyRed = it), status = "Chroma key color updated")
                }
                CutoutSliderV43("Key G", settings.keyGreen, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyGreen = it), status = "Chroma key color updated")
                }
                CutoutSliderV43("Key B", settings.keyBlue, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyBlue = it), status = "Chroma key color updated")
                }
                CutoutSliderV43("Similarity", settings.chromaSimilarity, .01f..0.40f) {
                    vm.setSelectedCutoutV43(settings.copy(chromaSimilarity = it), status = "Chroma similarity updated")
                }
                CutoutSliderV43("Softness", settings.chromaSoftness, .005f..0.30f) {
                    vm.setSelectedCutoutV43(settings.copy(chromaSoftness = it), status = "Chroma softness updated")
                }
                CutoutSliderV43("Spill", settings.spillSuppression, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(spillSuppression = it), status = "Spill suppression updated")
                }
            }
        }
    }
}

@Composable
private fun CutoutSliderV43(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("%.2f".format(value), fontSize = 9.sp, color = V8CutoutAccent)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}
