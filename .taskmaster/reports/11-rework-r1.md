Fixed all 5 MINOR code review issues in `.claude/rules/telegram.md`:

1. **Russian subsection headings** (line 73, 84): Renamed `### Как работает` → `### How It Works` and `### Авторизация` → `### Authorization` to match English-only convention.
2. **Bilingual section header** (line 64): Changed `## Quick Export (Быстрый экспорт)` → `## Quick Export`.
3. **Russian prose intro** (line 66): Translated to English: "Inline button on notifications for instant video export."
4. **QuickExportHandler missing from main Components table** (line 33): Added entry to the central inventory table.
5. **Export duration ambiguity** (line 80): Clarified from "±1 мин от recordTimestamp" to "±1 min from recordTimestamp in ORIGINAL mode (2 min total)", verified against `VideoExportService.kt:30`.

All workflow description text (lines 75-87) was also translated from Russian to English for consistency.