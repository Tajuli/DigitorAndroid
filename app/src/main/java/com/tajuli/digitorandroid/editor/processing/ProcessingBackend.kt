package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineProject
import java.io.File

sealed interface ExportProgress {
    data class Stage(val name: String, val fraction: Float? = null) : ExportProgress
}

data class ExportResult(
    val output: File,
    val backend: Backend,
    val note: String? = null,
)

enum class Backend { GPU, CPU }

interface ExportBackend {
    suspend fun export(
        project: TimelineProject,
        output: File,
        onProgress: (ExportProgress) -> Unit,
    ): ExportResult
}
