package com.tajuli.digitorandroid.editor.preview

import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TimelineProject
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest immutable editor snapshot for long-lived GPU preview effects/compositor settings.
 *
 * Media/timeline topology still owns CompositionPlayer lifetime, but visual parameters can be read
 * from the newest project without tearing down decoders every time a slider moves.
 */
internal object PreviewProjectRegistry {
    private val latest = AtomicReference<TimelineProject?>(null)

    fun update(project: TimelineProject) {
        latest.set(project)
    }

    fun project(): TimelineProject? = latest.get()

    fun clip(id: String): TimelineClip? = latest.get()?.clip(id)

    fun clear(project: TimelineProject? = null) {
        if (project == null) latest.set(null) else latest.compareAndSet(project, null)
    }
}
