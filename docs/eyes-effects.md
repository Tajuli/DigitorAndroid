# Face-tracked effects

Eyes and Funny Faces are gated by a full-clip face-tracking analysis. Their effect thumbnails remain
hidden until the selected clip has a complete compatible track. Analysis progress and Cancel are
visible in the Effects panel. The tracking job belongs to a process-lifetime runtime rather than the
composable, so switching category or leaving the Effects panel does not cancel the work while the
app process remains alive.

## Native ncnn Vulkan tracking

Face tracking now uses the same native ncnn Vulkan runtime as PP-MattingV2. It does not use the
MediaPipe Tasks GPU delegate at runtime.

The build downloads pinned Apache-2.0 MediaPipe-derived ONNX graphs and converts them with the same
pnnx/ncnn toolchain already used by PP-MattingV2:

- BlazeFace short-range detector: 128×128, used for acquisition/reacquisition.
- Face Mesh: 468 landmarks at 192×192, used inside the tracked face ROI.

The decoded working frame is capped at a 960 px long edge only as a source for ROI sampling. Dense
landmark inference never processes that whole 960 px frame. After acquisition, the native engine
uses a generously padded, roll-normalized landmarks-to-ROI crop and only runs the detector
only when the ROI loses the face. Both networks use a persistent ncnn Net with
`use_vulkan_compute=true`, the default Vulkan device, reusable Vulkan allocators, fp16 capabilities
when supported, and the same packaged ncnn Android Vulkan library as PP-MattingV2.

If ncnn reports no usable Vulkan compute device, the exact same ncnn graphs can run on CPU as a
compatibility fallback. On devices where PP-MattingV2 reports ncnn Vulkan, face tracking is expected
to use that same Vulkan device/runtime as well.

The old MediaPipe live-face preview tap is not used for Eyes/Funny Faces after this change. Preview
and export consume the durable ncnn-generated EyeTrack, preventing a second incompatible tracking
backend from overriding the analyzed result.

Video analysis sequentially decodes every frame using the existing MediaCodec/OES decoder and
stores the decoder's real presentation timestamps. It does not estimate source FPS, timestamp
OPTION_CLOSEST frames with requested times, or apply a causal pose smoother. A short preroll keeps
an interpolation anchor for trims between frames. Preview and export look up the same source-time
track. This trades additional landmark evaluations for frame-accurate motion (particularly for
fractional-rate/VFR footage); sequential decoding avoids repeated random seeks.

The next mesh crop is measured in the eye-line coordinate system with 1.5x padding. It uses
unclipped landmarks so head roll or crossing an image edge does not resize the crop artificially.
A valid mesh is not replaced by a detector crop every sixth sample. Acquisition is refined on the
same frame, failed mesh output invalidates the ROI, and warm-up never seeds video tracking.

The native tracker records both eyes, eye openness, roll, face bounds and mouth bounds for one
primary face. Current gaze-aware tracks use cache version 14; run Face Tracking again after updating.
Complete tracks are cached only after analysis succeeds. GPU handoff/cancellation remain intact.

26 public eye/face presets include Fire, the new Electric Eyes, two Flame Eyes variants, Flaming Horns,
reflection, scans and regional face distortions. 31 additional body decorations include wings,
rings, particles, strokes and clones. These are original procedural variants, not copied CapCut
assets or exact reproductions.

Body semantic effects keep the PP-MattingV2 workflow. Their person-matte path is independent from
the face-analysis gate.

Device QA should cover fast turns, occlusion, glasses, rotated source media, trim/reopen, background
panel changes, preview latency, ncnn GPU backend label, and export parity.


## Decoder pixel contract

The sequential decoder now copies GL_RGBA bytes directly into ARGB_8888 Bitmap storage (raw
buffer bytes are RGBA; getPixels returns ARGB integers). The old readback shader swapped R/B,
so switching face analysis from MediaMetadataRetriever to this decoder in adbd494 introduced
wrong-color model input. This was reproduced with the production GLSL: a red source quadrant
became blue before the fix. The model adapter still obtains correct ARGB integers via getPixels.

The shader owns clockwise metadata rotation and output dimensions. KEY_ROTATION is cleared
before configuring MediaCodec because surface-mode codecs otherwise rotate once before the
shader rotates again. The 90/270 inverse sampling maps were also corrected. SurfaceTexture's
producer crop/flip matrix remains applied exactly once. Normalized positions do not need a
960-to-export-resolution scale factor.

Validation: verify_tracking_decoder_gl.py checks the production shader at two resolutions and
all four right-angle rotations; verify_eye_effects_gl.py now checks off-center eye placement in
a non-square frame. TrackingFrameDecoderInstrumentedTest checks real MediaCodec/OES/Bitmap
output, metadata rotation, downscaling, colors, PTS, and the raw-buffer packing contract. This
instrumented test is included in the existing emulator parity job. Version 6 invalidates the
wrong-color v5 tracks. Device visual QA remains necessary for neural tracking accuracy.

Android contracts:
- https://developer.android.com/reference/android/graphics/Bitmap.Config#ARGB_8888
- https://developer.android.com/reference/android/media/MediaCodec#transformations-when-rendering-onto-surface


## Per-frame analysis and blink gating

Video face analysis runs once for every decoded source frame. The sequential MediaCodec/OES decoder
emits each frame at its actual presentation timestamp (PTS), and each unique PTS receives one ncnn
Vulkan face-tracking inference and one EyeSample. There is no fixed 12 fps video sampling interval.
A short decode preroll may be analyzed to seed tracking around a trim boundary, but persisted video
samples still use the decoder's real source-frame timestamps. Still images retain their lightweight
synthetic timeline samples because the image pixels do not change.

Eye openness is also treated as a per-frame signal. Position/radius/roll may interpolate between
adjacent EyeSamples for arbitrary preview times, but eye openness uses the nearest decoded frame so
a blink is not smeared across time.

Blink detection is computed natively from a rotation-invariant, three-pair median Eye Aspect Ratio (EAR)
instead of a single upper/lower eyelid pair. The left eye uses Face Mesh pairs 159/145, 158/153 and
160/144 over corners 33/133; the right eye uses 386/374, 385/380 and 387/373 over corners 362/263.
Each eye has independent hysteresis: it closes at EAR <= 0.18 and does not reopen until EAR >= 0.23.
While closed, the stored eye openness is exactly zero. A short missing per-frame pose may still
bridge position, but its eye openness is forced to zero so Fire/Electric Eyes cannot shine through an
uncertain/blink frame.

The production eye shader applies a final independent per-eye gate:
`smoothstep(0.18, 0.30, open)`. Therefore a closed left eye removes only the left-eye effect,
a closed right eye removes only the right-eye effect, and closing both eyes removes both visible
eye effects. Face-level effects that are not emitted from an eye remain independent.

These semantics use EyeTrack cache version 14 / `eye_tracks_v14`, forcing a fresh analysis after
the upgrade.


### 3D gaze tracking
The legacy Laser Eyes and Electric Eyes presets were removed. Electric Eyes uses Face Mesh 3D face orientation plus lightweight pupil refinement, anchors the emission near the pupil, rotates the gaze vector into the same eye-local roll basis as the rendered beam, and uses a white/gold broad-beam look inspired by the supplied reference footage without copying external effect assets. Frontal gaze is rendered with camera-facing foreshortening.


### Per-eye gaze tracking
The legacy Laser Eyes implementation remains removed. The public Electric Eyes preset now uses the new per-eye gaze pipeline and a completely new procedural renderer. Electric Eyes now consumes independent left/right gaze measurements rather than forcing both eyes through one shared direction. Each frame densely samples a roll-normalized eye ROI from the decoded source pixels, estimates the dark iris/pupil cluster with a two-pass robust centroid, exposes per-eye gaze/depth/confidence, and anchors the emitter near that measured pupil. High-confidence iris motion drives the beam angle directly; low-confidence or occluded eyes fall back to a short head/shared emitter instead of creating a long fake ray. Frontal gaze is rendered with camera-facing foreshortening.

Funny Face effects keep the existing face/mouth/head tracking and deformation path; this per-eye gaze upgrade does not change Funny Face tracking behavior.


### Electric Eyes lens-graze rendering
Camera-facing Electric Eyes now keep two distinct short foreshortened tubes, one per tracked eye,
instead of collapsing the effect into eye-socket glow. When both eyes face the camera, an additional
frame-space lens flare/halation is composited around the virtual camera lens so the overexposure
appears to happen at the viewer/lens rather than inside the eyes. Directional beams retain the
per-eye gaze angle and now carry low-frequency animated ionized haze/smoke outside the white-hot
core. Funny Face tracking remains unchanged.


### Electric Eyes natural-motion refinement
The Electric Eyes renderer now uses one dominant beam core/body per eye. The former secondary bright
filament was removed because it could read as two extra mini-beams. Turbulence and smoke drift use
slower, lower-amplitude motion, and several extra FBM evaluations were replaced with reused/cheap
noise so preview rendering is lighter. The two-eye frontal lens-graze remains, but its flare is
narrower and substantially softer, with a stricter strong-frontal gate.
