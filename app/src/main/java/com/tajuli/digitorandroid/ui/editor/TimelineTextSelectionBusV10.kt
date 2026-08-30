package com.tajuli.digitorandroid.ui.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny UI bridge between Edit timeline and Text workspace.
 * TimelineEditorV4 has an intentionally generic callback surface, so text selection is handed off
 * here without widening every historical editor-screen API.
 */
object TimelineTextSelectionBusV10 {
    private val _selectedTextId = MutableStateFlow<String?>(null)
    val selectedTextId: StateFlow<String?> = _selectedTextId.asStateFlow()

    fun select(textId: String) {
        _selectedTextId.value = textId
    }

    fun clear(textId: String? = null) {
        if (textId == null || _selectedTextId.value == textId) _selectedTextId.value = null
    }
}
