Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **All requirements satisfied in the final state.** `TelegramNotificationSender.kt` correctly imports `QuickExportHandler` (line 14) and calls `QuickExportHandler.createExportKeyboard(task.recordingId)` at all three notification branches (lines 41, 54, 78) — text-only, single photo, and media group follow-up button.
- **Single source of truth achieved.** The `"qe:"` prefix is defined only in `QuickExportHandler.CALLBACK_PREFIX`. No hardcoded callback strings appear anywhere in `TelegramNotificationSender.kt`.
- **No dead code.** No private `createExportKeyboard` duplicate method exists in the class — the concern in the task was pre-emptively resolved by prior work.
- **Comprehensive test coverage.** `TelegramNotificationSenderTest.kt` covers all four relevant scenarios: empty frames (text-only), single frame (photo), multiple frames (media group), and frames exceeding `MAX_MEDIA_GROUP_SIZE` (chunking). Each verifies the keyboard structure and callback data format.
- **Tests avoid magic strings.** `assertExportKeyboard` uses `QuickExportHandler.CALLBACK_PREFIX` for the callback data assertion (line 46) — the test itself is coupled to the same single source of truth.
- **Good inline documentation in tests.** The `extractReplyMarkup` helper (lines 52–81) includes an explicit `@comment` explaining the reflection approach, the tgbotapi version coupling risk, and what to check after upgrades. This is exemplary.
- **Constants are well-named.** `MAX_MEDIA_GROUP_SIZE`, `MAX_CAPTION_LENGTH`, and `EXPORT_PROMPT_TEXT` are all in the companion object, not scattered as magic literals.
- **Agents correctly identified no-op.** All four subtasks accurately determined the refactoring was already complete and made no spurious changes.

### Issues

**MINOR `TelegramNotificationSender.kt:70` — `@Suppress("OPT_IN_USAGE")` undocumented**
`@Suppress("OPT_IN_USAGE")` suppresses an experimental API warning for `bot.sendMediaGroup(...)`. The suppression is functional and causes no bug, but there's no comment explaining *which* API is experimental or *why* the opt-in was intentionally bypassed. If the API stabilises or changes, a future maintainer has no context.

Suggested fix: add a comment like `// sendMediaGroup is @BetaApi in tgbotapi — stable enough for production use here`.

**MINOR `TelegramNotificationSenderTest.kt:57–81` — reflection helper coupled to tgbotapi internals**
`extractReplyMarkup` uses reflection to look up `getData()` and `getReplyMarkup()` by method name strings. This is already explicitly called out in the doc comment, which is the right mitigation. No action required beyond what's already done, but be aware this is a maintenance liability on tgbotapi upgrades.

### Verdict

**APPROVE**

The task goal — eliminating the `createExportKeyboard` duplication in `TelegramNotificationSender` and routing all call sites through `QuickExportHandler.createExportKeyboard` — is fully achieved. The final code state is clean, the tests are meaningful and comprehensive, and no issues were introduced. The one suppressions annotation is a MINOR cosmetic concern that doesn't affect correctness or production readiness.