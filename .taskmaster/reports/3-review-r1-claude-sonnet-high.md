Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Exact spec conformance across all three branches**: The empty-frames branch attaches the keyboard to the text message, the single-frame branch adds `replyMarkup` to `SendPhoto`, and the media-group branch posts a separate follow-up message with the keyboard — exactly as required.
- **Consistent retry pattern**: The new "Send export button" call in the `else` branch correctly wraps in `RetryHelper.retryIndefinitely`, matching the existing pattern for all other sends.
- **Clean helper method**: `createExportKeyboard` is a properly scoped private method that avoids duplicating the keyboard-building DSL across the three call sites.
- **All three branches tested**: `TelegramNotificationSenderTest` provides one test per branch, each verifying both the button label and the callback-data format (`qe:<uuid>`).
- **Callback data format correct**: `"qe:$recordingId"` matches the spec (`qe:{UUID}`) and will be parseable by `QuickExportHandler`.
- **No scope creep or dead code**: The diff is surgical — no extra abstractions, no debug leftovers.

---

### Issues

**[IMPORTANT] `TelegramNotificationSenderTest.kt:52–65` — Reflection against internal tgbotapi types**

`extractReplyMarkup` uses `Class.methods.find { it.name == "getData" }` and `getReplyMarkup()` to introspect a `CommonMultipartFileRequest` at runtime. These are implementation details of tgbotapi's internal `CommonMultipartFileRequest` class, not part of its public contract. A library version change (renaming or removing those methods) will throw an `error(...)` at test time, not a compiler error, making it invisible until CI runs.

The comment acknowledges the issue, and there is no straightforward public API alternative because tgbotapi intentionally hides the wrapping. The trade-off is acceptable, but consider whether `mockkStatic` on the `sendTextMessage`/`sendPhotoMessage` extension functions could avoid the need for reflection entirely. If the library constraint is truly unavoidable, the comment should also mention the exact tgbotapi version the assumption is validated against (30.0.2).

**[MINOR] `TelegramNotificationSenderTest.kt:121` — Multiple-frames test hard-codes total `execute()` call count**

```kotlin
assertEquals(2, capturedRequests.size, "Should have 2 execute() calls")
```

The assertion assumes `sendMediaGroup` makes exactly one `execute()` call internally. This is true today (tgbotapi delegates to a single `execute(SendMediaGroup(...))` call), but it is an internal library behaviour that is not part of the public API. If tgbotapi ever changes how it dispatches media groups, this assertion will fail with a confusing error message.

A more robust alternative would be to verify the *last* request specifically (which is already done on line 124) and the *type* of the first request, rather than relying on a specific total count.

**[MINOR] `TelegramNotificationSenderTest.kt:43` — Emoji encoded as Unicode escape in assertion**

```kotlin
assertEquals("\uD83D\uDCF9 Экспорт видео", button.text)
```

The emoji `📹` is written as a raw character literal everywhere else in the codebase (source file, implementation). Using the surrogate-pair escape sequence here is not wrong but makes the test harder to read and introduces an inconsistency. Prefer the literal character or a named constant.

---

### Verdict

**APPROVE_WITH_NOTES**

The implementation is correct, complete, and well-structured — all three branches handle the new keyboard requirement exactly as specified, and every branch is covered by a meaningful test. The one IMPORTANT note (reflection on internal library types) is an acknowledged trade-off given tgbotapi's design, and the code comment adequately documents the reason; it does not block shipping but should be revisited if the library is upgraded.