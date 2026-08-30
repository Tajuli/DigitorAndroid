package com.tajuli.digitorandroid.ui.editor

/**
 * Holds the keyed editor-session ViewModel created by MainActivity.
 *
 * TimelineEditorV4 historically asks Compose for an un-keyed EditorViewModelV4, while MainActivity
 * deliberately creates a keyed instance per editor session. Any timeline helper that mutates the
 * un-keyed instance therefore changes the wrong state. Keep the active keyed instance explicit so
 * trim/resize mutations always land in the editor state that is actually on screen.
 */
object ActiveEditorVmRegistryV14 {
    @Volatile
    private var active: EditorViewModelV4? = null

    fun bind(vm: EditorViewModelV4) {
        active = vm
    }

    fun clear(vm: EditorViewModelV4? = null) {
        if (vm == null || active === vm) active = null
    }

    fun current(): EditorViewModelV4? = active
}
