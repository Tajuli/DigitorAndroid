package com.tajuli.digitorandroid.ui.editor

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.preview.MultitrackAudioPreviewEngine
import com.tajuli.digitorandroid.editor.processing.ExportFrameRateV72
import com.tajuli.digitorandroid.editor.processing.ExportProgress
import com.tajuli.digitorandroid.editor.processing.ExportQuality
import com.tajuli.digitorandroid.editor.processing.ExportResolutionV72
import com.tajuli.digitorandroid.editor.processing.ExportSettingsV72
import com.tajuli.digitorandroid.editor.processing.ProcessingRouter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private val ExportMuted = Color(0xFF909098)

internal suspend fun runEditorExport(
    context: Context,
    router: ProcessingRouter,
    audioPreview: MultitrackAudioPreviewEngine,
    project: TimelineProject,
    cursorUs: Long,
    destination: Uri,
    settings: ExportSettingsV72,
    onFraction: (Float?) -> Unit,
    onStatus: (String) -> Unit,
    onPreviewStatus: (String) -> Unit,
) {
    if (project.durationUs <= 0L) {
        onFraction(null)
        onStatus("Timeline is empty")
        return
    }

    val resolvedExport = settings.applyTo(project)
    val exportHasAudio = project.tracks.any {
        it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty()
    }
    var latestFraction = 0f

    runCatching { audioPreview.suspendForExternalWork() }
    onFraction(0f)
    onStatus(
        "Preparing ${resolvedExport.width}×${resolvedExport.height} · ${resolvedExport.frameRate} fps · ${settings.quality.label}",
    )

    val temp = File(context.cacheDir, "digitor_export_${System.currentTimeMillis()}.mp4")
    try {
        val result = router.export(project, temp, settings) { progress ->
            if (progress is ExportProgress.Stage) {
                onStatus(progress.name)
                progress.fraction?.coerceIn(0f, 1f)?.let { fraction ->
                    latestFraction = fraction
                    onFraction(fraction)
                }
            }
        }

        latestFraction = max(latestFraction, .99f)
        onFraction(latestFraction)
        onStatus("Saving file…")
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                temp.inputStream().use { it.copyTo(output, 1024 * 1024) }
            } ?: error("Could not open selected save location")
        }
        onFraction(1f)
        onStatus("Saved · ${result.backend} · ${settings.description(project)}")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFraction(null)
        onStatus(error.message ?: "Export failed")
    } finally {
        temp.delete()
        if (exportHasAudio) {
            try {
                val maxStartUs = (project.durationUs - 1L).coerceAtLeast(0L)
                audioPreview.rebuild(
                    project,
                    cursorUs.coerceIn(0L, maxStartUs) / 1000L,
                    resumePlayback = false,
                )
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                onPreviewStatus("Audio preview: ${error.message ?: "unavailable"}")
            }
        }
    }
}

@Composable
internal fun EditorExportDialog(
    project: TimelineProject,
    name: String,
    quality: ExportQuality,
    resolution: ExportResolutionV72,
    frameRate: ExportFrameRateV72,
    onNameChange: (String) -> Unit,
    onQualityChange: (ExportQuality) -> Unit,
    onResolutionChange: (ExportResolutionV72) -> Unit,
    onFrameRateChange: (ExportFrameRateV72) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = ExportSettingsV72(
        quality = quality,
        resolution = resolution,
        frameRate = frameRate,
    )
    val resolvedExport = settings.applyTo(project)
    val targetMbps = quality.videoBitrate(
        resolvedExport.width,
        resolvedExport.height,
        resolvedExport.frameRate,
    ) / 1_000_000f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Resolution", fontSize = 10.sp, color = ExportMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExportResolutionV72.entries.forEach { item ->
                        AssistChip(
                            onClick = { onResolutionChange(item) },
                            label = {
                                Text(
                                    if (resolution == item) "✓ ${item.label}" else item.label,
                                    fontSize = 9.sp,
                                )
                            },
                        )
                    }
                }

                Text("Frame rate", fontSize = 10.sp, color = ExportMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExportFrameRateV72.entries.forEach { item ->
                        AssistChip(
                            onClick = { onFrameRateChange(item) },
                            label = {
                                Text(
                                    if (frameRate == item) "✓ ${item.label}" else item.label,
                                    fontSize = 9.sp,
                                )
                            },
                        )
                    }
                }

                Text("Quality", fontSize = 10.sp, color = ExportMuted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ExportQuality.entries.forEach { item ->
                        AssistChip(
                            onClick = { onQualityChange(item) },
                            label = {
                                Text(
                                    if (quality == item) "✓ ${item.label}" else item.label,
                                    fontSize = 9.sp,
                                )
                            },
                        )
                    }
                }

                Text(
                    "${resolvedExport.width}×${resolvedExport.height} · ${resolvedExport.frameRate} fps · %.1f Mbps H.264 target".format(targetMbps),
                    fontSize = 9.sp,
                    color = ExportMuted,
                )
                Text("File type", fontSize = 10.sp, color = ExportMuted)
                AssistChip(onClick = {}, label = { Text("MP4 · H.264 / AAC") })
                Text(
                    "For video sources, FPS is a maximum: higher-FPS footage is reduced cleanly; lower-FPS footage is not fake-interpolated.",
                    fontSize = 8.sp,
                    color = ExportMuted,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val base = name.trim().ifEmpty { "Digitor_export" }.removeSuffix(".mp4")
                onConfirm(base)
            }) {
                Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Choose location")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
