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
| `SIGNAL_LOSS_ENABLED` | true | Master flag. `@ConditionalOnProperty(matchIfMissing=false)` — production has it on by default via `application.yaml`, but missing-property test contexts won't activate the task. |
| `SIGNAL_LOSS_THRESHOLD` | 3m | If `now - lastRecording > THRESHOLD` (strict) the signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | 30s | Detector tick period. Must be smaller than `SIGNAL_LOSS_THRESHOLD`. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | 24h | Window of "active" cameras. **Must be set to at least Frigate's recording retention.** Cameras whose last recording is older are not monitored. |
| `SIGNAL_LOSS_STARTUP_GRACE` | 5m | After startup, alerts are deferred (state seeded as `SignalLost(notificationSent=false)`). The first tick after grace ends fires any pending late LOSS alert if the gap still holds. |
