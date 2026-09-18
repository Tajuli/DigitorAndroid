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
 * Versioned implementation details stay behind the stable EditorViewModel and EditorWorkspaceScreen
 * boundaries so callers no longer depend on historical runtime suffixes.
 */
@UnstableApi
@Composable
fun DigitorEditorScreen(
    vm: EditorViewModel,
    onHome: () -> Unit = {},
) {
    AutoCaptionTrackIntegrityV84(vm)
    Box(Modifier.fillMaxSize()) {
        EditorWorkspaceScreen(vm = vm, onHome = onHome)
        AutoCaptionLauncher(
            vm = vm,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 72.dp),
        )
    }
}
