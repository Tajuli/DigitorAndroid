package com.tajuli.digitorandroid.editor.model

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Lightweight metadata rendered on the Home screen. */
data class RecentProjectSummary(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val updatedAtMs: Long,
)

/**
 * Bridge between the existing editor Save button and the app-level naming dialog.
 * Keeping this outside the editor UI lets both compact and labeled Save actions use the same flow.
 */
object ProjectSaveCoordinator {
    private val _requests = MutableSharedFlow<TimelineProject>(extraBufferCapacity = 4)
    val requests: SharedFlow<TimelineProject> = _requests.asSharedFlow()

    fun request(project: TimelineProject) {
        _requests.tryEmit(project)
    }
}

/**
 * Project persistence shared by the editor history and the Home screen.
 *
 * Auto-save writes only the internal recovery snapshot/recent-project entry. Explicit Save Project
 * asks for a user name and additionally creates a user-visible .digitor.json copy in
 * Downloads/Digitor Projects on Android 10+.
 */
class ProjectStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    fun encode(project: TimelineProject): String = gson.toJson(project)

    fun decode(raw: String): TimelineProject = gson.fromJson(raw, TimelineProject::class.java)

    /** Creates and selects a fresh project so the editor ViewModel opens it immediately. */
    fun createNewProject(width: Int, height: Int, title: String = defaultProjectTitle()): TimelineProject {
        require(width > 0 && height > 0)
        val project = TimelineProject(title = title, width = width, height = height)
        val raw = encode(project)
        val id = UUID.randomUUID().toString()
        val recents = upsertRecent(recentProjectsInternal(), id, project, System.currentTimeMillis())
        val committed = prefs.edit()
            .putString(KEY_CURRENT_PROJECT_ID, id)
            .remove(KEY_CURRENT_PROJECT_TITLE_OVERRIDE)
            .putString(KEY_LAST_PROJECT, raw)
            .putString(projectKey(id), raw)
            .putString(KEY_RECENT_INDEX, gson.toJson(recents))
            .commit()
        check(committed) { "Could not create project" }
        return project
    }

    /** Selects one recent project as the active editor project. */
    fun openRecentProject(id: String): TimelineProject? {
        val raw = prefs.getString(projectKey(id), null)?.takeIf { it.isNotBlank() } ?: return null
        val project = runCatching { decode(raw) }.getOrNull() ?: return null
        val recents = upsertRecent(recentProjectsInternal(), id, project, System.currentTimeMillis())
        val committed = prefs.edit()
            .putString(KEY_CURRENT_PROJECT_ID, id)
            .putString(KEY_CURRENT_PROJECT_TITLE_OVERRIDE, project.title)
            .putString(KEY_LAST_PROJECT, raw)
            .putString(KEY_RECENT_INDEX, gson.toJson(recents))
            .commit()
        return project.takeIf { committed }
    }

    fun recentProjects(limit: Int = MAX_RECENTS): List<RecentProjectSummary> =
        recentProjectsInternal().sortedByDescending { it.updatedAtMs }.take(limit.coerceAtLeast(0))

    /**
     * Existing editor Save action lands here. Do not silently choose a name: emit a request for the
     * app-level naming dialog. The internal recovery snapshot is already maintained by autoSave().
     */
    fun save(project: TimelineProject) {
        ProjectSaveCoordinator.request(project)
    }

    /**
     * Explicit user-confirmed save. The entered name becomes the canonical title used by Recent
     * Projects and the visible backup filename.
     */
    fun saveNamed(project: TimelineProject, requestedName: String): TimelineProject {
        val name = sanitizeProjectTitle(requestedName)
        require(name.isNotBlank()) { "Project name is required" }
        val namedProject = project.copy(title = name)
        val raw = encode(namedProject)
        val visibleResult = runCatching { writeVisibleBackup(namedProject, raw) }
        val visibleUri = visibleResult.getOrNull()
        val id = currentProjectIdOrCreate()
        val recents = upsertRecent(recentProjectsInternal(), id, namedProject, System.currentTimeMillis())

        val committed = prefs.edit()
            .putString(KEY_CURRENT_PROJECT_ID, id)
            .putString(KEY_CURRENT_PROJECT_TITLE_OVERRIDE, name)
            .putString(KEY_LAST_PROJECT, raw)
            .putString(projectKey(id), raw)
            .putString(KEY_RECENT_INDEX, gson.toJson(recents))
            .apply {
                if (visibleUri != null) putString(KEY_LAST_PROJECT_URI, visibleUri.toString())
            }
            .commit()

        check(committed) { "Project could not be written to app storage" }
        check(prefs.getString(projectKey(id), null) == raw) { "Project save verification failed" }

        val message = when {
            visibleUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "Project saved · Downloads/Digitor Projects"
            visibleUri != null -> "Project saved · Documents/Digitor Projects"
            else -> "Project saved · backup file unavailable"
        }
        showToast(message)
        return namedProject
    }

    /**
     * Crash/close recovery save. This intentionally does not create a Downloads file every time a
     * slider or timeline edit changes. If the project has already been explicitly named, preserve
     * that canonical name even when the current in-memory editor instance still has the old title.
     */
    fun autoSave(project: TimelineProject): TimelineProject {
        val id = currentProjectIdOrCreate()
        val titleOverride = prefs.getString(KEY_CURRENT_PROJECT_TITLE_OVERRIDE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val recoveryProject = if (titleOverride != null && project.title != titleOverride) {
            project.copy(title = titleOverride)
        } else {
            project
        }
        val raw = encode(recoveryProject)
        val recents = upsertRecent(recentProjectsInternal(), id, recoveryProject, System.currentTimeMillis())
        val committed = prefs.edit()
            .putString(KEY_CURRENT_PROJECT_ID, id)
            .putString(KEY_LAST_PROJECT, raw)
            .putString(projectKey(id), raw)
            .putString(KEY_RECENT_INDEX, gson.toJson(recents))
            .commit()
        check(committed) { "Auto-save failed" }
        return recoveryProject
    }

    /** Loads the currently selected project, falling back to the last snapshot/visible backup. */
    fun load(): TimelineProject? {
        val currentId = prefs.getString(KEY_CURRENT_PROJECT_ID, null)?.takeIf { it.isNotBlank() }
        if (currentId != null) {
            prefs.getString(projectKey(currentId), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { decode(raw) }.getOrNull() }
                ?.let { return it }
        }

        val privateRaw = prefs.getString(KEY_LAST_PROJECT, null)?.takeIf { it.isNotBlank() }
        if (privateRaw != null) {
            runCatching { decode(privateRaw) }.getOrNull()?.let { return it }
        }

        val backupUri = prefs.getString(KEY_LAST_PROJECT_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: return null
        val backupRaw = runCatching {
            appContext.contentResolver.openInputStream(backupUri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: return null
        return runCatching { decode(backupRaw) }.getOrNull()
    }

    /** Clears the active selection without deleting the Recent Projects library. */
    fun clear() {
        prefs.edit()
            .remove(KEY_CURRENT_PROJECT_ID)
            .remove(KEY_CURRENT_PROJECT_TITLE_OVERRIDE)
            .remove(KEY_LAST_PROJECT)
            .remove(KEY_LAST_PROJECT_URI)
            .commit()
    }

    private fun currentProjectIdOrCreate(): String =
        prefs.getString(KEY_CURRENT_PROJECT_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { id ->
                check(prefs.edit().putString(KEY_CURRENT_PROJECT_ID, id).commit()) { "Could not select project" }
            }

    private fun recentProjectsInternal(): List<RecentProjectSummary> {
        val raw = prefs.getString(KEY_RECENT_INDEX, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            gson.fromJson(raw, Array<RecentProjectSummary>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && prefs.contains(projectKey(it.id)) }
    }

    private fun upsertRecent(
        current: List<RecentProjectSummary>,
        id: String,
        project: TimelineProject,
        updatedAtMs: Long,
    ): List<RecentProjectSummary> {
        val next = RecentProjectSummary(id, project.title, project.width, project.height, updatedAtMs)
        return (listOf(next) + current.filterNot { it.id == id })
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_RECENTS)
    }

    private fun writeVisibleBackup(project: TimelineProject, raw: String): Uri? {
        val fileName = projectFileName(project)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloads(fileName, raw)
        } else {
            writeToLegacyAppDocuments(fileName, raw)
        }
    }

    private fun sanitizeProjectTitle(value: String): String = value
        .trim()
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(MAX_PROJECT_TITLE_LENGTH)
        .trim('.', ' ')

    private fun projectFileName(project: TimelineProject): String {
        val base = sanitizeProjectTitle(project.title).ifBlank { "Digitor_Project" }
        return if (base.endsWith(PROJECT_EXTENSION, ignoreCase = true)) base else "$base$PROJECT_EXTENSION"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(fileName: String, raw: String): Uri {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$PROJECT_DIRECTORY/"

        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }

        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, PROJECT_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("Could not create project file in Downloads")

        try {
            resolver.openOutputStream(uri, "rwt")?.bufferedWriter()?.use { writer -> writer.write(raw) }
                ?: error("Could not open project file for writing")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            if (existing == null) runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun writeToLegacyAppDocuments(fileName: String, raw: String): Uri? {
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir
        val directory = File(root, PROJECT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(raw)
        return Uri.fromFile(file)
    }

    private fun defaultProjectTitle(): String {
        val stamp = SimpleDateFormat("dd MMM yyyy HH-mm", Locale.getDefault()).format(Date())
        return "Project $stamp"
    }

    private fun projectKey(id: String): String = "$KEY_PROJECT_PREFIX$id"

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val PREFS_NAME = "digitor_projects_v1"
        const val KEY_LAST_PROJECT = "last_project"
        const val KEY_LAST_PROJECT_URI = "last_project_uri"
        const val KEY_CURRENT_PROJECT_ID = "current_project_id"
        const val KEY_CURRENT_PROJECT_TITLE_OVERRIDE = "current_project_title_override"
        const val KEY_RECENT_INDEX = "recent_project_index"
        const val KEY_PROJECT_PREFIX = "project_"
        const val PROJECT_DIRECTORY = "Digitor Projects"
        const val PROJECT_EXTENSION = ".digitor.json"
        const val PROJECT_MIME = "application/json"
        const val MAX_RECENTS = 12
        const val MAX_PROJECT_TITLE_LENGTH = 80
    }
}
