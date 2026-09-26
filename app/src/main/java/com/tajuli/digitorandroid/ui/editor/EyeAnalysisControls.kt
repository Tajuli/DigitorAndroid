package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.processing.FaceTrackingAnalysisRuntime

@Composable
internal fun EyeAnalysisControls(
    clip: TimelineClip,
    onReady: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val states by FaceTrackingAnalysisRuntime.states.collectAsState()
    val key = FaceTrackingAnalysisRuntime.key(clip)
    val state = states[key]

    LaunchedEffect(key) {
        FaceTrackingAnalysisRuntime.refresh(context, clip)
    }
    LaunchedEffect(key, state?.ready) {
        onReady(state?.ready == true)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when {
                state == null ->
                    "Face tracking must be analyzed before these effects are available."
                state.running -> "${state.progress}%"
                else -> state.message
            },
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = when (state?.gpuAccelerated) {
                true -> "12 fps motion tracking · MediaPipe GPU · continues offscreen"
                false -> "12 fps motion tracking · CPU fallback · continues offscreen"
                null -> "Fast motion tracking · GPU preferred · continues offscreen"
            },
            color = Color(0xFF909098),
            fontSize = 8.sp,
        )

        if (state?.gpuAccelerated == false && !state.gpuFailureReason.isNullOrBlank()) {
            Text(
                text = "GPU init: " + state.gpuFailureReason,
                color = Color(0xFFB8B8C0),
                fontSize = 7.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state?.running == true) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { FaceTrackingAnalysisRuntime.cancel(clip) },
            ) {
                Text("Cancel")
            }
        } else if (state?.ready != true) {
            FilledTonalButton(
                onClick = { FaceTrackingAnalysisRuntime.start(context, clip) },
            ) {
                Text("Analyze Face Tracking")
            }
        }
    }
}
