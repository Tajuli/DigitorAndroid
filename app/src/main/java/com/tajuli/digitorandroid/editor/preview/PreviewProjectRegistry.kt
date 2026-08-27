package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest immutable editor snapshot for long-lived GPU preview effects/compositor settings.
 *
 * Render parameters can be read from the newest project without tearing down decoders every time a
 * slider moves. The StateFlow is also used by the SurfaceView host so the physical preview surface
 * always follows the project/canvas aspect ratio instead of stretching to the editor panel bounds.
 */
internal object PreviewProjectRegistry {
    private val latest = AtomicReference<TimelineProject?>(null)
    private val mutableProject = MutableStateFlow<TimelineProject?>(null)

    val flow: StateFlow<TimelineProject?> = mutableProject.asStateFlow()

    fun update(project: TimelineProject) {
        latest.set(project)
        mutableProject.value = project
    }

    fun project(): TimelineProject? = latest.get()

    fun clip(id: String): TimelineClip? = latest.get()?.clip(id)

    fun clear(project: TimelineProject? = null) {
        if (project == null) {
            latest.set(null)
            mutableProject.value = null
            return
        }
        if (latest.compareAndSet(project, null)) {
            mutableProject.value = null
        }
    }
}
