---
paths: "modules/telegram/**"
---

# Telegram Bot Integration

Library: `dev.inmo:tgbotapi` (ktgbotapi)

Sub-domain rules (loaded conditionally — see `paths:` in each file):

| File | Loads when working with | Topic |
|------|-------------------------|-------|
| `telegram-export.md` | `**/handler/export/**`, `**/handler/quickexport/**`, `**/handler/cancel/**` | `/export` + Quick Export flow, cancellation, lock-ordering invariant |
| `telegram-notifications.md` | `**/handler/notifications/**` | `/notifications` dialog, callback protocol, per-user/global flag storage |
| `telegram-timeout-bug.md` | `**/TelegramAutoConfiguration*` | Long-polling timeout workaround status |

## Documentation

- Official: https://docs.inmo.dev/tgbotapi/index.html
- Telegram Bot API: https://core.telegram.org/bots/api
- **GitHub source** (for API verification): https://github.com/InsanusMokrassar/ktgbotapi
  - Waiter expectations: `tgbotapi.behaviour_builder/src/commonMain/kotlin/dev/inmo/tgbotapi/extensions/behaviour_builder/expectations/`
  - Triggers: `tgbotapi.behaviour_builder/src/commonMain/kotlin/dev/inmo/tgbotapi/extensions/behaviour_builder/triggers_handling/`
  - Raw source: `https://raw.githubusercontent.com/InsanusMokrassar/ktgbotapi/master/<path>`
- Context7: `/insanusmokrassar/ktgbotapi` for examples

## Components

| Component | Location | Purpose |
|-----------|----------|---------|
| FrigateAnalyzerBot | `telegram/bot/` | Routes registrar + auth/command menus. Polling lifecycle is owned by TelegramBotSupervisor; this class exposes `registerRoutes(ctx)`, `registerDefaultCommands()`, `registerOwnerCommandsIfPossible()` for the supervisor to call. |
| TelegramBotSupervisor | `telegram/bot/supervisor/` | Polling lifecycle — supervised retry-loop with 5s→60s exponential backoff; owns botScope, drives FrigateAnalyzerBot bootstrap on each (re)connect. |
| TelegramLongPollingRunner | `telegram/bot/supervisor/` | Adapter interface isolating ktgbotapi's `buildBehaviourWithLongPolling`. Production impl `KtgBotApiLongPollingRunner` returns `Throwable?` (null on clean exit). Lets supervisor stay testable without `mockkStatic` on a library top-level function. |
| TelegramBotSupervisorHealthIndicator | `telegram/bot/supervisor/` | Spring Actuator `HealthIndicator`; delegates to `supervisor.computeHealth(now)`. `@Profile("!test")` to avoid breaking aggregated /actuator/health in tests. Spring exposes under key `telegramBotSupervisor`. |
| CommandHandler + handlers | `telegram/bot/handler/` | Command-per-class architecture for bot commands |
| TelegramUserService | `telegram/service/` | Manages users (invite, activate, remove) |
| TelegramNotificationService | `telegram/service/` | Notification interface |
| TelegramNotificationServiceImpl | `telegram/service/impl/` | Active notification implementation |
| NoOpTelegramNotificationService | `telegram/service/impl/` | No-op when telegram disabled |
| TelegramNotificationQueue | `telegram/queue/` | Coroutine Channel-based notification queue |
| TelegramNotificationSender | `telegram/queue/` | Actual sending logic from queue |
| NotificationTask | `telegram/queue/` | Notification task data class |
| DescriptionEditJobRunner | `telegram/queue/` | Launches background "wait for AI → edit placeholders" job (gated by `application.ai.description.enabled=true`) |
| DescriptionEditScope | `telegram/queue/` | Structured coroutine scope for description-edit jobs; `@PreDestroy` cancels in-flight edits |
| AiDescriptionTelegramGuard | `telegram/queue/` | Startup guard — fails fast when `ai.description.enabled=true` but `telegram.enabled=false` |
| DescriptionMessageFormatter | `telegram/service/impl/` | HTML escape + truncation, builds caption suffix + expandable blockquote |
| SignalLossTelegramGuard | `telegram/config/` | Startup guard — fails fast when `signal-loss.enabled=true` but `telegram.enabled=false` |
| AuthorizationFilter | `telegram/filter/` | Role-based auth (OWNER, USER) |
| RetryHelper | `telegram/helper/` | Retry logic for Telegram API calls |
| TelegramProperties | `telegram/config/` | Spring Boot config |

Export/QuickExport/cancellation components are documented in `telegram-export.md`;
`/notifications` dialog components are in `telegram-notifications.md`.

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
| /notifications | USER, OWNER | Manage notification subscriptions |
| /version | USER, OWNER | Show application version |
| /adduser | OWNER | Invite user |
| /removeuser | OWNER | Remove user |
| /users | OWNER | List all users |

## Bot Architecture

- `FrigateAnalyzerBot` registers commands dynamically from `List<CommandHandler>`.
- Command ordering is controlled by handler metadata (`order`, then command name as tie-breaker).
- Authorization is centralized in bot router via `AuthorizationFilter.authorize()` and `requiredRole`.
- Owner menu registration uses `OwnerActivatedEvent` + `@EventListener` bridge with coroutine launch.

## Bot Supervision

`TelegramBotSupervisor` runs the polling loop with bounded exponential backoff
(`INITIAL_BACKOFF=5s` → `MAX_BACKOFF=60s`, capped). An attempt that ran at least
`STABLE_THRESHOLD=60s` resets backoff on the next failure (long-running pollings
do not inherit stale backoff). Cancellation propagates cleanly without bumping
failure counters.

`TelegramBotSupervisorHealthIndicator` exposes `telegramBotSupervisor` in `/actuator/health`
with one of:

- **UP** — polling has run uninterrupted for ≥ `STABLE_THRESHOLD`.
- **OUT_OF_SERVICE** — startup grace (≤ `STARTUP_GRACE=2m`), transient backoff
  (recent stable run within `HEALTH_STALENESS=5m`), or just (re)connected
  (< `STABLE_THRESHOLD`).
- **DOWN** — supervisor not running, startup failed
  (`STARTUP_FAILURE_THRESHOLD=5` or grace expired), or no stable polling for
  > `HEALTH_STALENESS=5m`.

All thresholds are hardcoded constants in `TelegramBotSupervisor.kt` — by intent,
matching the policy of `WatchRecordsTask` (single-deployment project, no operator
tuning). Does NOT trigger automatic restart — operator must monitor health and
act manually. See `.claude/rules/pipeline.md` §"Health" for the rationale.

## Authorization

`AuthorizationFilter.authorize(...)` returns a `sealed AuthResult`:

| Result | Meaning |
|---|---|
| `Active(role: UserRole, user: TelegramUserDto)` | ACTIVE record found; `role` is `OWNER` or `USER`. |
| `NeedsActivation` | Owner without a DB row (clean DB), or any user with `INVITED` status. Router replies `common.error.activation.required` for every command except `/start` and for non-command text. |
| `Unauthorized` | Not the configured owner and no DB record. Router replies `common.error.unauthorized`. |

The router (`FrigateAnalyzerBot.registerRoutes()`) does an exhaustive `when` over the three branches. `/start` (`requiredRole == null`) bypasses the auth check and is handled directly by `StartCommandHandler`, which performs invite + activate.

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

## ktgbotapi Waiter API (v33.1.0)

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

