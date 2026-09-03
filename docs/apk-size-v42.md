# APK size strategy V42

PR #48 introduced MediaPipe Tasks Vision, ML Kit Face Detection, and bundled semantic beauty models. A universal debug APK therefore packages the ML native libraries once per ABI.

V42 keeps all editor and beauty behavior while making CI's downloadable phone APK arm64-only via `-PdigitorAbi=arm64-v8a`. Emulator tests explicitly use `-PdigitorAbi=x86_64`, so semantic beauty instrumentation remains covered.

The dedicated `phone` build type stays behavior-identical to debug and uses debug signing. It intentionally does **not** enable R8 or resource shrinking: a prior debuggable + minified experiment produced a smaller APK but AGP warned that combination is unsupported and a real-device New Project crash was reported. The safe phone APK therefore reduces size only by packaging one requested ABI.

Normal local debug builds without `-PdigitorAbi` remain universal and unminified for development. Release builds enable R8 code shrinking and resource shrinking. Media3 UI remains because legacy editor screens still compile against `PlayerView`; removing it is not currently a safe dependency optimization.

## Still-image export regression

Gallery/Photos imports normally use `content://` URIs. The earlier native-image export regression only exercised an app-private `file://` PNG, so provider-backed single-image projects were not covered. Before GPU export, V42 now materializes only still-image `content://` sources into short-lived app-private cache files, preserving the image MIME type and leaving video/audio URIs untouched. Temporary image files are deleted after success, failure, or cancellation.

CI covers both app-private still images and a real MediaStore `content://` still image through `GpuExportBackend`. Run #676 completed 11/11 instrumentation tests with 0 failures, and the packaged-phone New Project -> 16:9 -> Editor UI smoke test also passed.

Examples:

```bash
# Safe installable phone APK (arm64 only, no debug minification)
gradle :app:assemblePhone -PdigitorAbi=arm64-v8a

# Normal compact arm64 debug APK, unminified
gradle :app:assembleDebug -PdigitorAbi=arm64-v8a

# x86_64 emulator APK/tests
gradle :app:connectedDebugAndroidTest -PdigitorAbi=x86_64

# Universal debug APK (unchanged default behavior)
gradle :app:assembleDebug
```
