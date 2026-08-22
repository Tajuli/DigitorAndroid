package com.tajuli.digitorandroid.editor.processing

import android.app.ActivityManager
import android.content.Context

class DeviceCapabilityProbe(private val context: Context) {
    fun supportsGpuEditing(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val req = activityManager.deviceConfigurationInfo.reqGlEsVersion
        // Media3 graphical effects use OpenGL. ES 3.x gives us a conservative GPU-first gate.
        return req >= 0x00030000
    }
}
