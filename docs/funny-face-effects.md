# Tracked Funny Faces

The Funny Faces category includes Fat Face, Ass Face, Chipmunk Cheeks, Tiny Face,
Long Face and Balloon Head in addition to the seven existing presets. Ass Face is
a cartoon lower-face distortion with two cheek lobes and a central cleft.

These use the existing face analysis, amount slider, timed node effects, FX lanes,
Undo/Redo and project serialization. New shader slots are appended at 27–32;
legacy names/slots and the reserved Electric slot remain unchanged. No additional
model or asset download is introduced. Thumbnails use the production pipeline and
its existing calibrated portrait pose.

**Fix for cheek effects appearing over the scarf/chest:** The ncnn eye
landmarks use normalized, top-origin bitmap coordinates, whereas mouth,
face-box and contour uniforms are passed in bottom-origin GL coordinates.
Fat Face now flips the eye Y *only in its cheek anchor calculations*, leaving
all other eye/electric effects unchanged. The mask is anchored directly to
real eye and mouth locations (not the potentially oversized face ROI) and
its support fades out before the mouth line. If an eye or mouth landmark is
missing or anatomically implausible, it samples the original image. Tracked
Face Mesh cheek points may adjust width within strict plausibility limits
but cannot extend the effect down the neck or clothing. A synthetic
top-origin eye regression now asserts the full chest strip is unchanged.

**Fat Face = cheek-only plumping (PR #106):** Only the two tracked lateral
cheek regions are expanded. Forehead, eyes, nose, lips, mouth and central
chin are unchanged: the old downward/rotated chin warp is removed entirely.
The shader's paired cheek lobes sit below the eye line and fade out before
the lower jaw. Local, roll-aware eye, nose and lip guards prevent expressions
from being enlarged. The cheek-only inverse warp runs in face-metric space
using tracked Face Mesh cheek/jaw landmarks, so it follows head rotation.

The existing 2-pass GPU effect keeps the same slot 27, amount slider,
project serialization, FX track behavior and fast/full rendering parity.
The second pass is damped to 75% to avoid texture inversion around protected
features. Cheek/jaw contour dropout is handled by head-relative fallback
landmarks. This still uses one final texture sample.

The native tracking contract remains a 40-float face pose; version 19
tracking caches automatically require fresh analysis when needed. Occluded
cheek pixels hidden by hair or a hijab cannot be reconstructed with a 2D warp.
Fullness is therefore constrained near headwear to avoid severe seam artifacts.

## Validation

`python3 tools/validation/verify_eye_effects_gl.py` checks all eye and
funny presets for GLSL compilation, public effect behavior, render parity,
strength variation, time stability and zero-strength identity. Cheek-only
tests now assert visible change in both cheeks and source-exact preservation
of forehead, nose, lips, eyes and central chin at several strengths, including
rotated-jaw and missing-contour cases. UV scanlines are checked for folding.

Android CI covers shader regression, Kotlin unit tests, ARM64 APK build,
optimized release build and emulator pixel-parity smoke tests. Real-device
video remains necessary to judge skin/headwear boundaries and tracking
accuracy for all head poses.

Both the full creator shader and the dedicated eye/funny fast shader consume the
same uniforms and deformation function. The fast route still samples the source
texture once. Existing GPU export requirements for tracked face effects remain.

## Validation

`python3 tools/validation/verify_eye_effects_gl.py` renders the production GLSL on
Mesa/EGL. It checks all public eye/funny presets for visible, distinct output,
zero-strength identity, missing-face identity and alpha preservation. New comic
checks cover strength, head pose, unchanged distant background, temporal stability,
missing-mouth fallback, and pixel equality between full and fast shader routes.
The existing electric/fire-eye regression suite is run unchanged alongside these.

`EyeEffectsTest` also checks new category/slot routing, timed activation and disable
behavior without shifting legacy slots. Android CI runs the JVM tests, shader
validation, build and existing emulator export tests.

These are 2D face-anchored effects, not a reconstructed 3D face mesh. Extreme
profiles, occlusions, face-box scale changes and phone GPU throughput still require
real-device video review. There is no measured claim of superiority to CapCut;
compare the same clips, strengths, device and export settings before making one.
