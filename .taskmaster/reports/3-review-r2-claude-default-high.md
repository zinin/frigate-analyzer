Reviewer: claude / default / high
Agent: claude
Model: default

### Strengths
- **Clean, focused implementation**: The `TelegramNotificationSender.kt` changes are minimal and well-targeted — only the necessary modifications for all three branches (`empty`, `single frame`, `media group`).
- **Good use of constants**: `EXPORT_BUTTON_TEXT`, `EXPORT_PROMPT_TEXT`, and `CALLBACK_PREFIX` are properly extracted into the companion object, avoiding magic strings in logic.
- **Correct Telegram API pattern**: The media group branch correctly sends the export button as a separate message after the group, since Telegram doesn't support inline keyboards on media groups. This is documented in the task and implemented correctly.
- **Comprehensive test coverage**: 4 tests covering all three code branches plus the multi-chunk edge case (20 frames → 2 chunks of 10). This goes beyond what was asked and catches a realistic scenario.
- **Consistent retry behavior**: The export button in the media group branch uses `RetryHelper.retryIndefinitely`, matching the existing retry pattern for all other send operations.
- **Tests verify actual values**: `assertExportKeyboard` asserts against literal strings (`"📹 Экспорт видео"`, `"qe:$recordingId"`) rather than referencing the production constants — correct testing practice that catches accidental constant changes.
- **Code style**: Follows existing Kotlin conventions, trailing commas, ktlint-formatted.

### Issues

**[MINOR] TelegramNotificationSenderTest.kt:55-68 — Reflection-based `extractReplyMarkup` is fragile**
The reflection approach to extract `replyMarkup` from `CommonMultipartFileRequest` is coupled to tgbotapi internals. The KDoc comment (lines 47-53) honestly documents this coupling and advises review after version upgrades, which mitigates the risk. However, if the library changes its wrapping mechanism, this test will fail with a confusing `error()` message rather than a clear assertion failure. Consider using `coVerify` with argument matchers as an alternative, or wrapping the reflection in a try-catch that gives a better diagnostic message referencing the tgbotapi version.

**[MINOR] TelegramNotificationSenderTest.kt:43 — Emoji literal uses unicode escape inconsistently**
In `assertExportKeyboard`, line 43 uses the literal emoji `"📹 Экспорт видео"` while one of the earlier test file revisions in the diff used `"\uD83D\uDCF9 Экспорт видео"`. The final file is consistent with direct emoji usage (matching the production code), so this is fine — just noting the diff showed an intermediate inconsistency that was resolved.

**[MINOR] TelegramNotificationSenderTest.kt:124,153 — Using `assert()` instead of `assertEquals` or JUnit assertions**
Lines 124 and 153 use Kotlin's `assert()` function (e.g., `assert(capturedRequests.size >= 2)`) which can be disabled by JVM flags (`-ea`). Prefer `assertTrue` or a dedicated assertion library call that always executes. This is unlikely to be an issue in practice (test runners typically enable assertions), but using `kotlin.test.assertTrue` would be more robust and consistent with the other assertions in the file.

### Verdict

**APPROVE**

The implementation precisely matches the task requirements across all three notification branches. Code is clean, follows existing patterns, and includes solid test coverage with 4 tests covering all branches plus the multi-chunk edge case. The minor issues (reflection fragility, `assert()` usage) are documented and low-risk.