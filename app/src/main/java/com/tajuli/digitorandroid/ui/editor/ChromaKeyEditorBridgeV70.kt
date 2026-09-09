package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ClipCutoutV43
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.preview.PreviewExportCoordinator

/** Explicit user action for starting/applying the realtime Chroma Key pipeline. */
fun EditorViewModelV4.startSelectedChromaKeyV70(settings: ClipCutoutV43) {
    setSelectedCutoutV43(
        settings.copy(mode = CutoutModeV43.CHROMA_KEY),
        status = "Chroma Key started · realtime preview",
        coalesce = false,
    )
    PreviewExportCoordinator.refreshActivePreviews(80L)
}
