# Filter / effect thumbnail pipeline

Digitor's filter/effect picker thumbnails use one shared neutral portrait source and the production render stack.

## Source image

- App resource: app/src/main/res/drawable-nodpi/filter_effect_preview_base.webp
- Bundled source: 320×180 WebP, already 16:9; decoded once, normalized to the renderer's logical 640×360 base, then rendered/cached at 320×180.
- Subject/background: realistic AI-generated photographic portrait of a young adult woman, face-forward and centered, with vivid green trees, blue sky, lake and mountains. This keeps skin, hair, foliage, water and sky visible so both portrait and landscape filter/effect changes are easy to judge.
- The image was generated specifically for Digitor's picker and is reused by every preset; no per-preset preview images are stored.

Only this one source image is bundled for filter/effect previews. No per-preset preview images are stored.

## Rendering contract

Filter/Effect thumbnails are generated from one shared neutral source image using Digitor’s real render pipeline.

A cache miss builds a synthetic one-second clip with Digitor's normal node graph:

- None is a true identity thumbnail: the shared source is returned untouched.
- Filters are represented by the same CreatorFilterCatalogV36 marker used by an edited clip, with thumbnail preview amount fixed at 1.0.
- Visual effects are taken directly from CreatorEffectCatalogV25 and attached as normal NodeEffect entries, also at thumbnail preview amount 1.0.
- Every preset renders the entire frame with SharedVideoPipeline.compositedExportEffectsFor(...), so the production LUT/node, beauty and CreatorEffectGraphV25 shader code is reused.
- Time-dependent effects render at the fixed source timestamp 0.35 s. Static thumbnails are therefore deterministic and do not animate.
- Final output is a single full-frame 320×180 image. There is no BEFORE/AFTER split and no center divider.

There is no hand-authored color overlay or thumbnail-only approximation.

## Performance and failure behavior

Compose picker rows are LazyRow-based, so visible cards request work first. Picker cards are 170 dp wide with a 90 dp-tall 16:9 preview area for better visibility. Rendering runs on Dispatchers.Default, and cache misses are serialized to avoid concurrent EGL graph churn. Finished thumbnails live in a 12 MiB LruCache and are reused across recomposition and scrolling.

Picker interaction is a direct toggle: first tap applies the filter/effect, and tapping the same active thumbnail again removes it. None is the first card: for filters it clears the current filter group on the selected node; for effects it clears creator effects on the selected node. Existing amount sliders and explicit Remove/Delete controls remain available.

The current implementation creates a short-lived Media3 offscreen graph for each cache miss. It does not recreate one per UI frame or recomposition, but a future optimization could batch several misses into one persistent offscreen graph.

If a graph/preset fails, the picker does not crash. It shows the untouched original frame with a small “!” indicator and logs the failure under DigitorFxThumb.

Beauty presets use the real BeautyFaceEffectV36 path. The shared still asset is not pre-analyzed into a persisted BeautyFaceTrack, so geometry/semantic-mask-dependent refinements can use the production shader's normal no-track fallback until a future thumbnail-specific face-analysis cache is added.
