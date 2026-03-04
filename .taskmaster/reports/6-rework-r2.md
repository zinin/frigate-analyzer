## Fixes Applied

### Issue 1 [IMPORTANT]: Deduplicate CALLBACK_PREFIX and createExportKeyboard

**Files changed:**
- `TelegramNotificationSender.kt` — Removed duplicated `CALLBACK_PREFIX`, `EXPORT_BUTTON_TEXT`, and `createExportKeyboard()`. Now imports and delegates to `QuickExportHandler.createExportKeyboard()` as the single canonical source. Removed unused imports (`CallbackDataInlineKeyboardButton`, `InlineKeyboardMarkup`, `matrix`, `row`, `UUID`).
- `TelegramNotificationSenderTest.kt` — Updated `assertExportKeyboard` to reference `QuickExportHandler.CALLBACK_PREFIX` instead of the removed `TelegramNotificationSender.CALLBACK_PREFIX`.
- `QuickExportHandlerTest.kt` — Removed the `CALLBACK_PREFIX matches TelegramNotificationSender prefix` test as it's no longer needed (deduplication eliminates the divergence risk by construction).

### Issue 2 [IMPORTANT]: Add test for sendVideo timeout path

**File changed:** `QuickExportHandlerTest.kt` — Added test `handle sends timeout message when sendVideo exceeds timeout` that:
- Mocks `bot.execute` to delay only the `sendVideo` request past the 3-minute `sendVideoTimeout`
- Verifies the timeout message "Не удалось отправить видео: превышено время ожидания." is sent
- Verifies cleanup (`cleanupExportFile`) is still called via the `finally` block
- Verifies the button is restored to the export state after timeout

**Build status:** Full build passes (78 tasks), all 54 telegram module tests pass.