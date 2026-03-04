Reviewer: claude / default / high
Agent: claude
Model: default

### Strengths

- **Proper coroutine safety**: `CancellationException` is correctly re-thrown (line 103-104), preventing silent coroutine cancellation swallowing — a common and dangerous Kotlin coroutine mistake.
- **Reliable resource cleanup**: The `finally` block at line 93-98 ensures temporary export files are always cleaned up, even when the video send times out or fails. Cleanup errors are caught and logged, not propagated.
- **Dual timeout strategy**: Export (5 min) and video send (configurable via `properties.sendVideoTimeout`) have separate, appropriate timeouts using `withTimeoutOrNull`, preventing indefinite hangs.
- **Well-structured tests**: 24 tests organized in nested classes with clear naming. The `createMessageCallback()` factory method (line 199-222) elegantly works around MockK's limitations with tgbotapi value classes by using real data class instances combined with interface mocks.
- **Prefix consistency verification**: The test at line 98-103 that asserts `QuickExportHandler.CALLBACK_PREFIX` matches `TelegramNotificationSender.CALLBACK_PREFIX` is a smart cross-module contract test that will catch divergence early.
- **Extracted `parseRecordingId`**: Moving UUID parsing into a testable companion function (line 137-144) with proper null return instead of exception is clean design.
- **Resilient button state management**: The `editMessageReplyMarkup` call at line 60-67 is wrapped in try-catch, so a Telegram API failure updating the button doesn't block the actual export operation.

### Issues

**[IMPORTANT] QuickExportHandler.kt:49-54 — Authorization check is incomplete**
The handler only verifies that `callback.user.username != null` (line 50), but does NOT actually check whether the user is authorized (owner or registered active user). Any Telegram user with a username set can trigger video exports. The existing `AuthorizationFilter.getRole()` works with `CommonMessage` not callback queries, but the handler should still verify the username against the owner property and the user service. As-is, this is an authorization bypass — anyone who receives a forwarded notification message can press the button and trigger an export.
Suggested fix: Either add a new method to `AuthorizationFilter` that accepts a username string directly (e.g., `suspend fun isAuthorized(username: String): Boolean`), or inline the check: verify `username == properties.owner || userService.findActiveByUsername(username) != null`.

**[IMPORTANT] QuickExportHandler.kt — No concurrent export protection**
The existing `ExportCommandHandler` uses `ActiveExportTracker` to prevent multiple concurrent exports from the same chat (see `ExportCommandHandler.kt:43`). The `QuickExportHandler` has no such guard. A user can rapidly press the export button multiple times (or multiple detection notifications arrive), triggering N concurrent `ffmpeg` export processes simultaneously. This could exhaust system resources (disk I/O, CPU, temp disk space).
Suggested fix: Inject `ActiveExportTracker` and call `tryAcquire/release` around the export operation, sending a "please wait" message if already active.

**[MINOR] QuickExportHandler.kt:107-112 — Error classification by message string is fragile**
Error types are determined by checking `e.message?.contains("not found")` and `e.message?.contains("missing")`. The `VideoExportServiceImpl.exportByRecordingId` throws `IllegalArgumentException` for "not found" and `IllegalStateException` for missing fields. Matching by exception type would be more robust and wouldn't break if someone changes the error message wording.
Suggested fix: Use `is IllegalArgumentException` / `is IllegalStateException` checks instead of string matching.

**[MINOR] QuickExportHandler.kt:146-154 — Duplicate keyboard creation logic with TelegramNotificationSender**
Both `QuickExportHandler.createExportKeyboard()` (line 146) and `TelegramNotificationSender.createExportKeyboard()` (TelegramNotificationSender.kt:105) produce identical inline keyboards. This is code duplication that could drift. The test at line 98-103 guards the prefix, but not the button text.
Suggested fix: Extract keyboard creation into a shared utility (e.g., in the companion of `QuickExportHandler` and have `TelegramNotificationSender` reuse it), or at minimum add a constant for `EXPORT_BUTTON_TEXT`.

**[MINOR] QuickExportHandler.kt:138 — `parseRecordingId` accepts data without prefix**
`String.removePrefix()` returns the original string unchanged if the prefix is absent. So `parseRecordingId("550e8400-e29b-41d4-a716-446655440000")` (a raw UUID without `qe:` prefix) would succeed, which is misleading. The test at line 74-78 (`returns null for data without prefix`) passes only because `"not-a-uuid"` isn't a valid UUID, not because the prefix is validated.
Suggested fix: Add an early `if (!callbackData.startsWith(CALLBACK_PREFIX)) return null` guard.

### Verdict

**APPROVE_WITH_NOTES**
The implementation is solid — well-structured, properly handles corout