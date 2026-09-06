package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43

private val C50Panel = Color(0xFF0B0B0F)
private val C50Text = Color.White

/** Compact Edit-tab Pro Cutout workspace. PP-MattingV2 is the only portrait matte backend. */
@Composable
fun CutoutWorkspaceV50(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val backendLabel by CutoutBackendStatusV50.label.collectAsState()
    val clip = state.project.clip(state.selectedClipId)
    val isVisualClip = clip != null && state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO

    Column(
        modifier
            .background(C50Panel)
            .verticalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pro Cutout & Chroma Key", fontSize = 12.sp, color = C50Text)
        Text(
            "PP-MattingV2 portrait matting. Choose analysis quality, then Analyze. Replacement background goes on a lower V track.",
            fontSize = 8.sp,
            color = C50Text.copy(alpha = .62f),
        )

        if (!isVisualClip) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = .06f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text("Select a video/image clip first.", fontSize = 9.sp, color = C50Text)
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
                status.contains("selecting", ignoreCase = true) ||
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
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                Text(if (settings.mode == CutoutModeV43.NONE) "✓ Off" else "Off", fontSize = 8.sp)
            }

            FilledTonalButton(
                enabled = !analysisBusy,
                onClick = { vm.enablePersonCutoutV43(settings) },
            ) {
                Text(
                    if (settings.mode == CutoutModeV43.PERSON) "✓ Pro Cutout" else "Pro Cutout",
                    fontSize = 8.sp,
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
                    if (settings.mode == CutoutModeV43.CHROMA_KEY) "✓ Chroma" else "Chroma",
                    fontSize = 8.sp,
                )
            }
        }

        when (settings.mode) {
            CutoutModeV43.NONE -> {
                Text(
                    "Pro Cutout removes a person background without green screen. Chroma Key is faster for clean green/blue-screen footage.",
                    fontSize = 8.sp,
                    color = C50Text.copy(alpha = .62f),
                )
            }

            CutoutModeV43.PERSON -> {
                BackendIndicatorV50(backendLabel)

                Text("Analysis quality", fontSize = 9.sp, color = C50Text)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    QualityChoiceV50(
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
                    QualityChoiceV50(
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
                    QualityChoiceV50(
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
                        "High analyzes every decoded source frame. Best motion accuracy, but slower and uses more matte storage.",
                        fontSize = 8.sp,
                        color = C50Text.copy(alpha = .62f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            analysisBusy -> "Building PP-MattingV2 matte…"
                            personReady -> "Pro matte ready"
                            analysisFailed -> "Analysis failed / incomplete"
                            else -> "Choose quality, then Analyze"
                        },
                        fontSize = 8.sp,
                        color = C50Text,
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
                            fontSize = 8.sp,
                        )
                    }
                }

                if (analysisStatus != null) {
                    Text(analysisStatus, fontSize = 8.sp, color = C50Text.copy(alpha = .70f))
                }

                Text("Realtime edge refinement", fontSize = 9.sp, color = C50Text)
                CutoutSliderV50("Shrink / Grow", settings.edgeShiftV44, -.18f..0.18f) {
                    vm.setSelectedCutoutV43(settings.copy(edgeShiftV44 = it), status = "Pro Cutout edge shift updated")
                }
                CutoutSliderV50("Edge Clean", settings.edgeCleanV44, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(edgeCleanV44 = it), status = "Pro Cutout edge clean updated")
                }
                CutoutSliderV50("Dehalo", settings.dehaloV44, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(dehaloV44 = it), status = "Pro Cutout dehalo updated")
                }

                Text("Analysis-time refinement", fontSize = 9.sp, color = C50Text)
                CutoutSliderV50("Hair Detail", settings.hairDetailV44, 0f..1f) {
                    vm.setSelectedCutoutV43(
                        settings.copy(hairDetailV44 = it),
                        status = "Pro Cutout Hair Detail changed · tap Analyze",
                    )
                }
                CutoutSliderV50("Temporal Stability", settings.temporalStabilityV44, 0f..0.92f) {
                    vm.setSelectedCutoutV43(
                        settings.copy(temporalStabilityV44 = it),
                        status = "Pro Cutout temporal stability changed · tap Analyze",
                    )
                }

                Text("Advanced alpha shaping", fontSize = 9.sp, color = C50Text)
                CutoutSliderV50("Alpha Bias", settings.personThreshold, .05f..0.95f) {
                    vm.setSelectedCutoutV43(settings.copy(personThreshold = it), status = "Pro Cutout alpha bias updated")
                }
                CutoutSliderV50("Edge Softness", settings.personFeather, .005f..0.45f) {
                    vm.setSelectedCutoutV43(settings.copy(personFeather = it), status = "Pro Cutout edge softness updated")
                }

                Text(
                    "Quality, Hair Detail and Temporal Stability are baked into the analyzed matte; refresh after changing them. Edge controls update in realtime.",
                    fontSize = 8.sp,
                    color = C50Text.copy(alpha = .62f),
                )
            }

            CutoutModeV43.CHROMA_KEY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = {
                            vm.setSelectedCutoutV43(
                                settings.copy(keyRed = 0f, keyGreen = 1f, keyBlue = 0f),
                                status = "Green screen key selected",
                                coalesce = false,
                            )
                        },
                    ) { Text("Green", fontSize = 8.sp) }
                    FilledTonalButton(
                        onClick = {
                            vm.setSelectedCutoutV43(
                                settings.copy(keyRed = 0f, keyGreen = .12f, keyBlue = 1f),
                                status = "Blue screen key selected",
                                coalesce = false,
                            )
                        },
                    ) { Text("Blue", fontSize = 8.sp) }
                }

                CutoutSliderV50("Key R", settings.keyRed, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyRed = it), status = "Chroma key color updated")
                }
                CutoutSliderV50("Key G", settings.keyGreen, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyGreen = it), status = "Chroma key color updated")
                }
                CutoutSliderV50("Key B", settings.keyBlue, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(keyBlue = it), status = "Chroma key color updated")
                }
                CutoutSliderV50("Similarity", settings.chromaSimilarity, .01f..0.40f) {
                    vm.setSelectedCutoutV43(settings.copy(chromaSimilarity = it), status = "Chroma similarity updated")
                }
                CutoutSliderV50("Softness", settings.chromaSoftness, .005f..0.30f) {
                    vm.setSelectedCutoutV43(settings.copy(chromaSoftness = it), status = "Chroma softness updated")
                }
                CutoutSliderV50("Spill", settings.spillSuppression, 0f..1f) {
                    vm.setSelectedCutoutV43(settings.copy(spillSuppression = it), status = "Spill suppression updated")
                }
            }
        }
    }
}

@Composable
private fun BackendIndicatorV50(backendLabel: String?) {
    val classification = when {
        backendLabel == null -> "Processing: not measured yet"
        backendLabel.contains("Paddle Lite OpenCL", ignoreCase = true) -> "Processing: GPU · PP-MattingV2 OpenCL"
        backendLabel.contains("Hardware", ignoreCase = true) ||
            backendLabel.contains("NNAPI", ignoreCase = true) -> "Processing: HARDWARE · NNAPI GPU/NPU"
        backendLabel.contains("XNNPACK", ignoreCase = true) -> "Processing: CPU · XNNPACK"
        else -> "Processing: CPU · ONNX Runtime"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .07f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(classification, fontSize = 9.sp, color = C50Text)
            Text(
                backendLabel ?: "Tap Analyze to detect the PP-MattingV2 execution backend on this device.",
                fontSize = 7.sp,
                color = C50Text.copy(alpha = .62f),
            )
            when {
                backendLabel?.contains("Paddle Lite OpenCL", ignoreCase = true) == true -> Text(
                    "Direct OpenCL: PP-MattingV2 inference is assigned to the mobile GPU; ARM is retained only for unsupported operators.",
                    fontSize = 7.sp,
                    color = C50Text.copy(alpha = .54f),
                )
                backendLabel?.contains("NNAPI", ignoreCase = true) == true -> Text(
                    "NNAPI chooses the vendor accelerator; on supported devices this may be Mali GPU, NPU/DSP, with unsupported ORT ops still able to use CPU.",
                    fontSize = 7.sp,
                    color = C50Text.copy(alpha = .54f),
                )
            }
        }
    }
}

@Composable
private fun RowScope.QualityChoiceV50(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(enabled = enabled, onClick = onClick, modifier = Modifier.weight(1f)) {
            Text("✓ $label", fontSize = 7.sp)
        }
    } else {
        OutlinedButton(enabled = enabled, onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 7.sp)
        }
    }
}

@Composable
private fun CutoutSliderV50(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 8.sp, color = C50Text)
            Spacer(Modifier.weight(1f))
            Text("%.2f".format(value), fontSize = 8.sp, color = C50Text.copy(alpha = .62f))
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}
