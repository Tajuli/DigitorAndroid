# Editor runtime naming

New integration code should depend on the stable editor runtime names:

- `EditorViewModel`
- `DigitorEditorScreen`
- `EditorWorkspace`

Historical implementation suffixes such as `EditorViewModelV4` and `DigitorEditorScreenV7` are compatibility details only. They should not be introduced into new entry-point or app-shell code.

The next cleanup step is to migrate internal editor files incrementally, then remove the compatibility aliases only after CI and pixel-parity coverage stay green.
