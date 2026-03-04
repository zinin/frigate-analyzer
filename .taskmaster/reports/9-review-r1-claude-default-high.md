Reviewer: claude / default / high
Agent: claude
Model: default

### Strengths

- **Clear, layered authorization flow:** The new auth logic at lines 51–67 of `QuickExportHandler.kt` is well-structured — username null check → owner check → active user check. Each step has a distinct early-return with an appropriate user-facing message.
- **Good test coverage:** Four new test cases cover all authorization paths: no username, unauthorized user, owner bypass, and active user access. Each test verifies both positive behavior (what should happen) and negative guards (what should NOT happen, e.g., `userService` not called when username is null).
- **Security logging:** `logger.warn` on line 64 for unauthorized access attempts provides audit traceability — good practice for a security-sensitive handler.
- **Tests use real data classes where possible:** Helper methods like `createOwnerCallback()` and `createMessageCallbackWithoutUsername()` use real `CommonUser`/`PrivateChatImpl` instances instead of fragile mocks, following the existing pattern in `createMessageCallback()`.
- **Faithful to task requirements:** The implementation matches the specified authorization logic precisely.

### Issues

**[IMPORTANT] QuickExportHandler.kt:61 — Unnecessary DB call for owner**
`val isActiveUser = userService.findActiveByUsername(username) != null` is evaluated eagerly on every request, even when `isOwner` is already `true` on line 60. This means every owner callback triggers an unnecessary database query via R2DBC.
Suggested fix: short-circuit the evaluation:
```kotlin
val isOwner = username == properties.owner
val isActiveUser = !isOwner && userService.findActiveByUsername(username) != null
```
Or restructure with an early return for the owner before the `userService` call.

**[MINOR] QuickExportHandler.kt:34,65 — `authorizationFilter` retained only for a string getter**
After replacing `authorizationFilter.getRole()` with direct `userService` + `properties.owner` checks, the `authorizationFilter` dependency is only used on line 65 for `getUnauthorizedMessage()`, which itself just returns `properties.unauthorizedMessage`. This adds an unnecessary dependency that could be replaced with `properties.unauthorizedMessage` directly, simplifying the constructor and removing a bean coupling.

**[MINOR] QuickExportHandlerTest.kt:296–310 — "No username" test doesn't verify response message text**
The `handle rejects user without username with set username message` test asserts that `bot.execute` was called and no export occurred, but unlike the "rejects unauthorized user" test (lines 313–337), it does not capture the `AnswerCallbackQuery` and verify the actual text is `"Пожалуйста, установите username в настройках Telegram."`. This is inconsistent — the test name promises message verification but only checks side effects.

### Verdict

**APPROVE_WITH_NOTES**

The implementation correctly adds the required authorization checks, is well-tested, and matches the task specification. The unnecessary DB call for owner requests (IMPORTANT) should ideally be fixed as a quick follow-up since it's a one-line change with a real performance benefit on the hot path. The other two items are minor polish.