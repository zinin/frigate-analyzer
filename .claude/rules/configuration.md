---
paths: "**/application.yaml,**/application*.yml,**/application*.properties"
---

# Configuration Reference

All settings in `modules/core/src/main/resources/application.yaml`.

## Core Settings

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_PORT` | 8080 | Server port |
| `TEMP_FOLDER` | /tmp/frigate-analyzer/ | Extracted frames storage |
| `FFMPEG_PATH` | /usr/bin/ffmpeg | ffmpeg binary path |

## Records Watcher

Settings under `application.records-watcher` in `application.yaml`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `FRIGATE_RECORDS_FOLDER` | /mnt/data/frigate/recordings/ | Frigate recordings path |
| `DISABLE_FIRST_SCAN` | false | Skip initial scan on startup |
| `WATCH_PERIOD` | P1D | ISO-8601 duration, how far back to watch directories |
| `WATCH_CLEANUP_INTERVAL` | PT1H | How often to clean up expired watch keys |

## Database

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | frigate_analyzer | Database name |
| `DB_USER` | frigate_analyzer_rw | Username |
| `DB_PASS` | frigate_analyzer_rw | Password |

## HTTP Client

| Variable | Default | Purpose |
|----------|---------|---------|
| `CONNECTION_TIMEOUT` | 10s | Connection timeout |
| `READ_TIMEOUT` | 30s | Read timeout |
| `WRITE_TIMEOUT` | 30s | Write timeout |
| `RESPONSE_TIMEOUT` | 30s | Response timeout |

## Detection

| Variable | Default    | Purpose |
|----------|------------|---------|
| `DETECT_DEFAULT_CONFIDENCE` | 0.6        | Confidence threshold |
| `DETECT_DEFAULT_IMG_SIZE` | 2016       | Default image size |
| `DETECT_DEFAULT_MODEL` | yolo26s.pt | Default YOLO model |
| `DETECT_GOOD_MODEL` | yolo26x.pt | High-quality YOLO model |
| `DETECT_RETRY_DELAY` | 500ms      | Retry delay on failure |
| `DETECT_FRAME_TIMEOUT` | 60s        | Single frame detection timeout |
| `DETECT_FRAME_EXTRACTION_TIMEOUT` | 5m         | Frame extraction timeout |
| `DETECT_VISUALIZE_TIMEOUT` | 60s        | Visualization timeout |
| `DETECT_HEALTH_CHECK_TIMEOUT` | 5s         | Health check timeout |
| `DETECT_HEALTH_CHECK_INTERVAL` | 30s        | Health check interval |

### Frame Extraction

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_SCENE_THRESHOLD` | 0.05 | Scene change threshold |
| `DETECT_MIN_INTERVAL` | 1.0 | Min interval between frames (sec) |
| `DETECT_MAX_FRAMES` | 50 | Max frames per recording |
| `DETECT_FRAME_QUALITY` | 85 | Extracted frame JPEG quality |

### Remote Visualization

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_MAX_DET` | 100 | Max detections to visualize |
| `DETECT_LINE_WIDTH` | 2 | Bounding box line width |
| `DETECT_SHOW_LABELS` | true | Show class labels |
| `DETECT_SHOW_CONF` | true | Show confidence scores |
| `DETECT_VISUALIZE_QUALITY` | 90 | Output JPEG quality |

### Video Visualization (Annotation)

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_VIDEO_VISUALIZE_TIMEOUT` | 45m | Full annotation job timeout. Must be < QuickExport annotated outer timeout (50m), otherwise generic error replaces the dedicated annotation-timeout message |
| `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT` | 10s | HTTP timeout for POST /jobs/{id}/cancel on vision server. Tolerant of all errors. |
| `DETECT_VIDEO_VISUALIZE_POLL_INTERVAL` | 3s | Annotation job status poll interval |
| `DETECT_VIDEO_VISUALIZE_MAX_DET` | 100 | Max detections per frame |
| `DETECT_VIDEO_VISUALIZE_DETECT_EVERY` | 1 | Detect every N frames |
| `DETECT_VIDEO_VISUALIZE_LINE_WIDTH` | 2 | Bounding box line width |
| `DETECT_VIDEO_VISUALIZE_SHOW_LABELS` | true | Show class labels |
| `DETECT_VIDEO_VISUALIZE_SHOW_CONF` | true | Show confidence scores |

## Detection Filter

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECTION_FILTER_ENABLED` | true | Enable/disable filtering |
| `DETECTION_FILTER_CLASSES` | person,car,motorcycle,truck,bicycle,cat,dog,bird,backpack,umbrella | Allowed object classes |

## Pipeline

| Variable | Default | Purpose |
|----------|---------|---------|
| `PIPELINE_FRAME_BUFFER_SIZE` | 500 | Channel buffer size |
| `PIPELINE_FRAME_MIN_CONSUMERS` | 1 | Min consumer coroutines |
| `PIPELINE_FRAME_PRODUCERS_COUNT` | 6 | Producer coroutines |
| `PIPELINE_IDLE_DELAY` | 1s | Producer idle delay |
| `PIPELINE_ERROR_DELAY` | 5s | Producer error delay |
| `PIPELINE_BATCH_SIZE` | 10 | Recording batch size |

## Local Visualization

| Variable | Default | Purpose |
|----------|---------|---------|
| `LOCAL_VIZ_LINE_WIDTH` | 2 | Bounding box line width |
| `LOCAL_VIZ_QUALITY` | 90 | Output JPEG quality |
| `LOCAL_VIZ_REFERENCE_HEIGHT` | 720 | Reference height for scaling |
| `LOCAL_VIZ_MIN_FONT_SCALE` | 0.5 | Min font scale factor |
| `LOCAL_VIZ_MAX_FONT_SCALE` | 2.2 | Max font scale factor |
| `LOCAL_VIZ_BASE_FONT_SCALE` | 2.0 | Base font scale factor |
| `LOCAL_VIZ_BASE_FONT_SIZE` | 16 | Base font size (px) |
| `LOCAL_VIZ_LABEL_PADDING` | 4 | Label padding (px) |
| `LOCAL_VIZ_MAX_FRAMES` | 10 | Max frames to visualize |

## AI Description

Settings under `application.ai.description` in `application.yaml`. Enables AI-generated short and detailed descriptions of detections via Claude (or future providers). Requires `APP_AI_DESCRIPTION_ENABLED=true`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_AI_DESCRIPTION_ENABLED` | false | Master flag for AI description. When `false`, no Claude calls, no placeholders, no edit jobs. |
| `APP_AI_DESCRIPTION_PROVIDER` | claude | Provider implementation. Currently only `claude` is supported. |
| `APP_AI_DESCRIPTION_LANGUAGE` | en | Reply language. `ru` or `en`. |
| `APP_AI_DESCRIPTION_SHORT_MAX` | 200 | Max characters of the short description (caption suffix). |
| `APP_AI_DESCRIPTION_DETAILED_MAX` | 1500 | Max characters of the detailed description (expandable blockquote). |
| `APP_AI_DESCRIPTION_MAX_FRAMES` | 10 | Max frames forwarded to the model per recording. |
| `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` | 30s | Max wait for a free concurrency slot. |
| `APP_AI_DESCRIPTION_TIMEOUT` | 60s | Per-call describe timeout (including internal retries). |
| `APP_AI_DESCRIPTION_MAX_CONCURRENT` | 2 | Max simultaneous Claude requests. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED` | true | Enable sliding-window throttle on AI description invocations. When `false`, every recording with AI enabled gets a description request. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_MAX` | 10 | Max invocations within the sliding window. Counter increments when a slot is granted; failed Claude calls (transport errors, retries) do not refund the slot. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW` | 1h | Sliding-window length. Spring Boot `Duration` simple format takes a single suffix (`30s`, `15m`, `1h`); for compound durations use ISO-8601 (`PT2H30M`). When the limit is exceeded, the recording goes to Telegram as a plain notification — no caption placeholder, no second reply message, no edit-job, no Claude call. |

## Telegram

| Variable | Default | Purpose |
|----------|---------|---------|
| `TELEGRAM_ENABLED` | true | Enable/disable bot |
| `TELEGRAM_BOT_TOKEN` | - | Bot token |
| `TELEGRAM_OWNER` | - | Owner username (without @) |
| `TELEGRAM_QUEUE_CAPACITY` | 100 | Notification queue size |
| `TELEGRAM_SEND_VIDEO_TIMEOUT` | 3m | Timeout for sending video |
| `TELEGRAM_PROXY_HOST` | (empty) | SOCKS5 proxy host. Empty = no proxy |
| `TELEGRAM_PROXY_PORT` | 1080 | SOCKS5 proxy port |

See `.claude/rules/telegram.md` for full Telegram module details.

## Signal Loss Detection

Settings under `application.signal-loss` in `application.yaml`. The detector polls the database for the most recent recording timestamp per camera and notifies Telegram on signal loss / recovery. Active when `SIGNAL_LOSS_ENABLED=true`; requires `TELEGRAM_ENABLED=true` (enforced at startup by `SignalLossTelegramGuard`).

| Variable | Default | Purpose |
|----------|---------|---------|
| `SIGNAL_LOSS_ENABLED` | true | Master flag. Default `true` is set by this YAML — production has the feature on. The bean is gated by `@ConditionalOnProperty(matchIfMissing=false)`, so test contexts that don't load this YAML and leave the property unset keep the feature off (existing integration tests are unaffected). |
| `SIGNAL_LOSS_THRESHOLD` | 3m | If `now - lastRecording > THRESHOLD` (strict) the signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | 30s | Detector tick period. Must be smaller than `SIGNAL_LOSS_THRESHOLD`. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | 24h | Window of "active" cameras. **Must be set to at least Frigate's recording retention.** Cameras whose last recording is older are not monitored. Validation at startup also enforces `activeWindow > threshold + startupGrace` so a camera lost just before boot does not fall out of the window before the late-alert tick fires. |
| `SIGNAL_LOSS_STARTUP_GRACE` | 5m | After startup, alerts are deferred for this duration. If a camera was already dark at boot, the first tick after grace ends fires a (late) LOSS alert provided the gap still holds. Shorten to surface boot-time outages faster; lengthen if Frigate restarts and you want to avoid spurious LOSS during its own warm-up. |

## Notifications

Settings under `application.notifications` in `application.yaml`. Object tracker suppresses duplicate recording notifications when an object remains in view across consecutive recordings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `NOTIFICATIONS_TRACK_TTL` | 30m | Track stays "active" this long after last detection. Match → updateLastSeen → no spam. |
| `NOTIFICATIONS_TRACK_IOU_THRESHOLD` | 0.3 | IoU threshold for cross-recording matching of (class, bbox). |
| `NOTIFICATIONS_TRACK_INNER_IOU` | 0.5 | IoU threshold for clustering same-class detections within one recording. |
| `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` | 0.3 | Ignore low-confidence detections before clustering/tracking. |
| `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS` | 3600000 | `@Scheduled` cleanup job period in milliseconds. |
| `NOTIFICATIONS_TRACK_CLEANUP_RETENTION` | 1h | DELETE rows with `last_seen_at < now() - retention`. Larger than TTL. |
| `NOTIFICATIONS_TRACK_REAPPEAR_GAP` | = TTL | Matched track absent longer than this → notify as `REAPPEARED`. Must be > 0 and <= TTL; defaulting to TTL makes it a no-op (the comparison is strict, and TTL also bounds `findActive`). |
| `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` | (empty) | Comma-separated classes allowed to notify as `REAPPEARED`. Empty = all classes (no-op). Matching is case-insensitive and trims; blank entries are ignored, but a list of nothing but blanks fails at binding. Does **not** affect `NEW_OBJECTS` — a class left out still notifies the first time it is seen. Unrelated to `DETECTION_FILTER_CLASSES`. |
| `NOTIFICATIONS_COOLDOWN_REAPPEAR` | PT0S | Minimum distance between two `REAPPEARED` notifications for one camera; extra ones are suppressed with reason `COOLDOWN`. `PT0S` (default) disables it. Measured on `recordTimestamp`, so a backlog drained newest-first is not collapsed. Does not gate `NEW_OBJECTS`, and never skips the tracker. |

Two things about that row surprise operators the first time and are worth stating outright:

- **`sinceLast` in the suppress line can be negative** (`sinceLast=PT-11S`). The distance is compared
  by absolute value, and the queue drains newest-first, so the recording being judged is often *older*
  than the one that anchored the window. A minus sign there is normal, not a bug.
- **A recording far older than the anchor notifies again.** The anchor holds the newest announced
  `recordTimestamp` per camera, and anything outside the window on either side counts as its own
  event. While a large backlog is being drained this can produce two notifications in quick
  succession — that is the deliberate trade-off which keeps a backlog from collapsing into a single
  notification, which is what a wall-clock cooldown would do.

### Tuning REAPPEAR_GAP under a long TTL

A long TTL suppresses static noise (parked car, fixed false positive) but also swallows real traffic:
tracks accumulate until any new detection overlaps one, so nothing is ever "new" again. `REAPPEAR_GAP`
restores notifications without shortening the TTL, by using absence rather than location.

It works because the two cases separate cleanly when recordings are continuous: a static object is
re-detected at the recording cadence, while a person who left and came back has a gap of hours. Set
it above the largest gap a *static* object shows (detector flakiness), below the smallest gap a real
visitor shows. Measure both from `detections` before picking a value. The value must also exceed the
camera's normal processing cadence: a wall-clock pause between evaluations longer than `REAPPEAR_GAP`
reads as an interruption and restarts the watch window, so a gap smaller than the cadence would keep
the feature permanently blind.

Gaps in *processing* are excluded automatically, and measuring `detections` would never reveal them.
After a restart, a deploy, a stalled pipeline or a camera signal loss, the first recordings processed
span the whole interruption — the queue is drained newest-first — so every static object would read
as having come back. The tracker only counts an absence that began while it was already watching that
camera, so reappearances stay silent for exactly `REAPPEAR_GAP` after such an interruption (the
window restarts at the first recording processed, and a track must then be absent for more than the
gap *inside* the new window before it can notify — with `PT1H`, the feature is blind for an hour
after every restart); the suppressed ones appear in its debug line as `unobserved=N`.

Known limitation: the interruption detector's threshold IS `REAPPEAR_GAP`, so an in-process stall
*shorter* than the gap (e.g. 55 min under `PT1H`, detection servers down without a restart) does not
close the window. The first recording after such a stall measures an absence whose middle nobody
observed, and a static object whose absence straddles the stall can notify falsely — one extra
notification, in line with the tracker's fail-open bias. A separate, shorter interruption threshold
would trade these rare false positives for blind windows after every minor hiccup; revisit only if
production shows the pattern.

Raising TTL on its own will not start: `cleanup-retention >= ttl` is validated in
`ObjectTrackerProperties.init` and defaults to 1h, so the container fails at binding time with a
`cleanup-retention` message. (A `reappear-gap` message cannot appear in that scenario — the gap
follows TTL by default; it fires only when `NOTIFICATIONS_TRACK_REAPPEAR_GAP` is explicitly set
above TTL.) The three move together:

```
NOTIFICATIONS_TRACK_TTL=PT12H
NOTIFICATIONS_TRACK_REAPPEAR_GAP=PT1H
NOTIFICATIONS_TRACK_CLEANUP_RETENTION=PT48H
```

Per-user toggles for recording detections and camera signal-loss alerts are stored in `telegram_users.notifications_recording_enabled` / `notifications_signal_enabled` (default `true`). Global toggles in `app_settings`: `notifications.recording.global_enabled`, `notifications.signal.global_enabled`. OWNER manages globals via `/notifications`.
