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

## Callback Protocol

Callback prefix: `nfs:`. Variants:

| Payload | Effect |
|---------|--------|
| `nfs:u:rec:1` / `nfs:u:rec:0` | Enable / disable per-user recording notifications |
| `nfs:u:sig:1` / `nfs:u:sig:0` | Enable / disable per-user signal-loss notifications |
| `nfs:g:rec:1` / `nfs:g:rec:0` | Enable / disable global recording notifications (OWNER only) |
| `nfs:g:sig:1` / `nfs:g:sig:0` | Enable / disable global signal-loss notifications (OWNER only) |
| `nfs:close` | Close the inline keyboard |

The `:1` / `:0` suffix is always explicit (never a toggle) — the renderer derives current state
from the DB and emits the *opposite* action in the new keyboard. This keeps callback handling
idempotent and survives the keyboard being clicked twice in a row.

## State Storage

| Scope | Storage | Default |
|-------|---------|---------|
| Per-user recording flag | `telegram_users.notifications_recording_enabled` | `TRUE` |
| Per-user signal-loss flag | `telegram_users.notifications_signal_enabled` | `TRUE` |
| Global recording flag | `app_settings.notifications.recording.global_enabled` | `TRUE` (seeded by migration) |
| Global signal-loss flag | `app_settings.notifications.signal.global_enabled` | `TRUE` (seeded by migration) |

Schema details in `database.md`.

## Consumers

The flags are read before dispatching notifications:

- **Recording detections** — `RecordingProcessingFacade` → `TelegramNotificationQueue` checks
  `notifications.recording.global_enabled` and per-user `notifications_recording_enabled` for
  each subscriber.
- **Signal-loss alerts** — `SignalLossMonitorTask` (in core, see `pipeline.md`) checks
  `notifications.signal.global_enabled` and per-user `notifications_signal_enabled`.

A `FALSE` global flag short-circuits the whole class of notifications regardless of per-user
state; a `FALSE` per-user flag only suppresses delivery to that user.
