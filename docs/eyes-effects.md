# Tracked Eyes effects

Effects > Eyes > Analyze Eyes performs a cancellable, 24 Hz source-frame analysis. Once complete, tap an effect to add it to the selected editable node, tap again to remove it, and use the existing amount/keyframe and timeline-duration controls.

Twelve original procedural presets: Fire, Laser, Lightning, Plasma, Ice, Galaxy, Neon, Solar, Cyber, Heart, Star and Rainbow Eyes. No competitor assets are copied. Uses the existing bundled ML Kit face detector; no new ML dependency or model download.

The tracker records eye contours, head roll, eyelid aperture and classification-based openness. It follows one primary face, interpolates nearby samples, suppresses missing detections, rejects large position jumps/identity changes, and publishes durable data only after complete analysis. Video analysis takes the shared preview/export decoder lease, then restores the paused frame. Cancellation waits for an in-flight detector task before releasing its bitmap/detector. Reopening a project uses the saved track; changing the source trim requires analysis again. Tracking currently targets one visible face, not independent multi-person selection or gaze-direction estimation. Laser beams extend along the eye axis; they do not infer a 3D gaze vector.

Eyes execute in the existing resident creator GPU graph with the owning serial/parallel node, timeline membership and amount keyframes. Preview and export share the same shader and source clock. The legacy transformed-input paths invert the clip display transform when locating the eyes. Alpha is preserved. Missing or closed eyes do not receive invented fallback positions. Export rejects missing analysis and unsupported CPU-only execution instead of silently omitting the effect. Thumbnails detect eyes on the common photograph and render the production shader.

Validation:
- `python3 tools/validation/verify_eye_effects_gl.py`: Mesa/EGL compiles and renders the actual production shader. Checks all 12 presets are visible and distinct, zero intensity, closed eyes, missing eyes, alpha preservation, and movement/roll uniforms.
- `EyeEffectsTest`: source-time interpolation, blink values, missing samples, identity changes, discontinuities, timed enable/amount controls and angular wraparound.
- Local standalone Kotlin model compilation and behavioral checks pass with lightweight timeline dependency stubs; this is not a full Android build.
- Local Gradle Android compilation cannot resolve the repository's pre-existing Android Gradle Plugin 9.3.0. Android CI/device validation remains required.

Device QA still needed: fast head turns, occlusion, eyeglasses, profile faces, rotated source video, API 24/26 image orientation, trim/split/reopen, scaled/rotated clips, parallel mixers, export at different frame rates, and cancel/retry during long analysis. No claim of superior quality to CapCut is made without a side-by-side device comparison.

## Minified detector initialization fix

The phone APK from commit `05ca9295f7d039f361c58edfd8fa1ecb9eee335e` retained ML Kit manifest entries and registrar classes, but DEX inspection confirmed that R8 removed the public no-argument constructors of CommonComponentRegistrar, VisionCommonRegistrar and FaceRegistrar. Reflection could not instantiate the registrars, so `FaceDetection.getClient` dereferenced a null internal factory before decoding any frame.

The targeted ComponentRegistrar keep rule preserves both class identities and public constructors. CI now reads the actual phone and release APK DEX tables and merged manifest before accepting the artifacts. `verify_mlkit_apk.py` reproduces the failure on the original phone APK. No manual ML Kit reinitialization or invented eye positions are used to hide the error. The UI also bounds failure text so the retry button remains visible; full exception details stay in logcat.
