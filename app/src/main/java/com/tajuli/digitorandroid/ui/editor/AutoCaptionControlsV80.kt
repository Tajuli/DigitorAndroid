package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80

/**
 * Compact editor-level Auto Caption entry point.
 *
 * V81 keeps the progress UI visible instead of immediately collapsing after Generate. The small
 * floating control can still be reopened while a background transcription is running.
 */
@Composable
internal fun AutoCaptionControlsV80(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(AutoCaptionLanguageV80.AUTO) }
    val runState by AutoCaptionStatusV80.state.collectAsState()

    FilledTonalButton(
        onClick = { expanded = true },
        modifier = modifier.height(34.dp),
    ) {
        val label = when {
            runState.running && runState.progressPercent != null -> "CC ${runState.progressPercent}%"
            runState.running -> "CC …"
            else -> "Auto CC"
        }
        Text(label, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }

    if (!expanded) return

    Dialog(onDismissRequest = { expanded = false }) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF17171C),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto Caption", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { expanded = false }, modifier = Modifier.height(30.dp)) {
                        Text("Close", fontSize = 9.sp)
                    }
                }

                Text(
                    "Whisper runs on-device. The first run downloads the speech model once; later runs can work offline.",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = .68f),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AutoCaptionLanguageV80.entries.forEach { option ->
                        TextButton(
                            onClick = { language = option },
                            enabled = !runState.running,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text(
                                if (option == language) "● ${option.label}" else option.label,
                                fontSize = 8.sp,
                                color = if (option == language) Color(0xFF30E0C3) else Color.White.copy(alpha = .74f),
                            )
                        }
                    }
                }

                if (runState.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF30E0C3),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Creating captions…", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                runState.message,
                                fontSize = 8.sp,
                                color = Color.White.copy(alpha = .72f),
                            )
                        }
                    }
                    Text(
                        "You can close this window. Auto CC continues in the background; reopen the button to check progress.",
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = .55f),
                    )
                } else {
                    val messageColor = when {
                        runState.failed -> Color(0xFFFF8A8A)
                        runState.completedCount != null -> Color(0xFF30E0C3)
                        else -> Color.White.copy(alpha = .66f)
                    }
                    Text(runState.message, fontSize = 8.sp, color = messageColor)
                }

                FilledTonalButton(
                    onClick = { vm.generateAutoCaptionsV80(language) },
                    enabled = !runState.running,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                ) {
                    Text(
                        if (runState.failed) "Retry" else "Generate captions",
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
