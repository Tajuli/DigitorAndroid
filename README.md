# DigitorAndroid

Native Android mobile video-editor foundation for **Digitor**.

## Core requirements

1. **GPU-first processing** — primary export uses AndroidX Media3 Transformer: hardware `MediaCodec` for decode/encode and OpenGL for graphical modifications.
2. **Multithread CPU fallback** — if the device has no suitable OpenGL path, or GPU export fails, Digitor automatically switches to a surface-free CPU compositor. Pixel color and alpha blending are split across a bounded worker pool; MP4 is encoded through byte-buffer AVC rather than an OpenGL input surface.
3. **Per-pixel color processing** — every output pixel is processed. GPU uses Media3 `RgbAdjustment` + `HslAdjustment`; CPU consumes the same `ColorGrade` values with Float per-pixel math.
4. **Multitrack timeline** — tracks contain clips and real gaps. Separate tracks may overlap. GPU export maps tracks to Media3 `EditedMediaItemSequence`s and `Composition`; CPU fallback samples all active visual tracks for every output frame.

## Current editor MVP

- Native Kotlin + Jetpack Compose UI
- Video/audio import through Android's document picker
- Add video/audio tracks
- Multitrack timeline with independent layers
- Drag clips horizontally with 50 ms snapping
- Real gap/offset preservation
- Per-track overlap validation
- GPU-first multitrack H.264/AAC export
- Automatic GPU -> CPU fallback
- CPU multitrack video compositor
- Multi-thread per-pixel color and alpha blend
- CPU ByteBuffer YUV420 -> H.264/MP4 encoder (no OpenGL surface)
- Android CI workflow

## Processing architecture

```text
EditorScreen
   ↓
EditorViewModel
   ↓
TimelineProject ── TimelineTrack ── TimelineClip ── ColorGrade
   ↓
ProcessingRouter
   ├── GPU backend
   │     Media3 Composition
   │     MediaCodec hardware decode/encode
   │     OpenGL per-pixel RGB/HSL effects
   │
   └── CPU fallback
         MediaMetadataRetriever frame decode
         N-thread pixel color processing
         N-thread layer alpha compositing
         ARGB -> YUV420
         ByteBuffer MediaCodec AVC -> MP4
```

## Color accuracy policy

The edit parameters are backend-independent. Both paths process pixels rather than applying a low-resolution proxy color calculation. Media3's GL effect path uses linear RGB for its color effects. Single-sequence HDR can be preserved on supported devices; current Media3 multi-video compositing has stricter SDR/same-ColorInfo constraints. The CPU fallback uses floating-point math until the final 8-bit AVC input conversion.

`Per-pixel color accurate` is not the same as mathematically lossless output: H.264/H.265 encoding, 8/10-bit quantization, chroma subsampling, device decoder behavior and display color management can still change the final viewed result. A later 10-bit/HDR CPU encoder path can extend this foundation.

## Multitrack behavior

Within a single track clips cannot overlap. Between different tracks they can overlap freely. `Media3CompositionBuilder` uses `EditedMediaItemSequence.Builder.addGap()` to preserve each clip's start time. Media3 `Composition` mixes overlapping sequences in GPU export. CPU fallback evaluates all active visual layers at each timeline timestamp. The first/top timeline track is composited last so its pixels appear above lower tracks, matching Media3 default compositor registration order.

## CPU fallback scope

CPU fallback exports video MP4 and fully handles visual multitrack compositing/color. **CPU audio mixing is not implemented yet**. GPU export already supports audio tracks through Media3 Composition/AAC. This is deliberately isolated so the next CPU milestone is an audio PCM mixer without rewriting timeline/render code.

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

## Build

Open the project in a current Android Studio and sync, or install Gradle 9.5 and run:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

## Next milestones

- CPU PCM decode/multitrack audio mixer + AAC encoder
- real multi-input preview compositor (keep isolated while Media3 CompositionPlayer multi-video preview evolves)
- trim handles, split/delete, ripple/roll/slip tools
- transforms, crop, position, scale, opacity keyframes
- custom `VideoCompositorSettings`
- transitions
- node-based color graph, curves, HSL qualifier, LUTs, scopes
- text/title/sticker tracks
- waveforms and audio mixer UI
- undo/redo and persistent project files
