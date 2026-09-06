package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.processing.PortraitMatteRuntimeStatusV50
import kotlinx.coroutines.flow.StateFlow

/** UI-facing proxy for the live PP-MattingV2 backend state owned by the processing layer. */
internal object CutoutBackendStatusV50 {
    val label: StateFlow<String?> = PortraitMatteRuntimeStatusV50.label

    fun update(value: String) {
        PortraitMatteRuntimeStatusV50.update(value)
    }

    fun clear() {
        PortraitMatteRuntimeStatusV50.update(null)
    }
}
