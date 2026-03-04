Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Correct authorization logic**: The two-flag pattern (`isOwner` / `isActiveUser`) with `if (!isOwner && !isActiveUser)` correctly blocks unauthorized users in all cases.
- **Clean separation of concerns**: Username null-check is now a distinct early exit with a user-friendly, actionable message ("Пожалуйста, установите username…"), separate from the authorization failure path.
- **Security observability**: `logger.warn { "Unauthorized quick export attempt from user: @$username" }` before returning the denied response is a good addition for auditing.
- **Meaningful test coverage**: Tests covering no-username, owner bypass, active-user allow, and unauthorized-deny all use concrete assertions on `AnswerCallbackQuery` text or `coVerify` interactions — not just "no exception thrown."
- **Test init default is safe**: `coEvery { userService.findActiveByUsername(any()) } returns mockk()` means tests are authorised by default and only tests that need to deny access opt out — reduces boilerplate and makes intent clear.

---

### Issues

**[IMPORTANT] QuickExportHandler.kt:33 — `authorizationFilter` retained in constructor despite task spec removing it**

The task specification shows the constructor as:
```kotlin
class QuickExportHandler(
    private val bot: TelegramBot,
    private val videoExportService: VideoExportService,
    private val userService: TelegramUserService, // ДОБАВИТЬ
    private val properties: TelegramProperties,   // УЖЕ ЕСТЬ
)
```
At `67f55fd` the handler still carries `authorizationFilter` as a 5th constructor param (line 33) and uses it only for `getUnauthorizedMessage()` — which is a one-liner passthrough to `properties.unauthorizedMessage`. `getRole()` is no longer called. This leaves a stale Spring dependency that adds coupling without providing value. Fix: replace `authorizationFilter.getUnauthorizedMessage()` with `properties.unauthorizedMessage` and remove `authorizationFilter` from the constructor and imports. *(Note: this cleanup does appear in a later commit beyond this review range.)*

**[MINOR] QuickExportHandler.kt:50 — unnecessary intermediate `val user`**

```kotlin
val user = callback.user
val username = user.username?.withoutAt
```
`user` is only used on the immediately following line and never referenced again. This intermediate binding adds visual noise without aiding readability. Simplify to:
```kotlin
val username = callback.user.username?.withoutAt
```

**[MINOR] QuickExportHandlerTest.kt — "no username" test verifies call presence but not message content**

At `67f55fd`, the test `handle rejects user without username with set username message` only checks:
```kotlin
coVerify { bot.execute(any<Request<*>>()) }
```
Given the test name explicitly advertises it checks the "set username message", the assertion should capture and inspect the `AnswerCallbackQuery.text` field. Without this, the test would pass even if the wrong message were sent (e.g., the unauthorized message). *(This is fixed in a later commit outside this range.)*

**[MINOR] QuickExportHandlerTest.kt — owner test doesn't assert DB skip**

`handle allows owner access even when userService returns null` sets up:
```kotlin
coEvery { userService.findActiveByUsername(properties.owner) } returns null
```
At `67f55fd` the code lacks the `!isOwner &&` short-circuit that appears at HEAD, so `findActiveByUsername` IS called for the owner at this revision (the mock is exercised). The test is technically correct. However, adding `coVerify { userService.findActiveByUsername(properties.owner) }` would document this behavior explicitly and guard against regressions where the service is mistakenly not called.

---

### Verdict

**APPROVE_WITH_NOTES**

The authorization logic is functionally correct and the new code path (owner check + active-user check + separate null-username guard + security logging) matches the intent of the task. The primary outstanding item is the stale `authorizationFilter` constructor dependency — the task spec explicitly shows it removed, but it lingers at `67f55fd` only as a thin wrapper call that should be replaced with `properties.unauthorizedMessage` directly. The remaining findings are minor test quality improvements.