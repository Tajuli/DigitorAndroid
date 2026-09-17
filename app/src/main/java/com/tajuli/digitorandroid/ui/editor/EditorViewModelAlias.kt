package com.tajuli.digitorandroid.ui.editor

/**
 * Canonical editor ViewModel types used by runtime and integration code.
 *
 * The versioned implementation name remains a compatibility detail until the implementation class
 * itself is renamed after the remaining hidden/internal callers are compile-audited.
 */
typealias EditorViewModel = EditorViewModelV4
typealias EditorUiState = EditorViewModelV4.UiState
