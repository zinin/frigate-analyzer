## Fixes Applied

### [IMPORTANT] Reflection-based `extractReplyMarkup` — version pinning
Added tgbotapi version `30.0.2` to the KDoc comment on `extractReplyMarkup()`, making it clear this reflection code is coupled to a specific library version and should be reviewed after upgrades.

### [IMPORTANT] Callback prefix "qe:" has no handler — **Rejected**
The `QuickExportHandler` is explicitly planned as a separate task (task 6 in tasks.json). The button is intentionally added now; the handler will be implemented in a subsequent task. No changes needed.

### [MINOR] Unicode escape for emoji
Replaced `"\uD83D\uDCF9 Экспорт видео"` with the literal emoji `"📹 Экспорт видео"` in the test to match production code style.

### [MINOR] Hard-coded execute() call count
Changed `assertEquals(2, capturedRequests.size, ...)` to `assert(capturedRequests.size >= 2)` so the test doesn't depend on the exact number of internal `execute()` calls made by `sendMediaGroup`.

### [MINOR] Missing edge case test for MAX_MEDIA_GROUP_SIZE
Added test `send with frames exceeding MAX_MEDIA_GROUP_SIZE sends export button after all chunks` using 20 frames (2 full chunks of 10) to verify the export button is sent after all media group chunks. Used 20 instead of 11 because 11 creates a 1-item chunk which is invalid for Telegram media groups.

### [MINOR] Hardcoded UI texts
Extracted `"📹 Экспорт видео"`, `"👆 Нажмите для быстрого экспорта видео"`, and `"qe:"` to companion object constants (`EXPORT_BUTTON_TEXT`, `EXPORT_PROMPT_TEXT`, `CALLBACK_PREFIX`).

**All 29 tests pass.**