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
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPhaseV66
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisRuntimeV66
import com.tajuli.digitorandroid.editor.processing.PersonRoiRuntimeStatusV60
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutPartialGenerationV69
import com.tajuli.digitorandroid.editor.processing.hasResumablePersonCutoutGenerationV66
import com.tajuli.digitorandroid.editor.processing.personCutoutSavedFrameCountV66

private val C50Panel = Color(0xFF0B0B0F)
private val C50Text = Color.White

/** Compact Edit-tab Pro Cutout workspace. PP-MattingV2 is the portrait matte backend. */
@Composable
fun CutoutWorkspaceV50(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val backendLabel by CutoutBackendStatusV50.label.collectAsState()
    val roiDebug by PersonRoiRuntimeStatusV60.label.collectAsState()
    val analysisRuntime by CutoutAnalysisRuntimeV66.state.collectAsState()
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
            "PP-MattingV2 portrait matting uses Analyze/checkpoints. Chroma Key is realtime: choose a key color, then tap Start Chroma Key.",
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
        val persistentResume = hasResumablePersonCutoutGenerationV66(appContext, selectedClip)
        val partialAvailable = hasPersonCutoutPartialGenerationV69(appContext, selectedClip)
        val persistentSaved = if (persistentResume || partialAvailable) {
            personCutoutSavedFrameCountV66(appContext, selectedClip)
        } else {
            0
        }
        val runtimeMatches = analysisRuntime.clipId == selectedClip.id
        val runtimePhase = if (runtimeMatches) analysisRuntime.phase else CutoutAnalysisPhaseV66.IDLE
        val runtimeSaved = if (runtimeMatches) analysisRuntime.savedFrames else 0
        val analysisBusy = runtimeMatches && analysisRuntime.busy
        val resumeAvailable = persistentResume ||
            (runtimeMatches &&
                (runtimePhase == CutoutAnalysisPhaseV66.PAUSED || runtimePhase == CutoutAnalysisPhaseV66.FAILED) &&
                persistentSaved > 0)
        val resumeSaved = maxOf(persistentSaved, runtimeSaved.takeIf { resumeAvailable } ?: 0)
        val partialSaved = maxOf(
            persistentSaved,
            runtimeSaved.takeIf {
                runtimePhase == CutoutAnalysisPhaseV66.CANCELLED || partialAvailable
            } ?: 0,
        )
        val analysisStatus = state.status.takeIf {
            it.startsWith("Pro Cutout") || it.startsWith("Auto Cutout")
        }
        val analysisFailed = runtimePhase == CutoutAnalysisPhaseV66.FAILED ||
            analysisStatus?.contains("failed", ignoreCase = true) == true ||
            analysisStatus?.contains("incomplete", ignoreCase = true) == true ||
            analysisStatus?.contains("interrupted", ignoreCase = true) == true

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                enabled = !analysisBusy,
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
                        status = "Chroma controls ready · choose key color, then tap Start Chroma Key",
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
                    "Pro Cutout removes a person background without green screen. Chroma Key is faster for clean green/blue/custom-color screen footage.",
                    fontSize = 8.sp,
                    color = C50Text.copy(alpha = .62f),
                )
            }

            CutoutModeV43.PERSON -> {
                PersonCutoutControlsV70(
                    vm = vm,
                    settings = settings,
                    backendLabel = backendLabel,
                    roiDebug = roiDebug,
                    runtimePhase = runtimePhase,
                    runtimeSaved = runtimeSaved,
                    analysisBusy = analysisBusy,
                    personReady = personReady,
                    resumeAvailable = resumeAvailable,
                    resumeSaved = resumeSaved,
                    partialAvailable = partialAvailable,
                    partialSaved = partialSaved,
                    analysisFailed = analysisFailed,
                    analysisStatus = analysisStatus,
                )
            }

            CutoutModeV43.CHROMA_KEY -> {
                ChromaKeyControlsV70(vm = vm, settings = settings, status = state.status)
            }
        }
    }
}

@Composable
private fun PersonCutoutControlsV70(
    vm: EditorViewModelV4,
    settings: com.tajuli.digitorandroid.editor.model.ClipCutoutV43,
    backendLabel: String?,
    roiDebug: String?,
    runtimePhase: CutoutAnalysisPhaseV66,
    runtimeSaved: Int,
    analysisBusy: Boolean,
    personReady: Boolean,
    resumeAvailable: Boolean,
    resumeSaved: Int,
    partialAvailable: Boolean,
    partialSaved: Int,
    analysisFailed: Boolean,
    analysisStatus: String?,
) {
    val displayBackend = backendLabel?.replace(
        "PP-MattingV2 384 resize",
        "PP-MattingV2 ${settings.mattingSizeV69} resize",
    )
    BackendIndicatorV50(displayBackend)
    if (roiDebug != null) {
        RoiProofIndicatorV60(
            roiDebug.replace(
                "crop-before-384=YES",
                "crop-before-${settings.mattingSizeV69}=YES",
            ),
        )
    }

    Text("Matting resolution · person ROI only", fontSize = 9.sp, color = C50Text)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            256 to "256\nFast",
            320 to "320\nBalanced",
            384 to "384\nQuality",
            512 to "512\nMax",
        ).forEach { (size, label) ->
            QualityChoiceV50(
                label = label,
                selected = settings.mattingSizeV69 == size,
                enabled = !analysisBusy,
            ) {
                vm.setSelectedCutoutV43(
                    settings.copy(mattingSizeV69 = size),
                    status = "Pro Cutout resolution · $size px · tap Analyze",
                    coalesce = false,
                )
            }
        }
    }
    Text(
        "The motion-safe person ROI is cropped first. 256/320 reduce GPU work; 384/512 trade speed for finer edges. Resolution is locked while Analyze is running.",
        fontSize = 7.sp,
        color = C50Text.copy(alpha = .58f),
    )

    Text("Analysis quality", fontSize = 9.sp, color = C50Text)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        listOf(
            Triple(CutoutAnalysisQualityV47.LOW, "Low\n4 fps", "Low · 4 fps"),
            Triple(CutoutAnalysisQualityV47.MEDIUM, "Medium\n12 fps", "Medium · 12 fps"),
            Triple(CutoutAnalysisQualityV47.HIGH, "High\nEvery frame", "High · every frame"),
        ).forEach { (quality, label, statusLabel) ->
            QualityChoiceV50(
                label = label,
                selected = settings.analysisQualityV47 == quality,
                enabled = !analysisBusy,
            ) {
                vm.setSelectedCutoutV43(
                    settings.copy(analysisQualityV47 = quality),
                    status = "Pro Cutout quality · $statusLabel · tap Analyze",
                    coalesce = false,
                )
            }
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
            when (runtimePhase) {
                CutoutAnalysisPhaseV66.RUNNING -> "Building PP-MattingV2 matte… · $runtimeSaved processed"
                CutoutAnalysisPhaseV66.PAUSE_REQUESTED -> "Pausing safely after current frame…"
                CutoutAnalysisPhaseV66.PAUSED -> "Paused · $resumeSaved saved · Resume keeps progress · Export allowed"
                CutoutAnalysisPhaseV66.CANCEL_REQUESTED -> "Cancelling safely… · saved mattes stay exportable"
                CutoutAnalysisPhaseV66.CANCELLED -> "Cancelled · $partialSaved saved · Partial export allowed"
                CutoutAnalysisPhaseV66.FAILED -> if (resumeAvailable) {
                    "Interrupted · $resumeSaved saved · Resume available · Export allowed"
                } else {
                    "Analysis failed · Export still allowed"
                }
                else -> when {
                    personReady -> "Pro matte ready"
                    resumeAvailable -> "Checkpoint found · $resumeSaved saved · Resume available · Export allowed"
                    partialAvailable -> "Partial matte · $partialSaved saved · Export allowed"
                    analysisFailed -> "Analysis failed / incomplete · Export allowed"
                    else -> "Choose resolution + quality, then Analyze · Export never waits for Cutout"
                }
            },
            fontSize = 8.sp,
            color = C50Text,
        )
        Spacer(Modifier.weight(1f))
        FilledTonalButton(
            enabled = runtimePhase != CutoutAnalysisPhaseV66.PAUSE_REQUESTED &&
                runtimePhase != CutoutAnalysisPhaseV66.CANCEL_REQUESTED,
            onClick = {
                if (runtimePhase == CutoutAnalysisPhaseV66.RUNNING) {
                    vm.pauseSelectedPersonCutoutV66()
                } else {
                    vm.analyzeSelectedPersonCutoutV43()
                }
            },
        ) {
            Text(
                when {
                    runtimePhase == CutoutAnalysisPhaseV66.RUNNING -> "Pause"
                    runtimePhase == CutoutAnalysisPhaseV66.PAUSE_REQUESTED -> "Pausing…"
                    runtimePhase == CutoutAnalysisPhaseV66.CANCEL_REQUESTED -> "Stopping…"
                    resumeAvailable -> "Resume"
                    personReady -> "Refresh Matte"
                    partialAvailable || runtimePhase == CutoutAnalysisPhaseV66.CANCELLED -> "Analyze again"
                    else -> "Analyze"
                },
                fontSize = 8.sp,
            )
        }
        if (
            runtimePhase == CutoutAnalysisPhaseV66.RUNNING ||
            runtimePhase == CutoutAnalysisPhaseV66.PAUSE_REQUESTED ||
            runtimePhase == CutoutAnalysisPhaseV66.PAUSED ||
            resumeAvailable
        ) {
            OutlinedButton(
                enabled = runtimePhase != CutoutAnalysisPhaseV66.CANCEL_REQUESTED,
                onClick = { vm.cancelSelectedPersonCutoutV69() },
            ) {
                Text(
                    if (runtimePhase == CutoutAnalysisPhaseV66.CANCEL_REQUESTED) "Cancelling…" else "Cancel",
                    fontSize = 8.sp,
                )
            }
        }
    }

    if (resumeAvailable && !analysisBusy) {
        Text(
            "Resume continues from the first missing/next durable frame. Cancel keeps saved mattes for partial export but ends Resume; Analyze after Cancel starts fresh.",
            fontSize = 7.sp,
            color = C50Text.copy(alpha = .58f),
        )
    } else if (partialAvailable || runtimePhase == CutoutAnalysisPhaseV66.CANCELLED) {
        Text(
            "Partial Cutout is best-effort: nearby saved mattes are used, unprocessed gaps pass through the original frame, and export is never blocked.",
            fontSize = 7.sp,
            color = C50Text.copy(alpha = .58f),
        )
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
    CutoutSliderV50(
        label = "Hair Detail",
        value = settings.hairDetailV44,
        range = 0f..1f,
        enabled = !analysisBusy,
    ) {
        vm.setSelectedCutoutV43(
            settings.copy(hairDetailV44 = it),
            status = "Pro Cutout Hair Detail changed · tap Analyze",
        )
    }
    CutoutSliderV50(
        label = "Temporal Stability",
        value = settings.temporalStabilityV44,
        range = 0f..0.92f,
        enabled = !analysisBusy,
    ) {
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
        "Resolution, Quality, Hair Detail and Temporal Stability are baked into the analyzed matte. Edge controls update in realtime and do not invalidate a checkpoint.",
        fontSize = 8.sp,
        color = C50Text.copy(alpha = .62f),
    )
}

@Composable
private fun ChromaKeyControlsV70(
    vm: EditorViewModelV4,
    settings: com.tajuli.digitorandroid.editor.model.ClipCutoutV43,
    status: String,
) {
    Text("Key color", fontSize = 9.sp, color = C50Text)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledTonalButton(
            onClick = {
                vm.setSelectedCutoutV43(
                    settings.copy(keyRed = 0f, keyGreen = 1f, keyBlue = 0f),
                    status = "Green screen key selected · tap Start Chroma Key",
                    coalesce = false,
                )
            },
        ) {
            Text("Green", fontSize = 8.sp)
        }
        FilledTonalButton(
            onClick = {
                vm.setSelectedCutoutV43(
                    settings.copy(keyRed = 0f, keyGreen = .12f, keyBlue = 1f),
                    status = "Blue screen key selected · tap Start Chroma Key",
                    coalesce = false,
                )
            },
        ) {
            Text("Blue", fontSize = 8.sp)
        }
        ChromaKeyColorPickerV70(
            red = settings.keyRed,
            green = settings.keyGreen,
            blue = settings.keyBlue,
        ) { red, green, blue ->
            vm.setSelectedCutoutV43(
                settings.copy(keyRed = red, keyGreen = green, keyBlue = blue),
                status = "Custom chroma key color selected · tap Start Chroma Key",
                coalesce = false,
            )
        }
    }

    FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { vm.startSelectedChromaKeyV70(settings) },
    ) {
        Text("Start Chroma Key", fontSize = 9.sp)
    }
    Text(
        "Chroma Key does not run PP-Matting analysis. Start applies the current key settings and refreshes the realtime preview.",
        fontSize = 8.sp,
        color = C50Text.copy(alpha = .62f),
    )
    if (status.startsWith("Chroma") || status.contains("screen key selected", ignoreCase = true)) {
        Text(status, fontSize = 8.sp, color = C50Text.copy(alpha = .70f))
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

@Composable
private fun RoiProofIndicatorV60(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF153329), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("ROI proof", fontSize = 9.sp, color = C50Text)
            Text(label, fontSize = 7.sp, color = C50Text.copy(alpha = .78f))
            Text(
                "If this says crop-before-<selected>=YES, PP-MattingV2 receives only the reported source-frame box. Outside that box the stored matte is forced to zero.",
                fontSize = 7.sp,
                color = C50Text.copy(alpha = .58f),
            )
        }
    }
}

@Composable
private fun BackendIndicatorV50(backendLabel: String?) {
    val vulkanActive = backendLabel?.contains("ncnn Vulkan", ignoreCase = true) == true
    val nnapiActive = backendLabel?.contains("Matting: Hardware (NNAPI", ignoreCase = true) == true
    val xnnpackActive = backendLabel?.contains("Matting: CPU (XNNPACK", ignoreCase = true) == true
    val classification = when {
        backendLabel == null -> "Processing: not measured yet"
        vulkanActive -> "Processing: GPU · PP-MattingV2 Vulkan"
        nnapiActive -> "Processing: HARDWARE · NNAPI GPU/NPU"
        xnnpackActive -> "Processing: CPU · XNNPACK"
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
                vulkanActive -> Text(
                    "Direct Vulkan: PP-MattingV2 neural inference is dispatched through ncnn to the mobile GPU. The ONNX CPU backend stays cold unless Vulkan fails.",
                    fontSize = 7.sp,
                    color = C50Text.copy(alpha = .54f),
                )
                nnapiActive -> Text(
                    "NNAPI chooses the vendor accelerator; on supported devices this may be GPU, NPU/DSP, with unsupported ORT ops still able to use CPU.",
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
    enabled: Boolean = true,
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
            enabled = enabled,
        )
    }
}
