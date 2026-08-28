# Creator Core feature contract

This branch adds the first creator-workflow layer on top of the PR #36 render core.

## Included

- Snapshot Undo/Redo (50 states, slider/drag coalescing)
- Project Save/Load + automatic restore of the last saved project
- Manual Text and Caption overlays with timing, position, size and caption background
- Preview text overlay and Media3 composition-level text export overlay
- Compositor-native Fade In / Fade Out transitions
- Speed bake (0.25x-4x core support; UI presets) with Media3 `EditedMediaItem.setSpeed`
- Reverse video derived render
- Freeze-frame derived clip insertion
- Audio clip volume, fade-in and fade-out through Media3 `GainProcessor`

## Transition boundary

Media3 1.11 does not support true crossfading between composition sequences. This branch therefore ships a stable fade-transition engine that is evaluated by the same Resolve compositor used by preview/export rather than introducing a separate unsupported crossfade graph.

## Retime boundary

Speed creates a derived MP4 and keeps linked audio when the source contains audio. Reverse creates a video-only derived MP4 and removes the linked source-audio clip. Freeze inserts a derived still-video segment and ripples following media, leaving an audio gap for the freeze duration.

Derived-media operations deliberately produce ordinary timeline media so the proven PR #36 preview/export renderer does not gain a second runtime timestamp model.
