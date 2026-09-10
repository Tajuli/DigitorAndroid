package com.tajuli.digitorandroid.editor.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live PP-MattingV2 execution backend, including provider fallback that occurs on first inference. */
object PortraitMatteRuntimeStatusV50 {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    internal fun update(value: String?) {
        _label.value = value
    }
}
