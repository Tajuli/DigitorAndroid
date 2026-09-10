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

# MediaPipe Tasks uses generated protobuf-lite messages whose private backing fields are resolved by
# generated/runtime schema code at runtime. R8 renaming/stripping those fields breaks HairSegmenter
# with errors such as "Field hostEnvironment_ ... MediaPipeLoggingProto$SystemInfo not found".
# Keep MediaPipe's generated proto classes/members intact while still allowing the rest of the app
# and dependencies to be minified/shrunk.
-keep class com.google.mediapipe.proto.** { *; }
-keep class com.google.mediapipe.tasks.**.proto.** { *; }

# The protobuf-lite runtime also contains well-known messages (for example com.google.protobuf.Any)
# whose generated message-info/schema metadata is discovered at runtime. R8 shrinking/obfuscation can
# otherwise leave MediaPipe with "Unable to get message info for com.google.protobuf.Any" even when
# MediaPipe's own proto package is kept. Preserve the protobuf runtime and its generated messages.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Gson, protobuf-lite and Kotlin generic/reflection metadata used by persisted collections/runtime.
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
