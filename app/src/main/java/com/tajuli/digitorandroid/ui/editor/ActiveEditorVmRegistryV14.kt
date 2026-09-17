package com.tajuli.digitorandroid.ui.editor

/**
 * Holds the keyed editor-session ViewModel created by MainActivity.
 *
 * Some timeline helpers historically asked Compose for an un-keyed editor ViewModel while
 * MainActivity deliberately creates a keyed instance per editor session. Any helper that mutates
 * the un-keyed instance therefore changes the wrong state. Keep the active keyed instance explicit
 * so trim/resize mutations always land in the editor state that is actually on screen.
 */
object ActiveEditorVmRegistryV14 {
    @Volatile
    private var active: EditorViewModel? = null

    fun bind(vm: EditorViewModel) {
        active = vm
    }

    fun clear(vm: EditorViewModel? = null) {
        if (vm == null || active === vm) active = null
    }

    fun current(): EditorViewModel? = active
}
