---
paths: "modules/telegram/**"
---

# Telegram Bot Integration

Library: `dev.inmo:tgbotapi` (ktgbotapi)

## Documentation

- Official: https://docs.inmo.dev/tgbotapi/index.html
- Telegram Bot API: https://core.telegram.org/bots/api
- **GitHub source** (для проверки API): https://github.com/InsanusMokrassar/ktgbotapi
  - Waiter expectations: `tgbotapi.behaviour_builder/src/commonMain/kotlin/dev/inmo/tgbotapi/extensions/behaviour_builder/expectations/`
  - Triggers: `tgbotapi.behaviour_builder/src/commonMain/kotlin/dev/inmo/tgbotapi/extensions/behaviour_builder/triggers_handling/`
  - Raw source: `https://raw.githubusercontent.com/InsanusMokrassar/ktgbotapi/master/<path>`
- Context7: `/insanusmokrassar/ktgbotapi` for examples

## Components

| Component | Location | Purpose |
|-----------|----------|---------|
| FrigateAnalyzerBot | `telegram/bot/` | Thin orchestrator: lifecycle, routing, auth, command menus |
| CommandHandler + handlers | `telegram/bot/handler/` | Command-per-class architecture for bot commands |
| ExportDialogRunner / ExportExecutor / ActiveExportTracker | `telegram/bot/handler/export/` | `/export` dialog, execution, concurrency guard |
| TelegramUserService | `telegram/service/` | Manages users (invite, activate, remove) |
| TelegramNotificationService | `telegram/service/` | Notification interface |
| TelegramNotificationServiceImpl | `telegram/service/impl/` | Active notification implementation |
| NoOpTelegramNotificationService | `telegram/service/impl/` | No-op when telegram disabled |
| TelegramNotificationQueue | `telegram/queue/` | Coroutine Channel-based notification queue |
| TelegramNotificationSender | `telegram/queue/` | Actual sending logic from queue |
| NotificationTask | `telegram/queue/` | Notification task data class |
| QuickExportHandler | `telegram/bot/handler/quickexport/` | Inline button callback for instant video export |
| AuthorizationFilter | `telegram/filter/` | Role-based auth (OWNER, USER) |
| RetryHelper | `telegram/helper/` | Retry logic for Telegram API calls |
| TelegramProperties | `telegram/config/` | Spring Boot config |

## Notification Queue

- `TelegramNotificationQueue` uses Kotlin Channel (configurable capacity)
- Coroutine-based: producer enqueues, consumer sends via `TelegramNotificationSender`
- Graceful shutdown via `@PreDestroy`
- `@ConditionalOnProperty` — only active when telegram enabled

## User Management

- Owner defined in config (`TELEGRAM_OWNER`)
- Users stored in `telegram_users` table
- Owner invites via `/adduser @username`
- Users activate via `/start`

## Bot Commands

| Command | Access | Description |
|---------|--------|-------------|
| /start | All | Activate subscription |
| /help | USER, OWNER | List commands |
| /export | USER, OWNER | Export camera video |
| /timezone | USER, OWNER | Configure timezone |
| /version | USER, OWNER | Show application version |
| /adduser | OWNER | Invite user |
| /removeuser | OWNER | Remove user |
| /users | OWNER | List all users |

## Quick Export

Inline button on notifications for instant video export.

| Component | Location | Purpose |
|-----------|----------|---------|
| QuickExportHandler | `telegram/bot/handler/quickexport/` | Handles callback queries `qe:{recordingId}` and `qea:{recordingId}` |
| NotificationTask.recordingId | `telegram/queue/` | Recording ID for callback data |

### How It Works

1. When sending a notification, `TelegramNotificationSender` adds two inline buttons in one row: "📹 Оригинал" and "📹 С объектами"
2. Callback data format: `qe:{UUID}` (original) / `qea:{UUID}` (annotated)
3. On button press:
   - Both buttons replaced with a single progress button (e.g. "⚙️ Склейка видео...", "⚙️ Аннотация 45%...")
   - Calls `VideoExportService.exportByRecordingId(recordingId, mode=ORIGINAL|ANNOTATED)`
   - Exports ±1 min from recordTimestamp (2 min total)
   - Timeouts: outer 5 min (original), outer 50 min (annotated); inner annotation timeout `DETECT_VIDEO_VISUALIZE_TIMEOUT` defaults to 45 min
   - On inner annotation timeout a dedicated message `quickexport.error.annotation.timeout` is shown (not the generic one)
   - Video is sent to the chat
   - Two buttons restored

### Authorization

Only the owner and active users can use quick export.

### Cancellation

Both QuickExport and `/export` support user-initiated cancellation.

| Component | Location | Purpose |
|---|---|---|
| ActiveExportRegistry | `telegram/bot/handler/export/` | Tracks active exports **in execution phase** by synthetic exportId. Dedup: by recordingId for QuickExport, by chatId for /export. |
| ActiveExportTracker | `telegram/bot/handler/export/` | Kept from before. Dialog-phase lock for /export — prevents two parallel /export dialogs in the same DM from hijacking each other's waiter replies. |
| CancelExportHandler | `telegram/bot/handler/cancel/` | Handles `xc:{exportId}` (cancel) and `np:{exportId}` (noop-ack). |
| ExportCoroutineScope | `telegram/bot/handler/export/` | Shared scope for export coroutines, gracefully cancelled on @PreDestroy. |
| CancellableJob (SAM) | `telegram/service/model/` | Hides AcquiredServer behind an abstraction; published via onJobSubmitted callback. |

Callback payload formats:
- `xc:{exportId}` — cancel: triggers registry `ACTIVE → CANCELLING` transition, cancels the coroutine
  (`Job.cancel()`), and if the vision server already has an active annotation job,
  `DetectService.cancelJob(server, jobId)` is fire-and-forget-posted to `POST /jobs/{id}/cancel`.
- `np:{exportId}` — no-op / "ack spinner" for the progress button shown alongside the cancel button
  (ktgbotapi requires every inline button to have either a URL or callback data). The handler
  silently acks the callback so the Telegram client stops the inline spinner without any UI change.

### Lock Ordering Invariant (Tracker + Registry)

`ActiveExportTracker` and `ActiveExportRegistry` both contain short-lived locks. To prevent any
future refactor from introducing a dual-lock deadlock, the following ordering must hold:

1. **Acquire `ActiveExportTracker.tryAcquire(chatId)` BEFORE any `ActiveExportRegistry.*` call.**
2. **Never call `ActiveExportRegistry.*` methods while holding `ActiveExportTracker`'s internal
   lock** (today the tracker's lock scope covers only `tryAcquire`/`release` — keep it that way).
3. Inside `ActiveExportRegistry`: `startLock` is held briefly by `tryStart*`; `synchronized(entry)`
   is held briefly inside `markCancelling`/`release` secondary cleanup. Neither acquires the other.
4. `ActiveExportRegistry.release()` must remove from `byExportId` BEFORE taking `synchronized(entry)`
   — see design for the rationale (avoids reverse-order deadlock with `markCancelling`).

### Known Limitations

- **ffmpeg cancellation is best-effort.** On MERGING/COMPRESSING stages `CancellationException` waits
  for `VideoMergeHelper.process.waitFor(...)` to return before unwinding. UI shows "⏹ Отменяется…"
  immediately but the final "❌ Отменён" appears only after ffmpeg finishes (seconds for merge, up to
  minutes for compress). Full sync cancel would need a cancellation-aware ffmpeg wrapper (out of scope).
- **Restart wipes registry.** Old cancel buttons respond with "Экспорт уже завершён или недоступен".
  Vision-server jobs orphaned on restart are killed by the server's TTL.
- **Legacy Telegram notifications.** Pre-deploy messages with `qe:/qea:` buttons don't have inline
  cancel — start a new export, which opens a fresh status message with the cancel button.

## Bot Architecture

- `FrigateAnalyzerBot` registers commands dynamically from `List<CommandHandler>`.
- Command ordering is controlled by handler metadata (`order`, then command name as tie-breaker).
- Authorization is centralized in bot router via `AuthorizationFilter.getRole()` and `requiredRole`.
- Owner menu registration uses `OwnerActivatedEvent` + `@EventListener` bridge with coroutine launch.

## Authorization

AuthorizationFilter returns UserRole (OWNER, USER) or null for unauthorized.

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `TELEGRAM_ENABLED` | true | Enable/disable bot |
| `TELEGRAM_BOT_TOKEN` | - | Bot token |
| `TELEGRAM_OWNER` | - | Owner username (without @) |
| `TELEGRAM_QUEUE_CAPACITY` | 100 | Notification queue size |
| `TELEGRAM_SEND_VIDEO_TIMEOUT` | 3m | Timeout for sending video |
| `TELEGRAM_PROXY_HOST` | (empty) | SOCKS5 proxy host. Empty = no proxy |
| `TELEGRAM_PROXY_PORT` | 1080 | SOCKS5 proxy port |

Disable for development: `java -Dapplication.telegram.enabled=false ...`

## ktgbotapi Waiter API (v31.2.0)

Source: https://github.com/InsanusMokrassar/ktgbotapi

Waiters return `Flow` — no `filter` param, use Flow operators `.filter{}.first()`.

| Function | Returns | Use case |
|----------|---------|----------|
| `waitDataCallbackQuery()` | `Flow<DataCallbackQuery>` | Inline button callbacks (has `.data`, `.message?.chat?.id`) |
| `waitTextMessage()` | `Flow<CommonMessage<TextContent>>` | Text input with chatId access (`.chat.id`, `.content.text`) |
| `waitText()` | `Flow<TextContent>` | Text content only (no chatId — prefer `waitTextMessage`) |
| `answer(callbackQuery)` | — | Answer callback query (extension on BehaviourContext) |

All accept optional `initRequest: Request<*>?` and `errorFactory: NullableRequestBuilder<*>`.

```kotlin
// Example: wait for callback with chatId filter
val cb = waitDataCallbackQuery()
    .filter { it.data.startsWith("prefix:") && it.message?.chat?.id == chatId }
    .first()
answer(cb)

// Example: wait for text with chatId filter
val msg = waitTextMessage()
    .filter { it.chat.id == chatId }
    .first()
val text = msg.content.text
```
