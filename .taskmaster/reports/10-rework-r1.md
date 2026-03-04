## Fixes Applied

### [IMPORTANT] Duplicate test `should handle export error gracefully` — FIXED
**Problem:** Test at line 733 threw the same `IllegalArgumentException("Recording not found")` as 3 existing tests, inflating test count without adding meaningful coverage.
**Fix:** Replaced with `handle does not send video or cleanup on generic RuntimeException` — a genuinely distinct test that:
- Uses `RuntimeException` (not `IllegalArgumentException`), exercising the generic "Ошибка экспорта" error path
- Verifies no video was sent (negative assertion with concrete type filtering)
- Verifies no cleanup was called (export threw before producing a file)

### [IMPORTANT] Fragile "video was sent" assertion — FIXED
**Problem:** Happy-path test detected video sending by counting `capturedRequests.size > knownRequestTypes`, which could produce false positives if new bot interactions are added.
**Fix:** Replaced with a two-step verification:
1. Filter out known non-video request types (AnswerCallbackQuery, EditChatMessageReplyMarkup, SendTextMessage)
2. Assert the remaining request's class name equals `"CommonMultipartFileRequest"` — the concrete tgbotapi type for `sendVideo()` with multipart uploads

Added a detailed comment explaining that `CommonMultipartFileRequest` is `internal` in tgbotapi, so `filterIsInstance` cannot be used and string-based class name checking is the necessary approach.

### [MINOR] Deviation from mocking style — SKIPPED
The review description was truncated at "Deviation from mocking style specifi" making the full recommendation unreadable. Inspecting lines 174-180: the code uses a real `TelegramProperties` constructor, which is explicitly listed as a **strength** by multiple reviewers ("Real objects over fragile mocks"). No change made.

### Verification
All 30 tests pass (5 test suites: CallbackPrefixTest, CreateExportKeyboardTest, CreateProcessingKeyboardTest, HandleTest, ParseRecordingIdTest).