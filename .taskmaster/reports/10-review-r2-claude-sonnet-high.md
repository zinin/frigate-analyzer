Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Comprehensive happy-path test**: `should export video for authorized user` verifies 6 distinct behaviors in one test — callback answer, export invocation with correct ID, video send, file cleanup, button state lifecycle (processing → restore), and absence of error messages. This is exactly the kind of "full flow" integration test the task asked for.
- **Clever `coAnswers` type routing**: The mock routes `AnswerCallbackQuery → Boolean` and everything else → `ContentMessage`, which is necessary because the handler stores the `bot.sendVideo()` result in `val sent = withTimeoutOrNull(...)` and null-checks it. Without this, the handler's null check would treat the mock as `null` and send a spurious timeout message, breaking the happy-path assertions.
- **Correct test resource hygiene**: Real temp files are created and deleted in a `try/finally` block, ensuring cleanup even on test failure.
- **Informative failure messages**: Every `assertTrue` / `assertEquals` includes a descriptive message that shows actual values, which greatly aids debugging.
- **Minimal, targeted enhancement to the unauthorized test**: The added `nonAnswerRequests.isEmpty()` assertion cleanly strengthens an existing test without duplicating setup.
- **Tests are well-placed** in the existing `@Nested inner class HandleTest` and follow the class's established conventions.

---

### Issues

**[IMPORTANT] QuickExportHandlerTest.kt:290–301 — Video-send assertion is a weak negative inference**

The assertion `capturedRequests.size > knownRequestTypes` proves that *some* request was captured that isn't `AnswerCallbackQuery | EditChatMessageReplyMarkup | SendTextMessage`, but it doesn't assert that request is specifically a video send. If the handler were refactored to send a photo or document instead, or to fire an additional unknown request type while skipping video, this assertion would still pass.

The test is meant to be the primary "full flow" integration test; the video send is its core claim. That deserves an explicit assertion, not a count residual. (A subsequent commit did fix this with a `CommonMultipartFileRequest` class-name check, but at the reviewed HEAD `df927c4` the weaker form is present.)

*Suggested fix*: After the count assertion, add:
```kotlin
assertTrue(
    videoSendRequests.any { it::class.simpleName == "CommonMultipartFileRequest" },
    "Expected CommonMultipartFileRequest (tgbotapi internal sendVideo wrapper), but got: ..."
)
```

---

**[MINOR] QuickExportHandlerTest.kt:733 — Error test uses owner callback without documented justification**

`should handle export error gracefully` uses `createOwnerCallback()` while all analogous error tests (`handle sends error message for not found recording`, `handle sends error message for missing files`, etc.) use `createMessageCallback()`. The `init` block already stubs `userService.findActiveByUsername(any()) returns mockk()`, so regular-user authorization is already handled for free — there is no simplification gain from using the owner. The asymmetry could mislead a future reader into thinking the error-handling behavior differs between owner and non-owner.

*Suggested fix*: Replace `createOwnerCallback()` with `createMessageCallback()` to be consistent with the surrounding error tests, or add a comment explaining why owner is used here.

---

**[MINOR] QuickExportHandlerTest.kt:270 — Mock arity misrepresents the actual handler call**

The new tests stub `exportByRecordingId(eq(recordingId), any(), any())` (3-arg), but the handler calls `videoExportService.exportByRecordingId(recordingId)` (1 explicit arg, 2 defaults). Pre-existing tests in the same file correctly use the 1-arg form. The 3-arg form implicitly suggests the handler passes an explicit `duration` and `onProgress`, which it does not. While MockK handles default parameters transparently, the inconsistency within a single test class is confusing documentation.

*Suggested fix*: Use `coEvery { videoExportService.exportByRecordingId(recordingId) }` in the new tests to match the 1-arg call site in the handler and to be consistent with the tests at lines 447, 492, and 512.

---

**[MINOR] QuickExportHandlerTest.kt:733 — Undocumented reason for using `returns mockk(relaxed = true)` instead of `coAnswers`**

The happy-path test carefully documents *why* it uses `coAnswers` with type-specific returns. The error test silently uses `returns mockk(relaxed = true)` for all requests. A future maintainer may not understand why this is safe here (it works because the error path never reaches `bot.sendVideo()`, whose result *is* used in the happy path). The inconsistency without explanation is a subtle maintenance trap.

*Suggested fix*: Add a one-line comment before the `coEvery` mock:
```kotlin
// Error path never reaches sendVideo, so a uniform relaxed mock is safe here.
coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
```

---

**[MINOR] QuickExportHandlerTest.kt:750 — Partial ov