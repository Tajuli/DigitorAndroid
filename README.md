# DigitorAndroid

Native Android mobile video-editor foundation for **Digitor**.

## Core requirements

1. **GPU-first processing** — primary export uses AndroidX Media3 Transformer with hardware `MediaCodec` decode/encode and OpenGL effects/compositing.
2. **GPU realtime preview** — video preview uses `MediaExtractor -> MediaCodec -> MultipleInputVideoGraph -> SurfaceView` at project resolution instead of a CPU/Bitmap hot path.
3. **Shared render semantics** — supported SDR preview/export use the same color, spatial, transform, opacity, compositor, output-color and effect-time contracts before the encoder.
4. **Multithread CPU fallback** — if GPU export fails, Digitor can switch to a surface-free CPU compositor/AVC path.
5. **Resolve-style multitrack timeline** — video and text/title items live freely on V tracks, audio lives on A tracks, and separate tracks may overlap.

## Current editor

- Native Kotlin + Jetpack Compose UI with a black/gray editor theme and white primary content
- Video/audio import through Android's document picker
- Multitrack V/A timeline with real gaps and per-track overlap validation
- Video and text clips share the same single lane on each V track; there is no separate title lane
- A V track can contain `video -> text -> video`, while text on an upper V track can overlay video on a lower V track
- New video/text/template insertion appends after the last item on the selected V track; an empty V track uses the playhead
- Press-and-hold text clips to move/snap them in time and move them between V1/V2/V3…
- Selected text clips are independently editable even when a video exists underneath at the same time
- Draggable left/right trim handles for text clips and video clips
- Split video clips can reveal trimmed source frames again by extending an edge, bounded by source duration and neighboring timeline items
- Text inspector with font, bold, color, stroke, shadow, background, alignment, X/Y, size and duration controls
- Playhead-based manual text keyframes for X, Y, Size and Opacity with interpolation between diamonds
- Quick text entry/exit presets (fade and directional slides)
- Data-driven animated title template catalog with 28 presets across Minimal, Caption, Social, Neon, Cinematic and Kinetic categories
- Hold-drag-release template insertion onto the selected V track
- Snapshot Undo/Redo plus project Save/Load and automatic project restore
- Home navigation button that autosaves before returning to the project screen
- Project-resolution direct-Surface GPU video preview
- Realtime multitrack audio preview
- Shared 33^3 color LUT and spatial node shader stack
- Resolve-style video compositor for z-order, position, scale, rotation and opacity
- Paused-frame self-healing after layer import/topology rebuild, app resume and export hand-off
- Preview hides a retained decoder surface frame when the playhead is outside every active video clip
- GPU-first H.264/AAC export with stable single-input and compositor routes
- Automatic GPU -> CPU export fallback
- CPU multitrack video compositor
- Multi-thread per-pixel color and alpha blend
- CPU ByteBuffer YUV420 -> H.264/MP4 encoder
- Android CI with unit/build checks, render-stage RGBA parity, TextOverlay safety and pure-text export coverage

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

GPU export (normal one-video timeline)
Media3 Transformer decode
  -> shared video effects
  -> composition-level timed TextOverlay effects
  -> project-resolution frame
  -> H.264 encoder

GPU export (real multi-video or video-free title intervals)
Media3 Transformer inputs
  -> ResolveVideoCompositorSettings
  -> composition-level timed TextOverlay effects
  -> project-resolution frame
  -> H.264 encoder
```

Preview transport and export transport are different, but `ParityRenderContract` keeps source metadata/output color and composition-time -> source-time mapping aligned around the shared GL stages. Trim boundaries use microsecond precision.

### Text export routing

Text is a timed composition-level overlay; its V-track assignment controls editor/timeline semantics without automatically requiring another video decoder.

- With exactly one real video stream and every title frame covered by that video, export stays on the stable single-input video graph.
- Real multi-video compositions use the Resolve compositor.
- If a title reaches a timeline interval with no real video frame, export supplies a tiny app-cache PNG as a synthetic frame stream so the encoder and text effects continue through the gap.
- A Text-only (or Text + Audio) project uses that synthetic black source as its full-duration video canvas and renders the title on top.
- Inactive timed titles keep a positive-size transparent placeholder instead of returning an empty `SpannableString`; this avoids Media3 creating an invalid zero-width text bitmap before encoding starts.
- Failed/cancelled exports remove incomplete output files instead of leaving a 0-byte MP4 behind.

## Render-stage pixel parity

For supported SDR timelines, the intended parity boundary is:

```text
decoded source
  -> Transformer-valid ColorInfo
  -> 33^3 color LUT
  -> spatial shader stack
  -> ResolveVideoCompositorSettings (when required)
  -> project-resolution render target
```

Android instrumentation tests render synthetic preview/export frames through production contracts and require captured RGBA buffers to be byte-for-byte identical. Coverage includes trimmed/offset timestamps, color correction, Blur, Film Grain, transform, opacity and compositor behavior. Separate device-side tests cover timed TextOverlay bitmap safety and pure-text MP4 export.

This is **not** a claim that the final encoded MP4 or physical display pixels are bit-identical. H.264 is lossy, and encoder quantization/chroma subsampling plus Android display composition/color management can change pixels after the render-stage parity boundary.

HDR is currently outside the realtime parity contract.

## Multitrack behavior

Within one V track, video and text items cannot occupy the same time range. Between different V tracks they can overlap freely, so a V2 title can appear over V1 video. Audio tracks keep their own clip/gap timeline.

`Media3CompositionBuilder` uses `EditedMediaItemSequence.Builder.addGap()` to preserve media start times. The export router keeps straightforward one-video edits on Media3's stable single-input graph and only creates a multi-input compositor graph when the timeline actually requires layering or video-free title coverage.

## CPU fallback scope

CPU fallback exports video MP4 and handles visual multitrack compositing/color. **CPU audio mixing and the full GPU text/title feature set are not yet parity-complete on the CPU fallback path.** GPU export supports audio tracks through Media3 Composition/AAC.

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

For normal JVM/build checks:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

CI also compiles instrumentation tests and runs device-side render parity plus pure-text export tests on an Android emulator.

## Next milestones

- CPU PCM decode/multitrack audio mixer + AAC encoder
- waveform generation and audio mixer UI
- advanced ripple/roll/slip editing tools
- true cross-dissolve/overlap transitions where the render architecture supports them safely
- image/sticker/shape overlay items with the same free V-track semantics as text
- expose additional title-animation controls such as rotation in the manual keyframe UI
- extend CPU fallback parity for titles/effects
- extend the realtime parity contract to HDR/10-bit workflows where device/Media3 support permits
