# Portrait Lens Blur

Select a visual clip, open **Effects → Portrait → Apply & Analyze**. The effect applies to the
whole clip after node effects, using the same PP-MattingV2 soft matte as Pro Cutout. Initial
analysis uses 512 px person ROI and every source frame. Pause/resume and cancellation reuse the
existing durable analysis workflow. Edit → Cutout exposes analysis resolution, cadence, hair
and temporal settings. Blur strength is live and does not require reanalysis.

The normalized 96-sample circular kernel excludes foreground and uncertain boundary samples,
including the bilinear color footprint. Fully opaque subject pixels use the original source
sample exactly, with no denoise, skin smoothing, or dehalo. Soft matte edges retain continuous
alpha blending. Radius scales with the frame's short edge (0–24 pixels at 1080). Input alpha is
preserved; this effect does not remove the background. CPU fallback implements the same kernel.

Preview and export use the existing shared Cutout shader stage, including the always-resident
live preview program and source-time matte interpolation. The following fabric/dehalo pass is
bypassed while lens blur is active, even when toggled inside an existing preview graph. The
settings persist in ClipCutoutV43 and participate in the existing project undo/save mechanism;
legacy projects default to blur off. Choosing Pro Cutout switches back to background removal.
Effects None also removes the portrait effect.

Missing/cancelled/unprocessed matte coverage passes through unchanged instead of blurring the
subject or applying a stale silhouette. Finish analysis before final export. This is a person
portrait effect, not arbitrary object matting or a depth-map lens simulation. Fast motion,
translucent hair and difficult backgrounds remain limited by the existing matte quality. The
96-sample shader and maximum-detail analysis have a device-dependent performance cost.

Validation:
- Six pure JVM pixel tests cover subject identity, background defocus, no foreground color bleed,
  alpha retention, unchanged input, zero strength, invalid input and tiny images.
- `python3 tools/validation/verify_portrait_lens_gl.py` compiles both changed production shaders
  and renders synthetic mattes through the GLES shader on EGL/Mesa.
- Android instrumentation tests exercise live-preview/export parity with cached synthetic mattes.
- Real phone performance and real hair/motion quality require device footage testing; synthetic
  tests cannot establish PP-Matting accuracy or equivalence to a physical camera lens.
