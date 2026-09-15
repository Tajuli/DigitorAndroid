package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/** V85 shell: V7 editor plus install-gated Zipformer Auto CC and CC-track integrity handling. */
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    AutoCaptionTrackIntegrityV84(vm)
    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)
        AutoCaptionLauncherV85(
            vm = vm,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 72.dp),
        )
    }
}
