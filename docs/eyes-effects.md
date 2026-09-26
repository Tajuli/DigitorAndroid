# Face-tracked effects

Eyes and Funny Faces are gated by a full-clip face-tracking analysis. Their effect thumbnails remain hidden until the selected clip has a complete compatible track. Analysis progress and Cancel are visible in the Effects panel. The tracking job belongs to a process-lifetime runtime rather than the composable, so switching category or leaving the Effects panel does not cancel the work.

Face analysis uses MediaPipe Face Landmarker in VIDEO mode. It prefers the Android GPU delegate and falls back to CPU when GPU initialization is unavailable. GPU creation and inference run on one dedicated worker thread as required by MediaPipe. Frames are sampled at 12 Hz with a 512 px long edge; the durable EyeTrack interpolates between adjacent samples under its existing 100 ms safety limit. VIDEO mode also lets MediaPipe reuse temporal tracking between frames instead of forcing a fresh face detection on every sample.

27 eye/face presets include Fire, Laser, Electric Eyes, two Flame Eyes variants, Flaming Horns, reflection, scans and seven regional face distortions. 31 additional body decorations include wings, rings, particles, strokes and clones. These are original procedural variants, not copied CapCut assets or exact reproductions. Musical Notes and Shape Trails use body-relative patterns, not hand tracking; trails are procedural, not optical-flow motion histories. Generative outfits, 3D Dragon Year and other reference transformations needing separate assets/models are not implemented.

Face tracking records both eyes, eye openness, roll, face bounds and mouth bounds for one primary face. The complete track is cached only after analysis succeeds. Source time, effect timing, node amounts/keyframes and alpha are preserved. Export reuses the cached track and still prepares a missing track if a project reaches export without compatible coverage.

Body semantic effects keep the PP-MattingV2 workflow. Their person matte path is independent from the face-analysis gate.

Validation from earlier revisions: production eye and body shaders compile and render under Mesa/EGL; all 27 face/eye and 31 body variants have distinct visible output, zero-strength identity, missing-detection behavior and preserved alpha. Device QA should cover fast turns, occlusion, glasses, rotated source media, trim/reopen, background panel changes, preview latency and export parity.

## Live refresh correction

Tracked-effect inference completion coalesces refresh work on the preview engine thread. During playback the next naturally decoded frame consumes the result; completion does not send a pause command. A paused viewer resubmits its latest requested cursor only if no newer transport request is pending. Preset changes reuse resident shaders and decoders unless graph topology requires a rebuild.
