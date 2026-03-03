Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Magic strings extracted to constants**: The implementation went beyond the spec and extracted `EXPORT_BUTTON_TEXT`, `EXPORT_PROMPT_TEXT`, and `CALLBACK_PREFIX` into `companion object` constants. This is strictly cleaner than the raw string literals in the task description.
- **Retry consistency**: The new export-button message in the media group branch is wrapped in `RetryHelper.retryIndefinitely`, perfectly consistent with the existing pattern. No other call site in the method is unprotected.
- **Correct Telegram API workaround**: The separate message for media groups is the right approach — Telegram's API does not support `replyMarkup` on `sendMediaGroup`, so sending an extra text message is the only viable solution.
- **Test coverage of all three branches**: All four test cases are meaningful: empty frames, single frame, multi-frame (≤ `MAX_MEDIA_GROUP_SIZE`), and multi-chunk (> `MAX_MEDIA_GROUP_SIZE`). The 20-frame chunking test is a valuable edge-case check.
- **Documented reflection fragility**: `extractReplyMarkup` clearly warns that it is coupled to tgbotapi 30.0.2 internals and will need review after version upgrades. This is the right posture for unavoidable reflection.
- **`NotificationTask.recordingId` KDoc**: The field received a short but correct documentation comment explaining its purpose.

---

### Issues

**[IMPORTANT] `TelegramNotificationSender.kt`:94 — `CALLBACK_PREFIX` is private, blocking reuse in `QuickExportHandler`**

The task description explicitly states that the `qe:` prefix will be consumed by `QuickExportHandler`. With `CALLBACK_PREFIX` declared `private` inside the companion object, the handler cannot reference it and will be forced to duplicate the string literal `"qe:"`. If the prefix ever changes, the two sites will drift silently (no compile-time link). This is one of the most common sources of subtle bugs in callback-driven bots.

Suggested fix: promote to `internal` (or `const val` in a dedicated shared constants file/object) so `QuickExportHandler` can import it without going through reflection or string literals:
```kotlin
companion object {
    internal const val CALLBACK_PREFIX = "qe:"   // accessible to QuickExportHandler
    ...
}
```

---

**[MINOR] `TelegramNotificationSenderTest.kt`:43-44 — test hard-codes strings that duplicate private production constants**

Both the button text (`"📹 Экспорт видео"`) and callback prefix (`"qe:"`) are repeated as string literals in the test. If the constants are promoted to `internal` (see above), the test can reference them directly, eliminating the duplication and ensuring the test breaks at compile time rather than only at runtime if either constant changes.

---

**[MINOR] `TelegramNotificationSenderTest.kt`:66 — `innerData!!` with `!!` operator**

```kotlin
val innerData = dataMethod.invoke(request)
val replyMarkupMethod =
    innerData!!::class.java.methods.find { it.name == "getReplyMarkup" }
        ?: error("Inner data ${innerData::class} ...")
```

`innerData` is obtained from `Method.invoke()` which can return `null` for void methods. The `!!` will throw `NullPointerException` rather than the informative `error(...)` message. A null-check before the double-bang would make failure diagnosis easier:
```kotlin
val innerData = dataMethod.invoke(request)
    ?: error("getData() returned null for ${request::class}")
```

---

### Verdict

**APPROVE_WITH_NOTES**

The implementation is correct, well-structured, and fully aligned with the task requirements. The private-constant issue is the only meaningful concern, and it is a forward-looking one (the `QuickExportHandler` hasn't been written yet). Promoting `CALLBACK_PREFIX` to `internal` before that handler is implemented would save a rework cycle; everything else is minor polish.