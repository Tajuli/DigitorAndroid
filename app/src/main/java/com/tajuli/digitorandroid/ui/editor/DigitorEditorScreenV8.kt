package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43

private val V8CutoutAccent = Color(0xFF30E0C3)
private val V8CutoutPanel = Color(0xF20B0B0F)
private val V8CutoutText = Color.White

/** Always-visible V47 Pro Cutout quick action with a non-modal, scrollable control panel. */
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    var showCutout by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)

        if (!showCutout) {
            Button(
                onClick = { showCutout = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 10.dp, bottom = 74.dp)
                    .height(38.dp),
                shape = RoundedCornerShape(9.dp),
            ) {
                Text("Cutout", fontSize = 10.sp, color = V8CutoutText)
            }
        }

        if (showCutout) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(.44f)
                    .background(V8CutoutPanel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .navigationBarsPadding(),
            ) {
                CutoutQuickPanelV44(vm = vm, onClose = { showCutout = false })
            }
        }
    }
}

@Composable
private fun CutoutQuickPanelV44(
    vm: EditorViewModelV4,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val clip = state.project.clip(state.selectedClipId)
    val isVisualClip = clip != null && state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pro Cutout & Chroma Key", fontSize = 16.sp, color = V8CutoutText)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close", fontSize = 9.sp, color = V8CutoutText) }
        }
        Text(
            "GPU-first portrait matting. Choose analysis quality first, then press Analyze. This panel stays scrollable without hiding the whole preview.",
            fontSize = 9.sp,
            color = V8CutoutText,
        )

        if (!isVisualClip) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = .06f), RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "No video/image clip selected. Tap a clip on the timeline first.",
                    fontSize = 11.sp,
                    color = V8CutoutText,
                )
            }
            return@Column
        }

        val selectedClip = clip!!
        val settings = selectedClip.resolvedCutoutV43()
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val personReady = hasPersonCutoutCoverageV43(appContext, selectedClip)
        val analysisStatus = state.status.takeIf {
            it.startsWith("Pro Cutout") || it.startsWith("Auto Cutout")
        }
        val analysisBusy = analysisStatus?.let { status ->
            val active = status.contains("starting", ignoreCase = true) ||
                status.contains("matting", ignoreCase = true) ||
                status.contains("analysis", ignoreCase = true) ||
                status.contains("refined frame", ignoreCase = true)
            active &&
                !status.contains("ready", ignoreCase = true) &&
                !status.contains("failed", ignoreCase = true) &&
                !status.contains("incomplete", ignoreCase = true)
        } == true
        val analysisFailed = analysisStatus?.contains("failed", ignoreCase = true) == true ||
            analysisStatus?.contains("incomplete", ignoreCase = true) == true

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
            ) {
                Text(
                    if (settings.mode == CutoutModeV43.NONE) "✓ Off" else "Off",
                    fontSize = 9.sp,
                    color = V8CutoutText,
                )
            }

            FilledTonalButton(
                enabled = !analysisBusy,
                onClick = { vm.enablePersonCutoutV43(settings) },
            ) {
                Text(
                    if (settings.mode == CutoutModeV43.PERSON) "✓ Pro Cutout" else "Pro Cutout",
                    fontSize = 9.sp,
                    color = V8CutoutText,
                )
            }

            FilledTonalButton(
                enabled = !analysisBusy,
                onClick = {
                    vm.setSelectedCutoutV43(
                        settings.copy(mode = CutoutModeV43.CHROMA_KEY),
                        status = "Chroma Key enabled",
                        coalesce = false,
                    )
                },
            ) {
                Text(
                    if (settings.mode == CutoutModeV43.CHROMA_KEY) "✓ Chroma Key" else "Chroma Key",
                    fontSize = 9.sp,
                    color = V8CutoutText,
                )
            }
        }

        when (settings.mode) {
            CutoutModeV43.NONE -> {
                Text(
                    "Pro Cutout creates a soft portrait alpha without a green screen. Chroma Key is faster and more controllable with a clean green/blue screen.",
                    fontSize = 10.sp,
                    color = V8CutoutText,
                )
            }

            CutoutModeV43.PERSON -> {
                Text("Analysis quality", fontSize = 10.sp, color = V8CutoutText)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QualityChoiceV47(
                        label = "Low\n4 fps",
                        selected = settings.analysisQualityV47 == CutoutAnalysisQualityV47.LOW,
                        enabled = !analysisBusy,
                    ) {
                        vm.setSelectedCutoutV43(
                            settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.LOW),
                            status = "Pro Cutout quality · Low · 4 fps · tap Analyze",
                            coalesce = false,
                        )
                    }
                    QualityChoiceV47(
                        label = "Medium\n12 fps",
                        selected = settings.analysisQualityV47 == CutoutAnalysisQualityV47.MEDIUM,
                        enabled = !analysisBusy,
                    ) {
                        vm.setSelectedCutoutV43(
                            settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.MEDIUM),
                            status = "Pro Cutout quality · Medium · 12 fps · tap Analyze",
                            coalesce = false,
                        )
                    }
                    QualityChoiceV47(
                        label = "High\nEvery frame",
                        selected = settings.analysisQualityV47 == CutoutAnalysisQualityV47.HIGH,
                        enabled = !analysisBusy,
                    ) {
                        vm.setSelectedCutoutV43(
                            settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.HIGH),
                            status = "Pro Cutout quality · High · every frame · tap Analyze",
                            coalesce = false,
                        )
                    }
                }
                if (settings.analysisQualityV47 == CutoutAnalysisQualityV47.HIGH) {
                    Text(
                        "High analyzes every decoded source frame. It gives the best motion accuracy but can take much longer and use more storage.",
                        fontSize = 9.sp,
                        color = V8CutoutText,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            analysisBusy -> "Building refined portrait matte…"
                            personReady -> "Pro matte ready"
                            analysisFailed -> "Analysis failed / incomplete"
                            else -> "Choose quality, then Analyze"
                        },
                        fontSize = 10.sp,
                        color = V8CutoutText,
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = !analysisBusy,
                        onClick = vm::analyzeSelectedPersonCutoutV43,
                    ) {
                        Text(
                            when {
                                analysisBusy -> "Analyzing…"
                                personReady -> "Refresh Matte"
                                else -> "Analyze"
                            },
                            fontSize = 9.sp,
                            color = V8CutoutText,
                        )
                    }
                }
                if (analysisStatus != null) {
                    Text(
                        analysisStatus,
                        fontSize = 9.sp,
                        color = V8CutoutText,
                    )
                }

                Text("Realtime edge refinement", fontSize = 10.sp, color = V8CutoutText)
                CutoutSliderV43("Shrink / Grow", settings.edgeShiftV44, -.18f..0.18f) {
                    vm.setSelectedCutoutV43(settings.copy(edgeShiftV44 = it), status = "Pro Cutout edge shift updated")
                }
                CutoutSliderV43("Edge Clean", settings.edgeCleanV44, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(edgeCleanV44 = it), status = "Pro Cutout edge clean updated")
                }
                CutoutSliderV43("Dehalo", settings.dehaloV44, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(dehaloV44 = it), status = "Pro Cutout dehalo updated")
                }

                Text("Analysis-time refinement", fontSize = 10.sp, color = V8CutoutText)
                CutoutSliderV43("Hair Detail", settings.hairDetailV44, 0f..1f) {
                    vm.setSelectedCutoutV43(
                        settings.copy(hairDetailV44 = it),
                        status = "Pro Cutout Hair Detail changed · tap Analyze",
                    )
                }
                CutoutSliderV43("Temporal Stability", settings.temporalStabilityV44, 0f..0.92f) {
                    vm.setSelectedCutoutV43(
                        settings.copy(temporalStabilityV44 = it),
                        status = "Pro Cutout temporal stability changed · tap Analyze",
                    )
                }

                Text("Advanced alpha shaping", fontSize = 10.sp, color = V8CutoutText)
                CutoutSliderV43("Alpha Bias", settings.personThreshold, .05f..0.95f) {
                    vm.setSelectedCutoutV43(settings.copy(personThreshold = it), status = "Pro Cutout alpha bias updated")
                }
                CutoutSliderV43("Edge Softness", settings.personFeather, .005f..0.45f) {
                    vm.setSelectedCutoutV43(settings.copy(personFeather = it), status = "Pro Cutout edge softness updated")
                }

                Text(
                    "Quality, Hair Detail and Temporal Stability are baked into the analyzed matte; press Analyze/Refresh Matte after changing them. Other edge controls update in realtime.",
                    fontSize = 9.sp,
                    color = V8CutoutText,
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
                    ) { Text("Green", fontSize = 9.sp, color = V8CutoutText) }
                    FilledTonalButton(
                        onClick = {
                            vm.setSelectedCutoutV43(
                                settings.copy(keyRed = 0f, keyGreen = .12f, keyBlue = 1f),
                                status = "Blue screen key selected",
                                coalesce = false,
                            )
                        },
                    ) { Text("Blue", fontSize = 9.sp, color = V8CutoutText) }
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
private fun RowScope.QualityChoiceV47(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(enabled = enabled, onClick = onClick, modifier = Modifier.weight(1f)) {
            Text("✓ $label", fontSize = 8.sp, color = V8CutoutText)
        }
    } else {
        OutlinedButton(enabled = enabled, onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 8.sp, color = V8CutoutText)
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
            Text(label, fontSize = 10.sp, color = V8CutoutText)
            Spacer(Modifier.weight(1f))
            Text("%.2f".format(value), fontSize = 9.sp, color = V8CutoutText)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}
