Reviewer: claude / default / high
Agent: claude
Model: default

### Strengths

- **Clean, focused implementation**: The production code changes in `TelegramNotificationSender.kt` are minimal and surgical — only the necessary `replyMarkup` parameter was added to each branch, following the existing code style with trailing commas, named parameters, and consistent formatting.
- **Correct architectural decision for media groups**: The media group branch correctly sends the export button as a separate message, properly handling the Telegram API limitation that inline keyboards can't be attached to media groups.
- **Consistent retry strategy**: The export button message in the media group branch is wrapped in `RetryHelper.retryIndefinitely()`, consistent with all other Telegram API calls in the class.
- **Good test coverage**: All three branches (`empty`, `single frame`, `multiple frames`) have dedicated tests that verify the keyboard structure, button text, and callback data format.
- **Well-structured helper in tests**: The `assertExportKeyboard()` helper method avoids duplication across all three tests and makes assertions clear.
- **`createExportKeyboard` is well-scoped**: Private method, single responsibility, clean use of the tgbotapi DSL (`matrix`/`row`).
- **Callback data format `qe:$recordingId`** is compact and follows the pattern described in the task for `QuickExportHandler`.

### Issues

**[MINOR] TelegramNotificationSenderTest.kt:52-66 — Reflection-based `extractReplyMarkup` is fragile**
The method uses reflection to extract `replyMarkup` from `CommonMultipartFileRequest` wrapping `SendPhoto`. This couples the test to internal tgbotapi implementation details (`getData()`, `getReplyMarkup()` method names). If the library upgrades and changes internal class structure, this test will fail with a confusing error. The well-documented KDoc comment mitigates this, and this approach is understandable given the library's wrapper design, but worth noting.
Consider: if tgbotapi exposes a public API to access inner request data in a future version, refactor away from reflection.

**[MINOR] TelegramNotificationSenderTest.kt:43 — Unicode escape for emoji in assertion**
`assertEquals("\uD83D\uDCF9 Экспорт видео", button.text)` uses a Unicode surrogate pair rather than the literal emoji `📹`. This is functionally equivalent but slightly less readable compared to the literal emoji used in the production code (`"📹 Экспорт видео"`). A minor inconsistency that doesn't affect correctness.

### Verdict

**APPROVE**

The implementation precisely matches all task requirements across all three notification branches. Code is clean, follows existing project patterns, and has meaningful test coverage for every branch. The minor issues identified (reflection in tests, emoji encoding inconsistency) are cosmetic and do not affect correctness or maintainability.