# DigitorAndroid - keep rules intentionally minimal for the MVP.

# JNI entry points use this exact Kotlin object/class name. Keep it stable in minified builds.
-keep class com.tajuli.digitorandroid.editor.processing.NcnnVulkanNativeV52 { *; }
