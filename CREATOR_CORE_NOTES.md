# Creator Core feature contract

This document describes the current creator/editor behavior on the active Android editor branch.

## Included

- Snapshot Undo/Redo with drag/slider coalescing
- Project Save/Load plus automatic restore of the latest saved project
- Resolve-style V/A timeline with free video/text placement on V tracks
- Video and text clips share the same lane inside each V track; separate V tracks may overlap
- New media/text/template insertion appends after the last item on the selected V track
- Press-and-hold title movement in time and between V1/V2/V3…
- Independent title selection even when another video is underneath at the same timestamp
- Left/right resize handles for titles and media clips
- Split media can be extended back into available source frames, bounded by neighboring items
- Manual Text and Caption overlays with styling, position, size and background controls
- Playhead-based manual title keyframes for X, Y, Size and Opacity
- Timed entry/exit title animation presets
- 28 data-driven animated title templates with hold-drag-release insertion
- Preview text overlay and Media3 composition-level text export overlay
- Compositor-native Fade In / Fade Out transitions
- Speed bake (0.25x-4x core support; UI presets) with Media3 `EditedMediaItem.setSpeed`
- Reverse video derived render
- Freeze-frame derived clip insertion
- Audio clip volume, fade-in and fade-out through Media3 `GainProcessor`
- Home navigation that autosaves before leaving the editor

## Text/title timeline contract

Titles are timeline items assigned to normal VIDEO tracks rather than a synthetic title track.

- V1 may contain `video -> text -> video` sequentially.
- V2/V3 titles may overlap V1 video and render above the lower video track.
- Inside one V track, video/text items may not overlap each other.
- The selected V track controls where newly added text/captions/templates are inserted.
- If the selected V track already contains items, insertion starts after the last item; an empty V track uses the playhead.
- Long-press title drag changes time and may move the title vertically between V tracks.
- Title edge handles change start/end duration without a fixed three-second limit.

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
- Waveforms and a full audio mixer UI are still future work.
- CPU fallback is not yet feature-parity-complete for the full GPU text/title stack.
- HDR/10-bit realtime parity remains outside the current supported contract.
