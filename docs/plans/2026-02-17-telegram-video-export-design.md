# Telegram Video Export Command — Design

## Overview

Telegram bot command `/export` for exporting merged camera recordings for a specified date/time range. User selects parameters through inline buttons and text input, bot concatenates Frigate recording segments with ffmpeg, compresses if needed, and sends the video file.

## User Scenario

```
User: /export
Bot: "Выберите дату:" [Сегодня] [Вчера] [Ввести дату]

If "Ввести дату":
Bot: "Введите дату (формат: 2025-12-28):"
User: 2025-12-28

Bot: "Введите диапазон времени (например: 09:15-09:20, макс. 5 минут):"
User: 09:15-09:20

Bot: "Выберите камеру:" [cam1 (3 записи)] [cam2 (5 записей)] [Все камеры]
(only cameras with recordings for the period are shown)

User: [cam1]
Bot: *processes and sends video*
```

- Step order: Date → Time → Camera (cameras filtered by available recordings)
- Cancel button available at every step
- Fragments are ~10 seconds; no cutting — include all fragments overlapping with the requested range

## Decisions

- **Dialog UX**: Inline buttons for date/camera, text input for time range
- **State management**: In-memory ConcurrentHashMap<ChatId, ExportDialogState> with 10-min TTL
- **Access**: All authorized users (OWNER + USER)
- **Max duration**: 5 minutes
- **File size limit**: 50 MB (Telegram bot limit)
- **Progress indicator**: None — just send the video when ready

## Architecture

### New Components

| Component | Module | Purpose |
|-----------|--------|---------|
| `ExportCommand` | telegram | `/export` handler, registers in bot |
| `ExportDialogState` | telegram | Sealed class for dialog steps |
| `ExportDialogManager` | telegram | In-memory state storage and management |
| `VideoExportService` | service | Business logic: find recordings, merge, compress |
| `VideoMergeService` | service | ffmpeg wrapper: concat + compress |

### Changes to Existing Components

| Component | Change |
|-----------|--------|
| `RecordingEntityRepository` | New query: find recordings by date + time range + camId |
| `FrigateAnalyzerBot` | Register `/export` command, handle callback_query and text messages for dialog |

### Data Flow

```
/export → ExportCommand → ExportDialogManager (state)
         ↓ callback/text
    ExportDialogManager → when all params collected →
    VideoExportService.export(date, timeRange, camId) →
        RecordingEntityRepository.findByDateAndTimeRange() → List<RecordingEntity>
        VideoMergeService.mergeAndCompress(filePaths) → tempFile
        Bot.sendVideo(chatId, tempFile)
        TempFileHelper.deleteIfExists(tempFile)
```

## Dialog State Machine

```kotlin
sealed class ExportDialogState {
    data object WaitingForDate : ExportDialogState()
    data class WaitingForCustomDate(val placeholder: Unit = Unit) : ExportDialogState()
    data class WaitingForTimeRange(val date: LocalDate) : ExportDialogState()
    data class WaitingForCamera(val date: LocalDate, val timeRange: Pair<LocalTime, LocalTime>) : ExportDialogState()
}
```

Stored in `ConcurrentHashMap<Long, ExportDialogState>` (key = chatId). Auto-cleanup entries older than 10 minutes.

## ffmpeg Strategy

### Step 1: Concatenation (no re-encoding)

Create concat file:
```
file '/path/to/segment1.mp4'
file '/path/to/segment2.mp4'
```

Command: `ffmpeg -f concat -safe 0 -i list.txt -c copy output.mp4`

### Step 2: Compression (if needed)

If result > 45 MB:
```
ffmpeg -i merged.mp4 -vcodec libx264 -crf 28 -preset fast -acodec aac compressed.mp4
```

If still > 50 MB after compression → error message to user.

## Error Handling

| Situation | Response |
|-----------|----------|
| Invalid date format | "Неверный формат. Введите дату: YYYY-MM-DD" |
| Invalid time format | "Неверный формат. Введите диапазон: HH:MM-HH:MM" |
| Range > 5 minutes | "Максимальный диапазон — 5 минут. Введите другой:" |
| No recordings found | "Записей за указанный период не найдено" + back to date selection |
| ffmpeg crash | "Ошибка обработки видео" + log error |
| File > 50 MB after compression | "Видео слишком большое. Попробуйте меньший диапазон" |
| Recording file missing on disk | Skip, log warning; if all missing → error |
| Dialog timeout (10 min) | State removed silently |

## Repository Query

New method in `RecordingEntityRepository`:

```kotlin
suspend fun findByCamIdAndRecordDateAndRecordTimeBetween(
    camId: String,
    recordDate: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
): List<RecordingEntity>
```

Also need:
```kotlin
suspend fun findDistinctCamIdsByDateAndTimeRange(
    recordDate: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
): List<CameraRecordingCount>  // camId + count
```
