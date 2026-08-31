package com.tajuli.digitorandroid.editor.render

import android.content.Context

/** App-scoped context bridge used only to decode persisted image overlay URIs during Media3 export. */
internal object VisualOverlayRenderEnvironmentV19 {
    @Volatile private var context: Context? = null

    fun install(appContext: Context) {
        context = appContext.applicationContext
    }

    fun contextOrNull(): Context? = context
}
