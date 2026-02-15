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

## File Watching

| Task | Purpose |
|------|---------|
| WatchRecordsTask | Monitors Frigate folder via Java WatchService |
| FirstTimeScanTask | Initial scan on startup (disable: `DISABLE_FIRST_SCAN=true`) |

WatchRecordsTask uses selective watching to limit monitored directories:
- Only directories within the configured `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` directory structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup task removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

WatchRecordsTask parses `.mp4` filenames to extract camera ID, date, time, timestamp.

## Configuration

| Variable | Default | Purpose |
|----------|---------|---------|
| `PIPELINE_FRAME_BUFFER_SIZE` | 500 | Channel buffer size |
| `PIPELINE_FRAME_MIN_CONSUMERS` | 1 | Min consumer coroutines |
| `PIPELINE_FRAME_PRODUCERS_COUNT` | 6 | Producer coroutines |
| `PIPELINE_IDLE_DELAY` | 1s | Producer idle delay |
| `PIPELINE_ERROR_DELAY` | 5s | Producer error delay |
| `PIPELINE_BATCH_SIZE` | 10 | Recording batch size |
