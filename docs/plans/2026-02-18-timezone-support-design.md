# Timezone Support & record_timestamp Query Refactoring — Design

## Overview

Two related changes:

1. **SQL refactoring:** Replace `record_date`/`record_time` filters in export queries with `record_timestamp` (absolute UTC `Instant`), which is the correct field for time-range filtering.
2. **Timezone support:** Add per-user timezone (`olson_code`) to `telegram_users`. Users set their timezone via `/timezone` command. All bot communication (export dialog, notifications) uses the user's timezone.

## User Scenario

### /timezone command

```
User: /timezone
Bot: "Ваш текущий часовой пояс: UTC
      Выберите часовой пояс:"

[Europe/Moscow (MSK +3)]  [Europe/Kaliningrad (EET +2)]
[Asia/Yekaterinburg (+5)] [Asia/Omsk (+6)]
[Asia/Krasnoyarsk (+7)]   [Asia/Irkutsk (+8)]
[Asia/Yakutsk (+9)]       [Asia/Vladivostok (+10)]
[Ввести вручную]          [Отмена]

User: [Europe/Moscow]
Bot: "Часовой пояс сохранён: Europe/Moscow (UTC+3)"
```

Branch "Ввести вручную":
```
Bot: "Введите Olson ID часового пояса (например: Europe/Moscow, Asia/Tokyo):"
User: America/New_York
Bot: "Часовой пояс сохранён: America/New_York (UTC-5)"
```

Invalid input: `"Неизвестный часовой пояс. Попробуйте снова или выберите из списка."`

### /export (timezone-aware)

When asking for time range, bot shows user's timezone:
```
"Введите диапазон времени (например: 9:15-9:20, макс. 5 минут)
 Время в вашем часовом поясе: Europe/Moscow"
```

User input is interpreted in their timezone and converted to UTC Instant before querying.

### Notifications

Each authorized user receives a notification with timestamp formatted in their own timezone. Previously all users received MSK time.

## Architecture

### Database

New Liquibase migration:

```sql
ALTER TABLE telegram_users
  ADD COLUMN olson_code VARCHAR(50);
```

Column is nullable. Default handling is in code: `ZoneId.of(entity.olsonCode ?: "UTC")`.

### Changed Components

| Component | Module | Change |
|-----------|--------|--------|
| Liquibase migration (new file) | — | `ALTER TABLE telegram_users ADD COLUMN olson_code VARCHAR(50)` |
| `TelegramUserEntity` | telegram | + field `olsonCode: String?` |
| `TelegramUserRepository` | telegram | + method to update `olsonCode` |
| `UserZoneInfo` (new DTO) | telegram | `data class UserZoneInfo(val chatId: Long, val zone: ZoneId)` |
| `TelegramUserService` | telegram | + `getUserZone(chatId): ZoneId` (with try/catch fallback to UTC), `updateTimezone(chatId, olsonCode)` (with validation + affected rows check), `getAuthorizedUsersWithZones(): List<UserZoneInfo>` |
| `RecordingEntityRepository` | service | Rewrite 2 queries: `(LocalDate, LocalTime, LocalTime)` → `(Instant, Instant)` |
| `VideoExportService` (interface) | telegram | Method signatures → `(Instant, Instant, ...)` |
| `VideoExportServiceImpl` | core | Method signatures → `(Instant, Instant, ...)` |
| `FrigateAnalyzerBot` | telegram | + `/timezone` command, update `/export` (TZ conversion + hint), update `/help` |
| `TelegramNotificationServiceImpl` | telegram | Remove hardcoded MSK, send each user message in their own TZ |

### What Does NOT Change

- `recordings` table schema — `record_date`, `record_time` columns remain in DB
- `ClockConfig` — `MOSCOW_ZONE_ID` constant stays (used elsewhere)
- `/export` dialog UX — same steps, only time interpretation changes

## Data Flow

### /export

```
FrigateAnalyzerBot loads userZone = userService.getUserZone(chatId)
User selects date:
  → "Сегодня"/"Вчера" computed in user's TZ: Instant.now(clock).atZone(userZone).toLocalDate()
User inputs time range (in their TZ)
  → startInstant = LocalDateTime.of(date, startTime).atZone(userZone).toInstant()
  → endInstant   = LocalDateTime.of(date, endTime).atZone(userZone).toInstant()
  → videoExportService.findCamerasWithRecordings(startInstant, endInstant)
  → videoExportService.exportVideo(startInstant, endInstant, camId)
```

### SQL Queries (after refactoring)

**findCamerasWithRecordings:**
```sql
SELECT cam_id, COUNT(*) as recordings_count
FROM recordings
-- 10-second buffer: Frigate records in segments; a recording's record_timestamp
-- marks the segment start, so the actual content may extend slightly before it.
WHERE record_timestamp >= :startInstant - INTERVAL '10 seconds'
  AND record_timestamp <= :endInstant
  AND file_path IS NOT NULL
  AND cam_id IS NOT NULL
GROUP BY cam_id
ORDER BY cam_id
```

**findByCamIdAndInstantRange:**
```sql
SELECT *
FROM recordings
WHERE cam_id = :camId
  -- 10-second buffer: same reason as above
  AND record_timestamp >= :startInstant - INTERVAL '10 seconds'
  AND record_timestamp <= :endInstant
  AND file_path IS NOT NULL
ORDER BY record_timestamp ASC
```

### Notifications

```
Detection event
  → TelegramNotificationServiceImpl
  → userService.getAuthorizedUsersWithZones() → List<UserZoneInfo>
  → for each (chatId, userZone):
      format recordTimestamp.atZone(userZone)
      send individual message to chatId
```

## /timezone Command — Predefined List

8 CIS timezone buttons + "Ввести вручную" + "Отмена". Implementation uses waiter pattern (same as `/export`).

Predefined zones:
- `Europe/Kaliningrad` (UTC+2)
- `Europe/Moscow` (UTC+3)
- `Asia/Yekaterinburg` (UTC+5)
- `Asia/Omsk` (UTC+6)
- `Asia/Krasnoyarsk` (UTC+7)
- `Asia/Irkutsk` (UTC+8)
- `Asia/Yakutsk` (UTC+9)
- `Asia/Vladivostok` (UTC+10)

Manual input validation:
1. Reject offset-based IDs (without `/`): `GMT+3`, `UTC+03:00` etc. — these lose DST handling. Only accept region-based Olson IDs (e.g. `Europe/Moscow`, `Asia/Tokyo`).
2. `ZoneId.of(input)` — catch `DateTimeException` (parent of `ZoneRulesException`) for both invalid format and unknown region IDs.

## Error Handling

| Situation | Response |
|-----------|----------|
| Invalid Olson ID (manual input) | "Неизвестный часовой пояс. Попробуйте снова или выберите из списка." (catch `DateTimeException`) |
| Offset-based zone (manual input, no `/` in ID) | "Пожалуйста, используйте формат Continent/City (например: Europe/Moscow)." |
| Invalid `olson_code` in DB | Fallback to UTC + log warning (in `getUserZone` and `getAuthorizedUsersWithZones`) |
| /timezone canceled | "Отменено." |
| No recordings in Instant range | "Записей за указанный период не найдено." (same as before) |
