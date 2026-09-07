package com.tajuli.digitorandroid.editor.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live proof that crop-before-384 ROI inference is active on the physical device. */
object PersonRoiRuntimeStatusV60 {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    internal fun update(value: String?) {
        _label.value = value
    }
}
