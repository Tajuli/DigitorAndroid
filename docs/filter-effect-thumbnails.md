# Filter / effect thumbnail pipeline

Digitor's filter/effect picker thumbnails use one shared neutral portrait source and the production render stack.

## Source image

- App resource: app/src/main/res/drawable-nodpi/filter_effect_preview_base.jpg
- Bundled master: 1024×683 JPEG; decoded once, center-cropped to a logical 640×360 (16:9) base, then rendered/cached at 320×180.
- Subject/background: centered young woman against neutral green foliage so skin, hair, greens, highlights and shadow changes are visible.
- Source: Wikimedia Commons, “Brunette woman portrait (Unsplash).jpg”, photographed by Christopher Campbell.
- License: CC0 1.0 Universal Public Domain Dedication. The source was published on Unsplash before its 2017 license change.
- Commons page: https://commons.wikimedia.org/wiki/File:Brunette_woman_portrait_(Unsplash).jpg

Only this one source image is bundled for filter/effect previews. No per-preset preview images are stored.

## Rendering contract

Filter/Effect thumbnails are generated from one shared neutral source image using Digitor’s real render pipeline.

A cache miss builds a synthetic one-second clip with Digitor's normal node graph:

- Filters are represented by the same CreatorFilterCatalogV36 marker used by an edited clip.
- Visual effects are taken directly from CreatorEffectCatalogV25 and attached as normal NodeEffect entries.
- The processed frame is rendered with SharedVideoPipeline.compositedExportEffectsFor(...), so the production LUT/node, beauty and CreatorEffectGraphV25 shader code is reused.
- Time-dependent effects render at the fixed source timestamp 0.35 s. Static thumbnails are therefore deterministic and do not animate.
- Final output is 320×180: left 50% original, right 50% processed, with a thin center divider.

There is no hand-authored color overlay or thumbnail-only approximation.

## Performance and failure behavior

Compose picker rows are LazyRow-based, so visible cards request work first. Rendering runs on Dispatchers.Default, and cache misses are serialized to avoid concurrent EGL graph churn. Finished thumbnails live in a 12 MiB LruCache and are reused across recomposition and scrolling.

The current implementation creates a short-lived Media3 offscreen graph for each cache miss. It does not recreate one per UI frame or recomposition, but a future optimization could batch several misses into one persistent offscreen graph.

If a graph/preset fails, the picker does not crash. It shows original | original with a small “!” indicator and logs the failure under DigitorFxThumb.

Beauty presets use the real BeautyFaceEffectV36 path. The shared still asset is not pre-analyzed into a persisted BeautyFaceTrack, so geometry/semantic-mask-dependent refinements can use the production shader's normal no-track fallback until a future thumbnail-specific face-analysis cache is added.
