## Summary of review issues

### Already addressed (no action needed)

1. **[IMPORTANT] Owner short-circuit missing** — Already fixed in current code. Line 59: `val isActiveUser = !isOwner && userService.findActiveByUsername(username) != null` correctly short-circuits the DB call for the owner. Reviewers confirmed this was fixed in the rework commit.

2. **[IMPORTANT] `authorizationFilter` retained in constructor** — Already fixed. The constructor has no `authorizationFilter` parameter; `properties.unauthorizedMessage` is used directly on line 63. Reviewers confirmed this.

3. **[MINOR] "No username" test doesn't verify message content** — Already fixed in current code. The test (lines 298–308) captures `Request<*>` instances, filters for `AnswerCallbackQuery`, and asserts the text equals `"Пожалуйста, установите username в настройках Telegram."`. The reviewer was looking at an older revision.

### Fixed

4. **[MINOR] Duplicate chat/message creation in callback helpers** — Extracted shared `PrivateChatImpl` + `mockMessage` boilerplate from `createMessageCallback()`, `createOwnerCallback()`, and `createMessageCallbackWithoutUsername()` into a single `createCallbackWithUser(user, callbackId)` helper. The three methods now delegate to it, reducing 63 lines to 37. All 28 tests pass.