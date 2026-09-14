package com.tajuli.digitorandroid.editor.processing

/**
 * Thin JNI surface over the pinned whisper.cpp runtime built in app/src/main/cpp.
 * The native library contains ggml Vulkan when the Android NDK host tools are available and always
 * retains the CPU backend for device/driver fallback.
 */
internal object WhisperCppNativeV78 {
    init {
        System.loadLibrary("digitor_whispercpp")
    }

    external fun createContext(modelPath: String, useGpu: Boolean): Long

    /**
     * Each returned string is `startCentiseconds<TAB>endCentiseconds<TAB>text`.
     * whisper.cpp owns the language detection and timestamps; Kotlin only maps them onto the editor timeline.
     */
    external fun transcribeSegments(
        contextPtr: Long,
        audioData: FloatArray,
        threadCount: Int,
        bestOf: Int,
    ): Array<String>

    external fun freeContext(contextPtr: Long)

    external fun systemInfo(): String
}
