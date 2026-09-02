# APK size strategy V42

PR #48 introduced MediaPipe Tasks Vision, ML Kit Face Detection, and bundled semantic beauty models. A universal debug APK therefore packages the ML native libraries once per ABI.

V42 keeps all editor and beauty behavior while making CI's downloadable phone APK arm64-only via `-PdigitorAbi=arm64-v8a`. Emulator tests explicitly use `-PdigitorAbi=x86_64`, so semantic beauty instrumentation remains covered.

The CI phone artifact uses a dedicated `phone` build type that is behavior-identical to `debug` and debug-signed for easy installation. Size reduction for this artifact is limited to the requested ABI. We intentionally do not enable R8 or resource shrinking on this debuggable build: AGP warns that debuggable + minify is an unsupported combination, and a real-device New Project crash was observed with that configuration.

Release builds still enable R8 code shrinking and resource shrinking and are verified by CI. Media3 UI remains because legacy editor screens still compile against `PlayerView`; removing it is not currently a safe dependency optimization.

CI also exercises the packaged phone APK through the home flow: launch app, tap `New project`, choose `16:9`, and confirm the editor opens. This guards the exact regression that exposed the unsafe shrunk-debug configuration.

Examples:

```bash
# Safe installable phone APK (arm64 only, debug behavior)
gradle :app:assemblePhone -PdigitorAbi=arm64-v8a

# Equivalent compact arm64 debug APK
gradle :app:assembleDebug -PdigitorAbi=arm64-v8a

# x86_64 emulator APK/tests
gradle :app:connectedDebugAndroidTest -PdigitorAbi=x86_64

# Universal debug APK (unchanged default behavior)
gradle :app:assembleDebug
```
