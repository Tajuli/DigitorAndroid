# Architecture versioning policy

Digitor uses stable source names for active runtime components. Feature iteration should update the existing implementation instead of creating another parallel `FooV2`, `FooV3`, ... file.

## Rules

- Public/editor entry points use canonical names such as `DigitorEditorScreen`.
- Do not create a new `DigitorEditorScreenV*` shell for feature work. Add integrations to the canonical shell or split a focused component into a descriptive file.
- Rendering implementations should keep one active class per responsibility. Replace obsolete implementations after the new path is proven instead of leaving several compiled copies.
- Numeric suffixes are allowed when they are part of a persisted compatibility contract (project JSON/cache/model schema) or when a migration genuinely requires simultaneous old/new readers.
- Persisted/versioned data contracts are not renamed only for cosmetic cleanup. Runtime code may continue consuming an older persisted contract while the active renderer/UI uses a canonical name.
- Prefer descriptive component names (`AutoCaptionWorkspace`, `CutoutWorkspace`, `TimelineEditor`) over iteration numbers.

## Current editor boundary

`MainActivity` enters through `DigitorEditorScreen`. The first cleanup pass keeps the large `DigitorEditorScreenV7` workspace implementation behind that stable entry point while old V2/V3/V4/V5/V8 screens are removed. A later refactor can split/rename the V7 implementation internally without changing the Activity-facing API.

## Beauty boundary

`BeautyFaceEffectV36` is the active portrait renderer used by `SharedVideoPipeline`. Older V28/V33/V34 render implementations are removed. V28/V29/V31 model and cache types remain because they are active persisted/analysis contracts consumed by the current renderer.
