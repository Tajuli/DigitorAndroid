package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.AutoCaptionLanguageV80

/** Compact editor-level entry point; generated captions become normal editable TextOverlayClip items. */
@Composable
internal fun AutoCaptionControlsV80(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(AutoCaptionLanguageV80.AUTO) }

    if (!expanded) {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = modifier.height(32.dp),
        ) {
            Text("Auto CC", fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = Color(0xEE17171C),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                Text("Auto Caption", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { expanded = false }, modifier = Modifier.height(26.dp)) {
                    Text("×", fontSize = 12.sp)
                }
            }
            Text(
                "Whisper · on-device after the first model download",
                fontSize = 7.sp,
                color = Color.White.copy(alpha = .62f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AutoCaptionLanguageV80.entries.forEach { option ->
                    TextButton(
                        onClick = { language = option },
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            if (option == language) "● ${option.label}" else option.label,
                            fontSize = 7.sp,
                            color = if (option == language) Color(0xFF30E0C3) else Color.White.copy(alpha = .72f),
                        )
                    }
                }
            }
            FilledTonalButton(
                onClick = {
                    vm.generateAutoCaptionsV80(language)
                    expanded = false
                },
                modifier = Modifier.height(32.dp),
            ) {
                Text("Generate captions", fontSize = 8.sp)
            }
        }
    }
}
