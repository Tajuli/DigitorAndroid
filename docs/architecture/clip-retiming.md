# Clip retiming contract

`TimelineClip.retime` is nullable persisted metadata; missing metadata is legacy 1x.
A `ClipRetime` anchors curve positions to a source window. Splitting or trimming
changes the clip's source bounds, never that anchor. Extending a trim outside the
anchor uses the nearest endpoint speed. Source URI is unchanged by speed editing.

`SpeedCurveTimeMap` integrates the sampled speed schedule once and performs binary
search for both composition-to-source and source-to-composition queries. Sampling
is bounded to 6,000 segments. All clocks passed between layers are microseconds.
`sourceTimeAtTimeline`, `timelineTimeAtSource`, and their clip-local counterparts
are the boundary API. Do not multiply timeline offsets by an independent speed.

Native decoder PTS are source timestamps. `nativeRetimeEffects` converts their
fixed stream offset into composition timestamps before shared effects. Tracking
looks up source time through the inverse map. Media3 receives `ClipSpeedProvider`
with the same source-anchored schedule and trim-relative change positions. Its
item duration must cover the source clipping end, not the retimed duration.

Native audio maps each output sample back to source PCM. Media3's speed processor
sets both Sonic speed and pitch to the provider value (Media3 1.10.1), matching
natural pitch-changing retiming. An AAC track alone is not proof of audible sound:
regression tests decode the output and check its PCM amplitude and duration.

The speed workspace edits snapshots live. Done commits one history entry; Cancel
restores the original snapshot. Subsequent clips and overlays ripple together;
same-track collisions reject the edit before publication. Separate video tracks
retain their independent overlap semantics.

## Smooth export

`SmoothRetimeFrameProducer` sequentially decodes neighbouring frames and requests
source positions using the same time map at integer output-frame timestamps.
`MotionFrameInterpolator` estimates block displacement on a bounded luma image,
warps both neighbours, and suppresses unmatched-pixel ghosting. It is original
CPU block-motion code, with no external model or proprietary implementation.
Motion estimation only runs for intermediate samples in sub-1x regions. Scene
cuts select the nearest frame; estimation failure falls back to blending.
Cancellation propagates rather than being treated as an interpolation failure.

The producer retains two source arrays and a scratch array. Native export queues
one bitmap at a time; Media3's bitmap pending-frame count is always zero, so the
single-frame timestamp iterator acknowledges upload before producer recycling.
This acknowledgement depends on Media3 1.10.1's bitmap texture-manager ordering
and must be rechecked when updating Media3. Export readback currently caps the
long edge at 1920 before the project-resolution compositor.

## Realtime preview and CPU fallback

`SmoothPreviewSource` feeds the same per-layer GPU compositor used by ordinary
preview. A cancellable `RetimeFrameStream` owns a two-frame queue and a sequential
interpolation worker. Paused seeks decode from the preceding keyframe until the requested sample is
bracketed; decoding stops immediately when that output window is complete.
Preroll timestamps are retained so low-frame-rate/VFR neighbours are not lost. Playback
skips outdated synthesis targets when the worker falls behind; it never moves the
editor/audio clock backward to catch up. Preview is capped at 480 pixels and
30 fps to bound CPU warping cost. Ordinary non-smooth preview keeps direct decoder
surfaces. Smooth-mode toggles change the decoder input type; ordinary curve
metadata updates do not rebuild the whole GPU graph.

CPU export uses the same frame producer and PCM/AAC mixdown, including remuxed
sound. If the native export route is unavailable, Media3 compatibility export
uses `CompatibilityRetimeSources` to materialize raw smooth video in temporary
files. This is the narrowly scoped export-only exception to the no-bake rule.
The composition builder substitutes only the video input; original clip metadata
still supplies source-time effect/tracking lookup, and original A tracks supply
retimed sound. Video speed is not applied a second time. Temporary files are
removed on completion, cancellation, or failure. No source/project URI changes.

## Validation and limits

The first complete preview integration passed Android CI, including all 36
emulator tests, debug/phone/release compilation, rendered seek checks, native and
CPU AAC audibility, smooth-export timestamps, and speed-session Undo/Redo/Cancel.
Subsequent changes must pass the same gates at the final PR head. These tests do
not establish mid-range physical-device throughput or quality on occlusions,
rolling shutter, or large motion. The compatibility interpolation path has its own full export/audio regression
test. Physical-device quality/performance qualification remains necessary. No claim of neural/dense optical flow or universal hardware quality
is made.
