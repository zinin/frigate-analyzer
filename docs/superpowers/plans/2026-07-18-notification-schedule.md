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

✅ Done — all four steps:

- Step 1 (docs): `854cfa6` + KDoc fixes `48614cd` + padding test `687d159`
- Step 2 (branch-wide review): no Critical; three Important (the waiter decision is pending with
  the human — see the SDD ledger); the seven triaged minor fixes landed in `72e1f81..8486a65`,
  re-review Spec ✅ / Approved
- Step 3 (full build, 2026-07-19): BUILD SUCCESSFUL in 5m04s — 692 tests / 0 failures /
  1 pre-existing skip (ai-description, untouched by this branch); model jacoco first-ever
  verification passed (11.15% ≥ 1%)
- Step 4 (commit): explicit-pathspec commits listed above; plan status marked in `e63df58`

---

## Self-Review (performed at plan time)

- **Spec coverage:** storage keys → Task 3; `AppSettingsService` strings → Task 2; window/zone types + `[start,end)` + midnight + DST → Task 1; `OUT_OF_SCHEDULE` + branch order + tracker-error + fail-open never-throws → Task 4; ViewState/factory fields → Task 5; status line + buttons → Task 6; pickers/zone screens + stateless callback data + equal-end warning → Tasks 7–8; auto-enable on save, zone materialization, `on`-without-window → Task 8; OWNER-only → Task 8; manual zone waiter (120 s, `/cancel`) → Task 9; bot routing → Task 9; docs → Task 10. No DB migration by design.
- **Known intentional deviation:** none after the iter-1 review sync — the spec now records the two-row keyboard (owner keyboard 5 → 7 rows) and the bot-level interception of `nfs:g:sched:*` (formerly implied handler-level dispatch), in addition to the pre-planning amendments (inline warning instead of toast; `on` without window opens picker; never-throws schedule reads).
- **Type consistency:** `ScheduleWindow.ofHours/parse/storageFormat/displayFormat/contains`, `NotificationSchedule.contains(Instant)`, `NotificationScheduleService` method set, `Outcome.RenderEndPicker(startHour, rejectedEqualEnd)` ↔ `endPicker(startHour, showEqualWarning, lang)`, `PREFIX = "nfs:g:sched:"` — verified consistent across Tasks 1/3/7/8/9.
- **Single placeholder-by-reference:** Task 4 asks the implementer to copy the literal `DetectionDelta(...)` argument list from the existing NEW_OBJECTS test in the same file (constructor not visible at plan time; copying the in-file literal is safer than inventing one).
