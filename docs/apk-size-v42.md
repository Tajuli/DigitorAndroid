# APK size strategy V42

PR #48 introduced MediaPipe Tasks Vision, ML Kit Face Detection, and bundled semantic beauty models. A universal debug APK therefore packages the ML native libraries once per ABI.

V42 keeps all editor and beauty behavior while making CI's downloadable phone APK arm64-only via `-PdigitorAbi=arm64-v8a`. Emulator tests explicitly use `-PdigitorAbi=x86_64`, so semantic beauty instrumentation remains covered.

Normal local builds without `-PdigitorAbi` remain universal and keep all supported ABIs.

Release builds enable R8 code shrinking and Android resource shrinking. Media3 UI remains because legacy editor screens still compile against `PlayerView`; removing it is not currently a safe size optimization.

Examples:

```bash
# Compact phone debug APK
gradle :app:assembleDebug -PdigitorAbi=arm64-v8a

# x86_64 emulator APK/tests
gradle :app:connectedDebugAndroidTest -PdigitorAbi=x86_64

# Universal debug APK (unchanged default behavior)
gradle :app:assembleDebug
```
