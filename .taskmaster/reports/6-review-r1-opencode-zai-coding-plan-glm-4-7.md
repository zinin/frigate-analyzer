Reviewer: opencode / zai-coding-plan/glm-4.7
Agent: opencode
Model: zai-coding-plan/glm-4.7

### Strengths
- Clean, readable code following existing project patterns (matches ExportExecutor style)
- Proper `CancellationException` handling - propagates correctly without swallowing coroutine cancellations
- Correct use of `withTimeoutOrNull` for both export and sendVideo operations
- Reliable file cleanup via `finally` block with nested try-catch for safety
- Early callback answer via `bot.answer(callback)` to clear Telegram's loading indicator
- Comprehensive test coverage (24 tests) covering:
  - Callback data validation (valid/invalid UUID, missing prefix, empty, partial)
  - Keyboard creation (structure, text, callback data)
  - Button state transitions (processing → export)
  - Error handling (not found, missing files, generic)
  - Timeout scenarios (export and sendVideo)
  - CancellationException propagation
  - Error recovery (button restoration)
- Extracted `parseRecordingId` as pure function for testability
- Proper error logging with context (recordingId, videoPath)
- BUTTON_PREFIX constant matches `TelegramNotificationSender.CALLBACK_PREFIX`
- Real data classes in tests (PrivateChatImpl, CommonUser) to avoid MockK issues with tgbotapi inline classes

### Issues

**[IMPORTANT] QuickExportHandler.kt:49-54 — Username authorization check is incomplete**
The handler checks only if username exists (`username == null`), but doesn't verify if that username is actually authorized via `AuthorizationFilter`. The code calls `authorizationFilter.getUnauthorizedMessage()` for the error text, but never calls `authorizationFilter.isAuthorized()` or similar.

Comparing to `ExportCommandHandler.kt:34`, authorization is handled at the command router level with `requiredRole`. QuickExportHandler is a callback handler (not a command), so router-level authorization may not apply. An explicit authorization check should be added.

**Suggested fix:**
Add explicit authorization check after retrieving username:
```kotlin
val username = callback.user.username?.withoutAt
if (username == null) {
    bot.answer(callback, authorizationFilter.getUnauthorizedMessage())
    return
}
// Verify username is authorized
if (!authorizationFilter.isAuthorized(username)) {
    bot.answer(callback, authorizationFilter.getUnauthorizedMessage())
    return
}
```
Note: Verify `AuthorizationFilter` has `isAuthorized(username)` method or appropriate equivalent.

**[MINOR] QuickExportHandler.kt:135 — Timeout value is hardcoded in companion object**
`QUICK_EXPORT_TIMEOUT_MS = 300_000L` is hardcoded instead of being configurable via properties. While `sendVideoTimeout` comes from `TelegramProperties`, export timeout is hardcoded.

**Impact:** Users cannot adjust export timeout without code changes. Different video export durations may require different timeouts.

**Suggested fix:** Move to `TelegramProperties` class:
```kotlin
data class TelegramProperties(
    ...
    val quickExportTimeout: Duration = Duration.ofMinutes(5)
)
```
Then use `properties.quickExportTimeout.toMillis()` in the handler.

**[MINOR] QuickExportHandlerTest.kt:248, 370, 409, 430, 451 — Mock uses `any()` matchers for optional parameters**
Test mocks use `coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }` with explicit `any()` matchers. While this works around the MockK default lambda parameter issue noted in the report, it's worth documenting why explicit matchers are required.

**Suggested fix:** Add comment above mock definition explaining MockK non-deterministic behavior with default lambda parameters.

**[MINOR] QuickExportHandler.kt:109-111 — Error message matching uses substring search**
Error mapping uses `e.message?.contains("not found") == true` which is fragile. If service throws "recording not found" (different wording) or "notFound" (no space), the message won't match and generic error is shown instead.

**Impact:** Users may see generic error message for specific errors.

**Suggested fix:** Consider either:
1. Documenting exact error message format in service contract
2. Using structured exceptions (custom exception types)
3. Using case-insensitive matching

### Verdict
APPROVE_WITH_NOTES

The implementation is functionally complete, well-tested, and follows project conventions. The only important issue is the incomplete username authorization check - it should verify authorization explicitly, not just check username existence. The timeout being hardcoded is minor but would be nice to make configurable. The error message substring matching is acceptable but could be more robust.