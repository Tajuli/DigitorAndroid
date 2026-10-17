# Digitor remote presets (GitHub)
These **data-only** preset definitions are downloaded by the Android application at startup.
They use built-in render engines for preview **and** export; JSON cannot install executable
shaders, arbitrary code, ML models or plugins. New render algorithms still require an APK update.

## Directory layout
- `transitions/presets.json`: `id`, `label`, `category` (BASIC/CAMERA/EFFECT),
  `engineStyle` (existing `TransitionStyleV22` enum).
- `effects/presets.json`: unique `name`, existing `category` (Basic, Glitch,
  Retro, Lens, Motion), and native effect `vector` parameters.
- `filters/presets.json`: unique `id`, `name`, `description`, `group` LOOKS,
  `corrections`, optional `log` color wheels and `defaultIntensity`.

All files require `schemaVersion: 1` and a `presets` array. Individual JSON files must be
no larger than 256 KiB or 200 entries; unsupported fields cannot extend engine capabilities.
Values outside the app's validated ranges are rejected. Do not rename a published preset
ID/name used by saved projects; add a new one instead. Built-ins always take priority on ID
collisions. Android downloads and caches the three files independently. Updates are checked
at most once every six hours while the app is launched; failed checks retry on subsequent
launches (after one hour). The last good cache is used offline.

## Move to a separate GitHub repository
The connected GitHub integration currently cannot create repositories. Once a public asset
repository (for example `Tajuli/Digitor-Assets`) has been created, copy the three folders
to its root, then change the `BASE_URL` constant in
`app/src/main/java/com/tajuli/digitorandroid/editor/assets/GithubAssetSyncV1.kt`
to `https://raw.githubusercontent.com/Tajuli/Digitor-Assets/main`.
Keep the new repository public for unauthenticated HTTPS downloads.
No GitHub access token is needed or should be embedded in the app.
