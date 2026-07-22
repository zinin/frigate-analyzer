---
paths: "modules/telegram/**/handler/notifications/**"
---

# /notifications Dialog

The `/notifications` command opens an inline-keyboard dialog to toggle per-user notification
subscriptions; the bot owner additionally sees global toggles in the same keyboard.

General Telegram bot context (auth, queue, ktgbotapi waiter API) lives in `telegram.md` and is
loaded alongside this file whenever you touch `modules/telegram/**`.

## Components

| Component | Location | Purpose |
|---|---|---|
| `NotificationsCommandHandler` | `bot/handler/notifications/` | Sends initial dialog message in response to `/notifications` |
| `NotificationsSettingsCallbackHandler` | `bot/handler/notifications/` | Handles `nfs:*` callbacks, mutates per-user/global state |
| `NotificationsMessageRenderer` | `bot/handler/notifications/` | Renders text + keyboard from current state (per-user, plus global section for OWNER) |
| `NotificationsViewStateFactory` | `bot/handler/notifications/` | Single assembly point for `NotificationsViewState` (command, re-render, schedule flow) |
| `ScheduleCallbackHandler` | `bot/handler/notifications/` | Pure dispatch for `nfs:g:sched:*`, mutates schedule settings |
| `ScheduleKeyboardRenderer` | `bot/handler/notifications/` | Hour-picker and timezone screens of the schedule sub-dialog |
| `ScheduleSettingsFlow` | `bot/handler/notifications/` | Telegram I/O: maps dispatch outcomes to screen edits, manual-zone waiter |
| `ActiveZoneInputTracker` | `bot/handler/notifications/` | Dialog-phase lock for the manual-zone waiter — one pending `zman` input per chat |

## Callback Protocol

Callback prefix: `nfs:`. Variants:

| Payload | Effect |
|---------|--------|
| `nfs:u:rec:1` / `nfs:u:rec:0` | Enable / disable per-user recording notifications |
| `nfs:u:sig:1` / `nfs:u:sig:0` | Enable / disable per-user signal-loss notifications |
| `nfs:g:rec:1` / `nfs:g:rec:0` | Enable / disable global recording notifications (OWNER only) |
| `nfs:g:sig:1` / `nfs:g:sig:0` | Enable / disable global signal-loss notifications (OWNER only) |
| `nfs:close` | Close the inline keyboard |
| `nfs:g:sched:on` / `nfs:g:sched:off` | Enable / disable the detection schedule (OWNER only; `on` without a configured window opens the picker) |
| `nfs:g:sched:cfg` | Open the start-hour picker |
| `nfs:g:sched:s:<H>` | Start hour chosen → end-hour picker (start rides in callback data) |
| `nfs:g:sched:e:<S>:<E>` | End hour chosen → save window `[S:00, E:00)`, materialize zone if unset, auto-enable |
| `nfs:g:sched:zone` | Open the timezone screen (presets as in `/timezone` + manual input) |
| `nfs:g:sched:z:<olson>` | Set schedule zone from preset |
| `nfs:g:sched:zman` | Manual zone input via waiter (120 s timeout; any command cancels; one per chat) |
| `nfs:g:sched:home` | Back to the main screen |

On the per-user and global flag rows the `:1` / `:0` suffix is always explicit (never a toggle) —
the renderer derives current state from the DB and emits the *opposite* action in the new
keyboard. This keeps callback handling idempotent and survives the keyboard being clicked twice
in a row. The schedule's `on` / `off` pair is explicit for the same reason.

## State Storage

| Scope | Storage | Default |
|-------|---------|---------|
| Per-user recording flag | `telegram_users.notifications_recording_enabled` | `TRUE` |
| Per-user signal-loss flag | `telegram_users.notifications_signal_enabled` | `TRUE` |
| Global recording flag | `app_settings.notifications.recording.global_enabled` | `TRUE` (seeded by migration) |
| Global signal-loss flag | `app_settings.notifications.signal.global_enabled` | `TRUE` (seeded by migration) |
| Schedule enabled | `app_settings` → `notifications.recording.schedule.enabled` | absent = `FALSE` |
| Schedule window | `app_settings` → `notifications.recording.schedule.window` (`HH:mm-HH:mm`, `[start,end)`, start>end crosses midnight) | absent |
| Schedule zone | `app_settings` → `notifications.recording.schedule.zone` (IANA id) | absent |

Schema details in `database.md`.

## Consumers

The flags are read before dispatching notifications:

- **Recording detections** — `RecordingProcessingFacade` → `TelegramNotificationQueue` checks
  `notifications.recording.global_enabled` and per-user `notifications_recording_enabled` for
  each subscriber.
- **Signal-loss alerts** — `SignalLossMonitorTask` (in core, see `pipeline.md`) checks
  `notifications.signal.global_enabled` and per-user `notifications_signal_enabled`.
- **Detection schedule** — `NotificationDecisionServiceImpl` additionally suppresses recording
  notifications with reason `OUT_OF_SCHEDULE` when the schedule is enabled and
  `recording.recordTimestamp` falls outside the window. The timestamp is event time (a morning
  backlog run still delivers night events) evaluated in the schedule's own zone — the zone the
  window is interpreted in, independent of the camera and of the owner's personal `/timezone`.
  The gate applies before per-user fan-out (suppresses for ALL users; only the OWNER sees it)
  and only matters while the global recording flag is on. Schedule reads are fail-open:
  corrupt/unreadable settings degrade to "no schedule" with a warn log — deliberately
  asymmetric with the global flag, whose read failures keep the recording retryable; a schedule
  read failure produces extra notifications, never lost ones. Signal-loss alerts ignore the
  schedule.

A `FALSE` global flag short-circuits the whole class of notifications regardless of per-user
state; a `FALSE` per-user flag only suppresses delivery to that user.

## Operational Notes

- **The settings cache is per-process, has no TTL, and caches absence too.** `AppSettingsService`
  caches every `app_settings` key from its first read — including the fact that the key was
  *missing* — and only a write through `AppSettingsService` itself evicts an entry (a failed read
  caches nothing, so read errors stay transient). Direct SQL against `app_settings` is therefore
  invisible to a running process once the key has been read: that covers an `UPDATE` of a seeded
  key such as `notifications.recording.global_enabled` just as much as an `INSERT` of an absent
  one such as the `notifications.recording.schedule.*` triple. Treat a restart as mandatory after
  any manual SQL edit. Single-instance deployment assumed.
- **`nfs:` callbacks are registered with `markerFactory = null`** (`FrigateAnalyzerBot`), i.e. each
  update is handled in its own coroutine, fully parallel. With the library default
  (`ByUserCallbackQueryMarkerFactory`) every `nfs:` callback of one user is serialized through one
  consumer, and the `zman` waiter — which runs *inside* the handler for up to 120 s — froze every
  later `nfs:` click for its whole lifetime and then replayed it late over the unbounded channel
  (observed 2026-07-22). Parallel handling is safe here because every payload carries an explicit
  value (`:1` / `:0`, never a toggle) and re-renders read state back from the DB. The quick-export
  and cancel/noop registrations keep the default marker on purpose — no waiter runs there, so their
  serialization is free double-click protection.
- **Manual-zone waiter (`nfs:g:sched:zman`)** — the only waiter in the codebase started from a
  callback query rather than an `onCommand` trigger. Behaviour and limitations:
  - **Double `zman` is prevented by `ActiveZoneInputTracker`** (per chat, same shape as `/export`'s
    `ActiveExportTracker`): the second click gets `notifications.sched.zone.manual.busy` instead of
    a second waiter. Acquired before the prompt, released in a `finally` — timeout, failed save and
    cancellation all release it.
  - **A command sent while the waiter pends is treated as cancel.** The waiter subscribes to the
    subcontext's update flow, so a command reaches it *and* its own `onCommand` registration; the
    predicate `isCancelInput` (any text starting with `/`) stops the flow from also answering
    "unknown timezone". No IANA zone id starts with `/`. **Divergence:** `/timezone`'s waiter does
    NOT do this — a command there is both executed and consumed by the waiter. Pre-existing, out of
    scope for the schedule branch, worth a follow-up.
  - Does **not** survive a bot restart: it is in-memory coroutine state in the polling scope, so a
    restart or a `TelegramBotSupervisor` reconnect drops it silently — the owner replies to the
    prompt and gets nothing back, not even the timeout message.
  - **Cross-dialog interference** remains: `waitTextMessage()` filters on `chat.id` only and the
    tracker guards only the `zman` dialog, so a pending `zman` and a concurrent `/timezone` or
    `/export` waiter in one DM both see the owner's next text message, and which one wins is not
    defined by anything in our code.
  - 120 s hard timeout, matching `/timezone`.
  - One-shot on invalid input by design: a bad zone id gets the error message and the waiter exits
    rather than re-prompting.
- **Rollback:** disable via the `/notifications` toggle (instant), or
  `DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'` + restart
  for a full reset.
