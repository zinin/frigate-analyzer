# Notification Controls and Object Tracking — Design

**Date:** 2026-04-27
**Branch:** `feature/notification-controls`
**Status:** Design

## Problem

Two issues with current Telegram notification flow:

1. **No way to disable notifications.** Active users automatically receive every recording-detection notification and every camera signal-loss/recovery alert. There is no per-user opt-out and no global kill-switch (other than turning the bot off entirely via `TELEGRAM_ENABLED=false`).
2. **Notification flood from stationary objects.** Frigate writes ~10s recording segments. A car parked in view produces a recording-with-detections every 10s, triggering a notification each time. Over 5 minutes that is ~30 identical notifications. Users want a notification only when *something new* appears.

## Goals

- Per-user **on/off** toggle for two independent notification streams (recording detections; camera signal-loss/recovery), managed by each ACTIVE user.
- Global on/off override for both streams, managed by the OWNER.
- System-level deduplication of recording notifications via per-camera object tracking: notify only when a new object enters the camera view (vs. an existing object remaining in view).
- Schema/architecture extensible to a future feature: time-based notification schedules (e.g. quiet hours) — explicitly **out of scope** for this iteration.

## Non-Goals

- Time-based schedules (will be added in a follow-up; data model leaves room).
- Per-class filters (e.g. "notify on `person`, not `car`").
- Per-zone filters (Frigate zones).
- AI-description-based filtering.
- Distributed deployments (single-instance only).

## High-Level Architecture

Two independent mechanisms, both applied to recording notifications:

```
FrameAnalyzerConsumer
   ↓ processAndNotify(request)
RecordingProcessingFacade
   1. visualizeFrames(...)
   2. saveProcessingResult(...)
   3. decision = notificationDecisionService.evaluate(rec, request)   ← NEW
   4. if (decision.shouldNotify)
        sendRecordingNotification(...)

NotificationDecisionService                                            ← NEW
   - orchestrates: tracker.evaluate(...) → DetectionDelta
   - checks AppSettings global toggle
   - returns NotificationDecision(shouldNotify, reason, delta)
   - extension point for future: schedule, class filters, zones

ObjectTrackerService                                                   ← NEW
   - aggregateDetections(...) → representative bboxes per (cam, class)
   - matchAgainstActiveTracks(...) → new vs repeated
   - updateTracks(...) → DB save (last_seen, new tracks)

object_tracks  (new table)                                             ← NEW

—

Telegram fan-out:
   sendRecordingNotification:
      if (!appSettings.recordingGlobalEnabled) return                  ← changed
      users.filter { it.notificationsRecordingEnabled }                ← changed
      enqueue per user
   sendCameraSignalLost / Recovered: analogous flag set

App-level global settings:
   app_settings (key, value) — managed by OWNER via /notifications     ← NEW
   AppSettingsService with in-memory cache + invalidate on update
```

**Module placement:**

| Module | New artifacts |
|---|---|
| `service` | `ObjectTrackerService` impl + interface, `NotificationDecisionService` impl + interface, `AppSettingsService` impl + interface, R2DBC repositories, persistent entities, Liquibase changelog 1.0.4 |
| `model` | DTOs: `DetectionDelta`, `NotificationDecision`, `AppSettingKey` enum (or constants), `ObjectTrackDto`; extension of `TelegramUserDto` |
| `telegram` | `NotificationsCommandHandler`, `NotificationsSettingsCallbackHandler`, `NotificationsMessageRenderer`, i18n keys; new fields on `UserZoneInfo` and `TelegramUserService` |
| `core` | one-line wiring change in `RecordingProcessingFacade` |

Module dependency direction is preserved: `core → telegram → service → model → common`.

## Database Schema

### New table `object_tracks`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `creation_timestamp` | TIMESTAMPTZ NOT NULL | First time the track was seen. |
| `cam_id` | VARCHAR(255) NOT NULL | Camera identifier. |
| `class_name` | VARCHAR(255) NOT NULL | YOLO class (`car`, `person`, …). |
| `bbox_x1` | REAL NOT NULL | Representative bbox of the most recent recording. |
| `bbox_y1` | REAL NOT NULL | |
| `bbox_x2` | REAL NOT NULL | |
| `bbox_y2` | REAL NOT NULL | |
| `last_seen_at` | TIMESTAMPTZ NOT NULL | Updated to `recording.record_timestamp` on match. |
| `last_recording_id` | UUID NULLABLE | FK → `recordings(id) ON DELETE SET NULL` for traceability. |

Indexes:
- `idx_object_tracks_cam_lastseen (cam_id, last_seen_at DESC)` for active-track lookup.
- `idx_object_tracks_lastseen (last_seen_at)` for cleanup deletes.

Coordinates use the same units as `detections.x1..y2`. In the current pipeline these are pixel coordinates; tracker logic only requires a consistent coordinate space because IoU is scale-invariant. Do not add normalized `0..1` constraints.

### New table `app_settings`

| Column | Type | Notes |
|---|---|---|
| `setting_key` | VARCHAR(64) PK | Hierarchical key, e.g. `notifications.recording.global_enabled`. |
| `setting_value` | VARCHAR(2048) NOT NULL | Serialized scalar (`"true"`/`"false"` for booleans). |
| `updated_at` | TIMESTAMPTZ NOT NULL | |
| `updated_by` | VARCHAR(255) NULLABLE | OWNER username, `NULL` for migration-seeded rows. |

Migration seeds two rows:
- `notifications.recording.global_enabled = "true"`
- `notifications.signal.global_enabled = "true"`

### Extending `telegram_users`

```
ALTER TABLE telegram_users
  ADD COLUMN notifications_recording_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN notifications_signal_enabled    BOOLEAN NOT NULL DEFAULT TRUE;
```

`DEFAULT TRUE` preserves current behavior for existing users.

### Liquibase

New file `docker/liquibase/migration/1.0.4.xml` with three changesets (one per table change above), registered in `master_frigate_analyzer.xml`.

## Algorithms

### 1. Aggregating detections within one recording

Inside one recording (~10 frames × N detections per frame), the same object appears with slightly different bboxes due to motion and detector noise. Reduce to a list of representative bboxes per `(class, cluster)`.

**Greedy clustering** (per class):

```
input: List<DetectionEntity> for one recording
output: List<RepresentativeBbox(class, x1, y1, x2, y2)>

groupBy class_name:
  for each group (class X detections):
    sort by confidence DESC
    clusters = []
    for det in detections:
      find cluster c where IoU(c.union_bbox, det.bbox) > INNER_IOU
      if found: add det to c, recompute c.union_bbox (axis-aligned union)
      else:     create new cluster with this det
    for each cluster:
      representative = confidence-weighted average of (x1, y1, x2, y2)
      yield RepresentativeBbox(class, representative)
```

Default `INNER_IOU = 0.5`. Tight enough to keep distinct adjacent objects in separate clusters, loose enough to absorb per-frame jitter.

### 2. IoU formula

Standard Intersection-over-Union for axis-aligned bboxes:

```kotlin
fun iou(a: Bbox, b: Bbox): Float {
    val ix1 = maxOf(a.x1, b.x1)
    val iy1 = maxOf(a.y1, b.y1)
    val ix2 = minOf(a.x2, b.x2)
    val iy2 = minOf(a.y2, b.y2)
    val iw = maxOf(0f, ix2 - ix1)
    val ih = maxOf(0f, iy2 - iy1)
    val intersection = iw * ih
    val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
    val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
    val union = areaA + areaB - intersection
    return if (union > 0f) intersection / union else 0f
}
```

Interpretation: `IoU ∈ [0, 1]`; 0 = disjoint; 1 = identical. Defaults: `0.3` for cross-recording matching (allows mild jitter for stationary objects); `0.5` for inner-recording clustering (must be very close).

### 3. Track matching

```
ObjectTrackerService.evaluate(recording, representativeBboxes):
  T   = recording.record_timestamp
  TTL = configured (default 120s)
  THRESHOLD = configured (default 0.3)

  active = SELECT * FROM object_tracks
           WHERE cam_id = :camId
             AND last_seen_at >= T - TTL_seconds

  newTracks = []
  toUpdate  = []
  unmatchedActive = active.toMutableList()

  // Best-match: for each bbox pick the active track with *highest* IoU
  // (not first-match). Prevents cross-match aliasing when two nearby
  // same-class objects both have IoU > threshold with multiple tracks.
  for bbox in representativeBboxes:
    match = unmatchedActive
      .filter { it.class_name == bbox.class }
      .map { it to IoU(it.bbox, bbox) }
      .filter { (_, iou) -> iou > THRESHOLD }
      .maxByOrNull { (_, iou) -> iou }
      ?.first
    if match != null:
      unmatchedActive.remove(match)
      toUpdate.add(TrackUpdate(
        id = match.id,
        bbox = bbox,                      // refresh bbox
        last_seen_at = T,                 // GREATEST(last_seen_at, T) in UPDATE
        last_recording_id = recording.id,
      ))
    else:
      newTracks.add(TrackInsert(
        cam_id = recording.camId, class_name = bbox.class,
        bbox = bbox, creation_timestamp = T,
        last_seen_at = T, last_recording_id = recording.id,
      ))

  // unmatchedActive = tracks alive in TTL but absent from this recording.
  // We do NOT update them — they decay naturally if absent for > TTL.

  // Single transaction: batch UPDATE + batch INSERT.

  return DetectionDelta(
    newTracksCount = newTracks.size,
    matchedTracksCount = toUpdate.size,
    staleTracksCount = unmatchedActive.size,
  )
```

The `UPDATE` uses `SET last_seen_at = GREATEST(last_seen_at, :T)` and updates `bbox_*` / `last_recording_id` only when `:T >= last_seen_at`. This prevents a late older recording from rolling the representative bbox or traceability pointer backward.

### 4. Decision

```
NotificationDecisionService.evaluate(recording, request):
  if (request.detectionsCount == 0):
    return NotificationDecision(shouldNotify = false, reason = NO_DETECTIONS, delta = null)

  globalEnabled = appSettings.recordingGlobalEnabled    // may throw — see Error Handling

  representatives = aggregateDetections(request.frames)
  if (representatives.isEmpty()):
    // All detections filtered by confidenceFloor — no valid input for tracker.
    return NotificationDecision(shouldNotify = false, reason = NO_VALID_DETECTIONS, delta = null)

  // Tracker runs unconditionally so that state stays coherent when the global toggle returns ON.
  delta = objectTracker.evaluate(recording, representatives)

  if (!globalEnabled):
    return NotificationDecision(shouldNotify = false, reason = GLOBAL_OFF, delta = delta)

  return NotificationDecision(
    shouldNotify = delta.newTracksCount > 0,
    reason       = if (delta.newTracksCount > 0) NEW_OBJECTS else ALL_REPEATED,
    delta        = delta,
  )
```

`NotificationDecisionReason` enum: `NO_DETECTIONS`, `NO_VALID_DETECTIONS`, `GLOBAL_OFF`, `ALL_REPEATED`, `NEW_OBJECTS`, `TRACKER_ERROR`.

Future extensions slot in *before* `objectTracker.evaluate` (e.g. schedule check) — they don't need to update tracker state if they short-circuit.

### 5. Edge-case behavior

| Case | Result |
|---|---|
| Car arrives for the first time | `newTracks=1` → notify |
| Car parked, recordings every 10s | match → updateLastSeen → no notify; track can live indefinitely while it keeps matching because TTL is sliding from `last_seen_at` |
| Stationary car, 1-recording detector blink | previous track still within TTL → match on return → no false notify |
| Car leaves for 5 min then returns | old track expired → `newTracks=1` → notify |
| Car parked, person enters | car matches; person → new track → notify (with the recording's frames as today) |
| Recording with no detections | early return at `detectionsCount==0`; tracker not invoked |
| App restart | `object_tracks` survives; behavior continues without spike |
| Recording reprocessed manually | `last_recording_id` traces it; matching still correct since it works on bboxes/timestamps |

### 6. Configurable parameters (env)

| Var | Default | Purpose |
|---|---|---|
| `NOTIFICATIONS_TRACK_TTL` | `PT2M` | Track stays "active" this long after last detection (ISO-8601 Duration) |
| `NOTIFICATIONS_TRACK_IOU_THRESHOLD` | `0.3` | Cross-recording match threshold |
| `NOTIFICATIONS_TRACK_INNER_IOU` | `0.5` | Inner-recording clustering threshold |
| `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` | `0.3` | Detections below this confidence are dropped before clustering |
| `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS` | `3600000` | Background cleanup job period in milliseconds |
| `NOTIFICATIONS_TRACK_CLEANUP_RETENTION` | `PT1H` | DELETE rows with `last_seen_at < now() - retention` (ISO-8601 Duration) |

Cleanup retention is intentionally larger than TTL (so a still-relevant track won't get deleted by races) but small enough to keep the table small. Lazy filtering on read (`last_seen_at >= now() - TTL`) handles correctness; the cleanup job is purely housekeeping. `ObjectTrackerProperties` enforces `cleanupRetention >= ttl`, `ttl > 0`, and `cleanupIntervalMs > 0` at startup so that bad env settings cannot cause active tracks to disappear.

## Bot UI

### Command

| Command | Access | Description |
|---|---|---|
| `/notifications` | USER, OWNER | Manage notification subscriptions |

Registered after `/timezone` / `/language` in the menu.

### Message — USER (non-OWNER)

```
🔔 Notification settings

📹 Recording detections: ON
⚠️  Camera alerts:        ON

[📹 Disable detections]
[⚠️  Disable alerts]
[✖ Close]
```

Tapping a toggle button: handler updates the user row, re-renders the message in place.

### Message — OWNER

OWNER sees per-user toggles **plus** a global block:

```
🔔 Notification settings

📹 Recording detections: ON (mine) | ON (global)
⚠️  Camera alerts:        ON (mine) | ON (global)

[📹 Disable detections (mine)]
[⚠️  Disable alerts (mine)]
[🌐 Disable detections globally]
[🌐 Disable alerts globally]
[✖ Close]
```

When global is OFF, the indicator shows that, but per-user controls remain functional (per-user flag is preserved so the user immediately starts receiving when global flips ON).

**Effective rule:** user receives notification ⇔ `global_enabled[type] == true AND user_enabled[type] == true`.

### Callback data

Prefix `nfs:` (notification settings):

| Callback | Action |
|---|---|
| `nfs:u:rec:1` / `nfs:u:rec:0` | explicitly enable / disable per-user recording for the callback sender |
| `nfs:u:sig:1` / `nfs:u:sig:0` | explicitly enable / disable per-user signal-loss for the callback sender |
| `nfs:g:rec:1` / `nfs:g:rec:0` | explicitly enable / disable global recording (OWNER only) |
| `nfs:g:sig:1` / `nfs:g:sig:0` | explicitly enable / disable global signal-loss (OWNER only) |
| `nfs:close` | remove keyboard, leave message |

Callbacks carry the target state instead of a pure toggle so old `/notifications` messages cannot invert a newer setting accidentally. Authorization in callback handler: `g:*` paths require OWNER role; `u:*` paths must use the Telegram callback sender, not only the message chat id, and require that sender to be ACTIVE.

### Telegram-side components

| Component | File | Purpose |
|---|---|---|
| `NotificationsCommandHandler` | `telegram/bot/handler/notifications/` | Handles `/notifications`, sends initial message |
| `NotificationsSettingsCallbackHandler` | `telegram/bot/handler/notifications/` | Listens to `nfs:*` callbacks, updates state, edits message |
| `NotificationsMessageRenderer` | `telegram/bot/handler/notifications/` | Renders text + keyboard from current state (per-user, global, role) |
| `AppSettingsService` | `service/` | CRUD for `app_settings`, in-memory cache, invalidate on update |
| `TelegramUserService` (extended) | `telegram/service/` | New methods: `updateNotificationsRecording(chatId, enabled)`, `updateNotificationsSignal(chatId, enabled)`, plus getters |

### i18n

New keys in `messages_ru.properties` / `messages_en.properties`:

- `notifications.settings.title`
- `notifications.settings.recording.label`
- `notifications.settings.signal.label`
- `notifications.settings.state.on`
- `notifications.settings.state.off`
- `notifications.settings.button.toggle.recording.user`
- `notifications.settings.button.toggle.signal.user`
- `notifications.settings.button.toggle.recording.global`
- `notifications.settings.button.toggle.signal.global`
- `notifications.settings.button.close`
- `notifications.settings.global.suffix`
- `notifications.settings.user.suffix`

## Integration with Existing Notification Pipeline

### `RecordingProcessingFacade.processAndNotify`

Before `sendRecordingNotification`:

```kotlin
val decision = notificationDecisionService.evaluate(recording, request)
if (!decision.shouldNotify) {
    logger.debug { "Notification suppressed for recording=$recordingId, reason=${decision.reason}" }
    return
}
telegramNotificationService.sendRecordingNotification(recording, visualizedFrames, descriptionSupplier)
```

When `shouldNotify == false`, the AI description supplier is **not** invoked → no AI tokens spent on suppressed recordings. This is a side benefit of placing the decision service before the Telegram call.

### `TelegramNotificationServiceImpl.sendRecordingNotification`

One change:

1. After `userService.getAuthorizedUsersWithZones()`: filter `it.notificationsRecordingEnabled`.

`UserZoneInfo` gains the boolean. `getAuthorizedUsersWithZones()` returns it.

The recording path does **not** re-check `appSettings.recordingGlobalEnabled` inside `sendRecordingNotification`. `NotificationDecisionService.evaluate(...)` already gates the global toggle before fan-out is invoked, and the facade catches and logs delivery exceptions without rethrowing — so any redundant `getBoolean(...)` here would (a) be silently swallowed on AppSettings failure (contradicting the design's "AppSettings failures propagate" rule) and (b) waste a settings read on every recording. The single source of truth for the recording-path global gate is the decision service.

### `TelegramNotificationServiceImpl.sendCameraSignalLost / Recovered`

Two changes (analogous to recording fan-out, plus the global gate):

1. Early `if (!appSettings.signalGlobalEnabled) return`. Signal-loss/recovery does **not** go through `NotificationDecisionService` (it's not a recording event), so the global toggle check inside the Telegram impl is the **only** gate for the signal flag.
2. After `userService.getAuthorizedUsersWithZones()`: filter `it.notificationsSignalEnabled`.

If `appSettings.getBoolean(...)` throws inside the signal path (DB unavailable), the exception propagates up to the signal-monitor task which catches/logs notification failures — the camera transition is then re-attempted on the next monitor tick. This is consistent with the recording path's "settings failures propagate" rule.

When global signal notifications are OFF, signal-loss/recovery events are intentionally dropped without catch-up. The signal-loss state machine may still record the camera transition; when global notifications are enabled again, only future transitions notify.

## Concurrency

Multiple `FrameAnalyzerConsumer` coroutines may process recordings of **the same camera** in parallel.

**Strategy: optimistic per-camera serialization**

`ObjectTrackerService` uses a `ConcurrentHashMap<String, Mutex>`. `evaluate(recording)` takes the camera's mutex, runs the SELECT → match → batch UPDATE/INSERT inside a transaction, releases. Hold time: milliseconds. Cross-camera concurrency unaffected.

This works correctly within a single application instance. If we later run multiple instances, switch to `SELECT … FOR UPDATE` or advisory locks; out of scope now.

**Out-of-order recordings:** `UPDATE … SET last_seen_at = GREATEST(last_seen_at, :T)` ensures we never roll `last_seen_at` backward. The same update must guard `bbox_*` and `last_recording_id` with `CASE WHEN :T >= last_seen_at THEN ... ELSE existing_value END`, so a late older recording does not roll the representative bbox backward. The match decision itself is based on the *current* set of active tracks at the moment of evaluation.

## Error Handling

`ObjectTrackerService.evaluate` uses `TransactionalOperator.executeAndAwait` on the **inner** code path (after `Mutex.withLock`), NOT a class-level `@Transactional` on the outer entry point. This prevents transactions from opening before the mutex is acquired — which would otherwise hold R2DBC connections from the pool while waiting for the lock. On exception:

- `AppSettingsService` read failures (DB unavailable, R2DBC error, etc.) are **not** converted into a default boolean. They indicate the application is not functioning correctly, so the exception propagates from `getBoolean(...)` through the decision service into the pipeline; the recording is left for retry by upstream pipeline mechanisms when present.
- `AppSettingsService.getBoolean(...)` distinguishes *unparseable* values from *failed reads*. If the stored value exists but is not a strict boolean (e.g. someone wrote `"weird"` directly into the table), `getBoolean` logs `WARN` with the raw value and falls back to the supplied default. This treats data corruption as a recoverable configuration issue rather than a fatal failure, while keeping a diagnostic trail.
- **Fail-safe = "notify anyway"** only if tracker evaluation fails after settings were read successfully **and** `globalEnabled == true`: avoids silent suppression of real security events when tracker state has a transient issue. Falls back to pre-tracker behavior — every recording with detections notifies. Log at `WARN` with stack trace.
- **Global OFF wins over tracker error.** If the tracker throws while `globalEnabled == false`, the decision returned is `NotificationDecision(shouldNotify = false, reason = TRACKER_ERROR)`. This prevents fan-out, prevents the AI description supplier from being invoked (which would otherwise spend AI tokens for no delivered notification), and respects the OWNER's global kill-switch.
- If tracker creates/updates tracks and the later Telegram enqueue/fan-out fails, the current iteration accepts the at-most-once gap: the next segment may be suppressed because the object is already tracked. This is documented for follow-up in `docs/telegram-outbox.md` rather than solved in this iteration.
- **Recording retry boundary (accepted limitation).** `RecordingProcessingFacade.processAndNotify` calls `saveProcessingResult` *before* `NotificationDecisionService.evaluate(...)`. As a result, if the decision throws an `AppSettingsService` exception, the recording is already marked `process_timestamp != null` in the DB and will not be reprocessed by the pipeline; the failure is logged and the notification for this specific recording is lost. This matches the existing telegram-outbox accepted gap (delivery semantics are best-effort across all post-save steps) and is documented as a known limitation rather than fixed in this iteration. A future task may move evaluate before save or share a retry-safe boundary.

Concretely, `NotificationDecisionService` reads settings *before* the tracker `try/catch`; tracker exceptions are converted to `TRACKER_ERROR` while preserving `globalEnabled` semantics:

```kotlin
val globalEnabled = appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true)
return try {
    val delta = objectTracker.evaluate(recording, detections)
    // decide NEW_OBJECTS / ALL_REPEATED / GLOBAL_OFF based on (globalEnabled, delta)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.warn(e) { "Tracker failure for recording=${recording.id} cam=${recording.camId}; globalEnabled=$globalEnabled, fail-open=$globalEnabled" }
    NotificationDecision(shouldNotify = globalEnabled, reason = TRACKER_ERROR)
}
```

For signal-loss/recovery, `AppSettingsService.getBoolean(...)` is read inside `TelegramNotificationServiceImpl.sendCameraSignalLost / Recovered`. A read failure there propagates out of the Telegram service — the signal-monitor task that owns these calls catches and logs notification failures and the camera transition can be re-attempted on the next monitor tick (events are idempotent in the monitor's state machine), so the read failure does not silently lose the alert across restarts.

## Behavior with Telegram Disabled

When `application.telegram.enabled = false`, `NoOpTelegramNotificationService` already replaces `TelegramNotificationService`. `ObjectTrackerService` and `AppSettingsService` are domain services in `service/` with no `@ConditionalOnProperty` — they work regardless.

Effect: when Telegram is disabled, tracker still runs and maintains state. When Telegram comes back online (config flip + restart), there is no behavioral spike — state is current.

## Logging

| Level | Event |
|---|---|
| `INFO` | `User @username toggled notifications.recording = false` |
| `INFO` | `OWNER @username set global notifications.recording = false` |
| `DEBUG` | `ObjectTracker: cam=$cam new=2 matched=3 stale=0 (recording=$rid)` — only when `new > 0` (DEBUG so that busy outdoor cameras do not flood logs with one INFO per passing object) |
| `DEBUG` | `Decision: suppress (all_repeated\|no_valid_detections\|global_off): cam=$cam recording=$rid` |
| `DEBUG` | `Decision: notify: cam=$cam newClasses=[car, person]` |
| `WARN`  | `Tracker failure for recording=$rid cam=$cam; globalEnabled=$g, shouldNotify=$g` + stack (fail-open only when global ON) |
| `WARN`  | `AppSettings: invalid stored value for $key=$raw; falling back to default=$default` (corruption signal, not fatal) |
| `ERROR` | `AppSettings failure while evaluating notification decision` + stack (read failure — propagates out of decision service / Telegram service) |

## Tests

| Layer | Coverage |
|---|---|
| Unit `ObjectTrackerServiceTest` | aggregateDetections (empty, one, two near, two far); matching (all match, partial match, none match); out-of-order recording does not roll back `bbox_*` / `last_recording_id` (older `:T` keeps existing values via `CASE WHEN`); empty detections short-circuit *before* mutex/transaction acquisition; low-confidence detections below `confidenceFloor` produce empty representatives and zero DB writes; TTL expiry; bbox edge cases (zero-size, identical, fully containing); IoU helper edge cases (disjoint, overlapping, identical, zero-area); recordTimestamp fallback semantics |
| Unit `NotificationDecisionServiceTest` | `shouldNotify=true` on new tracks; `false` when all matched; `false` when global OFF (tracker still ran and updated state); `tracker error + global ON → fail-open shouldNotify=true`; `tracker error + global OFF → suppressed shouldNotify=false reason=TRACKER_ERROR` (no AI supplier invocation); `representatives.isEmpty()` after confidenceFloor → `NO_VALID_DETECTIONS` (tracker not called); settings read error propagates (tracker not called); no-detection short-circuit (does not call tracker) |
| Unit `AppSettingsServiceTest` | get with default, set, cache invalidation, concurrent updates, missing key returns default, **invalid stored value (e.g. `"weird"`) logs WARN and falls back to default**; cache is updated only after successful `repository.upsert(...)` (no `@Transactional` race window) |
| Unit `NotificationsCommandHandlerTest` | initial message for USER vs OWNER; correct labels and buttons; `appSettings.getBoolean(...)` failure surfaces a user-facing error (or propagates per error-handling section); `getBoolean` only invoked for OWNER (USER rendering does not need global state) |
| Unit `NotificationsSettingsCallbackHandlerTest` | toggle updates DB and edits message; `nfs:g:*` rejected for non-OWNER; `nfs:u:*` rejected for unauthorized callback sender; explicit target-state `1`/`0` interpreted correctly when DB row already matches (no-op); unknown / malformed callback data ignored |
| Unit `TelegramNotificationServiceImplTest` | existing + new: skip recipient when `notifications_recording_enabled=false`; **`sendCameraSignalRecovered` filters by `notifications_signal_enabled`** (not just `sendCameraSignalLost`); signal global OFF short-circuit on both lost and recovered paths; signal AppSettings read failure propagates out of Telegram service |
| Unit `ObjectTracksCleanupTaskTest` | `cleanup()` invokes `tracker.cleanupExpired()` once; exceptions are caught and logged WARN without rethrowing |
| Unit `IsOwnerTest` (in `TelegramUserServiceImplNotificationsTest`) | `isOwner` returns `false` when `telegramProperties.owner` is null/blank or when `username` is null/blank; returns `true` only on exact match |
| Integration `ObjectTrackRepositoryIT` | insert/update/select-active-by-cam-and-ttl/cleanup-expired; `updateOnMatch` does not roll back `bbox_*` / `last_recording_id` for older `:lastSeenAt` |
| Integration `AppSettingsRepositoryIT` | upsert, default seed, OWNER-attribution, repeated upsert with same key |
| Integration Facade-level | recording with detections → first time: tracker creates row, `sendRecordingNotification` invoked; same data again: tracker matches, send not invoked, AI description supplier *not* invoked; different class: send invoked; settings read exception propagates from facade (recording is left in pipeline state for current iteration's accepted retry-boundary semantics) |

`NotificationDecisionServiceTest` mocks `ObjectTrackerService` and `AppSettingsService` to keep branch tests fast and DB-free.

## Out of Scope (deferred)

- **Schedules / quiet hours.** Future column on `telegram_users` (e.g. `notifications_schedule JSONB`) and/or `app_settings` row. The `NotificationDecisionService` orchestration point is designed exactly for this slot-in.
- **Per-class filters.** Same extension point.
- **Per-zone filters.** Requires Frigate zone integration.
- **Distributed deployments.** In-memory mutex assumes single instance. Move to advisory locks or `FOR UPDATE` if/when multi-instance.
- **Metrics (Micrometer).** Could expose `notifications.suppressed{reason}` and `object_tracks.active{cam_id}` — leave for a follow-up unless the project already wires actuator metrics elsewhere; logs cover diagnostics for now.

## Migration / Rollout

- All migrations are additive (new tables, new nullable/defaulted columns). Safe to deploy.
- Default values keep current behavior: every existing user has both flags `true`; both global flags seeded `true`. Tracker starts with empty state — first recording per camera after deploy creates initial tracks (and sends one notification per camera, which is fine and expected).
- No back-compat shims required.

## Open Questions

None at design time. Implementation plan will refine concrete API shapes (DTO field names, exact callback string format, and i18n string text) and may surface minor adjustments.
