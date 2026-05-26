---
paths: "modules/core/**/pipeline/**,modules/core/**/facade/**,modules/core/**/task/**"
---

# Pipeline Architecture

Coroutine-based producer-consumer pattern using Kotlin Channels.

## Components

| Component | Location | Purpose |
|-----------|----------|---------|
| FrameAnalysisPipeline | `core/pipeline/frame/` | Orchestrates entire lifecycle |
| FrameExtractorProducer | `core/pipeline/frame/` | Extracts frames via detection servers |
| FrameAnalyzerConsumer | `core/pipeline/frame/` | Sends frames to detection servers |
| RecordingTracker | `core/pipeline/frame/` | Thread-safe state management |
| RecordingState | `core/pipeline/frame/` | Per-recording state data class |
| FrameTask | `core/pipeline/frame/` | Frame processing task data class |
| RecordingProcessingFacade | `core/facade/` | Orchestrates save + notify + visualize |

## FrameAnalysisPipeline

- Creates buffered channel (configurable capacity)
- Spawns N producer coroutines for parallel extraction
- Spawns M consumer coroutines based on server capacity (min: configurable)
- Coordinates graceful shutdown

## FrameExtractorProducer

- Queries DB for unprocessed recordings in batches (configurable)
- Sends frame extraction requests to detection servers
- Registers recordings with RecordingTracker
- Configurable idle/error delays between batches

## FrameAnalyzerConsumer

- Multiple consumers (min-consumers to N based on server capacity)
- Reads frame files, sends to detection servers via DetectionPostProcessor
- Tracks completion/failure via RecordingTracker
- Calls RecordingProcessingFacade when all frames processed
- Cleans up temporary frame files

## RecordingTracker

- Thread-safe state for in-flight recordings
- Tracks pending/completed/failed frames per recording
- Aggregates detection results
- Determines when recording is fully processed

## RecordingProcessingFacade

- Saves processing results to DB
- Sends Telegram notification with best detection frame
- Orchestrates frame visualization for notifications

## File Watching & Startup

| Task | Location | Purpose |
|------|----------|---------|
| WatchRecordsTask | `core/task/` | Coroutine supervisor that drives WatchRecordsLoop; owns lifecycle, backoff, health state |
| WatchRecordsLoop | `core/task/` | Stateless logic of a single iteration: poll + handle ENTRY_CREATE + periodic cleanup |
| WatchRecordsTaskHealthIndicator | `core/task/` | HealthIndicator that exposes task state via `/actuator/health` |
| FirstTimeScanTask | `core/task/` | Initial scan on startup (disable: `DISABLE_FIRST_SCAN=true`) |
| StartupTelegramNotifier | `core/application/` | Sends owner one Telegram message on ApplicationReadyEvent (indirect restart-frequency signal) |

### Selective watching

WatchRecordsLoop uses selective watching to limit monitored directories:
- Only directories within `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

WatchRecordsLoop parses `.mp4` filenames to extract camera ID, date, time, timestamp.

### Supervision

WatchRecordsTask runs the loop on a dedicated `Dispatchers.IO.limitedParallelism(1)` coroutine (lifecycle via `@EventListener(ApplicationReadyEvent)` / `@PreDestroy`):
- Any non-cancellation exception is logged at ERROR, then exponential backoff (5s → 60s, capped). `currentBackoff` resets to 5s after `SUCCESSES_TO_RESET_BACKOFF=5` consecutive successes after a failure.
- `ClosedWatchServiceException` triggers WatchService recreation in the next iteration (`registeredDirs` is cleared and re-registered from scratch).
- `CancellationException` propagates cleanly — no backoff, no failure-bookkeeping.

All supervision thresholds (`INITIAL_BACKOFF`, `MAX_BACKOFF`, `SUCCESSES_TO_RESET_BACKOFF`, `HEALTH_STALENESS`) are hardcoded constants in `WatchRecordsTask.kt` — by intent (single-deployment project, no operator-tuning expected).

### Health (passive signal, no automatic restart)

WatchRecordsTaskHealthIndicator exposes `watchRecordsTask` component in `/actuator/health` with one of:
- **UP** — supervisor running normally, last successful iteration within `HEALTH_STALENESS=2m`, or just started up.
- **OUT_OF_SERVICE** — in backoff after one or more consecutive failures (transient).
- **DOWN** — supervisor coroutine not active, OR no successful iteration for longer than `HEALTH_STALENESS` while failures keep happening (permanent).

`/actuator/health` aggregation propagates DOWN → docker healthcheck returns non-200 → after `retries=3 × interval=30s ≈ 90s` docker marks the container `unhealthy`. **This does NOT trigger an automatic restart** — `restart: unless-stopped` in plain docker compose reacts only to `exited`/non-zero exit codes, not to `unhealthy`. Self-healing would require either `System.exit(...)` from within the application on sustained DOWN, or an autoheal sidecar (`willfarrell/autoheal`) in docker-compose; both are explicitly out of scope (see iter-1 review §D1). Operator must monitor `docker ps` and the actuator endpoint and run `docker restart` manually.

### Startup notification

StartupTelegramNotifier listens for `ApplicationReadyEvent` and sends the bot owner one plain-text message containing version, commit hash, build time, and current timestamp. Since there is no automatic restart on DOWN, this message arrives only on manual `docker restart`/deploy or JVM-level fatal exit (e.g. OOM). Treat it as a sanity signal that the container has actually come up — not as a restart-frequency metric. Gated by `@ConditionalOnProperty(application.telegram.enabled=true)` AND `@Profile("!test")` — no-op when Telegram is disabled or running under test profile. Failures during send are caught and logged at WARN; they do NOT prevent application startup.

## Signal-Loss Monitor

| Component | Location | Purpose |
|-----------|----------|---------|
| SignalLossMonitorTask | `core/task/` | `@Scheduled(fixedDelay=SIGNAL_LOSS_POLL_INTERVAL)` poller; runs per-camera state machine, dispatches Loss/Recovery alerts |
| SignalLossDecider | `core/task/` | Pure state-machine: input (lastRecording, now, threshold, activeWindow) → action (none/loss/recovery) |
| CameraSignalState | `core/task/` | Sealed Healthy/SignalLost data class kept in `ConcurrentHashMap<camId, state>` |
| SignalLossProperties | `core/config/properties/` | Bound to `application.signal-loss.*`; startup validation enforces `activeWindow > threshold + startupGrace` |
| SignalLossTelegramGuard | `telegram/config/` | Startup-time guard that fails fast when `signal-loss.enabled=true` but `telegram.enabled=false` |

Activation is gated by `@ConditionalOnProperty(application.signal-loss.enabled=true, matchIfMissing=false)` so test contexts that don't load `application.yaml` keep the feature off. The task `@DependsOn("signalLossTelegramGuard")` so the conflict guard runs first. Per-user/global opt-out is mediated via `telegram_users.notifications_signal_enabled` and `app_settings.notifications.signal.global_enabled`.

## Object Tracker (notification dedup)

| Component | Location | Purpose |
|-----------|----------|---------|
| ObjectTracksCleanupTask | `core/task/` | `@Scheduled` cleanup of `object_tracks` rows with `last_seen_at < now() - NOTIFICATIONS_TRACK_CLEANUP_RETENTION` |

Tracker logic (clustering same-class detections, IoU matching across recordings, TTL refresh) lives in the service module; `object_tracks` schema is documented in `database.md`. The facade calls into the tracker before deciding whether to enqueue a Telegram notification.

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `PIPELINE_FRAME_BUFFER_SIZE` | 500 | Channel buffer size |
| `PIPELINE_FRAME_MIN_CONSUMERS` | 1 | Min consumer coroutines |
| `PIPELINE_FRAME_PRODUCERS_COUNT` | 6 | Producer coroutines |
| `PIPELINE_IDLE_DELAY` | 1s | Producer idle delay |
| `PIPELINE_ERROR_DELAY` | 5s | Producer error delay |
| `PIPELINE_BATCH_SIZE` | 10 | Recording batch size |

Signal-loss and object-tracker tunables live in `configuration.md` (`SIGNAL_LOSS_*`, `NOTIFICATIONS_TRACK_*`).
