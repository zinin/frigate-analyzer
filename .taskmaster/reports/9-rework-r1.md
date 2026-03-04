## Fixes Applied

### [IMPORTANT] Short-circuit owner DB call (QuickExportHandler.kt:61)
Changed `val isActiveUser = userService.findActiveByUsername(username) != null` to `val isActiveUser = !isOwner && userService.findActiveByUsername(username) != null`. This avoids an unnecessary R2DBC database query when the user is already identified as the owner, and prevents owner lockout if the database is degraded — matching the short-circuit behavior of `AuthorizationFilter.getRole()`.

### [IMPORTANT] Remove vestigial `authorizationFilter` dependency (QuickExportHandler.kt:34,65)
- Replaced `authorizationFilter.getUnauthorizedMessage()` with `properties.unauthorizedMessage` (since `getUnauthorizedMessage()` is just a one-liner returning that same property)
- Removed `authorizationFilter` from the constructor parameters
- Removed the `AuthorizationFilter` import
- Removed the `authorizationFilter` mock and its usage from the test class
- Updated the unauthorized user test to verify against `properties.unauthorizedMessage` directly

### [MINOR] Strengthen "no username" test (QuickExportHandlerTest.kt:296-310)
Updated the test to capture `Request<*>` objects, filter for `AnswerCallbackQuery` instances, and assert that the response text equals `"Пожалуйста, установите username в настройках Telegram."` — matching the pattern used in the "rejects unauthorized user" test.

**Build verification:** Full `./gradlew build` passed (all 123 tests across all modules, ktlint clean).