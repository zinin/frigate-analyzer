# Notification Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Global (OWNER-configured) daily schedule that suppresses recording-detection Telegram notifications outside a configured time window (e.g. deliver only 00:00–07:00).

**Architecture:** Three new string keys in the existing `app_settings` table, read through a new fail-open `NotificationScheduleService`; one new suppression branch (`OUT_OF_SCHEDULE`) in `NotificationDecisionServiceImpl` keyed off `recording.recordTimestamp` in the schedule's explicit timezone; UI is an extension of the `/notifications` dialog — stateless inline hour pickers and a zone screen under the new `nfs:g:sched:*` callback subtree.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, R2DBC/PostgreSQL, ktgbotapi (inline keyboards + waiter API), JUnit 5 + mockk + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-18-notification-schedule-design.md` (same branch).

## Global Constraints

- Gradle module names carry the `frigate-analyzer-` prefix: `:frigate-analyzer-model`, `:frigate-analyzer-service`, `:frigate-analyzer-telegram`.
- Do NOT run `./gradlew build` directly in the main session — dispatch the `build-runner` agent (project CLAUDE.md). Per-task module tests (`./gradlew :frigate-analyzer-<module>:test --tests "..."`) also go through `build-runner`.
- On ktlint errors: `./gradlew ktlintFormat`, then retry.
- `git add <file>` immediately after creating or modifying every file (project CLAUDE.md rule).
- Every user-visible string goes to BOTH `messages_en.properties` AND `messages_ru.properties` (`MessageKeyParityTest` fails otherwise).
- Telegram callback data must stay ≤ 64 bytes; longest new payload is `nfs:g:sched:z:Asia/Yekaterinburg` (32 bytes).
- Storage window format is `HH:mm-HH:mm` (hyphen); display format is `HH:mm–HH:mm` (en dash).
- Half-open interval semantics `[start, end)` everywhere; `start > end` = midnight crossing; `start == end` is invalid.
- New settings reads must be fail-open: corrupt/unreadable schedule → warn + treat as disabled (notifications flow).

---

### Task 1: `ScheduleWindow` + `NotificationSchedule` model types

✅ Done — see commit(s): `6394584`

---

### Task 2: `AppSettingsService.getString` / `setString` + negative caching of absent keys

✅ Done — see commit(s): `55350a5`, `d2448eb`, `a18ec03`

---

### Task 3: `AppSettingKeys` + `NotificationScheduleService`

✅ Done — see commit(s): `ac4172c`

---

### Task 4: `OUT_OF_SCHEDULE` suppression in the decision service

✅ Done — see commit(s): `2667433`, `17f6670`

---

### Task 5: `NotificationsViewState` schedule fields + `NotificationsViewStateFactory`

✅ Done — see commit(s): `a13d316`

---

### Task 6: Schedule status line and buttons on the `/notifications` main screen

✅ Done — see commit(s): `f2807f1`, `ef1f07e`

---

### Task 7: `ScheduleKeyboardRenderer` — hour pickers and zone screen

✅ Done — see commit(s): `e9a6460`

---

### Task 8: `ScheduleCallbackHandler` — pure dispatch for `nfs:g:sched:*`

✅ Done — see commit(s): `70e0c8f`, `985b9fe`

---

### Task 9: `ScheduleSettingsFlow` + bot wiring

✅ Done — see commit(s): `e802ef6`, `ed47e3b`

⚠️ **Step 5 (manual live-bot checklist) was NOT performed** — it is the merge gate and is still
outstanding. The whole-branch review predicts its check #5 will FAIL (see the continuation prompt).

---

### Task 10: Documentation + full build

**Files:**
- Modify: `.claude/rules/telegram-notifications.md`
- Modify: `.claude/rules/database.md`

**Interfaces:** none (docs + verification only).

- [x] **Step 1: Update the module rule doc** — ✅ done: `854cfa6` (+ KDoc fixes `48614cd`, padding test `687d159`)

In `.claude/rules/telegram-notifications.md`:

1. Components table — add rows:

```markdown
| `NotificationsViewStateFactory` | `bot/handler/notifications/` | Single assembly point for `NotificationsViewState` (command, re-render, schedule flow) |
| `ScheduleCallbackHandler` | `bot/handler/notifications/` | Pure dispatch for `nfs:g:sched:*`, mutates schedule settings |
| `ScheduleKeyboardRenderer` | `bot/handler/notifications/` | Hour-picker and timezone screens of the schedule sub-dialog |
| `ScheduleSettingsFlow` | `bot/handler/notifications/` | Telegram I/O: maps dispatch outcomes to screen edits, manual-zone waiter |
```

2. Callback Protocol table — add rows:

```markdown
| `nfs:g:sched:on` / `nfs:g:sched:off` | Enable / disable the detection schedule (OWNER only; `on` without a configured window opens the picker) |
| `nfs:g:sched:cfg` | Open the start-hour picker |
| `nfs:g:sched:s:<H>` | Start hour chosen → end-hour picker (start rides in callback data) |
| `nfs:g:sched:e:<S>:<E>` | End hour chosen → save window `[S:00, E:00)`, materialize zone if unset, auto-enable |
| `nfs:g:sched:zone` | Open the timezone screen (presets as in `/timezone` + manual input) |
| `nfs:g:sched:z:<olson>` | Set schedule zone from preset |
| `nfs:g:sched:zman` | Manual zone input via waiter (120 s timeout, `/cancel`) |
| `nfs:g:sched:home` | Back to the main screen |
```

3. State Storage table — add rows:

```markdown
| Schedule enabled | `app_settings` → `notifications.recording.schedule.enabled` | absent = `FALSE` |
| Schedule window | `app_settings` → `notifications.recording.schedule.window` (`HH:mm-HH:mm`, `[start,end)`, start>end crosses midnight) | absent |
| Schedule zone | `app_settings` → `notifications.recording.schedule.zone` (IANA id) | absent |
```

4. Consumers section — append:

```markdown
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
```

5. Operational notes — append:

```markdown
- **Schedule ops:** `app_settings` values and key absence are cached per-process without TTL —
  direct SQL edits or inserts of `notifications.recording.schedule.*` are NOT picked up until restart (single-instance
  deployment assumed). The manual-zone waiter does not survive a bot restart; one active waiter
  per chat. Rollback: disable via the `/notifications` toggle (instant), or
  `DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'` +
  restart for a full reset.
```

6. In `.claude/rules/database.md`, `app_settings` section — after the seeding sentence add:

```markdown
Schedule keys `notifications.recording.schedule.{enabled,window,zone}` are NOT seeded — they are
created on first configuration via `/notifications`; absent keys mean "schedule disabled".
```

`git add .claude/rules/telegram-notifications.md .claude/rules/database.md`

- [x] **Step 2: Branch-wide code review** — ✅ done: no Critical; three Important (the waiter decision is pending with the human — see the SDD ledger); the seven triaged minor fixes landed in `72e1f81..8486a65`, re-review Approved

Dispatch the `superpowers:code-reviewer` agent over the full feature-branch diff; fix critical
findings and repeat until clean (project CLAUDE.md: review BEFORE build).

- [x] **Step 3: Full build** — ✅ done 2026-07-19: BUILD SUCCESSFUL in 5m04s, 692 tests / 0 failures / 1 pre-existing skip (ai-description, untouched by this branch), model jacoco first-ever verification passed (11.15% ≥ 1%)

Dispatch the `build-runner` agent: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules, all tests, ktlint). On ktlint errors run `./gradlew ktlintFormat` and retry.

- [x] **Step 4: Commit** — ✅ done: docs in `854cfa6`/`48614cd`/`687d159`, review fixes in `72e1f81..8486a65` (all with explicit pathspecs)

```bash
git commit -m "docs: schedule settings in telegram-notifications rule"
```

---

## Self-Review (performed at plan time)

- **Spec coverage:** storage keys → Task 3; `AppSettingsService` strings → Task 2; window/zone types + `[start,end)` + midnight + DST → Task 1; `OUT_OF_SCHEDULE` + branch order + tracker-error + fail-open never-throws → Task 4; ViewState/factory fields → Task 5; status line + buttons → Task 6; pickers/zone screens + stateless callback data + equal-end warning → Tasks 7–8; auto-enable on save, zone materialization, `on`-without-window → Task 8; OWNER-only → Task 8; manual zone waiter (120 s, `/cancel`) → Task 9; bot routing → Task 9; docs → Task 10. No DB migration by design.
- **Known intentional deviation:** none after the iter-1 review sync — the spec now records the two-row keyboard (owner keyboard 5 → 7 rows) and the bot-level interception of `nfs:g:sched:*` (formerly implied handler-level dispatch), in addition to the pre-planning amendments (inline warning instead of toast; `on` without window opens picker; never-throws schedule reads).
- **Type consistency:** `ScheduleWindow.ofHours/parse/storageFormat/displayFormat/contains`, `NotificationSchedule.contains(Instant)`, `NotificationScheduleService` method set, `Outcome.RenderEndPicker(startHour, rejectedEqualEnd)` ↔ `endPicker(startHour, showEqualWarning, lang)`, `PREFIX = "nfs:g:sched:"` — verified consistent across Tasks 1/3/7/8/9.
- **Single placeholder-by-reference:** Task 4 asks the implementer to copy the literal `DetectionDelta(...)` argument list from the existing NEW_OBJECTS test in the same file (constructor not visible at plan time; copying the in-file literal is safer than inventing one).
