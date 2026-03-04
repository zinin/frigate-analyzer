Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Clean internal structure**: `parseRecordingId` is correctly extracted as a companion function, making it independently testable without mocking infrastructure.
- **CancellationException is properly re-thrown** (line 109–110) — the handler doesn't accidentally suppress coroutine cancellation, which is the most critical coroutine contract to uphold.
- **Button state managed across all code paths**: processing state is always restored on timeout, error, and success — verified through request-capture tests.
- **`finally` block ensures cleanup** (lines 99–105): `cleanupExportFile` runs regardless of whether `sendVideo` times out, succeeds, or throws.
- **Good test coverage overall**: 24 tests with meaningful assertions (e.g., keyboard markup content inspection, error-message text matching, CancellationException propagation).
- **CALLBACK_PREFIX cross-checked**: the test at line 277 ensures the two constants (`QuickExportHandler` / `TelegramNotificationSender`) stay in sync.
- **Consistent Spring conventions**: `@ConditionalOnProperty`, constructor injection, and companion object constants align with the rest of the module.
- **Correct error-level logging**: `warn` for non-critical path failures, `error` only for unexpected exceptions.

---

### Issues

**[CRITICAL] `FrigateAnalyzerBot.kt` — Handler never invoked**

`QuickExportHandler` is a `@Component` that Spring instantiates, but nothing routes Telegram callback queries to it. `FrigateAnalyzerBot.registerRoutes()` registers only `onCommand` and `onContentMessage` triggers; there is no `onDataCallbackQuery` (or equivalent tgbotapi trigger) that dispatches `qe:…` payloads to `QuickExportHandler.handle()`. Pressing the export button will do nothing in production.

The fix requires adding a trigger in `FrigateAnalyzerBot.registerRoutes()`:
```kotlin
onDataCallbackQuery(Regex("${QuickExportHandler.CALLBACK_PREFIX}.+")) { callback ->
    quickExportHandler.handle(callback)
}
```
…and injecting `QuickExportHandler` into the bot. If this is intentionally deferred to a subsequent subtask, that must be explicitly tracked — the feature is completely non-functional as shipped.

---

**[IMPORTANT] `QuickExportHandler.kt:55–60` — Authorization only checks username presence, not user authorization**

The variable `username` is extracted and checked for null, but is then never used. `authorizationFilter` is injected and called only for its string message — the actual `getRole`/`findActiveByUsername` lookup is never performed. Any Telegram user who has a username (i.e., is not anonymous) can trigger a video export if they can interact with the message.

While the spec shows the same pattern, this differs from how `FrigateAnalyzerBot` handles authorization (it calls `authorizationFilter.getRole(message)`) and from how `ExportCommandHandler` prevents unauthorized use. At minimum, the handler should call `userService.findActiveByUsername(username)` or `authorizationFilter` should expose a method that works for callback contexts. The injected `authorizationFilter` currently serves no real security purpose here.

---

**[IMPORTANT] `QuickExportHandler.kt` — No concurrent export guard**

`ExportCommandHandler` uses `ActiveExportTracker` to prevent multiple simultaneous exports per chat. `QuickExportHandler` has no equivalent guard: a user can double-click the button and trigger two parallel 5-minute export jobs for the same recording ID. Under load, this also means double resource consumption (Frigate CPU, disk, outbound bandwidth). `ActiveExportTracker` already exists as a bean — it should be injected and used here the same way it is in the existing export flow.

---

**[IMPORTANT] `TelegramNotificationSender.kt:94` / `QuickExportHandler.kt:134` — Duplicate `CALLBACK_PREFIX` and keyboard factory**

Both classes define `CALLBACK_PREFIX = "qe:"` independently, and `TelegramNotificationSender` has a private `createExportKeyboard` that creates the identical "📹 Экспорт видео" button. A cross-test (`CallbackPrefixTest`) keeps the constants in sync, which is good hygiene, but this is still two sources of truth for the same protocol token. If the prefix ever changes, both classes must be updated.

Suggested fix: make `TelegramNotificationSender` delegate to `QuickExportHandler.createExportKeyboard(recordingId)` — or extract a shared `QuickExportKeyboards` object. The current direction (`TelegramNotificationSender` → `QuickExportHandler`) is architecturally acceptable because sender already knows about the handler's domain.

---

**[MINOR] `QuickExportHandlerTest.kt:426,471,491` — Inconsistent mock setup for default-parameter methods**

Tests added in subtasks 6.1–6.2 stub `exportByRecordingId` with one argument:
```kotlin
coEvery { videoExportService.exportByRecordingId(recordingId) } returns tempFile
```
Tests added in subtasks 6.3–6.4 use the explicit form:
```kotlin
coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } returns tempFile
``