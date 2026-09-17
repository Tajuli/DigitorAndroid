package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/**
 * Canonical editor entry point.
 *
 * Keep top-level editor integrations here instead of creating another DigitorEditorScreenV* shell.
 * The large workspace implementation is still hosted by DigitorEditorScreenV7 during this first
 * cleanup pass; a later internal-only refactor can rename/split that implementation without changing
 * MainActivity or creating another public versioned screen.
 */
@UnstableApi
@Composable
fun DigitorEditorScreen(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    AutoCaptionTrackIntegrityV84(vm)
    Box(Modifier.fillMaxSize()) {
        DigitorEditorScreenV7(vm = vm, onHome = onHome)
        AutoCaptionLauncherV86(
            vm = vm,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 72.dp),
        )
    }
}
