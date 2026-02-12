---
paths: "modules/core/**/pipeline/**"
---

# Pipeline Architecture

Coroutine-based producer-consumer pattern using Kotlin Channels.

## Components

| Component | Location | Purpose |
|-----------|----------|---------|
| FrameAnalysisPipeline | `core/pipeline/frame/` | Orchestrates entire lifecycle |
| FrameExtractorProducer | `core/pipeline/frame/` | Extracts frames via ffmpeg |
| FrameAnalyzerConsumer | `core/pipeline/frame/` | Sends frames to detection servers |
| RecordingTracker | `core/pipeline/frame/` | Thread-safe state management |

## FrameAnalysisPipeline

- Creates buffered channel (500 frame capacity)
- Spawns 6 producer coroutines for parallel extraction
- Spawns N consumer coroutines based on server capacity
- Coordinates graceful shutdown

## FrameExtractorProducer

- Queries DB for unprocessed recordings in batches (10)
- Uses ffmpeg at 0.1 FPS
- Registers recordings with RecordingTracker

## FrameAnalyzerConsumer

- Multiple consumers (1-N based on server capacity)
- Reads frame files, sends to detection servers
- Tracks completion/failure via RecordingTracker
- Finalizes recordings when all frames processed
- Cleans up temporary frame files

## RecordingTracker

- Thread-safe state for in-flight recordings
- Tracks pending/completed/failed frames
- Aggregates detection results
- Determines when recording is fully processed

## File Watching

| Task | Purpose |
|------|---------|
| WatchRecordsTask | Monitors Frigate folder via Java WatchService |
| FirstTimeScanTask | Initial scan on startup (disable: `-Dapplication.disable-first-scan-task=true`) |

WatchRecordsTask parses `.mp4` filenames to extract camera ID, date, time, timestamp.
