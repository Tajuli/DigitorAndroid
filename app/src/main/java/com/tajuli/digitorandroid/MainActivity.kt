package com.tajuli.digitorandroid

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tajuli.digitorandroid.editor.model.ProjectSaveCoordinator
import com.tajuli.digitorandroid.editor.model.ProjectStore
import com.tajuli.digitorandroid.editor.model.TimelineProject
import com.tajuli.digitorandroid.editor.processing.CutoutAnalysisPowerGuardV48
import com.tajuli.digitorandroid.ui.editor.ActiveEditorVmRegistryV14
import com.tajuli.digitorandroid.ui.editor.DigitorEditorScreenV8
import com.tajuli.digitorandroid.ui.editor.EditorViewModelV4
import com.tajuli.digitorandroid.ui.home.DigitorHomeScreen
import com.tajuli.digitorandroid.ui.theme.DigitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val projectStore by lazy { ProjectStore(applicationContext) }
    @Volatile private var latestEditorProject: TimelineProject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the display awake for an active Pro Cutout analysis. If the user explicitly turns the
        // screen off, CutoutAnalysisPowerGuardV48's PARTIAL_WAKE_LOCK still keeps analysis runnable.
        lifecycleScope.launch {
            CutoutAnalysisPowerGuardV48.active.collectLatest { active ->
                if (active) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            DigitorTheme {
                val scope = rememberCoroutineScope()
                var destination by rememberSaveable { mutableStateOf(DESTINATION_HOME) }
                var editorSession by rememberSaveable { mutableLongStateOf(0L) }
                var recentRefresh by remember { mutableIntStateOf(0) }
                var pendingSaveProject by remember { mutableStateOf<TimelineProject?>(null) }
                var projectName by remember { mutableStateOf("") }
                var savingNamedProject by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    ProjectSaveCoordinator.requests.collect { project ->
                        pendingSaveProject = project
                        projectName = projectStore.load()?.title
                            ?.takeIf { it.isNotBlank() }
                            ?: project.title
                    }
                }

                if (pendingSaveProject != null) {
                    AlertDialog(
                        onDismissRequest = {
                            if (!savingNamedProject) pendingSaveProject = null
                        },
                        title = { Text("Save project") },
                        text = {
                            OutlinedTextField(
                                value = projectName,
                                onValueChange = { projectName = it.take(80) },
                                label = { Text("Project name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            Button(
                                enabled = projectName.trim().isNotEmpty() && !savingNamedProject,
                                onClick = {
                                    val request = pendingSaveProject ?: return@Button
                                    savingNamedProject = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                projectStore.saveNamed(request, projectName)
                                            }
                                        }.onSuccess { namedProject ->
                                            latestEditorProject = namedProject
                                            pendingSaveProject = null
                                            recentRefresh++
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                this@MainActivity,
                                                error.message ?: "Project save failed",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        savingNamedProject = false
                                    }
                                },
                            ) { Text(if (savingNamedProject) "Saving…" else "Save") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !savingNamedProject,
                                onClick = { pendingSaveProject = null },
                            ) { Text("Cancel") }
                        },
                    )
                }

                fun returnToHome() {
                    latestEditorProject?.let { project ->
                        runCatching { projectStore.autoSave(project) }
                    }
                    destination = DESTINATION_HOME
                    latestEditorProject = null
                    recentRefresh++
                }

                BackHandler(enabled = destination == DESTINATION_EDITOR) {
                    returnToHome()
                }

                when (destination) {
                    DESTINATION_EDITOR -> {
                        val editorVm: EditorViewModelV4 = viewModel(key = "editor-session-$editorSession")
                        ActiveEditorVmRegistryV14.bind(editorVm)
                        val editorState by editorVm.state.collectAsState()
                        latestEditorProject = editorState.project

                        // Short debounce keeps slider/drag edits cheap while still providing fast crash recovery.
                        LaunchedEffect(editorState.project) {
                            delay(AUTOSAVE_DEBOUNCE_MS)
                            val snapshot = editorState.project
                            runCatching {
                                withContext(Dispatchers.IO) { projectStore.autoSave(snapshot) }
                            }
                        }

                        DigitorEditorScreenV8(
                            vm = editorVm,
                            onHome = ::returnToHome,
                        )
                    }

                    else -> {
                        ActiveEditorVmRegistryV14.clear()
                        latestEditorProject = null
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

    /** Flush the latest editor state when Android backgrounds/stops the Activity. */
    override fun onStop() {
        latestEditorProject?.let { project ->
            runCatching { projectStore.autoSave(project) }
        }
        super.onStop()
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
        const val AUTOSAVE_DEBOUNCE_MS = 250L
    }
}
