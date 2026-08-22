package com.tajuli.digitorandroid.editor.processing

import android.app.ActivityManager
import android.content.Context

class DeviceCapabilityProbe(private val context: Context) {
    val requestedGlEsVersion: Int
        get() {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.deviceConfigurationInfo.reqGlEsVersion
        }

    /**
     * Media3 Transformer performs graphical modifications through OpenGL.
     * Do not require ES 3.x here: an Android device exposing GLES 2.0+ has the
     * graphics path needed for the GPU-first editor pipeline.
     */
    fun supportsGpuEditing(): Boolean = requestedGlEsVersion >= 0x00020000

    fun gpuDescription(): String {
        val version = requestedGlEsVersion
        val major = (version shr 16) and 0xffff
        val minor = version and 0xffff
        return if (supportsGpuEditing()) "OpenGL ES $major.$minor" else "No OpenGL ES 2.0+ GPU"
    }
}
