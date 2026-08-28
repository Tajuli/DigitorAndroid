# DigitorAndroid

DigitorAndroid is an Android video-editing prototype focused on a mobile-first timeline, node-based color grading, realtime preview, and GPU/CPU export parity.

## Creator workflow

- HomePage with New Project, Share App, and Recent Projects
- Project save naming plus internal autosave recovery
- Undo / Redo
- Multi-track video/audio timeline
- Add video/audio tracks with `+V` / `+A`
- Long-press a track header (`V1`, `V2`, `A1`, `A2`, …) to confirm and delete that track; track deletion is undoable
- One-finger horizontal timeline panning and a single zoom slider
- Text / captions
- Clip fade transitions
- Speed, reverse, and freeze-frame tools
- Audio volume and fade controls
- Export quality presets: High / Medium / Low

## Render architecture

The realtime preview and export paths share Digitor render-stage semantics so color, spatial transforms, and compositing remain aligned. Device-side CI includes an RGBA byte-for-byte parity gate for the validated render stage.

## Important boundaries

- Media3 sequence crossfades are not faked; current transition support is stable clip fade-in / fade-out.
- Reverse is currently video-only and removes linked source audio.
- Device encoder implementations may adjust requested export bitrate according to codec capabilities.
