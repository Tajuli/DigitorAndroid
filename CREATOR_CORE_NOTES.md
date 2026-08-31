# Creator Core feature contract

This document describes the current creator/editor behavior on the active Android editor branch.

## Included

- Snapshot Undo/Redo with drag/slider coalescing
- Project Save/Load plus automatic restore of the latest saved project
- Resolve-style V/A timeline with free video/text/visual-overlay placement on V tracks
- Video, text and visual overlay clips share the same lane inside each V track; separate V tracks may overlap
- New media/text/template/visual insertion appends after the last item on the selected V track
- Press-and-hold title/visual movement in time and between V1/V2/V3…
- Independent title/visual selection even when another video is underneath at the same timestamp
- Left/right resize handles for titles, visual overlays and media clips
- Split media can be extended back into available source frames, bounded by neighboring items
- Manual Text and Caption overlays with styling, position, size and background controls
- Image, Sticker and Shape visual overlays with position, scale, rotation, opacity and duration controls
- Built-in sticker presets: Heart, Star, Lightning, Check, Arrow and Smile
- Built-in shape presets: Rectangle, Circle, Triangle and Arrow
- Playhead-based manual title keyframes for X, Y, Size and Opacity
- Timed entry/exit title animation presets
- 28 data-driven animated title templates with hold-drag-release insertion
- Preview text/visual overlays and Media3 composition-level overlay export
- Compositor-native Fade In / Fade Out transitions
- Speed bake (0.25x-4x core support; UI presets) with Media3 `EditedMediaItem.setSpeed`
- Reverse video derived render
- Freeze-frame derived clip insertion
- Audio clip volume, fade-in and fade-out through Media3 `GainProcessor`
- Source-aware audio waveforms rendered inside A-track clips with background decode and memory/disk caching
- Home navigation that autosaves before leaving the editor

## Audio waveform contract

Waveforms are editor metadata derived from the source audio and are never required to reopen or export a project.

- Audio decoding runs off the UI thread through Android `MediaExtractor` + `MediaCodec`.
- One normalized source envelope is shared by clips that reference the same media URI, including split clips.
- Timeline drawing maps each clip's `sourceInUs..sourceOutUs` range into the source waveform, so trimmed/split clips display the correct section instead of stretching the full source.
- Long sources dynamically compact the peak envelope to a bounded 8192 samples rather than growing memory with media duration.
- PCM 8-bit, 16-bit, float, 24-bit packed and 32-bit decoder output are handled when exposed by the device codec.
- Waveforms use an in-memory LRU plus disposable disk cache under the app cache directory; cache identity includes URI plus available source length/modified metadata.
- Timeline rendering limits draw columns per clip so deep zoom does not create an unbounded number of Canvas draw operations.
- Decode failure is non-fatal: the audio clip remains editable/playable and displays a neutral center line.

## Text/title timeline contract

Titles are timeline items assigned to normal VIDEO tracks rather than a synthetic title track.

- V1 may contain `video -> text -> video` sequentially.
- V2/V3 titles may overlap V1 video and render above the lower video track.
- Inside one V track, video/text/visual items may not overlap each other.
- The selected V track controls where newly added text/captions/templates are inserted.
- If the selected V track already contains items, insertion starts after the last item; an empty V track uses the playhead.
- Long-press title drag changes time and may move the title vertically between V tracks.
- Title edge handles change start/end duration without a fixed three-second limit.

## Visual overlay contract

Images, stickers and shapes use one persisted `VisualOverlayClipV19` model rather than three independent editor systems.

- Every visual overlay is assigned to a normal VIDEO track and participates in that lane's occupancy rules.
- An overlay may overlap media on another V track, but it cannot overlap media, text or another visual item on its own V track.
- Image overlays persist a content URI; the editor requests persistable read access when the document provider supports it.
- Sticker and shape overlays are generated from deterministic vector-style raster sources and do not require bundled image assets.
- Inspector controls expose normalized X/Y position, project-width-relative scale, clockwise rotation, opacity and duration.
- Timeline items support long-press horizontal movement, vertical V-track movement and left/right duration trim handles.
- Old project JSON remains readable because the project-level visual overlay field is nullable and resolves to an empty list when absent.
- Invalid transform/timing values are normalized before commit; visual durations have a 100 ms minimum.

## Visual overlay preview/export contract

Preview and GPU export consume the same persisted overlay values.

- Compose preview uses the same decoded/generated bitmap source as Media3 export.
- Media3 renders images, stickers and shapes as timed composition-level `BitmapOverlay` layers.
- Project V-track order determines composition overlay z-order; upper V tracks are rendered above lower V tracks.
- A one-video timeline whose overlays stay inside real video coverage remains on the stable single-input export path.
- If any composition overlay reaches a video-free interval, the shared blank-frame path supplies continuous encoder frames.
- Overlay-only and Overlay + Audio projects use the synthetic black frame source as their full-duration video canvas.
- Imported image decoding is bounded/downsampled and cached in memory to avoid repeatedly decoding large source images during preview/export.
- Missing/unreadable image data fails visually to a small placeholder instead of crashing editor playback.

## Text animation contract

The Text workspace exposes direct title editing plus manual keyframes evaluated from composition time.

Manual UI keyframes currently cover:

- X position
- Y position
- Size
- Opacity

Values interpolate between keyframe diamonds. Entry/exit presets (fade and directional slides) can be combined with the manual animation. The underlying render model also carries rotation state for preview/export compatibility, while rotation is not yet exposed as a manual keyframe slider in this workspace.

## Text export contract

Text is rendered as a Media3 composition-level `TextOverlay`.

- A normal one-video timeline whose titles stay inside real video coverage uses the stable single-input export path.
- Real multi-video compositions use the Resolve compositor.
- If a title reaches a video-free interval, a tiny app-cache PNG provides continuous synthetic video frames so the encoder and text overlay keep running.
- A Text-only or Text + Audio project uses the synthetic black frame source as the full-duration video canvas.
- Inactive timed titles use a positive-width transparent placeholder rather than an empty `SpannableString`, avoiding Media3 zero-width bitmap creation before the first encoded frame.
- Runtime failure/cancel removes incomplete output files instead of leaving 0-byte MP4s.

## Transition boundary

Media3 1.11 does not provide a general true crossfade between arbitrary composition sequences. The editor therefore ships a stable fade-transition engine evaluated by the same Resolve compositor used by preview/export rather than introducing a separate unsupported crossfade graph.

## Retime boundary

Speed creates a derived MP4 and keeps linked audio when the source contains audio. Reverse creates a video-only derived MP4 and removes the linked source-audio clip. Freeze inserts a derived still-video segment and ripples following media, leaving an audio gap for the freeze duration.

Derived-media operations deliberately produce ordinary timeline media so the proven preview/export renderer does not gain a second runtime timestamp model.

## Current gaps

- Advanced ripple/roll/slip tools are still future work.
- A full audio mixer UI is still future work.
- CPU fallback is not yet feature-parity-complete for the full GPU composition-overlay stack.
- HDR/10-bit realtime parity remains outside the current supported contract.
