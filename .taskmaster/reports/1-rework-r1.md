Fixed both MINOR code review issues:

1. **Named arguments in NotificationTask constructor** (`TelegramNotificationServiceImpl.kt:45-51`): Replaced 5 positional arguments with named arguments (`id =`, `chatId =`, `message =`, `visualizedFrames =`, `recordingId =`) for improved readability and safety against future parameter reordering.

2. **Unit test for recordingId propagation** (new `TelegramNotificationServiceImplTest.kt`): Added 4 unit tests:
   - `sendRecordingNotification propagates recordingId to NotificationTask` — captures the enqueued task and asserts `recordingId == recording.id`
   - `sendRecordingNotification skips notification when no detections`
   - `sendRecordingNotification skips notification when no subscribers`  
   - `sendRecordingNotification sends to all subscribers with correct recordingId` — verifies multi-subscriber scenario

All 25 telegram module tests pass (21 existing + 4 new). Build is clean.