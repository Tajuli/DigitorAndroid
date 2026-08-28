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

/**
 * One project codec for explicit save/load and undo/redo snapshots.
 *
 * Media URIs are persisted as strings; imported document URIs already receive persistable read
 * permission in EditorViewModelV4, so reopening a saved project can continue reading the source.
 *
 * Explicit Save Project writes two copies:
 * 1) a verified app-private snapshot used by fast restore/undo flows; and
 * 2) a user-visible .digitor.json backup in Downloads/Digitor Projects on Android 10+.
 *
 * The visible copy makes the Save Project action observable to the user instead of silently writing
 * only to SharedPreferences. On older Android versions we use the app-specific Documents directory
 * so saving still works without requesting legacy broad-storage permission.
 */
class ProjectStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    fun encode(project: TimelineProject): String = gson.toJson(project)

    fun decode(raw: String): TimelineProject = gson.fromJson(raw, TimelineProject::class.java)

    fun save(project: TimelineProject) {
        val raw = encode(project)
        val visibleResult = runCatching { writeVisibleBackup(project, raw) }
        val visibleUri = visibleResult.getOrNull()

        val committed = prefs.edit()
            .putString(KEY_LAST_PROJECT, raw)
            .apply {
                if (visibleUri != null) putString(KEY_LAST_PROJECT_URI, visibleUri.toString())
            }
            .commit()

        check(committed) { "Project could not be written to app storage" }
        check(prefs.getString(KEY_LAST_PROJECT, null) == raw) { "Project save verification failed" }

        val message = when {
            visibleUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "Project saved · Downloads/Digitor Projects"
            visibleUri != null -> "Project saved · Documents/Digitor Projects"
            else -> "Project saved in app · backup file unavailable"
        }
        showToast(message)
    }

    fun load(): TimelineProject? {
        val privateRaw = prefs.getString(KEY_LAST_PROJECT, null)
            ?.takeIf { it.isNotBlank() }
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

    fun clear() {
        prefs.edit().remove(KEY_LAST_PROJECT).remove(KEY_LAST_PROJECT_URI).commit()
    }

    private fun writeVisibleBackup(project: TimelineProject, raw: String): Uri? {
        val fileName = projectFileName(project)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloads(fileName, raw)
        } else {
            writeToLegacyAppDocuments(fileName, raw)
        }
    }

    private fun projectFileName(project: TimelineProject): String {
        val base = project.title.trim()
            .ifBlank { "Digitor_Project" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ')
            .ifBlank { "Digitor_Project" }
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
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
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
            resolver.openOutputStream(uri, "rwt")?.bufferedWriter()?.use { writer ->
                writer.write(raw)
            } ?: error("Could not open project file for writing")

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
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: appContext.filesDir
        val directory = File(root, PROJECT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeText(raw)
        return Uri.fromFile(file)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val PREFS_NAME = "digitor_projects_v1"
        const val KEY_LAST_PROJECT = "last_project"
        const val KEY_LAST_PROJECT_URI = "last_project_uri"
        const val PROJECT_DIRECTORY = "Digitor Projects"
        const val PROJECT_EXTENSION = ".digitor.json"
        const val PROJECT_MIME = "application/json"
    }
}
