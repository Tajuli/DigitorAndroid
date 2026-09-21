package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisRuntimeV66
import com.tajuli.digitorandroid.editor.processing.hasPersonCutoutCoverageV43

/** Clip-wide portrait optics share Pro Cutout's durable analysis, not a node color preset. */
@Composable
fun PortraitLensBlurControlsV99(clip: TimelineClip, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val settings = clip.resolvedCutoutV43()
    val active = settings.mode == CutoutModeV43.PERSON && settings.portraitLensBlurV99
    val runtime by CutoutAnalysisRuntimeV66.state.collectAsState()
    val busy = runtime.busy
    val thisBusy = busy && runtime.clipId == clip.id
    val context = LocalContext.current.applicationContext
    val ready = active && hasPersonCutoutCoverageV43(context, clip)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Portrait Lens Blur", fontSize = 16.sp)
        Text("Keep the person sharp with a soft, circular background blur. Applies to the whole clip.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = !busy, onClick = {
                vm.setSelectedCutoutV43(
                    settings.copy(mode = CutoutModeV43.NONE, portraitLensBlurV99 = false),
                    status = "Portrait Lens Blur off", coalesce = false,
                )
            }) { Text("None") }
            Button(enabled = !busy && !active, onClick = {
                vm.setSelectedCutoutV43(
                    settings.copy(
                        mode = CutoutModeV43.PERSON, portraitLensBlurV99 = true,
                        lensBlurAmountV99 = .55f, mattingSizeV69 = 256,
                        analysisQualityV47 = CutoutAnalysisQualityV47.HIGH,
                    ),
                    status = "Portrait Lens Blur · preparing subject", coalesce = false,
                )
                vm.analyzeSelectedPersonCutoutV43()
            }) { Text(if (active) "Applied" else "Apply & Analyze") }
        }
        if (active) {
            Text("Blur strength · ${(settings.lensBlurAmountV99 * 100).toInt()}%")
            Slider(value = settings.lensBlurAmountV99, onValueChange = {
                vm.setSelectedCutoutV43(settings.copy(lensBlurAmountV99 = it), status = "Portrait blur strength updated")
            }, valueRange = 0f..1f)
            Text(when {
                thisBusy -> "Preparing subject · ${runtime.savedFrames} frames processed"
                ready -> "Ready · full clip analyzed"
                else -> "Analysis incomplete. Unprocessed frames stay unchanged; finish analysis before final export."
            }, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy || thisBusy, onClick = {
                    if (thisBusy) vm.pauseSelectedPersonCutoutV66() else vm.analyzeSelectedPersonCutoutV43()
                }) { Text(if (thisBusy) "Pause" else if (ready) "Analyze again" else "Analyze / Resume") }
                if (thisBusy) {
                    OutlinedButton(onClick = { vm.cancelSelectedPersonCutoutV69() }) { Text("Cancel") }
                }
            }
            Text("Motion-safe person crop first, then 256 px analysis, every frame. Analysis can take time. More analysis quality options are in Edit → Cutout.", fontSize = 11.sp)
        }
        Text("For people. Hair, motion blur and complex backgrounds depend on the matte quality. This effect replaces background removal or chroma key on this clip.", fontSize = 11.sp)
    }
}
