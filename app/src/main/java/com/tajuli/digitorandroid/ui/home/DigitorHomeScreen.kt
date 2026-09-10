package com.tajuli.digitorandroid.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.RecentProjectSummary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HomeBackground = Color(0xFF08080A)
private val HomeCard = Color(0xFF151519)
private val HomeMuted = Color(0xFF96969E)
private val HomeAccent = Color(0xFF30E0C3)
private const val RECENT_THUMB_LONG_EDGE = 480

@Composable
fun DigitorHomeScreen(
    recentProjects: List<RecentProjectSummary>,
    onNewProject: (width: Int, height: Int) -> Unit,
    onOpenRecent: (projectId: String) -> Unit,
    onDeleteRecent: (projectId: String) -> Unit,
    onShareApp: () -> Unit,
) {
    var showProjectDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RecentProjectSummary?>(null) }

    if (showProjectDialog) {
        AlertDialog(
            onDismissRequest = { showProjectDialog = false },
            title = { Text("New project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose canvas ratio", fontSize = 13.sp, color = HomeMuted)
                    RatioChoice(
                        title = "16:9",
                        subtitle = "Landscape · YouTube / widescreen",
                        icon = { Icon(Icons.Rounded.Movie, null, modifier = Modifier.size(22.dp)) },
                        onClick = {
                            showProjectDialog = false
                            onNewProject(1920, 1080)
                        },
                    )
                    RatioChoice(
                        title = "9:16",
                        subtitle = "Portrait · Shorts / Reels / TikTok",
                        icon = { Icon(Icons.Rounded.Smartphone, null, modifier = Modifier.size(22.dp)) },
                        onClick = {
                            showProjectDialog = false
                            onNewProject(1080, 1920)
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showProjectDialog = false }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete project?") },
            text = { Text("${project.title} will be removed from Recent Projects.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteRecent(project.id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = HomeBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Digitor",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Create · Grade · Export",
                fontSize = 13.sp,
                color = HomeMuted,
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { showProjectDialog = true },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Text("New project", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onShareApp,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text("Share app", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Recent projects",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(10.dp))

            if (recentProjects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HomeCard, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    Column {
                        Text("No recent projects", color = Color.White, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Create a new project and it will appear here.", color = HomeMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 18.dp),
                ) {
                    items(recentProjects, key = { it.id }) { project ->
                        RecentProjectCard(
                            project = project,
                            onClick = { onOpenRecent(project.id) },
                            onDelete = { pendingDelete = project },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatioChoice(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        }
    }
}

@Composable
private fun RecentProjectCard(
    project: RecentProjectSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(project.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(.94f)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(HomeCard),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(HomeAccent.copy(alpha = .10f)),
        ) {
            RecentProjectThumbnail(
                project = project,
                modifier = Modifier.fillMaxSize(),
            )

            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Project options",
                        tint = Color.White,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete project") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = project.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val ratio = if (project.width >= project.height) "16:9" else "9:16"
            Text(
                text = "$ratio · ${project.width}×${project.height}",
                color = HomeMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatUpdated(project.updatedAtMs),
                color = HomeMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentProjectThumbnail(
    project: RecentProjectSummary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = project.thumbnailUri,
        key2 = project.thumbnailTimeUs,
    ) {
        value = withContext(Dispatchers.IO) {
            loadVideoThumbnail(context, project.thumbnailUri, project.thumbnailTimeUs)
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "${project.title} video thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = HomeAccent,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

private fun loadVideoThumbnail(
    context: Context,
    uriText: String?,
    sourceTimeUs: Long,
): Bitmap? {
    if (uriText.isNullOrBlank()) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, Uri.parse(uriText))
        val safeTimeUs = sourceTimeUs.coerceAtLeast(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(2)
                ?: RECENT_THUMB_LONG_EDGE
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(2)
                ?: RECENT_THUMB_LONG_EDGE
            val longest = maxOf(width, height).coerceAtLeast(1)
            val scale = (RECENT_THUMB_LONG_EDGE.toFloat() / longest.toFloat()).coerceAtMost(1f)
            retriever.getScaledFrameAtTime(
                safeTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).toInt().coerceAtLeast(2),
                (height * scale).toInt().coerceAtLeast(2),
            )
        } else {
            retriever.getFrameAtTime(safeTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatUpdated(timestampMs: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
}.getOrDefault("")
