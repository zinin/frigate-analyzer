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

**Completed tasks (1-8):**
- [x] Task 1: Liquibase migration `1.0.4` — `c51ab81`
- [x] Task 2: `ObjectTrackEntity` + `ObjectTrackRepository` — `a0f9610`
- [x] Task 3: `AppSettingEntity` + `AppSettingRepository` + `AppSettingKeys` — `9e02186`
- [x] Task 4: Domain DTOs — `c5066ec`
- [x] Task 5: `IouHelper` + tests — `8582c34`
- [x] Task 6: `BboxClusteringHelper` + tests — `6efc4e5`
- [x] Task 7: `ObjectTrackerProperties` — `c76ecbe`
- [x] Task 8: `ObjectTrackerService` + tests — `83a5a7e`, `9b2190b`

**Remaining tasks (9-21):**
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
- [ ] Task 20: `application.yaml` + `.env.example` + config
- [ ] Task 21: Documentation updates

## SESSION CONTEXT

### Build Environment

**Java 25 required but NOT default.** Set before any gradle command:
```bash
export JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64
```
Default `java` is 21. Without JAVA_HOME: "Cannot find Java matching {languageVersion=25}".

**Gradle daemon issues:** `./gradlew --stop` first if builds hang. Use `--no-daemon` if problems persist.

### Task 8 Test Fix

`ObjectTrackerServiceImplTest` mock initially used `Mono.blockOptional()` → deadlock in `runTest`. Fixed in `9b2190b` using `.flux()` instead. If tests hang, verify the mock uses non-blocking reactive conversion.

### ObjectTrackerProperties Not Registered Yet

NOT in `@EnableConfigurationProperties`. Deferred to Task 20. Service injection will fail until registered.

### Plan Bug Fixed

Task 6 test `innerIou=0.4f` was wrong (test bboxes have IoU=0.25). Fixed to `0.2f`.

### Key Architecture Decisions

- Best-match IoU (`maxByOrNull`), not first-match
- Per-camera `Mutex`; transaction via `TransactionalOperator.executeAndAwait` INSIDE mutex
- Sliding TTL — matched tracks live indefinitely
- Fail-open on tracker error with global ON; global OFF wins over tracker error
- `AppSettingsService.getBoolean()` corrupt value → WARN+default; read failure → propagates
- Callback auth by `callback.user.username` (mirrors QuickExportHandler)
- `nfs:*` callbacks carry explicit target state, not toggle
- `CancellationException` always re-thrown before generic catch
- "Bad Request: message is not modified" → DEBUG
- Recording path does NOT re-read global flag
- `NotificationsViewState` globals nullable for USER
- `isOwner(username: String?)` null-safe
- Tracker log: DEBUG (not INFO)

## PLAN QUALITY WARNING

The plan was iterated 3 times but may still contain errors. If something doesn't match the codebase — STOP and ask.

## INSTRUCTIONS

1. Read the documents
2. Summarize what you understood (2-4 sentences)
3. **STOP and WAIT** — do NOT implement anything
4. Ask: "What would you like me to work on?"
