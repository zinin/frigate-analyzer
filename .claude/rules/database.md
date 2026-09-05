---
paths: "docker/liquibase/**,**/repository/**,**/entity/**,**/persistent/**"
---

# Database

PostgreSQL with R2DBC (reactive). Liquibase for migrations.

## Management

```bash
# Apply migrations
./gradlew :frigate-analyzer-core:liquibaseUpdate
```

Migrations location: `docker/liquibase/migration/`

## Schema

### recordings

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| creation_timestamp | TIMESTAMPTZ | Row creation time |
| file_path | VARCHAR(16384) | Recording file path (unique) |
| file_creation_timestamp | TIMESTAMPTZ | File creation time |
| cam_id | VARCHAR(255) | Camera identifier |
| record_date | DATE | Recording date |
| record_time | TIME | Recording time |
| record_timestamp | TIMESTAMPTZ | Recording timestamp |
| start_processing_timestamp | TIMESTAMPTZ | When processing started (15min cooldown) |
| process_timestamp | TIMESTAMPTZ | When processing completed |
| process_attempts | INT | Number of processing attempts |
| detections_count | INT | Total detections found |
| analyze_time | INT | Processing time (ms) |
| analyzed_frames_count | INT | Frames processed |
| error_message | VARCHAR(65536) | Error details for unprocessable videos |

### detections

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| creation_timestamp | TIMESTAMPTZ | Row creation time |
| recording_id | UUID | FK to recordings (cascade delete) |
| detection_timestamp | TIMESTAMPTZ | Detection time |
| frame_index | INT | Frame number |
| model | VARCHAR(255) | Detection model name |
| class_id | INT | Detected object class ID |
| class_name | VARCHAR(255) | Detected object class name |
| confidence | REAL | Detection confidence |
| x1, y1, x2, y2 | REAL | Bounding box coordinates |

### telegram_users

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| username | VARCHAR(255) | Telegram username (unique) |
| chat_id | BIGINT | Telegram chat ID (unique, null if invited) |
| user_id | BIGINT | Telegram user ID (unique, null if invited) |
| first_name | VARCHAR(255) | First name |
| last_name | VARCHAR(255) | Last name |
| status | VARCHAR(20) | INVITED or ACTIVE |
| creation_timestamp | TIMESTAMPTZ | Creation time |
| activation_timestamp | TIMESTAMPTZ | Activation time |
| language_code | VARCHAR(64) | User language code, nullable (e.g. "ru", "en", null if not set) |
| olson_code | VARCHAR(50) | User timezone (Olson format) |
| notifications_recording_enabled | BOOLEAN NOT NULL DEFAULT TRUE | Per-user toggle for recording notifications |
| notifications_signal_enabled | BOOLEAN NOT NULL DEFAULT TRUE | Per-user toggle for signal-loss alerts |

### object_tracks

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| creation_timestamp | TIMESTAMPTZ | First time this track was seen |
| cam_id | VARCHAR(255) | Camera identifier |
| class_name | VARCHAR(255) | YOLO class |
| bbox_x1, bbox_y1, bbox_x2, bbox_y2 | REAL | Representative bbox of latest match |
| last_seen_at | TIMESTAMPTZ | Last match timestamp (updated via GREATEST) |
| last_recording_id | UUID NULL | FK → recordings (ON DELETE SET NULL) |

Index: `idx_object_tracks_cam_lastseen (cam_id, last_seen_at DESC)`. Cleanup via `ObjectTracksCleanupTask`.

### notification_verdicts

One row per notification candidate that reached the LLM judge (or was bypassed / snoozed / failed
over). Migration `1.0.6.xml`. **No cleanup** — rows are kept forever so `/verdicts` and offline
review of `context_json` stay possible. Cascade-deleted with the recording.

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| created_at | TIMESTAMPTZ | Row creation time |
| recording_id | UUID | FK → recordings (`ON DELETE CASCADE`) |
| cam_id | VARCHAR(255) | Camera identifier (denormalized for history queries) |
| record_timestamp | TIMESTAMPTZ | Recording timestamp (event time, not insert time) |
| stage | VARCHAR(16) | `JUDGE` / `SNOOZE` / `FAILOVER` / `BYPASS` |
| verdict | VARCHAR(8) | `PUBLISH` / `SUPPRESS` |
| reason | VARCHAR(32) | Model reason or fail-open reason — see `VerdictReason` |
| tracker_reason | VARCHAR(32) | Why the tracker wanted to notify (`NEW_OBJECTS` / `REAPPEARED`) |
| classes | VARCHAR(255) | Clustered class counts, e.g. `bicycle:2,car:1` |
| confidence | REAL NULL | Model confidence in `[0, 1]`, or null |
| summary | VARCHAR(512) NULL | Model one-liner; stored, not shown to recipients |
| wanted | VARCHAR(512) NULL | Model's extra-context request; stored and ignored |
| snooze_until | TIMESTAMPTZ NULL | When the in-memory snooze (if any) expires |
| preset_id | VARCHAR(32) NULL | Preset that produced a `JUDGE` row |
| model | VARCHAR(255) NULL | Effective model of that preset |
| latency_ms | INT NULL | Model call latency |
| context_json | TEXT NULL | Prompt context that was sent (or built before a failover) |
| error | VARCHAR(1024) NULL | Failover detail (`ClassName: message`), never a secret |

Indexes: `idx_notification_verdicts_cam_record (cam_id, record_timestamp DESC)` for `/verdicts` and
the history block; `idx_notification_verdicts_created (created_at)` for `/status` 24h counters.

### app_settings

| Column | Type | Purpose |
|--------|------|---------|
| setting_key | VARCHAR(64) | PK; hierarchical key |
| setting_value | VARCHAR(2048) | Serialized scalar |
| updated_at | TIMESTAMPTZ | |
| updated_by | VARCHAR(255) NULL | OWNER username, NULL for migration-seeded |

Seeded with `notifications.recording.global_enabled=true` and `notifications.signal.global_enabled=true`.

Schedule keys `notifications.recording.schedule.{enabled,window,zone}` are NOT seeded — they are
created on first configuration via `/notifications`; absent keys mean "schedule disabled".

AI-description keys are not seeded either — both are written by `/ai`
(`AppSettingsDescriptionRuntimeSettings` in core, `application/`):

| Key | Type | Absent means |
|-----|------|--------------|
| `ai.description.preset.active` | string, a preset id | nothing chosen: `default-preset`, else the first usable preset of the catalog |
| `ai.description.enabled` | boolean | `true` — descriptions are on; `APP_AI_DESCRIPTION_ENABLED=false` still wins, as the feature beans do not exist then |

Judge keys are not seeded either — written by `/ai` (`AppSettingsJudgeRuntimeSettings`, gated on
`application.ai.judge.enabled=true`):

| Key | Type | Absent means |
|-----|------|--------------|
| `ai.judge.preset.active` | string, a preset id | nothing chosen: `APP_AI_JUDGE_DEFAULT_PRESET`, else the descriptions fallback |
| `ai.judge.enabled` | boolean | `true` — the judge is on; `APP_AI_JUDGE_ENABLED=false` still wins, as the feature beans do not exist then |

`AppSettingsService` caches keys per-process without TTL, absence included, so direct SQL edits to
this table need an application restart — see "Operational Notes" in `telegram-notifications.md`.

**This cache makes the feature single-instance.** A write invalidates only the cache of the process
that performed it, so with two application containers behind one database the preset choice and the
runtime description/judge switch diverge: the container that did not serve the `/ai` tap keeps serving the
previous value until it restarts. The deployment is designed for one instance
(`docker/deploy/docker-compose.yml` runs a single `frigate-analyzer` service); nothing detects or
repairs the split.

## Patterns

- All repositories use Spring Data R2DBC
- Services return `Mono`/`Flux`, consumed as suspend functions
- Entity classes in `model/persistent/`
- Repositories in `service/repository/`
