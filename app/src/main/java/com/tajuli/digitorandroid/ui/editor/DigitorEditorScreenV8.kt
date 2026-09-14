package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/** V77 shell: keeps the V7 editor stable and layers creator Auto CC just above the workspace rail. */
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)
        AutoCaptionLauncherV77(
            vm = vm,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 72.dp),
        )
    }
}