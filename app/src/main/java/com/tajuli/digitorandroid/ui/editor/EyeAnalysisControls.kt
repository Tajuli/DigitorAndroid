package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.processing.EyeTrackStore
import com.tajuli.digitorandroid.editor.processing.EyeTrackingAnalyzer
import kotlinx.coroutines.*

@Composable
internal fun EyeAnalysisControls(clip: TimelineClip, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var job by remember(clip.uri, clip.sourceInUs, clip.sourceOutUs) { mutableStateOf<Job?>(null) }
    var percent by remember(clip.id) { mutableIntStateOf(0) }
    var message by remember(clip.id) { mutableStateOf("Analyze eyes before applying an Eyes effect. Keep one face clearly visible.") }
    var ready by remember(clip.uri, clip.sourceInUs, clip.sourceOutUs) { mutableStateOf(false) }
    LaunchedEffect(clip.uri, clip.sourceInUs, clip.sourceOutUs) {
        ready = withContext(Dispatchers.IO) { EyeTrackStore.load(context, clip)?.covers(clip) == true }
        if (ready) message = "Done · Eyes tracked"
        onReady(ready)
    }
    DisposableEffect(clip.uri, clip.sourceInUs, clip.sourceOutUs) { onDispose { job?.cancel() } }
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (job != null) "$percent%" else message, color = Color.White, fontSize = 10.sp)
        Text("24 fps tracking · blink aware · follows head tilt", color = Color(0xFF909098), fontSize = 8.sp)
        if (job != null) {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") }
        } else if (!ready) {
            FilledTonalButton(onClick = {
                percent = 0
                job = scope.launch {
                    try {
                        EyeTrackingAnalyzer(context).analyze(clip) { value ->
                            scope.launch { percent = value }
                        }
                        ready = true
                        message = "Done · Eyes tracked"
                        onReady(true)
                    } catch (cancelled: CancellationException) {
                        message = "Analysis cancelled. Select Analyze Eyes to retry."
                        throw cancelled
                    } catch (error: Exception) {
                        message = error.message ?: "Eye analysis failed. Please retry."
                    } finally { job = null }
                }
            }) { Text("Analyze Eyes") }
        }
    }
}
