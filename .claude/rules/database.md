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
| file_path | TEXT | Recording file path |
| cam_id | TEXT | Camera identifier |
| recorded_at | TIMESTAMP | Recording timestamp |
| detections_count | INT | Total detections found |
| analyze_time | BIGINT | Processing time (ms) |
| analyzed_frames_count | INT | Frames processed |

### detections

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| recording_id | UUID | FK to recordings |
| class_name | TEXT | Detected object class |
| confidence | FLOAT | Detection confidence |
| x1, y1, x2, y2 | FLOAT | Bounding box |
| frame_index | INT | Frame number |
| detection_timestamp | TIMESTAMP | Detection time |

### telegram_users

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| username | TEXT | Telegram username |
| chat_id | BIGINT | Telegram chat ID (null if invited) |
| user_id | BIGINT | Telegram user ID (null if invited) |
| first_name | TEXT | First name |
| last_name | TEXT | Last name |
| status | TEXT | INVITED or ACTIVE |
| created_at | TIMESTAMP | Creation time |
| activated_at | TIMESTAMP | Activation time |

## Patterns

- All repositories use Spring Data R2DBC
- Services return `Mono`/`Flux`, consumed as suspend functions
- Entity classes in `model/persistent/`
- Repositories in `service/repository/`
