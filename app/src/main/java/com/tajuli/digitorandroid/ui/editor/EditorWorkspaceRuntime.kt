package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi

/**
 * Canonical runtime workspace boundary.
 *
 * The proven V7 implementation remains behind this boundary until its remaining implementation
 * symbols are migrated in smaller CI-gated steps.
 */
@UnstableApi
@Composable
internal fun EditorWorkspaceScreen(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    DigitorEditorScreenV7(vm = vm, onHome = onHome)
}
