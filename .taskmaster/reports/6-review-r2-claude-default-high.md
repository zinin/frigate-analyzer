Reviewer: claude / default / high
Agent: claude
Model: default

### Strengths

- **Proper coroutine semantics**: `CancellationException` is correctly re-thrown (line 103-104), `withTimeoutOrNull` is used for non-throwing timeout handling, and `runTest` is used in all test cases. This shows solid understanding of structured concurrency.
- **Defensive error handling**: Every bot API call (`editMessageReplyMarkup`, `cleanupExportFile`, `sendVideo`) is wrapped in try-catch to avoid cascading failures. The `finally` block for cleanup (lines 93-98) ensures temp files are cleaned even on send failure.
- **Cross-module contract test**: The test at lines 99-104 asserting `QuickExportHandler.CALLBACK_PREFIX == TelegramNotificationSender.CALLBACK_PREFIX` is smart defensive programming that catches divergence at compile time rather than runtime.
- **Button state management**: The processing → restore lifecycle is well-implemented with proper restoration in all code paths (success, timeout, error). The `restoreButton` method is wrapped in try-catch so a Telegram API failure doesn't mask the real error.
- **Test quality**: 24 tests covering parsing, keyboard creation, authorization, button transitions, timeout, error mapping, and CancellationException propagation. The request capture pattern (`mutableListOf<Request<*>>()` + `filterIsInstance`) is an effective approach for verifying bot interactions with tgbotapi.
- **Consistent with project patterns**: Uses `@ConditionalOnProperty`, `KotlinLogging`, `@Component` annotation — all matching the existing code style. The authorization check mirrors the pattern in `AuthorizationFilter.getRole(username)`.

### Issues

**[IMPORTANT] QuickExportHandler.kt:134 & :146-154 — Duplicated CALLBACK_PREFIX and createExportKeyboard with TelegramNotificationSender**
Both `QuickExportHandler` and `TelegramNotificationSender` independently define `CALLBACK_PREFIX = "qe:"` and create identical "📹 Экспорт видео" keyboards. Since `TelegramNotificationSender.CALLBACK_PREFIX` is already `internal` (changed in a prior subtask specifically for this purpose), `QuickExportHandler` should import it instead of declaring its own. For the keyboard, consider making `QuickExportHandler.createExportKeyboard` the shared implementation, or extract it to a shared utility.
Suggested fix: `const val CALLBACK_PREFIX = TelegramNotificationSender.CALLBACK_PREFIX` or direct import; extract keyboard creation to a shared companion or utility.

**[MINOR] QuickExportHandler.kt:137-143 — `parseRecordingId` doesn't guard against input without prefix**
`removePrefix("qe:")` is a no-op when the input doesn't start with `"qe:"`. So `parseRecordingId("550e8400-e29b-41d4-a716-446655440000")` would return a valid UUID even though it lacks the prefix. In practice this is safe because `FrigateAnalyzerBot` filters on `it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX)` before dispatching, but the function would be more correct with a prefix presence guard.
Suggested fix: Add `if (!callbackData.startsWith(CALLBACK_PREFIX)) return null` as the first line.

**[MINOR] QuickExportHandler.kt:109-111 — Error classification via exception message string matching is fragile**
Matching error types by `e.message?.contains("not found")` / `contains("missing")` is brittle — any exception with those words in the message (even unrelated ones) would match, and refactored exception messages would silently break the mapping. This was in the spec so it's not a deviation, but consider using typed exceptions (e.g., `is IllegalArgumentException` for not-found, `is IllegalStateException` for missing) which aligns with the `VideoExportService` javadoc (lines 33-34): `@throws IllegalArgumentException if the recording is not found`, `@throws IllegalStateException if the recording has no camId`.

**[MINOR] QuickExportHandlerTest.kt:423-441 vs 504-534 — Duplicate test for "not found" error**
`handle sends error message for not found recording` (line 423) and `handle sends not found message and restores button for not found recording` (line 504) test the same scenario with the same setup. The second test is a strict superset — it also checks button restoration. The first test is redundant.
Suggested fix: Remove the test at line 423 or merge the assertion into the test at line 504.

### Verdict

**APPROVE_WITH_NOTES**

The implementation is solid, well-tested, and matches the task requirements. The code follows project conventions, handles errors defensively, and respects coroutine cancellation semantics. The CALLBACK_PREFIX duplication is the most notable concern — the constant was already made `internal` in a prior subtask specifically for reuse, so using it directly would be better. The remaining issues are minor improvements that don't affect correctness or production safety.