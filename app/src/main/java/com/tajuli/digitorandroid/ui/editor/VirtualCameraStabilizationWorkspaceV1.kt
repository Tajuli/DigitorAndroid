package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.VirtualStabilizationModeV1
import kotlin.math.roundToInt

private val S1Raised = Color(0xFF141419)
private val S1Muted = Color(0xFF909098)
private val S1Accent = Color(0xFF30E0C3)

@Composable
internal fun VirtualCameraStabilizationWorkspaceV1(
    clip: TimelineClip,
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val editorState by vm.state.collectAsState()
    val liveClip = editorState.project.clip(clip.id) ?: clip
    val stabilization = liveClip.virtualCameraStabilizationV1
    val busy = editorState.busyOperation == "Stabilize"

    Column(
        modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Virtual Camera · ${liveClip.label}", fontSize = 10.sp, color = Color.White)
        Text(
            "Global path solve · no Perspective warp",
            fontSize = 8.sp,
            color = S1Muted,
        )

        ActionChipV1(
            label = when {
                busy -> editorState.status
                stabilization?.hasAnalysis == true -> "Re-analyze"
                else -> "Analyze"
            },
            enabled = !busy,
        ) {
            vm.analyzeSelectedStabilizationV1()
        }

        if (stabilization?.hasAnalysis == true) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VirtualStabilizationModeV1.entries.forEach { mode ->
                    ActionChipV1(
                        label = when (mode) {
                            VirtualStabilizationModeV1.TRANSLATION -> "Translation"
                            VirtualStabilizationModeV1.SIMILARITY -> "Similarity"
                            VirtualStabilizationModeV1.TRIPOD -> "Tripod"
                        },
                        selected = stabilization.mode == mode,
                    ) {
                        vm.setStabilizationModeV1(mode)
                    }
                }
            }

            Text(
                "Strength ${(stabilization.strength * 100f).roundToInt()}%",
                fontSize = 8.sp,
                color = S1Muted,
            )
            Slider(
                value = stabilization.strength,
                onValueChange = vm::setStabilizationStrengthV1,
                valueRange = 0f..1f,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChipV1(
                    label = if (stabilization.enabled) "Enabled" else "Bypassed",
                    selected = stabilization.enabled,
                ) {
                    vm.setStabilizationEnabledV1(!stabilization.enabled)
                }
                ActionChipV1(
                    label = if (stabilization.zoomEnabled) "Auto Zoom" else "Show Edges",
                    selected = stabilization.zoomEnabled,
                ) {
                    vm.setStabilizationZoomV1(!stabilization.zoomEnabled)
                }
                ActionChipV1(label = "Clear") {
                    vm.clearStabilizationV1()
                }
            }

            val cover = when (stabilization.mode) {
                VirtualStabilizationModeV1.TRANSLATION -> stabilization.translationCoverScale
                VirtualStabilizationModeV1.SIMILARITY -> stabilization.similarityCoverScale
                VirtualStabilizationModeV1.TRIPOD -> stabilization.tripodCoverScale
            }
            Text(
                "Static cover zoom: ${(cover * 100f).roundToInt()}% · no zoom breathing",
                fontSize = 8.sp,
                color = S1Muted,
            )
        } else if (!busy) {
            Text(
                "Analyze once. Translation/Similarity/Tripod can then switch instantly.",
                fontSize = 8.sp,
                color = S1Muted,
            )
        }
    }
}

@Composable
private fun ActionChipV1(
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 9.sp,
        color = when {
            !enabled -> S1Muted
            selected -> S1Accent
            else -> Color.White
        },
        modifier = Modifier
            .background(S1Raised, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}
