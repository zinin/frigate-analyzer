# Notification Schedule — Design

Date: 2026-07-18
Status: approved (brainstorming session)
Branch: feature/notification-schedule

## Problem

Detection notifications are currently controlled only by on/off flags (global + per-user).
The owner wants time-based control: e.g. deliver detection notifications only at night,
between 00:00 and 07:00. Outside that window notifications are noise (household members
moving around during the day).

## Agreed Requirements

| Question | Decision |
|---|---|
| Scope | One **global** schedule, configured by OWNER only |
| Notification types | **Recording detections only**; signal-loss alerts are unaffected |
| Outside the window | **Drop** — notification is never created (detections still stored in DB; no AI description job) |
| Schedule structure | **One daily window** `start–end`, same every day, midnight crossing supported (e.g. 23:00–07:00) |
| Timezone | **Explicit zone stored with the schedule** (defaults to the owner's current timezone via `TelegramUserService.getUserZone`, UTC fallback, when first enabled); not tied to owner's current zone afterwards |
| Time basis | **`recording.recordTimestamp`** (event time), not processing/send time — a night event processed from backlog in the morning is still delivered; a daytime event is never delivered |
| Configuration UI | Extend the existing `/notifications` dialog (OWNER global section) with an **inline hour picker** (whole hours) |

Effective delivery condition for a recording-detection notification:

```
recording.global_enabled AND (schedule disabled OR recordTimestamp in window) AND per-user flag
```

The schedule is an additional constraint layered on top of the existing flags; flag
semantics do not change.

The gate applies in `evaluate()` before per-user fan-out: an enabled schedule suppresses
detection notifications for ALL users, while only the OWNER sees or controls it in
`/notifications` — accepted (single-owner household system).

## Storage

Three new keys in the existing `app_settings` table (no schema migration, no seeding —
keys are created on first configuration; absent keys mean "schedule disabled"):

| Key | Value | Notes |
|---|---|---|
| `notifications.recording.schedule.enabled` | `"true"` / `"false"` | absent → `false` |
| `notifications.recording.schedule.window` | `"HH:mm-HH:mm"`, e.g. `"00:00-07:00"` | half-open interval `[start, end)`; `start > end` means midnight crossing |
| `notifications.recording.schedule.zone` | IANA code, e.g. `"Europe/Moscow"` | |

- `AppSettingsService` gains `getString`/`setString`, mirroring the existing
  `getBoolean`/`setBoolean` (storage is already string-typed; per-key cache unchanged).
- Toggling `enabled` and setting the zone write exactly one key each (window survives
  toggles). Saving a window writes up to three keys in a fixed order — window → zone
  (materialized if absent) → enabled LAST — so a concurrent reader sees either the old
  `enabled=false` or the complete new state. Per-key upserts are atomic, the sequence is
  not (a mid-sequence failure is covered by fail-open reads). The write order is an
  invariant — keep `enabled` last.
- Minutes are stored (`HH:mm`) even though the UI offers whole hours — format headroom
  without a future migration.

## Model & Services

- `NotificationSchedule(start: LocalTime, end: LocalTime, zone: ZoneId)` — data class in
  `model` with a pure `contains(instant: Instant): Boolean`: convert the instant to
  `LocalTime` in `zone`, test membership in `[start, end)` with midnight wrap when
  `start > end`.
- New `NotificationScheduleService` in `service`:
  - `suspend fun getRecordingSchedule(): NotificationSchedule?` — `null` when disabled
    **or** when stored data is unusable (fail-open, see Edge Cases);
  - `suspend fun setEnabled(Boolean)`, `setWindow(LocalTime, LocalTime)`, `setZone(ZoneId)`.
  - Encapsulates the three keys and window parsing/formatting; used by both the decision
    service and the Telegram handlers.
  - Lives in the `service` module and is deliberately NOT gated by
    `application.telegram.enabled` — the decision layer applies the schedule regardless
    of the bot being enabled.

## Decision Enforcement

`NotificationDecisionServiceImpl.evaluate()`:

- `scheduleAllows = schedule == null || schedule.contains(recording.recordTimestamp)` is
  computed **before** the tracker `try` block (independent of tracker results; reads go
  through the settings cache, so no prefetch/interface change is needed — the
  `globalEnabled` parameter of `evaluate()` stays as is).
- `getRecordingSchedule()` **never throws**: read failures degrade to `null` (schedule
  disabled) with a warn log. This deliberately diverges from the global flag's
  propagate-to-retry semantics: the facade prefetches the flag before
  `saveProcessingResult` precisely so settings failures keep the recording retryable,
  while the schedule is read inside `evaluate()` (after the save) — its safe failure
  direction is "extra notification", never a lost one.
- New suppression branch immediately **after** the `!resolvedGlobalEnabled` check:
  `!scheduleAllows` → `NotificationDecision(false, OUT_OF_SCHEDULE, delta)`.
  `OUT_OF_SCHEDULE` is a new `NotificationDecisionReason` value.
- `TRACKER_ERROR` branch becomes `shouldNotify = resolvedGlobalEnabled && scheduleAllows`:
  a tracker failure outside the window is suppressed even though `newTracksCount` is
  unknown — deliberate, the schedule is a hard time gate independent of tracker state
  ("a daytime event is never delivered").
- Branch order is otherwise unchanged: the object tracker still runs and updates its
  state even when the notification is suppressed, so cross-recording dedup state stays
  fresh.
- No `Clock` dependency: the check uses `recordTimestamp`, not "now" — fully
  deterministic and trivially testable.

## UI — `/notifications` Dialog Extension

Visible to OWNER only, inside the existing global section.

### Main screen additions

- Status line: `⏰ Detection schedule: 00:00–07:00 (Europe/Moscow)` when enabled; when
  disabled — `…: OFF (00:00–07:00, Europe/Moscow)` if a window is stored (the owner sees
  what `Enable` will activate), plain `…: OFF` otherwise.
- Two extra keyboard rows: `[⏰ Schedule on/off]` and `[🕐 Window] [🌐 Zone]` — the owner
  keyboard grows from 5 to 7 rows (a single three-button row is too narrow for the labels).
  Window/Zone buttons are always visible even while the schedule is disabled (the zone
  can be pre-configured without enabling; saving a window auto-enables, see below).

### Window picker

Two steps, editing the same message (`editMessageText`):

1. "Start hour" — 24-button grid (4 rows × 6), plus `‹ Back`.
2. "End hour" — same grid; the chosen start hour is shown in the message text and
   embedded in every button's callback data (stateless).

### Callback protocol (extends the `nfs:` prefix; all payloads explicit and idempotent)

| Payload | Action |
|---|---|
| `nfs:g:sched:on` / `nfs:g:sched:off` | Enable / disable the schedule |
| `nfs:g:sched:cfg` | Open the start-hour picker |
| `nfs:g:sched:s:<H>` | Start hour chosen → show end-hour picker |
| `nfs:g:sched:e:<S>:<E>` | End hour chosen → save window `[S:00, E:00)`, auto-enable schedule, re-render main screen |
| `nfs:g:sched:zone` | Open the zone screen |
| `nfs:g:sched:z:<olson>` | Set zone from a preset |
| `nfs:g:sched:zman` | Manual zone input via waiter (same pattern as `/timezone`) |
| `nfs:g:sched:home` | Back to main screen, no changes |

Key UI decisions:

- **Stateless picker:** the start hour rides inside the end-hour callback data
  (`nfs:g:sched:e:23:7` ≈ 18 bytes, well under Telegram's 64-byte limit). No in-memory
  dialog state; survives restarts and stale keyboards.
- **`end == start` is rejected** by re-rendering the end-hour picker with a warning line
  in the message text ("end must differ from start"). A toast is not possible: the bot
  answers every `nfs:` callback immediately (to clear the button spinner) and a callback
  cannot be answered twice.
- **Enabling without a window** (`nfs:g:sched:on` when no window is configured) opens
  the start-hour picker instead of enabling — prevents an "on but empty" state that
  would silently behave as disabled.
- **Saving a window auto-enables the schedule** — "picked a window, expected it to work"
  is the base scenario; disabling remains an explicit button.
- **Zone materialization:** on first enable/save (BOTH the `on` action and the window
  save), if the zone key is absent it is set from the owner's current timezone via
  `getUserZone` (fallback `UTC`) and immediately shown in the status line — the state
  "enabled + window + no zone" must not survive any UI path.
- **Zone screen:** same city presets as `/timezone` (shared preset constant) plus manual
  input via waiter — both patterns already exist in the project. Manual input uses the
  `/timezone` dialog conventions: 120-second timeout, `/cancel` to abort, error message
  on unknown zone. The preset list is a UI shortcut, NOT a whitelist —
  `nfs:g:sched:z:<olson>` accepts any valid IANA id.
- **OWNER-only:** `nfs:g:sched:*` goes through the same owner check as existing
  `nfs:g:*` callbacks.

### Component changes

| Component | Change |
|---|---|
| `NotificationsViewState` | new nullable fields: `scheduleEnabled`, `scheduleWindow` (pre-formatted string), `scheduleZone` — populated for OWNER only |
| `NotificationsMessageRenderer` | status line + button row; picker/zone screens live in a new `ScheduleKeyboardRenderer` to keep the file small |
| `FrigateAnalyzerBot` | intercepts `nfs:g:sched:*` BEFORE the generic `NotificationsSettingsCallbackHandler.dispatch` and routes it to the schedule flow (routing-order invariant; guarded by a test that the legacy dispatch IGNOREs `nfs:g:sched:*`) |
| `NotificationsCommandHandler` | fills the new state fields via `NotificationScheduleService` |
| i18n bundles (ru/en) | new keys for all strings; `MessageKeyParityTest` guards parity |

## Edge Cases & Error Handling

1. **Corrupt settings** (unparsable window, unknown zone, `start == end`, zone missing
   while enabled): warn log + schedule treated as disabled → notifications flow
   (**fail-open**: for a security system, noise beats a missed alarm).
2. **DST transitions:** `Instant → ZonedDateTime → LocalTime` is always unambiguous (we
   never parse a local time into an instant). On transition nights the window is
   physically one hour longer/shorter — accepted, no special handling. (Spring-forward:
   some of the window's local times don't exist that night; fall-back: the repeated
   local hour maps two distinct instants into the window — each instant still resolves
   to exactly one local time.)
3. **Stale keyboards:** every callback carries explicit values — a stale click rewrites
   the same value or opens a fresh screen. One accepted exception: a stale end-picker
   click (`nfs:g:sched:e:<S>:<E>`, e.g. on an older `/notifications` message) re-saves
   that window AND re-enables the schedule even if it was explicitly disabled in the
   meantime (single owner, low likelihood).
4. **Double taps / concurrent clicks:** idempotent operations, atomic per-key upsert,
   last write wins.
5. **Setting changed between decision and send:** decision is taken once in
   `evaluate()` — same behavior as the existing global flag. Additionally the schedule
   state is three keys read non-atomically: a concurrent settings change can yield a
   mixed read for one evaluation (either a valid window — delivery follows it, or an
   invalid combination — fail-open delivers). Accepted (fail-open, single owner).
6. **Invalid manual zone input:** error message, then the flow exits (one-shot — matches
   the actual `/timezone` behavior); the owner re-opens manual input via the zone screen.
   `/cancel` and the 120-second timeout behave as in `/timezone`.
7. **Suppressed decision:** no AI-description job is created (existing suppression
   behavior, unchanged). Signal-loss pipeline untouched.
8. **Backlog retroactivity:** events are checked against the CURRENT schedule
   configuration at processing time (no schedule history) — accepted, the window
   changes rarely and backlogs are short.

## Testing

- `NotificationSchedule.contains`: plain window (00–07), midnight crossing (23–07),
  half-open boundaries (start inclusive, end exclusive), zone conversion
  (23:30 UTC == 02:30 MSK → inside 00–07 MSK), DST-transition day.
- Window string parsing: valid, garbage, `start == end` → invalid.
- `NotificationScheduleService`: disabled/enabled, corrupt values → `null` + warn,
  setters write the right keys.
- `NotificationDecisionServiceImpl`: `OUT_OF_SCHEDULE` branch; reason precedence
  (`GLOBAL_OFF` before `OUT_OF_SCHEDULE`); `TRACKER_ERROR` honors both conditions; no
  schedule → behavior identical to today (regression).
- Callback handler: every payload transition; non-OWNER rejection; `end == start` toast;
  auto-enable on window save; zone materialization on first enable.
- Renderer: status line in both states, picker screens, no schedule fields for
  non-OWNER.
- i18n: existing `MessageKeyParityTest` catches ru/en drift.

## Documentation

- Update `.claude/rules/telegram-notifications.md`: new callbacks, storage keys,
  schedule semantics.
- `database.md`: add the three `app_settings` schedule keys (no schema change).
- `configuration.md`: no changes (no env vars).

## Out of Scope

- Per-user schedules; multiple windows per day; day-of-week variation.
- Cron-style expressions (single daily window chosen; the stored format can evolve).
- Digest/summary of suppressed notifications.
- Applying the schedule to signal-loss alerts.
- Minute-granularity input in the UI (format supports it; picker offers hours).
- Prefetching the schedule in the facade or reading it in parallel with the tracker —
  rejected: the cached read is O(1), there is nothing to win.
