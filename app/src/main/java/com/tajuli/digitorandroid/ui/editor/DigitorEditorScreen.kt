package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
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
    AutoCaptionTrackIntegrity(vm)
    EditorWorkspaceScreen(vm = vm, onHome = onHome)
}
