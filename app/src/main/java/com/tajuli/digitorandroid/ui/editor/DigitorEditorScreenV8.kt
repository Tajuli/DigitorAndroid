package com.tajuli.digitorandroid.ui.editor

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi

/** V50 shell: Cutout now lives inside Edit, immediately beside Retime. */
@UnstableApi
@Composable
fun DigitorEditorScreenV8(
    vm: EditorViewModelV4,
    onHome: () -> Unit = {},
) {
    DigitorEditorScreenV7(vm = vm, onHome = onHome)
}
