## TASK

Continue executing the implementation plan for "notification controls and object tracking" in a fresh Claude Code session.

## CRITICAL: DO NOT START WORKING YET

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the design and plan documents.
2. Report a brief summary (2-4 sentences) of what you understood.
3. **WAIT for explicit user instructions** before taking ANY action.

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

**Completed plan tasks (1-8):**
- [x] Task 1: Liquibase migration `1.0.4` — `c51ab81`
- [x] Task 2: `ObjectTrackEntity` + `ObjectTrackRepository` — `a0f9610`
- [x] Task 3: `AppSettingEntity` + `AppSettingRepository` + `AppSettingKeys` — `9e02186`
- [x] Task 4: Domain DTOs — `c5066ec`
- [x] Task 5: `IouHelper` + tests — `8582c34`
- [x] Task 6: `BboxClusteringHelper` + tests — `6efc4e5`
- [x] Task 7: `ObjectTrackerProperties` — `c76ecbe`
- [x] Task 8: `ObjectTrackerService` + tests — `83a5a7e`, `9b2190b`, `0316cbd`, `13cf068`

**Additional completed stabilization/review fixes:**
- [x] Restore full build after tasks 1-8 — `e7d2297`
  - Fixed Liquibase XML element order in `1.0.4.xml` (`preConditions` before `comment`).
  - Registered `ObjectTrackerProperties::class` in `FrigateAnalyzerApplication` because `ObjectTrackerServiceImpl` is already a Spring bean.
- [x] External review fixes — `13cf068`
  - Promoted `libs.coroutines.reactor` from `testImplementation` to `implementation` in `modules/service/build.gradle.kts` because production code uses `TransactionalOperator.executeAndAwait`.
  - Added integration tests for custom R2DBC SQL:
    - `ObjectTrackRepositoryTest`: `findActive`, `updateOnMatch`, out-of-order `CASE/GREATEST` guard, `deleteExpired`.
    - `AppSettingRepositoryTest`: `upsert` insert/update.
  - Added `ObjectTrackerServiceImpl` check that `updateOnMatch` updates exactly one row; if it updates zero rows, fail instead of silently treating the detection as matched/suppressed.
  - Added tests for `staleTracksCount`, `updateOnMatch=0`, `confidenceFloor` filtering, and renamed a misleading IoU test.
  - Documented static camera assumption for `perCameraMutex`.

**Remaining tasks:**
- [ ] Task 9: `AppSettingsService` interface + impl + tests
- [ ] Task 10: `NotificationDecisionService` interface + impl + tests
- [ ] Task 11: `ObjectTracksCleanupTask` (`@Scheduled`) + tests
- [ ] Task 12: Telegram user model extensions
- [ ] Task 13: `TelegramNotificationServiceImpl` filtering
- [ ] Task 14: i18n message keys (ru, en)
- [ ] Task 15: `NotificationsMessageRenderer` + tests
- [ ] Task 16: `NotificationsCommandHandler` + `isOwner` + tests
- [ ] Task 17: `NotificationsSettingsCallbackHandler` + tests
- [ ] Task 18: Wire callback into `FrigateAnalyzerBot.registerRoutes()`
- [ ] Task 19: `RecordingProcessingFacade` integration
- [ ] Task 20: `application.yaml` + `.env.example` + config (**partially done:** `ObjectTrackerProperties::class` registration already committed in `e7d2297`; still add YAML/env defaults and verify config docs)
- [ ] Task 21: Documentation updates
- [ ] Task 22: Final integration test + remove design/plan from PR

## SESSION CONTEXT

### Build Environment

**Java 25 required but NOT default.** Set before any gradle command:
```bash
export JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64
```
Default `java` is 21. Without JAVA_HOME: "Cannot find Java matching {languageVersion=25}".

**Build commands:** project instructions say use the `build-runner` agent for build/test/lint commands. Do not run Gradle directly in the main session. If builds hang, try `./gradlew --stop` first via build-runner context; use `--no-daemon` if problems persist.

### Current Verification Status

After `13cf068`, full build passed:
```bash
JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64 ./gradlew clean build
```
Result: `BUILD SUCCESSFUL` in 2m 23s.

### External Code Review Status

`/external-code-review default` was run after tasks 1-8 and build stabilization:
- Completed reviewers: superpowers, Codex, CCS `glm`, CCS `albb-glm`, CCS `albb-qwen`, CCS `ollama-kimi`, CCS `ollama-minimax`, Gemini.
- CCS `ollama-deepseek` was blocked by external rate limit (`503 rate_limit`) and produced no findings.
- No Critical issues were reported by completed reviewers.
- Confirmed actionable findings were fixed in `13cf068`.
- Findings intentionally not fixed now:
  - Multi-instance locking: design explicitly says single-instance only; per-camera in-process `Mutex` is accepted for this iteration.
  - `Persistable.isNew() = true`: existing project pattern; current code uses `save()` for inserts and custom `upsert`/`updateOnMatch` for updates.
  - Cleanup task missing: this is Task 11, not a defect in completed tasks.
  - `docs/superpowers/**` PR hygiene: remove before PR in Task 22, not during normal task execution.

### Task 8 Test Fix

`ObjectTrackerServiceImplTest` mock initially used `Mono.blockOptional()` → deadlock in `runTest`. Fixed in `9b2190b` using `.flux()` instead. If tests hang, verify the mock uses non-blocking reactive conversion.

### Task 1 / Liquibase Fix

Full build initially failed because `docker/liquibase/migration/1.0.4.xml` had `<preConditions>` after `<comment>` in a `changeSet`. Liquibase XSD requires `preConditions` before `comment`. Fixed in `e7d2297`.

### Task 20 Partial Completion Warning

The original context said `ObjectTrackerProperties` was not registered yet and deferred to Task 20. That is no longer true: registration was required for current full build and was committed in `e7d2297`.

When doing Task 20, do **not** duplicate the registration. Instead:
- verify `ObjectTrackerProperties::class` is already in `FrigateAnalyzerApplication.kt`;
- add `application.notifications.tracker` YAML properties;
- update `docker/deploy/.env.example`;
- run the planned build verification via build-runner.

### Plan Bug Fixed

Task 6 test `innerIou=0.4f` was wrong (test bboxes have IoU=0.25). Fixed to `0.2f`.

### Key Architecture Decisions

- Best-match IoU (`maxByOrNull`), not first-match.
- Per-camera `Mutex`; transaction via `TransactionalOperator.executeAndAwait` INSIDE mutex.
- Sliding TTL — matched tracks live indefinitely.
- Fail-open on tracker error with global ON; global OFF wins over tracker error.
- `AppSettingsService.getBoolean()` corrupt value → WARN+default; read failure → propagates.
- Callback auth by `callback.user.username` (mirrors QuickExportHandler).
- `nfs:*` callbacks carry explicit target state, not toggle.
- `CancellationException` always re-thrown before generic catch.
- "Bad Request: message is not modified" → DEBUG.
- Recording path does NOT re-read global flag.
- `NotificationsViewState` globals nullable for USER.
- `isOwner(username: String?)` null-safe.
- Tracker log: DEBUG (not INFO).

### Git / Working Tree Notes

At the time this prompt was generated, there were two untracked files:
- `docs/reset-liquibase-checksums.sh`
- `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking-execution-prompt.md`

They were not committed. Be careful before adding/removing them; ask the user if unsure.

Project rule: after creating or modifying files, run `git add <specific-file>` immediately. Avoid `git add .`.

## PLAN QUALITY WARNING

The plan was iterated 3 times and has already had some issues found and fixed during execution. It may still contain:
- errors or inaccuracies in implementation details;
- assumptions that do not match the actual codebase;
- missing steps or incomplete instructions;
- steps that are now partially superseded by commits `e7d2297` and `13cf068`.

**If you notice any issue during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan does not work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.

## INSTRUCTIONS

1. Read the documents listed above.
2. Understand current progress and session context.
3. Provide a brief summary of what you understood (2-4 sentences).
4. **STOP and WAIT** — do NOT proceed with any implementation.
5. Ask: "What would you like me to work on?"
