Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Clean separation of concerns**: The auth check is correctly split into two distinct steps — username null check and owner/active-user check — making the logic easy to follow.
- **Follows `AuthorizationFilter` logic faithfully**: The dual-check (`isOwner || isActiveUser`) correctly mirrors what `AuthorizationFilter.getRole()` does internally.
- **Security logging added**: `logger.warn { "Unauthorized quick export attempt from user: @$username" }` at line 64 provides operational observability for security incidents — consistent with what `AuthorizationFilter` already does.
- **Comprehensive test coverage**: All authorization paths are tested — no username, unauthorized user, owner bypass, active-user grant — with separate dedicated tests for each. The `createOwnerCallback()`, `createMessageCallbackWithoutUsername()` helpers keep tests well-structured.
- **Correct mock setup in `init`**: The default `coEvery { userService.findActiveByUsername(any()) } returns mockk()` sets a permissive baseline so existing export-flow tests don't need to be touched.
- **Test for unauthorized user now checks message content**: `filterIsInstance<AnswerCallbackQuery>()` with `.text` assertion is a meaningful, non-trivial test (line 329–333).

---

### Issues

**[IMPORTANT] QuickExportHandler.kt:34,65 — `authorizationFilter` is now a vestigial dependency**

After the refactor, `authorizationFilter` is only used on line 65:
```kotlin
bot.answer(callback, authorizationFilter.getUnauthorizedMessage())
```
But `AuthorizationFilter.getUnauthorizedMessage()` is a one-liner that simply returns `properties.unauthorizedMessage` (AuthorizationFilter.kt:49). Since `properties` is already injected into `QuickExportHandler`, this dependency now adds coupling without providing any value. The high-level task description's constructor example explicitly omitted `authorizationFilter`, and subtask 9.5 stated: *"Использовать `properties.unauthorizedMessage` вместо `authorizationFilter.getUnauthorizedMessage()`, так как AuthorizationFilter не является зависимостью QuickExportHandler."*

Suggested fix: Replace `authorizationFilter.getUnauthorizedMessage()` with `properties.unauthorizedMessage` and remove both the `authorizationFilter` constructor parameter and its import. Remove the corresponding mock from `HandleTest`.

---

**[MINOR] QuickExportHandler.kt:61 — Unnecessary DB call for bot owner**

```kotlin
val isOwner = username == properties.owner
val isActiveUser = userService.findActiveByUsername(username) != null  // always called
```

Both expressions are evaluated eagerly. When `isOwner == true`, the `findActiveByUsername` DB call is still made — as confirmed by the test which sets up `coEvery { userService.findActiveByUsername(properties.owner) } returns null`. This differs from `AuthorizationFilter.getRole()`, which uses `when` and short-circuits after the owner check.

Suggested fix:
```kotlin
val isOwner = username == properties.owner
val isActiveUser = !isOwner && userService.findActiveByUsername(username) != null
```

---

**[MINOR] QuickExportHandlerTest.kt:304–310 — "no username" test doesn't verify message text**

The test `handle rejects user without username with set username message` only verifies that `bot.execute` was called at least once, without checking the content:
```kotlin
coVerify { bot.execute(any<Request<*>>()) }  // any call passes
```
Compare this with the stronger "rejects unauthorized user" test that captures requests and verifies `it.text == unauthorizedMessage`. For consistency, the no-username test should capture the `AnswerCallbackQuery` and assert the text equals `"Пожалуйста, установите username в настройках Telegram."`.

---

### Verdict

**APPROVE_WITH_NOTES**

The core security logic is correct and the implementation broadly matches the task requirements. The main concern is that `authorizationFilter` was supposed to be removed (per the task spec's constructor signature and subtask 9.5 wording) but remains as a dependency used only as a pass-through to `properties.unauthorizedMessage`. This creates unnecessary coupling; replacing line 65 with `properties.unauthorizedMessage` directly would complete the intended refactor and is a two-line fix.