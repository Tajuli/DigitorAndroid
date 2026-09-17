package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi

/**
 * Canonical editor runtime type used by new integration code.
 *
 * The V4 implementation name is kept temporarily as a compatibility detail while the remaining
 * editor files are migrated incrementally.
 */
typealias EditorViewModel = EditorViewModelV4

/** Canonical workspace entry used by DigitorEditorScreen. */
@UnstableApi
@Composable
internal fun EditorWorkspace(
    vm: EditorViewModel,
    onHome: () -> Unit = {},
) {
    EditorWorkspaceScreen(vm = vm, onHome = onHome)
}
