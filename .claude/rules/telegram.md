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
| SharedFrameIds | `telegram/queue/` | Frame `file_id`s of one recording, shared by all its recipients — the first upload feeds the rest |
| DescriptionEditJobRunner | `telegram/queue/` | Launches background "wait for AI → edit placeholders" job (gated by `application.ai.description.enabled=true`) |
| DescriptionEditScope | `telegram/queue/` | Structured coroutine scope for description-edit jobs; `@PreDestroy` cancels in-flight edits |
| AiDescriptionTelegramGuard | `telegram/queue/` | Startup guard — fails fast when `ai.description.enabled=true` but `telegram.enabled=false` |
| RichNotificationRenderer | `telegram/service/impl/` | Builds the rich-message HTML — heading, metadata table, frames, `<details>`; escaping and trimming live here |
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

### Known exposure: a permanently rejected message stalls every notification

`RetryHelper.retryIndefinitely` catches every `Exception` except `CancellationException` and loops
forever (backoff 30s → 5min). The send path is wrapped in it, so on a **deterministic** failure —
a permanent `400`, a `403` from a user who blocked the bot, a deleted chat — `send()` never returns
and never throws. `consumeNotifications()` is a single sequential consumer, so it stays blocked on
that one task: notifications stop for every camera and every user until the application restarts,
and once `TELEGRAM_QUEUE_CAPACITY` tasks pile up `enqueue` suspends and pushes back-pressure into
`RecordingProcessingFacade`.

Note what this defeats: the consumer's own `try/catch` around `sender.send(task)` already logs and
moves to the next task. It is written for exactly this case and never fires, because the infinite
retry leaves nothing to throw. The duplicate-send behaviour documented under "Do not emit Bot API
10.3 constructs" has the same root.

Pre-existing (master behaves identically), but the rich-message rewrite widened it: the non-AI path
used to send plain text with no parse mode, so there was little to reject; every notification is now
an HTML document against an API with four undeclared limits (500 blocks, 16 nesting levels, 50
media, 20 table columns) and `SendRichMessage` performs no client-side validation at all.

Not fixed deliberately. The fix — bounding the attempts, then letting the existing catch drain the
queue — needs a number this repository does not contain: how long the system must keep trying before
it silently drops a motion alert. That is a product decision. `LocalVisualizationProperties.maxFrames`
is capped at `@Max(50)` — the same number as `MAX_MEDIA` — so a config drift cannot exceed Telegram's
media ceiling, but a collage above ten frames has never been rendered live: a large
`LOCAL_VIZ_MAX_FRAMES` is a live-verification item, not something the code guarantees.

## Recording Notification

One recording produces **one** rich message per recipient (`sendRichMessage`, Bot API 10.2).
`RichNotificationRenderer.render(data, DescriptionState, frameCount, language)` builds the whole
HTML — the same call serves the initial send and the later description edit. Signal-loss and
recovery alerts are not rich: they stay plain `SimpleTextNotificationTask` text.

| Block | Built from |
|---|---|
| `<h2>` heading + `<table bordered striped compact>` | `RecordingNotificationData`, i18n keys `notification.recording.*` |
| `<p>` short description | `DescriptionState` — placeholder, model text or fallback; omitted when `Absent` |
| frames | one frame → a bare `<img>`, two or more → `<tg-collage>`; capped at `MAX_MEDIA = 50`, which is also the `@Max` of `LOCAL_VIZ_MAX_FRAMES` (default 10; only up to ten frames were ever rendered live) |
| `<details>` detailed description | omitted when `Absent` **and when `Failed`** (the `<p>` already carries the reason); the model text is trimmed to whatever is left of `MAX_LENGTH = 32768` |
| Quick Export keyboard | `QuickExportHandler.createExportKeyboard`, passed as `reply_markup` — never as HTML buttons |

`<img src="tg://photo?id=fN"/>` and `InputRichMessageMedia.id` must carry the same string
(`RichNotificationRenderer.mediaId`), or Telegram rejects the message.

Trust boundary: only the three bundle values that *are* markup go in raw — the two
`ai.description.placeholder.*` keys and `ai.description.fallback.unavailable` carry `<i>…</i>`, and
escaping would show the tags. The other nine i18n values (title, the seven table labels, the
`<details>` summary) go through `msgText` and are escaped, as is everything that came from outside —
`RecordingNotificationData` fields and both model texts. The three raw values are guarded by nothing
but this paragraph: a malformed one is a deterministic 400 on the initial send, i.e. the queue stall
above.

Placeholders are rendered only when all three hold: `descriptionHandle != null`, a non-empty frame
list, and a `DescriptionEditJobRunner` bean. If any is missing, `descriptionHandle` is cancelled
rather than leaving an hourglass nobody will rewrite.

### file_id reuse across recipients

All tasks of one recording share one `SharedFrameIds`, created in `TelegramNotificationServiceImpl`.
The first recipient uploads the bytes and stores the `file_id`s from the send response; the rest
reference them instead of uploading again.

- The cached attempt is made **once and without `retryIndefinitely`**: a stale or unusable id would
  otherwise be retried forever and the upload path would never be reached. If Telegram *answers* the
  cached send with an error — any `RequestException` except a flood-wait; the library recognises
  `wrong file identifier` by its literal text, and a rich-media rejection comes back as
  `RICH_MESSAGE_PHOTO_INVALID` — the holder is invalidated and the frames go out as bytes, with the
  usual infinite retry. A transport failure (no answer at all) keeps the ids for the other recipients;
  this recipient still uploads. A 429 never reaches this code — ktgbotapi's default
  `ExceptionsOnlyLimiter` retries it inside `execute` — and would keep the cache if it did.
- If the photo-id count in the response does not match the number of frames sent — any mismatch,
  not only an undercount — the sender warns and does not cache that answer. It still edits when the
  holder already carries a full list from another recipient (a full list re-declares exactly the
  frames that were sent); only with nothing cached does it return and skip its own edit.
  A short list is unusable both ways: an edit re-declares the media wholesale, so a short array
  would strip frames off a message that was already delivered. `descriptionHandle` is **not**
  cancelled here — it is shared by every recipient of the recording, and a bad answer to one of
  them must not disown the rest.

Photos are collected recursively (`RichBlock.subBlocks`), not by filtering the top-level blocks:
two or more frames go out inside `<tg-collage>`, which comes back as a `RichBlockCollage` container
with the photos one level below.

### Do not emit Bot API 10.3 constructs

**Verified live: `<tg-button>`.** Telegram accepts it and the message arrives, but ktgbotapi 36.1.0
throws while deserializing the response — `RawMessage$$serializer` does not know the entity. The bot
then sees a failed send for a message already in the chat, and `RetryHelper.retryIndefinitely` sends
it a second time. Keep buttons in `reply_markup` until the library ships 10.3.

**What fails is a block or entity *type* the library does not model.** `RichBlockSerializer`
dispatches on `type` and errors on an unknown one, so every 10.3 block or entity type
(`<tg-document>`, `<tg-button>`) fails as above. Unknown *fields* on a known type are ignored — the
executor's JSON format is lenient — which is why `<table bordered striped compact>` is fine:
`compact` (`is_compact`) is a 10.3 addition, and the 2026-08-31 live check rendered and echoed it
correctly (recorded in the plan at `d2b8f4f:docs/superpowers/plans/2026-08-31-rich-message-notification.md`,
git history only). `<blockquote expandable>` inside a rich message is untested — the expandable
blockquote did work in plain messages before this branch, so the tag itself is not the problem — but a
new entity type would fail the same way, and that failure mode — a duplicate notification — is not
worth probing in production.

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

## ktgbotapi Waiter API (v36.1.0)

Source: https://github.com/InsanusMokrassar/ktgbotapi

Waiters return `Flow` — no `filter` param, use Flow operators `.filter{}.first()`.

| Function | Returns | Use case |
|----------|---------|----------|
| `waitDataCallbackQuery()` | `Flow<DataCallbackQuery>` | Inline button callbacks (has `.data`, `.message?.chat?.id`) |
| `waitTextMessage()` | `Flow<ChatContentMessage<TextContent>>` | Text input with chatId access (`.chat.id`, `.content.text`) |
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

## Message Type Hierarchy (changed in 34.0.0)

`CommonMessage<T>` was **renamed and re-parented** in 34.0.0. Both facts matter — the rename alone
is a trap:

| ≤ 33.1.0 | ≥ 34.0.0 | Notes |
|---|---|---|
| `CommonMessage<T>` | `ChatContentMessage<T>` | The correct substitution — this is what handler params and `waitTextMessage()` elements use |
| — | `CommonContentMessage<T>` | Textually identical to the old `CommonMessage<T>`, but does **not** extend the new `ChatMessage`, so `reply()` rejects it. Wrong substitution despite looking right |

`reply(to = ...)` now takes `ChatMessage`, not `AccessibleMessage`. Upstream remapped its own
typealiases the same way (`TextMessage` = `ChatContentMessage<TextContent>`).

**Mocking constraint.** `ChatContentMessage` and `CommonContentMessage` are `sealed` and the Kotlin
compiler emits a JVM `PermittedSubclasses` attribute for them, so **MockK cannot proxy them** —
`mockk<ChatContentMessage<…>>()` fails with `IncompatibleClassChangeError`. The sealed chain is
`ChatContentMessage` → `PossiblySentViaBotCommonMessage` → {`BusinessContentMessage`,
`ChannelContentMessage`, `PrivateContentMessage`, `PublicContentMessage`}, of which the first three
are ordinary interfaces. Tests use **`PrivateContentMessage<T>`** — mockable, and semantically right
since the bot works in DMs.

Untyped `coEvery { bot.execute(any()) } returns mockk(relaxed = true)` yields a bare
`java.lang.Object` and throws `ClassCastException` at the suspend resume point. Always give the
stub a concrete type.

