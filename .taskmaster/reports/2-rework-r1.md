## Fixes Applied

### Fixed (3 issues)

1. **Multi-subscriber test unique UUIDs** (`TelegramNotificationServiceImplTest.kt:99-125`): Replaced single `returns taskId` with `returnsMany listOf(taskId1, taskId2)` so each task gets a unique UUID, matching production behavior. Added assertions verifying `tasks[0].id == taskId1` and `tasks[1].id == taskId2`.

2. **Message field assertion** (`TelegramNotificationServiceImplTest.kt:52-73`): Added `assertTrue(taskSlot.captured.message.contains("camera1"))` to the propagation test, verifying the formatted message includes the camera ID from the recording.

3. **KDoc for `recordingId`** (`NotificationTask.kt:12`): Added documentation: `/** ID of the recording, used for callback data in inline export buttons. */`

### Skipped (3 issues — not actionable)

- **Hardcoded date in test fixture**: Fixed dates in test fixtures are standard practice. The inconsistency with `Instant.now()` is harmless and changing it adds no value.
- **Git diff range observation**: Not a code issue — just a review artifact observation.
- **Commit prefix semantics**: Reviewer explicitly noted "not retroactively actionable."

### Build verification
Full build passed: 99 tests across all modules, including 25 telegram tests.