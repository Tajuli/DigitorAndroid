package com.tajuli.digitorandroid.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.RecentProjectSummary
import java.text.DateFormat
import java.util.Date

private val HomeBackground = Color(0xFF08080A)
private val HomeCard = Color(0xFF151519)
private val HomeMuted = Color(0xFF96969E)
private val HomeAccent = Color(0xFF30E0C3)

@Composable
fun DigitorHomeScreen(
    recentProjects: List<RecentProjectSummary>,
    onNewProject: (width: Int, height: Int) -> Unit,
    onOpenRecent: (projectId: String) -> Unit,
    onShareApp: () -> Unit,
) {
    var showProjectDialog by remember { mutableStateOf(false) }

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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recentProjects, key = { it.id }) { project ->
                        RecentProjectRow(project = project, onClick = { onOpenRecent(project.id) })
                    }
                    item { Spacer(Modifier.height(18.dp)) }
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
private fun RecentProjectRow(project: RecentProjectSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(HomeCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(HomeAccent.copy(alpha = .12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Movie, null, tint = HomeAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = project.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val ratio = if (project.width >= project.height) "16:9" else "9:16"
            Text(
                text = "$ratio · ${project.width}×${project.height} · ${formatUpdated(project.updatedAtMs)}",
                color = HomeMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatUpdated(timestampMs: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))
}.getOrDefault("")
