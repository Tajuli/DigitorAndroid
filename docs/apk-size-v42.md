# APK size strategy V42

PR #48 introduced MediaPipe Tasks Vision, ML Kit Face Detection, and bundled semantic beauty models. A universal debug APK therefore packages the ML native libraries once per ABI.

V42 keeps all editor and beauty behavior while making CI's downloadable phone APK arm64-only via `-PdigitorAbi=arm64-v8a`. Emulator tests explicitly use `-PdigitorAbi=x86_64`, so semantic beauty instrumentation remains covered.

The CI phone artifact now uses the dedicated `phone` build type. It is debug-signed and installable like a development APK, but enables R8 code shrinking and Android resource shrinking. This safely removes unreachable legacy editor code, unused extended icon classes, and other dead resources without deleting source files or changing the normal `debug` build.

Normal local debug builds without `-PdigitorAbi` remain universal and unminified for development. Release builds also enable R8 code shrinking and resource shrinking. Media3 UI remains because legacy editor screens still compile against `PlayerView`; removing it is not currently a safe dependency optimization.

Examples:

```bash
# Small installable phone APK (arm64 + R8/resource shrinking)
gradle :app:assemblePhone -PdigitorAbi=arm64-v8a

# Normal compact arm64 debug APK, unminified
gradle :app:assembleDebug -PdigitorAbi=arm64-v8a

# x86_64 emulator APK/tests
gradle :app:connectedDebugAndroidTest -PdigitorAbi=x86_64

# Universal debug APK (unchanged default behavior)
gradle :app:assembleDebug
```
