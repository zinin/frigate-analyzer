# Camera Signal Loss Detection — Design

**Date:** 2026-04-25
**Status:** Approved (brainstorming complete, awaiting user review before plan)
**Branch:** `feature/camera-signal-loss`

## Problem

When a Frigate camera loses signal (camera Wi-Fi outage, camera power outage, network issue, full power outage taking out Frigate too), Frigate simply stops writing recording segments for that camera. `frigate-analyzer` has no recordings to analyze, so the user gets no detections — and currently no notification that a camera has gone silent. The user wants to be alerted in Telegram when a camera that was recording continuously stops.

## Goals

- Detect that a camera which was recording continuously has stopped writing recordings for longer than a configurable threshold.
- Send a Telegram notification on signal loss.
- Send a Telegram notification on signal recovery (so the user knows the issue is resolved without checking).
- Avoid spam: never repeat-alert about the same loss; never alert on cameras that have been dead for over 24h (those are out of service).
- Avoid false alarms after `frigate-analyzer` restarts.

## Non-Goals

- No interaction with Frigate API (`/api/stats`, MQTT). The detector relies only on recordings appearing in the Frigate folder, same as the rest of the system.
- No event-based / motion-only mode support in this iteration. Assumes Frigate is in continuous-record mode (the user confirmed this is the case).
- No per-camera configuration. Single threshold for all cameras.
- No metrics export (Micrometer counters). Add later if needed.
- No persistence of detector state — purely in-memory; restart-recovery is covered by the startup grace period.

## High-Level Approach

A scheduled task (`SignalLossMonitorTask`) wakes up every `pollInterval` (default 30s), queries the database for the most recent `record_timestamp` per active camera, and runs each through a small state machine. State transitions trigger Telegram notifications via the existing notification queue.

Three reasons for this approach over event-driven alternatives:

1. **Isolation:** zero changes to `WatchRecordsTask`, `Pipeline`, or `RecordingProcessingFacade`.
2. **Testability:** with an injected `Clock`, every state transition is deterministic and unit-testable without coroutine-timing tricks.
3. **Project consistency:** matches the existing pattern of `ServerHealthMonitor.checkHealth` (`@Scheduled` healthcheck + in-memory state).

The latency cost of polling vs. event-driven is bounded by `pollInterval` (~30s), which is small relative to the 3-minute threshold and irrelevant for human reaction time.

## Architecture

### New Components

| Component | Module | Responsibility |
|---|---|---|
| `SignalLossMonitorTask` | `core/task/` | Spring `@Scheduled` tick driver as `suspend fun tick()` (Spring 6.1+ supports suspend in `@Scheduled`); holds in-memory `Map<camId, CameraSignalState>`; runs the pure state-machine `decide(...)` function and dispatches loss/recovery via `TelegramNotificationService` |
| `decide(prev, observation, config) -> Decision` (pure function) | `core/task/` | State-machine decision logic extracted as a pure function `(prev: CameraSignalState?, observation: Observation, config: SignalLossConfig) -> Decision(newState, event?)` for testability without mocks |
| `CameraSignalState` (sealed class) | `core/task/` | `Healthy(lastSeenAt)` / `SignalLost(lastSeenAt, notificationSent: Boolean)` — `notificationSent` flag enables the late-alert-after-grace flow described in "Restart Behavior" |
| `SignalLossProperties` | `core/config/properties/` | Holds `enabled`, `threshold`, `pollInterval`, `activeWindow`, `startupGrace`; uses Jakarta `@Validated` + `@field:NotNull/@field:Positive` for per-field checks plus a `@PostConstruct` for cross-field invariants (`pollInterval < threshold`, `activeWindow > threshold`) |
| `SignalLossTelegramGuard` | `telegram/config/` (or similar, mirroring `AiDescriptionTelegramGuard`) | Single-purpose `@Component` that fails application startup with a clear actionable message when `application.signal-loss.enabled=true` AND `application.telegram.enabled=false`. Mirrors the existing `AiDescriptionTelegramGuard` pattern (see commit `ee5d925`). |
| `LastRecordingPerCameraDto` | `model/dto/` | New projection DTO mirroring `CameraRecordingCountDto` style: explicit `@Column("cam_id")` and `@Column("last_record_timestamp")` annotations |
| `RecordingEntityRepository.findLastRecordingPerCamera(activeSince)` | `service/repository/` | New query returning `List<LastRecordingPerCameraDto>`: `SELECT cam_id AS cam_id, MAX(record_timestamp) AS last_record_timestamp FROM recordings WHERE record_timestamp >= :activeSince AND cam_id IS NOT NULL GROUP BY cam_id ORDER BY cam_id` |
| `TelegramNotificationService.sendCameraSignalLost(...)` | `telegram/service/` | New interface method, plus impl + NoOp impl |
| `TelegramNotificationService.sendCameraSignalRecovered(...)` | `telegram/service/` | New interface method, plus impl + NoOp impl |
| New `NotificationTask` subtypes | `telegram/queue/` | Carry the loss/recovery payloads through the existing queue. **Note:** the refactor of `NotificationTask` from `data class` to `sealed interface` requires an explicit audit step (see plan Task 3) of all 5 production + 2 test usages identified by `grep "NotificationTask"`. |

### Untouched

- `WatchRecordsTask`, `FirstTimeScanTask` — the detector reads recordings from the database after they exist; how they got there is irrelevant.
- Frame analysis pipeline — fully orthogonal.
- `RecordingProcessingFacade` — orthogonal.

### Feature Flag

- Master flag: `application.signal-loss.enabled` — gated via `@ConditionalOnProperty(matchIfMissing = false)`. The `application.yaml` default is `true` (so production has the feature on by default), but `matchIfMissing = false` means existing test contexts (which omit the property) will NOT activate the task — preventing breakage of existing integration tests where `application.telegram.enabled=false`.
- **Conflict-fail at startup** is delegated to a dedicated `SignalLossTelegramGuard` `@Component` that mirrors the existing `AiDescriptionTelegramGuard` (commit `ee5d925`). When `application.signal-loss.enabled=true` AND `application.telegram.enabled=false`, the guard throws an `IllegalStateException` with a clear actionable message naming both env-vars in conflict. Putting the check in a dedicated bean (rather than `SignalLossMonitorTask.@PostConstruct`) avoids race conditions with bean initialization order and keeps the task focused on its single responsibility.

## State Machine

```kotlin
sealed class CameraSignalState {
    abstract val lastSeenAt: Instant   // most recent recording observed for this camera

    data class Healthy(override val lastSeenAt: Instant) : CameraSignalState()

    data class SignalLost(
        override val lastSeenAt: Instant,   // last recording BEFORE the loss (used to compute downtime on recovery)
        val notificationSent: Boolean,      // false during startup grace; flipped to true after late-alert dispatch
    ) : CameraSignalState()
}
```

The `notificationSent` flag is the mechanism that fixes the "camera dead before startup never gets alerted" hole: during `startupGrace` we may transition to `SignalLost(notificationSent = false)`. After grace ends, the next tick checks each `SignalLost` entry and dispatches the late alert for any that still have `notificationSent = false`.

```
                           gap = now - lastSeenAt > threshold
                  ┌──────────────────────────────────────────────┐
                  ▼                                              │
              Healthy ──────────────────────────────────► SignalLost
                  ▲                                              │
                  │                                              │
                  └──────────────────────────────────────────────┘
                           new recording arrived
                           (lastSeenAt advanced past SignalLost.lastSeenAt)
```

### Tick Algorithm

`tick()` is a `suspend fun` (Spring 6.1+ supports `suspend` in `@Scheduled`), so it does NOT block any TaskScheduler thread. The DB call and notification enqueue use natural coroutine `suspend` semantics; backpressure on the Telegram queue (which uses `channel.send()`) suspends the tick coroutine without blocking platform threads.

For each tick (running every `pollInterval`):

```
1. now = clock.instant()
   inGrace = now < startedAt + startupGrace
   activeSince = now - activeWindow                             // default: 24h
   stats: List<(camId, maxRecordTs)> = repo.findLastRecordingPerCamera(activeSince)
   seenCamIds = stats.map { it.camId }.toSet()

2. For each (camId, maxRecordTs):
     prev = state[camId]
     observation = Observation(maxRecordTs = maxRecordTs, now = now)
     decision = decide(prev, observation, config(threshold = threshold, inGrace = inGrace))
     state[camId] = decision.newState
     if (decision.event != null) emit decision.event
   // Pure decide() function semantics — see "decide() decision table" below.

3. Cleanup: for camId in state but NOT in stats (camera fell out of activeWindow):
     - If state[camId] is Healthy → remove silently.
     - If state[camId] is SignalLost → KEEP. We MUST retain SignalLost entries
       that fell out of activeWindow so that an eventual recovery can still
       emit a RECOVERY notification with the correct downtime.
     // Rationale: the cleanup-only-Healthy rule fixes the "user got loss alert
     // but never got recovery" gap that pure activeWindow-based cleanup created.
```

### `decide()` Decision Table

Pure function `decide(prev: CameraSignalState?, obs: Observation, cfg: Config) -> Decision(newState, event?)`. No side effects, no I/O — fully unit-testable without mocks.

Inputs per call: `prev`, `obs.maxRecordTs`, `obs.now`, `cfg.threshold`, `cfg.inGrace`.

Computed: `gap = max(Duration.ZERO, now - maxRecordTs)` (the `max` clamps clock skew where Frigate's recording timestamp is in the future relative to our `now`; we log a warn in that case but treat gap as zero), `overThreshold = gap > cfg.threshold` (strict inequality — at exactly `threshold` we consider it healthy; this is the conservative choice).

| `prev` | `overThreshold` | `inGrace` | `newState` | `event` |
|---|---|---|---|---|
| `null` | false | any | `Healthy(maxRecordTs)` | none |
| `null` | true | true | `SignalLost(maxRecordTs, notificationSent = false)` | none (deferred) |
| `null` | true | false | `SignalLost(maxRecordTs, notificationSent = true)` | `Loss(camId, maxRecordTs, gap)` |
| `Healthy(_)` | false | any | `Healthy(maxRecordTs)` | none |
| `Healthy(h)` | true | true | `SignalLost(maxRecordTs, notificationSent = false)` | none (deferred) |
| `Healthy(h)` | true | false | `SignalLost(maxRecordTs, notificationSent = true)` | `Loss(camId, maxRecordTs, gap)` |
| `SignalLost(s)` | false | any | `Healthy(maxRecordTs)` | `Recovery(camId, downtime = maxRecordTs - s.lastSeenAt)` |
| `SignalLost(s, sent=false)` | true | false | `SignalLost(s.lastSeenAt, notificationSent = true)` | `Loss(camId, s.lastSeenAt, gap)` *(LATE ALERT)* |
| `SignalLost(s, sent=true)` | true | any | `SignalLost(s.lastSeenAt, notificationSent = true)` | none (no-op) |
| `SignalLost(s, sent=false)` | true | true | `SignalLost(s.lastSeenAt, notificationSent = false)` | none (still in grace) |

Two key changes from earlier draft:

1. **Healthy → SignalLost uses `maxRecordTs`** as the new `lastSeenAt`, not the prior `Healthy.lastSeenAt`. Rationale: `maxRecordTs` is the actual last recording the DB has; the prior `Healthy.lastSeenAt` is staler. The emitted `Loss.lastSeen` is `maxRecordTs` correspondingly.

2. **Late-alert row** (`SignalLost(sent=false)`, overThreshold, NOT inGrace): this is the new flow that ensures cameras dead before startup get an alert after grace ends. Previously they were silently stuck.

### Restart Behavior

- State is in-memory and lost on restart. The startup grace period (default 5min) gives Frigate and cameras time to settle before the detector emits user-facing alerts.
- During grace, cameras with `gap > threshold` are still tracked as `SignalLost(notificationSent = false)` — the alert is *deferred*, not lost.
- After grace ends, on the next tick any `SignalLost` entry with `notificationSent = false` whose `gap` still exceeds `threshold` triggers a late LOSS alert. The flag is then flipped to `true`.
- A camera that recovers during grace period gets a silent state seed (`Healthy`) — no recovery alert, because no loss was ever observed.

### Concurrency

`@Scheduled(fixedDelay = pollInterval)` with a `suspend fun tick()` guarantees at-most-one in-flight tick (Spring serializes consecutive invocations of the same scheduled method, even when the method suspends). The state map is therefore accessed serially. We use `ConcurrentHashMap<String, CameraSignalState>` defensively — the cost is negligible and it protects against future changes (e.g., if anyone adds parallel access via JMX exposure or test instrumentation). The map is private to the task; there are no external readers today.

## Configuration

New block in `application.yaml`:

```yaml
application:
  signal-loss:
    enabled: ${SIGNAL_LOSS_ENABLED:true}
    threshold: ${SIGNAL_LOSS_THRESHOLD:3m}
    poll-interval: ${SIGNAL_LOSS_POLL_INTERVAL:30s}
    active-window: ${SIGNAL_LOSS_ACTIVE_WINDOW:24h}
    startup-grace: ${SIGNAL_LOSS_STARTUP_GRACE:5m}
```

| Env var | Default | Purpose |
|---|---|---|
| `SIGNAL_LOSS_ENABLED` | `true` | Master flag. `@ConditionalOnProperty(matchIfMissing = false)` — feature ON by default in production via `application.yaml`, but missing-property contexts (e.g., test contexts that don't set the key) won't activate the task. |
| `SIGNAL_LOSS_THRESHOLD` | `3m` | If `now - lastRecording > THRESHOLD` (strict) then signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | `30s` | Tick period. Must be smaller than threshold. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | `24h` | "Active camera" window. Cameras whose last recording is older are not monitored. **Must be set to at least Frigate's recording retention** to avoid losing track of normally-recording cameras. |
| `SIGNAL_LOSS_STARTUP_GRACE` | `5m` | After startup, alerts are deferred (state still seeded as `SignalLost(notificationSent = false)`). After grace ends, deferred alerts fire on the next tick if the gap still holds. |

### Validation

Per-field validation uses Jakarta `@Validated` + `@field:NotNull` / `@field:Positive` (or equivalent) on `SignalLossProperties`, mirroring the existing `TelegramProperties` style. Cross-field invariants are checked in `@PostConstruct` because Bean Validation cannot express them concisely:

- (per-field, via annotations) `enabled` not null
- (per-field, via annotations) `threshold`, `pollInterval`, `activeWindow` positive (`@Positive` on `Duration` works in Spring Boot 3+)
- (per-field, via annotations) `startupGrace` non-negative
- (cross-field, in `@PostConstruct`) `pollInterval < threshold` (otherwise the detector's resolution is degenerate)
- (cross-field, in `@PostConstruct`) `activeWindow > threshold`
- (cross-field, in `@PostConstruct`) **The user MUST set `activeWindow` to be `>=` Frigate's recording retention.** Otherwise active cameras can fall out of the activeWindow during normal operation. We cannot validate this automatically (we don't know Frigate's retention), but the constraint is documented in the `.claude/rules/configuration.md` entry for `SIGNAL_LOSS_ACTIVE_WINDOW`.

All `@PostConstruct` failures throw `IllegalStateException` with a message naming both the offending property and the conflicting value.

### Database Indexes

The query `SELECT cam_id, MAX(record_timestamp) FROM recordings WHERE record_timestamp >= :activeSince AND cam_id IS NOT NULL GROUP BY cam_id` is satisfied by the existing `idx_recordings_record_timestamp` (single-column btree on `record_timestamp`). PostgreSQL performs a range scan on the 24h window followed by hash aggregation. **No new index migration is required.** For very large deployments (50+ cameras, > 1M rows in 24h), a composite `(cam_id, record_timestamp DESC)` would enable a loose index scan; this is deferred until measured to be necessary.

### Documentation

Update `.claude/rules/configuration.md` to register the five new env vars (with the explicit activeWindow >= retention guidance for `SIGNAL_LOSS_ACTIVE_WINDOW`). Update `.claude/rules/database.md` ONLY IF a new index is added (per the decision above, none is added in this iteration).

## Telegram Notifications

### Recipients

Use the same recipient resolution as existing detection notifications: `TelegramUserService.getAuthorizedUsersWithZones()` — every ACTIVE user gets the alert. Per the user's choice, signal-loss alerts are not owner-only.

### Message Format (informal — final wording in implementation)

**Loss:**

> 📡❌ Camera `front_door` lost signal
> Last recording: 14:32:18 (3 min 14 s ago)

**Recovery:**

> 📡✅ Camera `front_door` is back online
> Downtime: 12 min 48 s

Each message is localized using `language_code` and `olson_code` from the user's row in `telegram_users` (consistent with detection notifications). Time formatting reuses the existing helpers used for detection notifications.

### Delivery Path

The new `sendCameraSignalLost` and `sendCameraSignalRecovered` methods build a new `NotificationTask` subtype per recipient and enqueue it onto the existing `TelegramNotificationQueue`. `TelegramNotificationSender` extends to handle these subtypes — short text-only messages, no inline buttons, no video.

## Error Handling

### Principles

- A tick must never throw out of the scheduler — wrap the body in `try/catch (Exception)`, log, return. The next tick will retry.
- No internal retry loop — the next scheduled tick replaces any retry logic.
- State map is not mutated until "scan + diff" succeeds. If the DB call fails, the state is untouched.

### Scenarios

| Scenario | Behavior |
|---|---|
| Repo query failed (DB timeout, network) | Catch (excluding `CancellationException`), `logger.warn { "Signal-loss tick failed: ${e.message}" }`. State unchanged. Next tick retries. |
| Telegram queue full | `notificationQueue.enqueue(task)` uses suspend `channel.send()`. Because `tick()` is `suspend fun`, this suspends the tick coroutine without blocking any platform thread. Spring `@Scheduled` waits for the suspending tick to complete before scheduling the next one (single in-flight invariant). When the consumer drains the queue, the tick resumes and finishes; the next tick fires `pollInterval` after that. Trade-off: under sustained queue pressure individual ticks may take longer than `pollInterval`, so freshness of state degrades — but `ServerHealthMonitor` and other `@Scheduled` jobs are unaffected (they run on their own scheduler-thread coroutines), and no notification is lost. The state transition is already applied for ticks that completed; cameras already in `SignalLost` fall into the no-op branch on subsequent ticks (no spam). |
| `TelegramNotificationService` threw on enqueue (non-cancellation) | Catch, log warn, transition stays applied (per state-machine: `SignalLost(notificationSent = true)` even if enqueue failed). Subsequent ticks see `(SignalLost, sent=true, true)` no-op. Trade-off: one notification can be lost under unrecoverable Telegram failure, but the detector cannot become a runaway alert source. |
| `CancellationException` propagation | Always rethrown (never swallowed) — required for proper coroutine cancellation on shutdown. |
| Camera deleted from DB between ticks | `cleanup` step removes it on the next tick (only if `Healthy`), no notification. `SignalLost` entries are preserved (see Tick Algorithm step 3). |
| Conflict at startup (signal-loss=true, telegram=false) | `SignalLossTelegramGuard` `@Component` (mirrors `AiDescriptionTelegramGuard`) throws `IllegalStateException` at context refresh. Exception names both `application.signal-loss.enabled` and `application.telegram.enabled`. Application context fails to start. |
| Clock skew (Frigate `record_timestamp` is in the future relative to `now`) | `gap = max(Duration.ZERO, now - maxRecordTs)`. Log warn at most once per camera per tick. Treats the camera as healthy. |

### Logging Levels

| Level | Event |
|---|---|
| `INFO` | Task startup with config; `Healthy → SignalLost` transition (with `lastSeenAt`, `gap`); `SignalLost → Healthy` transition (with `downtime`); end of startup grace; **late LOSS alert dispatched after grace** (per camera, with `gap`) |
| `DEBUG` | Per-tick summary: "scanned N cameras, currentlyLost=M, healthy=K, removed=R" (note: `currentlyLost` is a gauge — count of cameras currently in `SignalLost` — NOT new losses this tick) |
| `WARN` | Tick failure; clock-skew detected for camera (rate-limited per camera); cleanup of `SignalLost` entries that fell out of activeWindow (none today; reserved); `TelegramNotificationService` enqueue threw |
| —     | `CancellationException` is always rethrown (never logged at any level) |

No `ERROR` — detector failure does not interrupt the rest of the system; it is degraded, not fatal.

## Testing

### Unit tests for `decide()` (pure function, no mocks needed)

Parameterized coverage of the full decision table (rows from "decide() Decision Table" above). Each row is one parameterized test case asserting `(newState, event)`. No `Clock`, no DB, no MockK — just `decide(prev, observation, config)`. This is the bulk of state-machine testing.

### Unit tests for `SignalLossMonitorTask` (JUnit 5 + MockK + injected `Clock`)

These wrap `decide()` integration with the IO surfaces. Tests call `tick()` directly (not via `@Scheduled` triggers).

- **State seeding from DB**: tick reads stats, applies decisions, updates state map.
- **Cleanup logic**: camera absent from stats AND `Healthy` → removed; camera absent from stats AND `SignalLost` → KEPT (regression test for the recovery-after-cleanup gap).
- **Startup grace**: in the first `startupGrace` minutes, all `Loss`/`Recovery` emissions are suppressed but state is seeded as `SignalLost(notificationSent = false)` if applicable.
- **Late alert after grace** (CRITICAL test): camera dead at startup → first tick (in grace) seeds `SignalLost(sent=false)` silently → tick after grace ends emits LOSS, flips to `sent=true` → subsequent tick falls into no-op.
- **Healthy → SignalLost uses `maxRecordTs`** (not stale `Healthy.lastSeenAt`): assert that `Loss` event's `lastSeen` matches the DB value, not the prior state's value.
- **Repo throws** → `tick` catches non-cancellation exceptions, state unchanged.
- **`CancellationException` propagates** (does NOT get caught) — assertion that the cancellation surfaces.
- **Telegram enqueue throws** → state transition retained, next tick falls into no-op branch (no repeat alert).
- **Clock skew handling**: `maxRecordTs > now` → `gap = 0`, treated as healthy, warn logged.

### Tests for `SignalLossTelegramGuard`

- `signal-loss.enabled=true && telegram.enabled=false` → guard throws `IllegalStateException` mentioning both env-vars.
- `signal-loss.enabled=true && telegram.enabled=true` → guard accepts (no throw).
- `signal-loss.enabled=false` → guard bean either not constructed (`@ConditionalOnProperty`) or accepts; covered by integration test (see below).

### Integration tests for `RecordingEntityRepository.findLastRecordingPerCamera`

Uses `IntegrationTestBase` (testcontainers Postgres, like other repo tests):

- Three cameras, five recordings each → returns three rows with correct `MAX(record_timestamp)`
- Recordings with `cam_id IS NULL` excluded
- Recordings with `record_timestamp < activeSince` excluded
- Empty table → empty result

### Tests for new `TelegramNotificationServiceImpl` methods

- `sendCameraSignalLost` enqueues one task per active user from `getAuthorizedUsersWithZones`
- `sendCameraSignalRecovered` likewise; downtime rendered in human form ("2 h 15 min", "47 sec") respecting the user's `language_code` and `olson_code`
- Zero active users → zero enqueue calls
- `NoOpTelegramNotificationService` — both new methods exist and are no-ops

### Property validation tests

Parameterized: `threshold=0`, `pollInterval >= threshold`, `activeWindow <= threshold`, `startupGrace < 0` — each fails with an informative message identifying the offending property.

### Conflict-fail integration test

`@SpringBootTest` with `application.signal-loss.enabled=true`, `application.telegram.enabled=false` → context startup fails because `SignalLossTelegramGuard` throws; the exception names both env-vars in conflict.

### Test config compatibility check

Run the existing `core` and `service` integration test suites unchanged (no patches to `modules/core/src/test/resources/application.yaml`). Because `@ConditionalOnProperty(matchIfMissing = false)` is in effect for `application.signal-loss.enabled`, and the test config does not set the property, neither `SignalLossMonitorTask` nor `SignalLossTelegramGuard` is constructed in test contexts → no breakage.

### Out of scope (explicitly)

- Real Telegram delivery — mocked at the service layer
- Real Spring `@Scheduled` timing — tests call the tick method directly
- Wall-clock timing — all tests use a controllable mock `Clock`

## Accepted Trade-offs (consciously deferred)

These were raised in the iter-1 review and explicitly accepted as deferred to a future iteration:

- **First-sighting LOSS by very stale cameras**: a camera last seen 23h ago (still inside `activeWindow`) that we observe for the first time triggers a LOSS alert. Operationally this can mean alerts on cameras the user has already decommissioned but not yet purged from the DB. Accepted as is — the alert volume is bounded (one alert per stale camera at startup, never repeated), and adding a `firstSightingMaxAge` cutoff has its own UX trade-offs.
- **Wording**: messages say "Camera X lost signal" rather than the more accurate "Recordings stopped arriving from camera X". Accepted as the snappier UX is more useful in the Telegram notification context.
- **Flapping suppression / hysteresis / cooldown**: a camera that toggles around the threshold can produce LOSS+RECOVERY churn. Not addressed in this iteration; will be added if observed in production.
- **Micrometer metrics** (`signal_loss_detected_total`, `signal_loss_cameras_lost` gauge, etc.): not added in this iteration. INFO-level logging on every transition is sufficient for first-iteration debugging.

## Open Questions

None at this time. All ambiguities were resolved during brainstorming and the iter-1 review cycle.

## Next Step

Pass to `superpowers:writing-plans` for an implementation plan.
