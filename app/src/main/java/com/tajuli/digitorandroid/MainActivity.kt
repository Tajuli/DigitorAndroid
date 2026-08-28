package com.tajuli.digitorandroid

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.ui.editor.DigitorEditorScreenV7
import com.tajuli.digitorandroid.ui.editor.EditorViewModelV4
import com.tajuli.digitorandroid.ui.home.DigitorHomeScreen
import com.tajuli.digitorandroid.ui.theme.DigitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DigitorTheme {
                val projectStore = remember { ProjectStore(applicationContext) }
                var destination by rememberSaveable { mutableStateOf(DESTINATION_HOME) }
                var editorSession by rememberSaveable { mutableLongStateOf(0L) }
                var recentRefresh by remember { mutableIntStateOf(0) }

                BackHandler(enabled = destination == DESTINATION_EDITOR) {
                    destination = DESTINATION_HOME
                    recentRefresh++
                }

                when (destination) {
                    DESTINATION_EDITOR -> {
                        val editorVm: EditorViewModelV4 = viewModel(key = "editor-session-$editorSession")
                        DigitorEditorScreenV7(vm = editorVm)
                    }

                    else -> {
                        val recents = remember(recentRefresh) { projectStore.recentProjects() }
                        DigitorHomeScreen(
                            recentProjects = recents,
                            onNewProject = { width, height ->
                                runCatching { projectStore.createNewProject(width, height) }
                                    .onSuccess {
                                        editorSession++
                                        destination = DESTINATION_EDITOR
                                    }
                                    .onFailure { error ->
                                        Toast.makeText(
                                            this@MainActivity,
                                            error.message ?: "Could not create project",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            },
                            onOpenRecent = { projectId ->
                                val opened = runCatching { projectStore.openRecentProject(projectId) }.getOrNull()
                                if (opened != null) {
                                    editorSession++
                                    destination = DESTINATION_EDITOR
                                } else {
                                    Toast.makeText(this@MainActivity, "Project could not be opened", Toast.LENGTH_SHORT).show()
                                    recentRefresh++
                                }
                            },
                            onShareApp = { shareApp() },
                        )
                    }
                }
            }
        }
    }

    private fun shareApp() {
        val storeUrl = "https://play.google.com/store/apps/details?id=$packageName"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Digitor")
            putExtra(Intent.EXTRA_TEXT, "Create and edit videos with Digitor.\n$storeUrl")
        }
        startActivity(Intent.createChooser(intent, "Share Digitor"))
    }

    private companion object {
        const val DESTINATION_HOME = "home"
        const val DESTINATION_EDITOR = "editor"
    }
}
