package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
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
import com.tajuli.digitorandroid.editor.processing.supportedAutoCaptionLanguagesV82

/** Compact GPU-only Auto Caption entry point. */
@Composable
internal fun AutoCaptionControlsV80(
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    val languages = remember { supportedAutoCaptionLanguagesV82() }
    var expanded by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var languageQuery by remember { mutableStateOf("") }
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

    Dialog(
        onDismissRequest = {
            choosingLanguage = false
            expanded = false
        },
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF17171C),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            if (choosingLanguage) {
                LanguagePickerV82(
                    languages = languages,
                    selected = language,
                    query = languageQuery,
                    onQueryChange = { languageQuery = it },
                    enabled = !runState.running,
                    onSelect = { selected ->
                        language = selected
                        languageQuery = ""
                        choosingLanguage = false
                    },
                    onBack = {
                        languageQuery = ""
                        choosingLanguage = false
                    },
                )
            } else {
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
                        "Auto Caption now uses Digitor's ncnn Vulkan GPU runtime directly. It does not automatically switch to CPU or OpenCL.",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = .68f),
                    )
                    Text(
                        "First use downloads the multilingual ncnn Whisper base GPU model pack (~147 MB) once; later runs work offline.",
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = .55f),
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Spoken language", fontSize = 8.sp, color = Color.White.copy(alpha = .58f))
                            Text(
                                if (language.whisperCode == "auto") language.label else "${language.label} · ${language.whisperCode}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        TextButton(
                            onClick = { choosingLanguage = true },
                            enabled = !runState.running,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("Change", fontSize = 8.sp, color = Color(0xFF30E0C3))
                        }
                    }

                    Text(
                        if (language.whisperCode == "auto") {
                            "Auto Detect identifies the spoken language on-device, then transcribes it on the Vulkan GPU."
                        } else {
                            "Manual language selection can improve recognition when you already know the spoken language."
                        },
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = .55f),
                    )

                    if (runState.running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF30E0C3),
                            )
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("Creating captions on GPU…", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
                            if (runState.failed) "Retry GPU" else "Generate captions",
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerV82(
    languages: List<AutoCaptionLanguageV80>,
    selected: AutoCaptionLanguageV80,
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    onSelect: (AutoCaptionLanguageV80) -> Unit,
    onBack: () -> Unit,
) {
    val normalizedQuery = query.trim()
    val filtered = if (normalizedQuery.isBlank()) {
        languages
    } else {
        languages.filter { option ->
            option.label.contains(normalizedQuery, ignoreCase = true) ||
                option.whisperCode.contains(normalizedQuery, ignoreCase = true)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Caption language", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack, modifier = Modifier.height(30.dp)) {
                Text("Back", fontSize = 9.sp)
            }
        }

        Text(
            "Choose Auto Detect for mixed international use, or select the known spoken language for a stronger recognition hint.",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = .62f),
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text("Search language or code", fontSize = 8.sp) },
        )

        Text(
            "${filtered.size} language option${if (filtered.size == 1) "" else "s"}",
            fontSize = 8.sp,
            color = Color.White.copy(alpha = .5f),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items = filtered, key = { it.whisperCode }) { option ->
                val isSelected = option.whisperCode == selected.whisperCode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSelect(option) }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF30E0C3) else Color.White.copy(alpha = .86f),
                        )
                        Text(
                            if (option.whisperCode == "auto") "Automatic language detection" else option.whisperCode,
                            fontSize = 7.sp,
                            color = Color.White.copy(alpha = .48f),
                        )
                    }
                    if (isSelected) {
                        Text("✓", fontSize = 11.sp, color = Color(0xFF30E0C3))
                    }
                }
            }
        }
    }
}
