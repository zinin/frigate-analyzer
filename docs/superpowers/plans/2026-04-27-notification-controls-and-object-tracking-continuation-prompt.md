## TASK

Begin executing the implementation plan for "notification controls and object tracking" in a fresh Claude Code session.

## CRITICAL: DO NOT START WORKING YET

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the design and plan documents.
2. Skim the three review iteration files for the decision history.
3. Report a brief summary (2–4 sentences) of what you understood.
4. **WAIT for explicit user instructions** before taking ANY action.

**DO NOT:**
- Start implementing tasks autonomously.
- Make any code changes.
- Run any commands (except reading documents).
- Assume which task to start with.

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

**Primary:**
- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`

**Decision history (skim — do not re-litigate already-decided issues):**
- Iteration 1: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-1.md`
- Iteration 2: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-2.md`
- Iteration 3: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-3.md`

**Follow-up note:**
- `docs/telegram-outbox.md` (accepted at-most-once delivery gap, future task)

## PROGRESS

**Implementation tasks:** None completed. All 21 tasks (Task 1 through Task 21) are pending.

**Review iterations:** 3 completed. Latest commit: `6745f97 docs: review iteration 3 for notification-controls-and-object-tracking`. Design and plan reflect all accepted iter-1/2/3 fixes.

**Branch:** `feature/notification-controls`. Base: `master`.

## TASK INVENTORY

The plan defines 21 tasks. They have an implicit dependency order — execute sequentially unless told otherwise:

| # | Task | Module |
|---|------|--------|
| 1 | Liquibase migration `1.0.4` (object_tracks, app_settings, telegram_users flags) | docker/liquibase |
| 2 | `ObjectTrackEntity` + `ObjectTrackRepository` | model + service |
| 3 | `AppSettingEntity` + `AppSettingRepository` | model + service |
| 4 | Domain DTOs (`RepresentativeBbox`, `DetectionDelta`, `NotificationDecision`, `NotificationDecisionReason`) | model |
| 5 | `IouHelper` + tests | service |
| 6 | `BboxClusteringHelper` + tests | service |
| 7 | `ObjectTrackerProperties` (Spring config + cross-field validation) | service |
| 8 | `ObjectTrackerService` interface + impl + tests (TransactionalOperator + Mutex) | service |
| 9 | `AppSettingsService` interface + impl + tests (cached, no @Transactional, WARN on corrupt) | service |
| 10 | `NotificationDecisionService` interface + impl + tests (orchestrates tracker + settings) | service |
| 11 | `ObjectTracksCleanupTask` (`@Scheduled`) + tests | core |
| 12 | Telegram user model extensions (entity, DTO, UserZoneInfo, repo, service) | telegram |
| 13 | `TelegramNotificationServiceImpl` per-user filtering + signal-flow global gate (recording flow does NOT re-read global) | telegram |
| 14 | i18n message keys (ru, en) | telegram resources |
| 15 | `NotificationsMessageRenderer` + `NotificationsViewState` DTO + tests | telegram |
| 16 | `NotificationsCommandHandler` + `isOwner` + tests | telegram |
| 17 | `NotificationsSettingsCallbackHandler` + tests | telegram |
| 18 | Wire callback subscription into `FrigateAnalyzerBot.registerRoutes()` | telegram |
| 19 | `RecordingProcessingFacade` — call `NotificationDecisionService` + add `findByRecordingId` | core + service |
| 20 | `application.yaml` + `docker/deploy/.env.example` + `@EnableConfigurationProperties` | core |
| 21 | Documentation updates (`.claude/rules/*`) | docs |

## SESSION CONTEXT (KEY DECISIONS YOU SHOULD KNOW)

These decisions were made over three review iterations. Do not re-debate them; the design/plan reflect them already.

**Architecture:**
- Best-match IoU (per-bbox `maxByOrNull` of IoU over active tracks).
- Per-camera `Mutex` from `kotlinx.coroutines.sync`; transaction opened **inside** mutex via `TransactionalOperator.executeAndAwait` (not class-level `@Transactional`).
- Sliding TTL is intentional — a continuously matched track can live indefinitely.
- Single-instance only; multi-instance / advisory locks / `FOR UPDATE` are out of scope.
- Schedules / quiet hours / per-class / per-zone filters are out of scope (data model leaves room).

**Coordinates:** pixel coordinates in the same space as `DetectionEntity.x1..y2`. Do **not** introduce normalized `[0..1]` constraints.

**Error handling:**
- Tracker exception with `globalEnabled = true` → fail-open (`shouldNotify = true`, `TRACKER_ERROR`).
- Tracker exception with `globalEnabled = false` → suppress (`shouldNotify = false`, `TRACKER_ERROR`). Global OFF wins; AI description supplier is not invoked.
- `AppSettingsService.getBoolean(...)` read failure → propagates (NOT default-open / default-closed); pipeline reports the failure.
- `AppSettingsService.getBoolean(...)` *unparseable* stored value (e.g. someone wrote `"weird"` into the table) → log `WARN`, fall back to default. Recoverable corruption, not fatal.
- Recording retry boundary: decision call happens **after** `saveProcessingResult`, so AppSettings exception loses the notification for that recording (accepted limitation, similar to telegram-outbox gap).
- Telegram enqueue gap: tracker may mutate state before fan-out succeeds — accepted at-most-once, documented in `docs/telegram-outbox.md`.

**Callbacks (`/notifications` dialog):**
- Callback data carries explicit target state, e.g. `nfs:u:rec:1` / `nfs:u:rec:0` / `nfs:close`. Idempotent against stale messages.
- Authentication uses `callback.user.username` (mirroring `QuickExportHandler`/`CancelExportHandler`); avoids ktgbotapi `UserId` typing pitfalls.
- `bot.answer(callback)` is called **first** to clear the spinner.
- `CancellationException` is **always re-thrown** before the generic `catch (e: Exception)`.
- "Bad Request: message is not modified" (Telegram) is downgraded to `DEBUG`.
- USER (non-OWNER) variant of `/notifications` does **not** read `app_settings` — `recordingGlobalEnabled` and `signalGlobalEnabled` in `NotificationsViewState` are nullable and `null` for USER. Renderer `requireNotNull(...)` for OWNER.
- `isOwner(username: String?)` is null-safe; returns `false` if either side is null/blank.

**Logging:**
- Tracker per-recording logs are `DEBUG` (busy outdoor cameras would otherwise flood logs).
- WARN for tracker fail-open, WARN for corrupt setting fallback, ERROR for AppSettings read failure that propagates.

**Tech debt accepted:**
- `runBlocking` in `ObjectTracksCleanupTask` for a single hourly DELETE.
- `ConcurrentHashMap<String, Mutex>` without eviction (camera count fixed 2–10).
- `BboxClusteringHelper` stays as a static `object` (pure deterministic logic; tested directly).
- Per-row `save()` accepted; batch insert is a future optimization.

**Known compile-blocker traps the plan now guards against:**
- `modules/service/build.gradle.kts` may be missing `spring-boot-starter-validation`, `kotlinx-coroutines-test`, `mockk`, `kotlinx-coroutines-core` — Task 7 Step 0 verifies and adds.
- `TelegramUserServiceImpl` does **not** currently take `TelegramProperties` in its constructor; Task 16 Step 2 adds it and lists all existing tests that need updating.
- `NotificationsViewState` lives in `modules/telegram/.../telegram/dto/`, not in the renderer file.
- `findByUsernameAsDto` (not `findByUserIdAsDto`) is the lookup method used by `/notifications` callbacks.

## PROJECT CONVENTIONS (FROM CLAUDE.md)

- **Do NOT** run `./gradlew build` (or any test/lint/format) directly. Always delegate to the `build-runner` agent.
- One commit per implementation task. Use `git add <file>` per file — never `git add .` or `git add -A`.
- Fix critical comments after a `superpowers:code-reviewer` pass before running build.
- On ktlint errors: `./gradlew ktlintFormat` then retry build.
- Module dependencies: `core → telegram → service → model → common`. Don't pull telegram into service or model.
- Always inject `java.time.Clock`; never call `Instant.now()` directly.
- Logging: `kotlin-logging` (`io.github.oshai.kotlinlogging.KotlinLogging`).
- Coroutines `Mutex`: `kotlinx.coroutines.sync.Mutex` (suspend-friendly), not `java.util.concurrent.locks`.

**Before opening a PR:**
- `git rm` all `docs/superpowers/...` plan/spec/review files for this branch and commit (per global workflow rules in user's `~/.claude/CLAUDE.md`). Documents remain in branch git history.
- Standard ktlint clean.

## PLAN QUALITY WARNING

The plan was iterated three times by 6+ external reviewers, but it is still a 3500-line document and may contain:
- Snippet imports or method names that drift from the live codebase.
- Assumptions about Spring R2DBC / ktgbotapi versions that may have changed.
- Missing edge cases the reviewers did not catch.

**If you notice any issue during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem (file path, line number, what doesn't match).
3. Explain why the plan step doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do **NOT** silently work around plan issues or make significant deviations without user approval.

## RECOMMENDED FIRST STEPS (FOR THE USER TO PICK FROM)

These are suggestions for the user — present them after your summary, do not act on them yourself:

1. **Sequential execution from Task 1.** Use the `superpowers:executing-plans` skill to walk the plan task-by-task with review checkpoints.
2. **Subagent-driven parallelism for independent tasks.** Use `superpowers:subagent-driven-development` for the early independent tasks (Tasks 1–6 are largely independent), then synchronize before Task 7+.
3. **Spike a single task first.** Pick Task 5 (`IouHelper`) or Task 1 (Liquibase) as a smoke test before committing to the full sequence.

## INSTRUCTIONS FOR THE FRESH SESSION

1. Read **Design** and **Plan** in full.
2. Skim **iter-1 / iter-2 / iter-3** review files for context (do not re-read every issue — the decisions are already applied).
3. Skim `docs/telegram-outbox.md` (one short page).
4. Provide a brief 2–4 sentence summary of what you understood — confirm the goal, the architecture, and the current state.
5. **STOP and WAIT** — do NOT proceed with any implementation.
6. Ask: "What would you like me to work on?"
