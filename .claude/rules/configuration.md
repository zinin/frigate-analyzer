---
paths: "**/application.yaml,**/application*.yml,**/application*.properties"
---

# Configuration Reference

All settings in `application.yaml`.

## Core Settings

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_PORT` | 8080 | Server port |
| `FRIGATE_RECORDS_FOLDER` | - | Frigate recordings path |
| `TEMP_FOLDER` | - | Extracted frames storage |
| `FFMPEG_PATH` | - | ffmpeg binary path |
| `DISABLE_FIRST_SCAN` | false | Skip initial scan |

## Database

| Variable | Purpose |
|----------|---------|
| `DB_HOST` | PostgreSQL host |
| `DB_PORT` | PostgreSQL port |
| `DB_NAME` | Database name |
| `DB_USER` | Username |
| `DB_PASS` | Password |

## HTTP Client

| Variable | Purpose |
|----------|---------|
| `CONNECTION_TIMEOUT` | Connection timeout |
| `READ_TIMEOUT` | Read timeout |
| `WRITE_TIMEOUT` | Write timeout |
| `RESPONSE_TIMEOUT` | Response timeout |

## Detection

| Variable | Default    | Purpose |
|----------|------------|---------|
| `DETECT_DEFAULT_CONFIDENCE` | 0.6        | Confidence threshold |
| `DETECT_DEFAULT_MODEL` | yolo12s.pt | YOLO model |

## Pipeline

| Variable | Default | Purpose |
|----------|---------|---------|
| `PIPELINE_FRAME_BUFFER_SIZE` | 500 | Channel buffer size |
| `PIPELINE_FRAME_PRODUCERS_COUNT` | 6 | Producer coroutines |

## Telegram

See `.claude/rules/telegram.md` for Telegram-specific settings.
