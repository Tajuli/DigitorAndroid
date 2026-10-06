# Tracked Funny Faces

The Funny Faces category includes Fat Face, Ass Face, Chipmunk Cheeks, Tiny Face,
Long Face and Balloon Head in addition to the seven existing presets. Ass Face is
a cartoon lower-face distortion with two cheek lobes and a central cleft.

These use the existing face analysis, amount slider, timed node effects, FX lanes,
Undo/Redo and project serialization. New shader slots are appended at 27–32;
legacy names/slots and the reserved Electric slot remain unchanged. No additional
model or asset download is introduced. Thumbnails use the production pipeline and
its existing calibrated portrait pose.

The renderer uses bounded inverse lenses in aspect-correct, face-roll coordinates.
The tracked mouth anchors cheek position, with a face-relative fallback if mouth
bounds are unavailable. Head yaw shifts the cheek pair. Each deformation has a
smooth compact boundary; distant background is unchanged. Multiple new presets
in one node combine with a normalized displacement budget. Existing presets keep
their original math. There is no procedural temporal jitter or extra tracking
smoothing, and no face pose means no deformation.

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
