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
primary face. Cache version 5 invalidates earlier tracks; run Face Tracking again after updating.
Complete tracks are cached only after analysis succeeds. GPU handoff/cancellation remain intact.

27 eye/face presets include Fire, Laser, Electric Eyes, two Flame Eyes variants, Flaming Horns,
reflection, scans and regional face distortions. 31 additional body decorations include wings,
rings, particles, strokes and clones. These are original procedural variants, not copied CapCut
assets or exact reproductions.

Body semantic effects keep the PP-MattingV2 workflow. Their person-matte path is independent from
the face-analysis gate.

Device QA should cover fast turns, occlusion, glasses, rotated source media, trim/reopen, background
panel changes, preview latency, ncnn GPU backend label, and export parity.

