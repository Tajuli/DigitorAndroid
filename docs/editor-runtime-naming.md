# Editor runtime naming

New integration code should depend on the stable editor runtime names:

- `EditorViewModel`
- `DigitorEditorScreen`
- `EditorWorkspace`
- `EditorWorkspaceScreen`

The historical `DigitorEditorScreenV7` workspace symbol has been removed. The active workspace implementation now uses the canonical `EditorWorkspaceScreen` name directly.

`EditorViewModelV4` remains a compatibility implementation detail behind the `EditorViewModel` alias. New entry-point or app-shell code should use `EditorViewModel`.

Further runtime naming cleanup should continue in small CI-gated steps. Persisted model and migration version suffixes should remain unchanged unless an explicit compatibility migration is planned.
