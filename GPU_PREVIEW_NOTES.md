# GPU preview and export architecture

The editor preview uses a project-resolution MediaCodec + Media3 OpenGL render path rather than the older CompositionPlayer/Bitmap preview path.

## Video preview path

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
- Multilayer z-order, position, scale, rotation and opacity are resolved by the same `ResolveVideoCompositorSettings` contract used by compositor export.
- Preview source metadata is normalized with the same Transformer-compatible color rules used by export.
- Animated color and spatial effects share one composition-time -> source-time mapping through `ParityRenderContract`.
- Paused frames are retained/retried until the output Surface is ready, and the current frame is explicitly refreshed after topology changes, app resume and export hand-off.
- If the playhead is outside every active video clip, the UI covers the retained decoder Surface buffer with the preview pasteboard so a previous clip's last frame is not shown as if it still exists on the timeline.

## Text preview

Text/title overlays are evaluated independently from the retained decoded video surface. A title may therefore remain visible during a video-free interval while the video pasteboard is blank.

The title model is assigned to normal V tracks for timeline semantics. Manual title animation is evaluated from composition time; the current editor exposes X, Y, Size and Opacity keyframes plus timed entry/exit presets.

## Audio preview

Each active audio track uses an audio-only single-sequence `CompositionPlayer`; Android mixes the player outputs. This avoids tying multilayer video preview scheduling to CompositionPlayer while preserving realtime multitrack audio.

## GPU export routing

Export deliberately avoids the multi-input compositor when the timeline does not need it.

### Stable single-input route

Exactly one real decoded video track can stay on Media3's stable single-input video graph when clip opacity/transitions permit it and every timed title frame is covered by a real video frame. Composition-level `TextOverlay` effects are applied after the video effects, so a V2/V3 title over V1 video does not require a second decoder input by itself.

### Resolve compositor route

Real multi-video edits use `ResolveVideoCompositorSettings` for track layering. The compositor route is also used when a title reaches a video-free timeline interval.

A tiny black PNG written into app cache can be supplied as a synthetic video source. It provides real frame timestamps through gaps without holding the previous decoded frame. In compositor mode it acts as an invisible sentinel/frame source where required.

### Pure-text route

If a project has Text (optionally plus Audio) but no real video clips, the same cached black PNG becomes the actual full-duration video source. Media3 scales it to the project canvas and applies the timed text effects on top, allowing a Text-only timeline to export as H.264 MP4.

Timed titles never return an empty string while inactive. Media3's `TextOverlay` rasterizer can measure an empty `SpannableString` to width zero and attempt to create an invalid zero-width bitmap. Digitor instead returns a positive-width space and sets overlay alpha to zero outside the title interval.

Failed/cancelled exports delete incomplete output files so a runtime failure does not leave a 0-byte MP4 behind.

## Preview/export parity

For supported SDR timelines, preview and export share the render-stage contract:

```text
decoded source
  -> Transformer-valid ColorInfo
  -> 33^3 color LUT
  -> spatial shader stack
  -> ResolveVideoCompositorSettings (when required)
  -> project-resolution render target
```

CI includes Android instrumentation that renders synthetic preview/export frames through production effect/compositor contracts and requires captured RGBA buffers to be byte-for-byte identical. It also runs device-side regressions for timed `TextOverlay` bitmap safety and pure-text MP4 export.

This guarantee ends at the render target/frame entering the encoder. H.264 encoding is lossy, and Android display composition/color management may change physical screen pixels.

HDR/10-bit realtime parity is still outside the current supported contract.
