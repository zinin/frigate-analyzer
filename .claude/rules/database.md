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

## Patterns

- All repositories use Spring Data R2DBC
- Services return `Mono`/`Flux`, consumed as suspend functions
- Entity classes in `model/persistent/`
- Repositories in `service/repository/`
