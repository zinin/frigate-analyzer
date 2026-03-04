Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Implementation exceeds the spec on authorization** — `QuickExportHandler.kt:51` adds `|| authorizationFilter.getRole(username) == null` beyond what the spec required (which only checked for `username == null`). This is a correctness improvement: a user with a Telegram username but no app-level role is now properly rejected.
- **`parseRecordingId` correctly extracted** — Pulled into a `companion object` `internal fun` at `QuickExportHandler.kt:137–143`, making it directly testable without a running Spring context. The blanket catch of `_: IllegalArgumentException` (Kotlin 2.x wildcard syntax) is clean.
- **Proper coroutine cancellation discipline** — `CancellationException` is re-thrown at both the outer try-catch level (`QuickExportHandler.kt:103–104`) and through `FrigateAnalyzerBot.kt:136–138`, preventing silent coroutine swallowing.
- **Reliable cleanup via `finally`** — `videoExportService.cleanupExportFile(videoPath)` is called in a `finally` block (`QuickExportHandler.kt:93–98`), ensuring the file is deleted even when `sendVideo` throws or times out.
- **Two-level timeout protection** — Both the export (`QUICK_EXPORT_TIMEOUT_MS = 300_000L`) and the Telegram upload (`properties.sendVideoTimeout`) are individually bounded.
- **Test class `init` block for shared mock setup** — `HandleTest.init { coEvery { authorizationFilter.getRole(any<String>()) } returns UserRole.USER }` correctly sets a default auth stub for all tests, with specific tests overriding it (e.g., `handle rejects unauthorized user with username`). This was not visible in the diff preview but is confirmed at line 193 of the test file.
- **Real tgbotapi data classes in tests** — Using `PrivateChatImpl`, `CommonUser`, `MessageDataCallbackQuery` directly instead of MockK mocks is the right call given MockK's known incompatibility with tgbotapi's inline/value class hierarchies.
- **Integration in `FrigateAnalyzerBot` is clean** — `onDataCallbackQuery(initialFilter = { it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX) })` at `FrigateAnalyzerBot.kt:131–141` correctly routes by prefix, with an outer catch as a safety net.
- **24 focused tests** — Tests cover pure functions, keyboard shape/text/data, button lifecycle transitions, timeout, error classification, `CancellationException` propagation, and auth failure.

---

### Issues

**[IMPORTANT] `TelegramNotificationSender.kt:92,105–113` vs `QuickExportHandler.kt:146–154` — Button text and keyboard factory are duplicated with no shared source**

`TelegramNotificationSender` defines `private const val EXPORT_BUTTON_TEXT = "📹 Экспорт видео"` and a private `createExportKeyboard`. `QuickExportHandler.createExportKeyboard` hardcodes the same string literal directly. These are two independent implementations that must stay byte-for-byte identical to show a consistent UI.

If someone changes the label in `TelegramNotificationSender` (e.g., adds a count, or renames the emoji), the button the handler _restores_ after an export will differ from the button the notification _originally sent_ — the user sees the keyboard flicker between two different labels.

Suggested fix: promote `EXPORT_BUTTON_TEXT` to `internal` (or `TelegramNotificationSender` could delegate to `QuickExportHandler.createExportKeyboard` since it is already `public`). One canonical factory, one canonical text.

---

**[IMPORTANT] `QuickExportHandlerTest.kt` — No test for the `sendVideo` timeout path (`sent == null`)**

`QuickExportHandler.kt:90–92`:
```kotlin
if (sent == null) {
    bot.sendTextMessage(chatId, "Не удалось отправить видео: превышено время ожидания.")
}
```
This branch is reachable when Telegram's upload takes longer than `properties.sendVideoTimeout` (3 minutes in tests). There is no test exercising it. The export-timeout path IS tested (subtask 6.3 test 2), but the upload-timeout path is not, leaving a user-facing message completely untested. The button-restore after upload timeout is also untested.

---

**[MINOR] `QuickExportHandler.kt:109–110` — Error-to-message mapping by `e.message?.contains(...)` is fragile**

```kotlin
e.message?.contains("not found") == true -> "Запись не найдена."
e.message?.contains("missing") == true  -> "Файлы записи недоступны."
```

`VideoExportService` documents its failure modes as specific exception _types_ (`IllegalArgumentException` for not-found, `IllegalStateException` for no camId/timestamp). Matching on exception message text is brittle: if the implementation changes wording, logging locale, or wraps the exception, the mapping silently falls through to the generic message. Using `is IllegalArgumentException` / `is IllegalStateException` would align with the documented contract and be refactor-safe.

---

**[MINOR] `QuickExportHandlerTest.kt` — `handle continues processing when editMessageReplyMarkup throws` stubs with 1-arg but verifies with 3-arg**

```kotlin
coEvery { videoExportService.exportByRecordingId(recordingId) } returns tempFile  // 1-arg stub
...
c