# GPU preview architecture

The editor preview now uses a project-resolution MediaCodec + Media3 OpenGL render path rather than the older CompositionPlayer/Bitmap preview path.

## Video path

```text
MediaExtractor
  -> MediaCodec decoder
  -> DigitorRenderCore input Surface
  -> shared 33^3 color LUT
  -> shared spatial node shader stack
  -> ResolveVideoCompositorSettings
  -> project-resolution SurfaceView
```

- `DavinciFramePreviewEngine` owns decode/play/seek scheduling.
- `DigitorRenderCore` owns the Media3 `MultipleInputVideoGraph` and project-resolution render target.
- Preview is displayed directly through `SurfaceView`; there is no normal-path `ImageReader -> Bitmap -> Compose` readback.
- Multilayer z-order, position, scale, rotation and opacity are resolved by the same `ResolveVideoCompositorSettings` contract used by export.
- Preview source metadata is normalized with the same Transformer-compatible color rules used by export.
- Animated color and spatial effects share one composition-time -> source-time mapping through `ParityRenderContract`.
- Paused frames are retained/retried until the output Surface is ready, and the current frame is explicitly refreshed after topology changes, app resume and export hand-off.

## Audio preview

Each active audio track uses an audio-only single-sequence `CompositionPlayer`; Android mixes the player outputs. This avoids tying multilayer video preview scheduling to CompositionPlayer while preserving realtime multitrack audio.

## Preview/export parity

For supported SDR timelines, preview and export share the render-stage contract:

```text
decoded source
  -> Transformer-valid ColorInfo
  -> 33^3 color LUT
  -> spatial shader stack
  -> ResolveVideoCompositorSettings
  -> project-resolution render target
```

Exactly-one-video-track export adds an ephemeral zero-opacity sentinel input so Media3 selects its multi-input compositor without changing visible pixels.

CI includes an Android instrumentation test that renders synthetic preview/export frames through the production effect/compositor contracts and requires the captured RGBA buffers to be byte-for-byte identical.

This guarantee ends at the render target/frame entering the encoder. H.264 encoding is lossy, and Android display composition/color management may change physical screen pixels.
