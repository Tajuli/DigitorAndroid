package com.tajuli.digitorandroid.ui.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime status for the portrait matting backend used by Pro Cutout.
 * Kept separate from the general editor status so progress updates do not hide whether
 * PP-MattingV2 is running through NNAPI hardware acceleration or a CPU backend.
 */
internal object CutoutBackendStatusV50 {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    fun update(value: String) {
        _label.value = value
    }

    fun clear() {
        _label.value = null
    }
}
