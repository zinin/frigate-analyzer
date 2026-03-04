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

## Quick Export (Быстрый экспорт)

Инлайн-кнопка на уведомлениях для мгновенного экспорта видео.

| Component | Location | Purpose |
|-----------|----------|---------|
| QuickExportHandler | `telegram/bot/handler/quickexport/` | Handles callback query `qe:{recordingId}` |
| NotificationTask.recordingId | `telegram/queue/` | Recording ID for callback data |

### Как работает

1. При отправке уведомления (TelegramNotificationSender) добавляется inline-кнопка "📹 Экспорт видео"
2. Callback data формат: `qe:{UUID}` (например `qe:550e8400-e29b-41d4-a716-446655440000`)
3. При нажатии кнопки:
   - Кнопка меняется на "⚙️ Экспорт..."
   - Вызывается `VideoExportService.exportByRecordingId(recordingId)`
   - Экспортируется ±1 мин от recordTimestamp в режиме ORIGINAL
   - Видео отправляется в чат
   - Кнопка восстанавливается

### Авторизация

Только владелец и активные пользователи могут использовать быстрый экспорт.

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
| `TELEGRAM_UNAUTHORIZED_MESSAGE` | (Russian text) | Message for unauthorized users |
| `TELEGRAM_SEND_VIDEO_TIMEOUT` | 3m | Timeout for sending video |
| `TELEGRAM_PROXY_HOST` | (empty) | SOCKS5 proxy host. Empty = no proxy |
| `TELEGRAM_PROXY_PORT` | 1080 | SOCKS5 proxy port |

Disable for development: `java -Dapplication.telegram.enabled=false ...`

## Known Issues

- **Long polling timeout ERROR logs** — see [telegram-timeout-bug.md](telegram-timeout-bug.md) for details and workaround

## ktgbotapi Waiter API (v30.0.2)

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
