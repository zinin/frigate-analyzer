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
| `SignalLossMonitorTask` | `core/task/` | Spring `@Scheduled` tick driver; holds in-memory `Map<camId, CameraSignalState>`; detects transitions; calls `TelegramNotificationService` for loss/recovery |
| `CameraSignalState` (sealed class) | `core/task/` | `Healthy(lastSeenAt)` / `SignalLost(lastSeenAt, notifiedAt)` |
| `SignalLossProperties` | `core/config/properties/` | Holds `enabled`, `threshold`, `pollInterval`, `activeWindow`, `startupGrace`; validates on construction |
| `RecordingEntityRepository.findLastRecordingPerCamera(activeSince)` | `service/repository/` | New query: `SELECT cam_id, MAX(record_timestamp) FROM recordings WHERE record_timestamp >= :activeSince AND cam_id IS NOT NULL GROUP BY cam_id` |
| `TelegramNotificationService.sendCameraSignalLost(...)` | `telegram/service/` | New interface method, plus impl + NoOp impl |
| `TelegramNotificationService.sendCameraSignalRecovered(...)` | `telegram/service/` | New interface method, plus impl + NoOp impl |
| New `NotificationTask` subtypes | `telegram/queue/` | Carry the loss/recovery payloads through the existing queue |

### Untouched

- `WatchRecordsTask`, `FirstTimeScanTask` — the detector reads recordings from the database after they exist; how they got there is irrelevant.
- Frame analysis pipeline — fully orthogonal.
- `RecordingProcessingFacade` — orthogonal.

### Feature Flag

- Master flag: `application.signal-loss.enabled` (default `true`), gated via `@ConditionalOnProperty`.
- **Conflict-fail at startup** (matches commit `ee5d925` pattern): if `application.signal-loss.enabled=true` AND `application.telegram.enabled=false`, the application must fail to start with a clear actionable message naming both env-vars in conflict — signal-loss alerts have nowhere to be sent.

## State Machine

```kotlin
sealed class CameraSignalState {
    abstract val lastSeenAt: Instant   // most recent recording observed for this camera

    data class Healthy(override val lastSeenAt: Instant) : CameraSignalState()

    data class SignalLost(
        override val lastSeenAt: Instant,   // last recording BEFORE the loss
        val notifiedAt: Instant,            // when we emitted the loss notification
    ) : CameraSignalState()
}
```

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

For each tick (running every `pollInterval`):

```
1. If now < startedAt + startupGrace:
     run steps 2-3 to seed state, but DO NOT emit notifications
     return

2. activeSince = now - activeWindow                             // default: 24h
   stats: List<(camId, maxRecordTs)> = repo.findLastRecordingPerCamera(activeSince)

3. For each (camId, maxRecordTs):
     gap = now - maxRecordTs
     prev = state[camId]
     overThreshold = gap > threshold

     case (prev, overThreshold):
       (null,            false) -> state[camId] = Healthy(maxRecordTs)
                                   // first sighting, healthy: silent init

       (null,            true)  -> state[camId] = SignalLost(maxRecordTs, now)
                                   emit LOSS(camId, lastSeen=maxRecordTs, gap)
                                   // skipped during startup grace

       (Healthy h,       false) -> state[camId] = Healthy(maxRecordTs)

       (Healthy h,       true)  -> state[camId] = SignalLost(h.lastSeenAt, now)
                                   emit LOSS(camId, lastSeen=h.lastSeenAt, gap)

       (SignalLost sl,   false) -> state[camId] = Healthy(maxRecordTs)
                                   emit RECOVERY(camId,
                                                 downtime = maxRecordTs - sl.lastSeenAt)

       (SignalLost sl,   true)  -> no-op, no emit (do not spam; keep notifiedAt)

4. Cleanup: for camId in state but NOT in stats (camera fell out of activeWindow):
     remove state[camId]
     no notification
     // The camera's last recording is older than activeWindow — treated as
     // out-of-service. If it ever comes back, it re-enters via the (null, _)
     // branches as a fresh sighting.
```

### Restart Behavior

- State is in-memory and lost on restart. The startup grace period (default 5min) gives Frigate and cameras time to settle before the detector starts emitting alerts.
- A camera that is genuinely dead at restart time will be re-detected as `SignalLost` after the grace period (correct: it really is down, the user should know).
- A camera that recovers during grace period gets a silent state seed — no recovery notification, because we never observed a loss. This is acceptable: recovery alerts for events the user never saw a loss for would be confusing.

### Concurrency

Single Spring `@Scheduled(fixedDelay = pollInterval)` worker — at most one tick at a time. The `Map<camId, CameraSignalState>` does not need extra synchronization because all access is from the scheduler's single worker thread. (No external readers — the map is private.)

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
| `SIGNAL_LOSS_ENABLED` | `true` | Master flag. `@ConditionalOnProperty`. |
| `SIGNAL_LOSS_THRESHOLD` | `3m` | If `now - lastRecording > THRESHOLD` then signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | `30s` | Tick period. Must be meaningfully smaller than threshold. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | `24h` | "Active camera" window. Cameras whose last recording is older are not monitored. |
| `SIGNAL_LOSS_STARTUP_GRACE` | `5m` | After startup, only seed state — no notifications. |

### Validation (in `SignalLossProperties`)

Validate on construction (or `@PostConstruct`); fail fast with clear messages:

- `threshold > 0`
- `pollInterval > 0`
- `pollInterval < threshold` (otherwise the detector's resolution is degenerate)
- `activeWindow > threshold`
- `startupGrace >= 0`

### Documentation

Update `.claude/rules/configuration.md` to register the five new env vars.

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
| Repo query failed (DB timeout, network) | `logger.warn { "Signal-loss tick failed: ${e.message}" }`. State unchanged. Next tick retries. |
| Telegram queue full (`Channel.trySend` returned failure) | `logger.warn { "Failed to enqueue signal-loss notification for $camId: queue full" }`. The state transition has already been applied — we will NOT re-attempt on subsequent ticks (the state is now `SignalLost`, the next tick falls into the `(SignalLost, lost)` no-op branch). Trade-off: one notification can be lost under queue pressure, but the detector cannot become a runaway alert source. |
| `TelegramNotificationService` threw on enqueue | Same as above: log warn, transition stays applied. |
| Camera deleted from DB between ticks | `cleanup` step removes it on the next tick, no notification. |
| Conflict at startup (signal-loss=true, telegram=false) | Application fails to start — caught at `@PostConstruct` or via `BeanFactoryPostProcessor`, exception names both env-vars. |

### Logging Levels

| Level | Event |
|---|---|
| `INFO` | Task startup with config; `Healthy → SignalLost` transition; `SignalLost → Healthy` transition; end of startup grace |
| `DEBUG` | Per-tick summary: "scanned N cameras, M lost, K healthy" |
| `WARN` | Tick failure; queue full; cleanup of cameras outside active window (count only — single line per tick if any) |

No `ERROR` — detector failure does not interrupt the rest of the system; it is degraded, not fatal.

## Testing

### Unit tests for `SignalLossMonitorTask` (JUnit 5 + MockK + injected `Clock`)

Parameterized state-machine coverage — all 6 cases of `(prev state, overThreshold)`:

- `(null, false)` → state seeded as `Healthy`, no notification
- `(null, true)` → state seeded as `SignalLost`, **loss** emitted (outside grace)
- `(Healthy, false)` → `lastSeenAt` advanced
- `(Healthy, true)` → state → `SignalLost`, **loss** emitted with `lastSeen` from the prior `Healthy`
- `(SignalLost, false)` → state → `Healthy`, **recovery** emitted with `downtime = newRec - prevLastSeen`
- `(SignalLost, true)` → no-op, no emit

Plus:

- Cleanup: camera in state but absent from stats → removed silently
- Startup grace: in the first `startupGrace` minutes, all loss/recovery emissions are suppressed but state is seeded
- After grace ends: a camera still in `SignalLost` from grace-time emits no late alert (that path is `(SignalLost, true)` no-op); a camera that flipped during grace stays in seeded state and behaves normally afterwards
- Repo throws → tick swallows the exception, state unchanged
- Telegram enqueue throws → state transition retained, next tick falls into no-op branch (no repeat alert)

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

`@SpringBootTest` with `application.signal-loss.enabled=true`, `application.telegram.enabled=false` → context startup fails; the exception names both env-vars in conflict.

### Out of scope (explicitly)

- Real Telegram delivery — mocked at the service layer
- Real Spring `@Scheduled` timing — tests call the tick method directly
- Wall-clock timing — all tests use a controllable mock `Clock`

## Open Questions

None at this time. All ambiguities were resolved during brainstorming.

## Next Step

Pass to `superpowers:writing-plans` for an implementation plan.
