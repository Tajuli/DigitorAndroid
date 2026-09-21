package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPhaseV66
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisRuntimeV66
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43

private val PortraitTextV100 = Color.White
private val PortraitMutedV100 = Color.White.copy(alpha = .68f)

/** Clip-wide portrait optics share Pro Cutout's PP-MattingV2 settings and durable analysis. */
@Composable
fun PortraitLensBlurControlsV99(clip: TimelineClip, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val settings = clip.resolvedCutoutV43()
    val active = settings.mode == CutoutModeV43.PERSON && settings.portraitLensBlurV99
    val runtime by CutoutAnalysisRuntimeV66.state.collectAsState()
    val runtimeMatches = runtime.clipId == clip.id
    val busy = runtime.busy
    val thisBusy = busy && runtimeMatches
    val context = LocalContext.current.applicationContext
    val ready = active && hasPersonCutoutCoverageV43(context, clip)
    val done = ready || (runtimeMatches && runtime.phase == CutoutAnalysisPhaseV66.COMPLETED)
    val expected = runtime.expectedFrames.coerceAtLeast(0)
    val processed = if (runtimeMatches) runtime.savedFrames.coerceAtLeast(0) else 0
    val percent = when {
        done -> 100
        expected > 0 -> ((processed * 100f) / expected).toInt().coerceIn(0, 99)
        else -> 0
    }
    val progress = (percent / 100f).coerceIn(0f, 1f)

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Portrait Lens Blur", fontSize = 16.sp, color = PortraitTextV100)
        Text(
            "Keep the person sharp with a soft circular background blur. PP-MattingV2 uses the same Cutout analysis settings.",
            fontSize = 12.sp,
            color = PortraitMutedV100,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                enabled = !busy,
                colors = ButtonDefaults.filledTonalButtonColors(contentColor = PortraitTextV100),
                onClick = {
                    vm.setSelectedCutoutV43(
                        settings.copy(mode = CutoutModeV43.NONE, portraitLensBlurV99 = false),
                        status = "Portrait Lens Blur off",
                        coalesce = false,
                    )
                },
            ) { Text("None") }

            Button(
                enabled = !busy && !active,
                colors = ButtonDefaults.buttonColors(contentColor = Color.Black),
                onClick = {
                    vm.setSelectedCutoutV43(
                        settings.copy(
                            mode = CutoutModeV43.PERSON,
                            portraitLensBlurV99 = true,
                            lensBlurAmountV99 = .55f,
                        ),
                        status = "Portrait Lens Blur · preparing subject",
                        coalesce = false,
                    )
                    vm.analyzeSelectedPersonCutoutV43()
                },
            ) { Text(if (active) "Applied" else "Apply & Analyze") }
        }

        if (active) {
            Text("Blur strength · ${(settings.lensBlurAmountV99 * 100).toInt()}%", color = PortraitTextV100)
            Slider(
                value = settings.lensBlurAmountV99,
                onValueChange = {
                    vm.setSelectedCutoutV43(
                        settings.copy(lensBlurAmountV99 = it),
                        status = "Portrait blur strength updated",
                    )
                },
                valueRange = 0f..1f,
            )

            Text("PP-MattingV2 resolution", fontSize = 11.sp, color = PortraitTextV100)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PortraitChoiceV100("256 px", settings.mattingSizeV69 == 256, !busy) {
                    vm.setSelectedCutoutV43(settings.copy(mattingSizeV69 = 256), "Portrait Lens Blur · 256 px · analyze again", false)
                }
                PortraitChoiceV100("320 px", settings.mattingSizeV69 == 320, !busy) {
                    vm.setSelectedCutoutV43(settings.copy(mattingSizeV69 = 320), "Portrait Lens Blur · 320 px · analyze again", false)
                }
                PortraitChoiceV100("384 px", settings.mattingSizeV69 == 384, !busy) {
                    vm.setSelectedCutoutV43(settings.copy(mattingSizeV69 = 384), "Portrait Lens Blur · 384 px · analyze again", false)
                }
                PortraitChoiceV100("512 px", settings.mattingSizeV69 == 512, !busy) {
                    vm.setSelectedCutoutV43(settings.copy(mattingSizeV69 = 512), "Portrait Lens Blur · 512 px · analyze again", false)
                }
            }

            Text("Analysis FPS", fontSize = 11.sp, color = PortraitTextV100)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PortraitChoiceV100("4 fps", settings.analysisQualityV47 == CutoutAnalysisQualityV47.LOW, !busy) {
                    vm.setSelectedCutoutV43(
                        settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.LOW),
                        "Portrait Lens Blur · 4 fps · analyze again",
                        false,
                    )
                }
                PortraitChoiceV100("12 fps", settings.analysisQualityV47 == CutoutAnalysisQualityV47.MEDIUM, !busy) {
                    vm.setSelectedCutoutV43(
                        settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.MEDIUM),
                        "Portrait Lens Blur · 12 fps · analyze again",
                        false,
                    )
                }
                PortraitChoiceV100("Full FPS", settings.analysisQualityV47 == CutoutAnalysisQualityV47.HIGH, !busy) {
                    vm.setSelectedCutoutV43(
                        settings.copy(analysisQualityV47 = CutoutAnalysisQualityV47.HIGH),
                        "Portrait Lens Blur · full FPS · analyze again",
                        false,
                    )
                }
            }

            Text(
                "Motion-safe ROI crop happens first, then the selected 256/320/384/512 px PP-MattingV2 inference. FPS matches Pro Cutout: 4 fps, 12 fps, or every decoded frame.",
                fontSize = 10.sp,
                color = PortraitMutedV100,
            )

            if (thisBusy || done || runtimeMatches) {
                Text(
                    when {
                        done -> "Done · 100%"
                        thisBusy -> "Processing · $percent%"
                        runtime.phase == CutoutAnalysisPhaseV66.CANCELLED -> "Cancelled · $percent%"
                        runtime.phase == CutoutAnalysisPhaseV66.PAUSED -> "Paused · $percent%"
                        runtime.phase == CutoutAnalysisPhaseV66.FAILED -> "Incomplete · $percent%"
                        else -> "Ready to analyze · $percent%"
                    },
                    fontSize = 13.sp,
                    color = PortraitTextV100,
                )
                Box(
                    Modifier.fillMaxWidth().height(8.dp)
                        .background(Color.White.copy(alpha = .16f), RoundedCornerShape(99.dp)),
                ) {
                    if (progress > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progress).fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(99.dp)),
                        )
                    }
                }
                if (expected > 0 && !done) {
                    Text("$processed / $expected frames", fontSize = 10.sp, color = PortraitMutedV100)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy || thisBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PortraitTextV100),
                    onClick = {
                        if (thisBusy) vm.pauseSelectedPersonCutoutV66()
                        else vm.analyzeSelectedPersonCutoutV43()
                    },
                ) {
                    Text(if (thisBusy) "Pause" else if (done) "Analyze again" else "Analyze / Resume")
                }
                if (thisBusy) {
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PortraitTextV100),
                        onClick = { vm.cancelSelectedPersonCutoutV69() },
                    ) { Text("Cancel") }
                }
            }

            if (!done && !thisBusy) {
                Text(
                    "Analysis incomplete. Unprocessed frames stay unchanged until analysis finishes.",
                    fontSize = 11.sp,
                    color = PortraitMutedV100,
                )
            }
        }

        Text(
            "For people. Hair, motion blur and difficult backgrounds depend on matte quality. This effect replaces background removal or chroma key on this clip.",
            fontSize = 11.sp,
            color = PortraitMutedV100,
        )
    }
}

@Composable
private fun RowScope.PortraitChoiceV100(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = Modifier.weight(1f),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) Color.White.copy(alpha = .24f) else Color.White.copy(alpha = .08f),
            contentColor = PortraitTextV100,
            disabledContainerColor = Color.White.copy(alpha = if (selected) .18f else .05f),
            disabledContentColor = Color.White.copy(alpha = .45f),
        ),
        onClick = onClick,
    ) {
        Text(if (selected) "✓ $label" else label, fontSize = 9.sp, maxLines = 1)
    }
}
