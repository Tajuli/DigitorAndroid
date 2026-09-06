# DigitorAndroid R8 / ProGuard rules.
#
# The phone CI build is minified to keep APK size down. Some editor entry points are created by
# AndroidX through reflection and the project model is serialized/deserialized by Gson, so those
# runtime contracts must keep stable constructors/field names.

# JNI entry points use this exact Kotlin object/class name.
-keep class com.tajuli.digitorandroid.editor.processing.NcnnVulkanNativeV52 { *; }

# AndroidX ViewModelProvider creates this AndroidViewModel through its Application constructor.
# Keep the class/constructor so opening a freshly-created project cannot fail only in minified APKs.
-keep class com.tajuli.digitorandroid.ui.editor.EditorViewModelV4 { *; }

# ProjectStore persists these Kotlin data classes/enums with reflection-based Gson. Keep the model
# names and members stable across minified builds so a new project, autosave and recent-project load
# use the same schema as debug/non-minified builds.
-keep class com.tajuli.digitorandroid.editor.model.** { *; }

# Gson and Kotlin generic/reflection metadata used by persisted collections.
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
