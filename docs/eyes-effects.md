# Automatic tracked effects

Tap a preset to apply it immediately. A preview-only GPU frame tap downsamples the current decoded frame and performs asynchronous face detection / CPU person segmentation. There is no required Analyze button or full-clip scan before selection. Tracking still has model startup and inference latency; instant zero-latency tracking is not promised. Preview frames expire after 250 ms and never satisfy export coverage.

27 eye/face presets include Fire, Laser, Electric Eyes, two Flame Eyes variants, Flaming Horns, reflection, scans and seven regional face distortions. 31 additional body decorations include wings, rings, particles, strokes and clones. These are original procedural variants, not copied CapCut assets or exact reproductions. Musical Notes and Shape Trails use body-relative patterns, not hand tracking; trails are procedural, not optical-flow motion histories. Generative outfits, 3D Dragon Year and other reference transformations needing separate assets/models are not implemented.

Export automatically computes missing complete eye/person tracks before rendering using the shared GPU graphs. Unsupported CPU fallback fails explicitly. Face tracking targets one primary face and records eye contours, blink, face and mouth bounds. Source time, effect timing, node amounts/keyframes and alpha are preserved. Full tracking caches are only published after complete analysis.

Validation: actual production eye and body shaders compile and render under Mesa/EGL; all 27 face/eye and 31 body variants have distinct visible output, zero-strength identity, missing-detection behavior and preserved alpha. Android CI and physical-device testing remain necessary. Device QA includes fast turns, occlusion, glasses, rotated source media, transform/trim/reopen, preview latency and export parity. No comparative superiority claim is made without side-by-side testing.

## Minified detector initialization fix

The phone APK from commit `05ca9295f7d039f361c58edfd8fa1ecb9eee335e` retained ML Kit manifest entries and registrar classes, but DEX inspection confirmed that R8 removed the public no-argument constructors of CommonComponentRegistrar, VisionCommonRegistrar and FaceRegistrar. Reflection could not instantiate the registrars, so `FaceDetection.getClient` dereferenced a null internal factory before decoding any frame.

The targeted ComponentRegistrar keep rule preserves both class identities and public constructors. CI now reads the actual phone and release APK DEX tables and merged manifest before accepting the artifacts. `verify_mlkit_apk.py` reproduces the failure on the original phone APK. No manual ML Kit reinitialization or invented eye positions are used to hide the error. The UI also bounds failure text so the retry button remains visible; full exception details stay in logcat.
