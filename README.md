# DigitorAndroid

Native Android mobile video-editor foundation for **Digitor**.

## Core requirements

1. **GPU-first processing** — primary export uses AndroidX Media3 Transformer with hardware `MediaCodec` decode/encode and OpenGL effects/compositing.
2. **GPU realtime preview** — video preview uses `MediaExtractor -> MediaCodec -> MultipleInputVideoGraph -> SurfaceView` at project resolution instead of a CPU/Bitmap hot path.
3. **Shared render semantics** — supported SDR preview/export use the same color, spatial, transform, opacity, compositor, output-color and effect-time contracts before the encoder.
4. **Multithread CPU fallback** — if GPU export fails, Digitor can switch to a surface-free CPU compositor/AVC path.
5. **Multitrack timeline** — video/audio tracks contain clips and real gaps; separate tracks may overlap.

## Current editor MVP

- Native Kotlin + Jetpack Compose UI
- Video/audio import through Android's document picker
- Multitrack timeline with independent video/audio layers
- Drag clips horizontally with 50 ms snapping
- Real gap/offset preservation and per-track overlap validation
- Project-resolution direct-Surface GPU video preview
- Realtime multitrack audio preview
- Shared 33^3 color LUT and spatial node shader stack
- Resolve-style video compositor for z-order, position, scale, rotation and opacity
- Paused-frame self-healing after layer import/topology rebuild, app resume and export hand-off
- GPU-first multitrack H.264/AAC export
- Single-layer export parity through an invisible compositor sentinel input
- Automatic GPU -> CPU export fallback
- CPU multitrack video compositor
- Multi-thread per-pixel color and alpha blend
- CPU ByteBuffer YUV420 -> H.264/MP4 encoder
- Android CI with unit/build checks and device-side RGBA preview/export parity gate

## GPU preview/export architecture

```text
Realtime preview
MediaExtractor
  -> MediaCodec decoder
  -> DigitorRenderCore
  -> shared 33^3 LUT
  -> shared spatial shaders
  -> ResolveVideoCompositorSettings
  -> project-resolution SurfaceView

GPU export
Media3 Transformer decode
  -> same render contract
  -> ResolveVideoCompositorSettings
  -> project-resolution frame
  -> H.264 encoder
```

Preview transport and export transport are different, but `ParityRenderContract` keeps source metadata/output color and composition-time -> source-time mapping aligned around the shared GL stages. Trim boundaries use microsecond precision.

## Render-stage pixel parity

For supported SDR timelines, the intended parity boundary is:

```text
decoded source
  -> Transformer-valid ColorInfo
  -> 33^3 color LUT
  -> spatial shader stack
  -> ResolveVideoCompositorSettings
  -> project-resolution render target
```

The Android instrumentation parity test renders synthetic preview/export frames through these production contracts and requires captured RGBA buffers to be byte-for-byte identical. The test covers trimmed/offset timestamps, color correction, Blur, Film Grain, transform, opacity and the single-layer sentinel compositor path.

This is **not** a claim that the final encoded MP4 or physical display pixels are bit-identical. H.264 is lossy, and encoder quantization/chroma subsampling plus Android display composition/color management can change pixels after the render-stage parity boundary.

HDR is currently outside the realtime parity contract.

## Multitrack behavior

Within a single track clips cannot overlap. Between different tracks they can overlap freely. `Media3CompositionBuilder` uses `EditedMediaItemSequence.Builder.addGap()` to preserve each clip's start time. The Resolve-style compositor keeps upper/lower track transparency, transform and opacity semantics consistent between preview and GPU export.

Exactly-one-video-track GPU exports receive an ephemeral duplicate sequence with zero opacity. It exists only to make Media3 select `MultipleInputVideoGraph`; it is not written into editor state and contributes no visible pixels.

## CPU fallback scope

CPU fallback exports video MP4 and handles visual multitrack compositing/color. **CPU audio mixing is not implemented yet**. GPU export already supports audio tracks through Media3 Composition/AAC.

## Build versions

- compileSdk 37
- targetSdk 37
- minSdk 23
- AGP 9.3.0
- Gradle 9.5
- Compose compiler/Kotlin plugin 2.3.21
- Compose BOM 2026.08.00
- Media3 1.11.0
- JDK 17

## Build and test

For the normal JVM/build checks:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

CI additionally runs the Android device-side `PreviewExportPixelParityInstrumentedTest` on an emulator and fails if the preview/export RGBA buffers differ.

## Next milestones

- CPU PCM decode/multitrack audio mixer + AAC encoder
- trim handles, split/delete, ripple/roll/slip tools
- transitions
- text/title/sticker tracks
- waveforms and audio mixer UI
- undo/redo and persistent project files
- extend the realtime parity contract to HDR/10-bit workflows where device/Media3 support permits
