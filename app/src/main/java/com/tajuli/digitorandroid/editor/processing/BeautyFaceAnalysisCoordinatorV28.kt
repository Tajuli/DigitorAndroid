package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.TimelineClip
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-process coordinator so face analysis is not cancelled when the user leaves the Filters panel. */
object BeautyFaceAnalysisCoordinatorV28 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun ensure(context: Context, clip: TimelineClip) {
        val appContext = context.applicationContext
        if (BeautyFaceTrackStoreV28.hasCoverage(appContext, clip)) return
        val key = "${clip.uri}|${clip.sourceInUs}|${clip.sourceOutUs}"
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                BeautyFaceAnalyzerV28(appContext).analyzeAndStore(clip)
            } finally {
                inFlight.remove(key)
            }
        }
    }
}
