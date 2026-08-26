# GPU preview architecture

This branch keeps the playhead-driven editor contract from PR #30 while moving the normal preview processing path to Media3's GPU composition graph.

- MediaCodec/CompositionPlayer performs video decode.
- Shared preview GL effects handle color and node processing.
- ResolveVideoCompositorSettings handles multilayer z-order, transform and opacity.
- Preview output is capped to a 720 px long edge.
- ImageReader only reads back the final RGBA surface for the existing Compose bitmap viewer.
- Nearby playhead requests are treated as continuous playback so the decoder runs forward instead of seeking every 33 ms.
- Scrubs and large jumps use the already prepared composition.
- The previous CPU renderer remains a device-safety fallback if Media3 reports a player/GL failure.

Final export is unchanged.
