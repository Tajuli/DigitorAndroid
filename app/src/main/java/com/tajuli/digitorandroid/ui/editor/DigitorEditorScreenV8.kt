package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/** V80 shell: editor-level Auto Caption control backed by on-device whisper.cpp. */
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)
        AutoCaptionControlsV80(
            vm = vm,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 52.dp, end = 8.dp),
        )
    }
}
