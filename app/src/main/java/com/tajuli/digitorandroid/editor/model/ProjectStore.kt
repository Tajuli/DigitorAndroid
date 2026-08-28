package com.tajuli.digitorandroid.editor.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * One project codec for explicit save/load and undo/redo snapshots.
 *
 * Media URIs are persisted as strings; imported document URIs already receive persistable read
 * permission in EditorViewModelV4, so reopening a saved project can continue reading the source.
 */
class ProjectStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    fun encode(project: TimelineProject): String = gson.toJson(project)

    fun decode(raw: String): TimelineProject = gson.fromJson(raw, TimelineProject::class.java)

    fun save(project: TimelineProject) {
        prefs.edit().putString(KEY_LAST_PROJECT, encode(project)).apply()
    }

    fun load(): TimelineProject? = prefs.getString(KEY_LAST_PROJECT, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { decode(raw) }.getOrNull() }

    fun clear() {
        prefs.edit().remove(KEY_LAST_PROJECT).apply()
    }

    private companion object {
        const val PREFS_NAME = "digitor_projects_v1"
        const val KEY_LAST_PROJECT = "last_project"
    }
}
