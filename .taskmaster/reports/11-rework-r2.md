## Summary of fixes applied

### [IMPORTANT] Language inconsistency — Fixed
The previous rework (commit `35ac998`) had already translated all sub-headings (`### Как работает` → `### How It Works`, `### Авторизация` → `### Authorization`) and all prose to English. The only remaining Russian text was the button label literals (`"📹 Экспорт видео"` and `"⚙️ Экспорт..."`), which are actual source code values from `QuickExportHandler.kt` and should NOT be fully replaced. Added parenthetical English translations — `(Export video)` and `(Exporting...)` — to eliminate the mixed-language ambiguity while preserving the accurate code references.

### [MINOR] QuickExportHandler in main Components table — Already resolved
`QuickExportHandler` was already added to the main Components table at line 33 in the previous rework (commit `35ac998`). No duplicate exists — the entry in the Quick Export section table uses a different description focused on the callback query pattern. Both entries are intentional and consistent.

### [MINOR] "(2 min total)" clarification — Kept as-is
The clarification is technically accurate (verified against `VideoExportService.kt` comments), improves understanding, and the reviewers themselves acknowledged it as "defensible as a useful clarification." No change needed.