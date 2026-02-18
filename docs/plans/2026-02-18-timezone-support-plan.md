# Timezone Support & record_timestamp Query Refactoring — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add per-user timezone (`olson_code`) to `telegram_users`, switch export queries from `record_date`/`record_time` to `record_timestamp` (Instant), add `/timezone` command, and use user timezone in `/export` dialog and notifications.

**Architecture:** `olson_code VARCHAR(50) NULL` in `telegram_users`; default UTC handled in code via `ZoneId.of(olsonCode ?: "UTC")`. Repository queries switch to `Instant` parameters against indexed `record_timestamp` column. Bot loads user timezone before `/export` dialog and converts input to Instant. Notifications send per-user formatted messages.

**Design doc:** `docs/plans/2026-02-18-timezone-support-design.md`

**Tech Stack:** Kotlin, Spring Data R2DBC, ktgbotapi (waiters), Liquibase, PostgreSQL, `java.time.ZoneId`

---

### Task 1: Liquibase migration — add olson_code column

**Files:**
- Create: `docker/liquibase/migration/1.0.1.xml`
- Modify: `docker/liquibase/migration/master_frigate_analyzer.xml`

**Step 1: Create migration file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-5.0.xsd">
    <changeSet author="zinin" id="20260218-01-add-timezone-to-users">
        <comment>Add olson_code timezone field to telegram_users</comment>
        <sql>
            ALTER TABLE telegram_users
              ADD COLUMN olson_code VARCHAR(50);
        </sql>
    </changeSet>
</databaseChangeLog>
```

**Step 2: Include in master changelog**

In `master_frigate_analyzer.xml`, add after the existing `<include>` line:
```xml
<include file="1.0.1.xml" relativeToChangelogFile="true"/>
```

**Step 3: Commit**

```bash
git add docker/liquibase/migration/1.0.1.xml docker/liquibase/migration/master_frigate_analyzer.xml
git commit -m "feat: add olson_code column to telegram_users (FA-18)"
```

---

### Task 2: TelegramUserEntity — add olsonCode field

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt`

**Step 1: Add field**

Add after `activationTimestamp` field (line 30):
```kotlin
    @Column("olson_code")
    var olsonCode: String? = null,
```

No new imports needed (`@Column` is already imported).

**Step 2: Verify existing usages compile**

`inviteUser` in `TelegramUserServiceImpl` creates `TelegramUserEntity(...)` with named params — `olsonCode` has a default value `null`, so no changes needed there.

**Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt
git commit -m "feat: add olsonCode field to TelegramUserEntity (FA-18)"
```

---

### Task 3: TelegramUserRepository — add timezone queries

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt`

**Step 1: Add findByChatId**

Spring Data derives this from the field name. Add after `findByUsernameAndStatus`:
```kotlin
suspend fun findByChatId(chatId: Long): TelegramUserEntity?
```

**Step 2: Add updateOlsonCode**

```kotlin
@Modifying
@Query("UPDATE telegram_users SET olson_code = :olsonCode WHERE chat_id = :chatId")
suspend fun updateOlsonCode(
    @Param("chatId") chatId: Long,
    @Param("olsonCode") olsonCode: String,
): Long
```

**Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt
git commit -m "feat: add timezone repository methods (FA-18)"
```

---

### Task 4: TelegramUserService + Impl — add timezone methods

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`

**Step 1: Add methods to interface**

```kotlin
suspend fun getUserZone(chatId: Long): ZoneId

suspend fun updateTimezone(chatId: Long, olsonCode: String)

suspend fun getAuthorizedUsersWithZones(): List<Pair<Long, ZoneId>>
```

Add import to interface file: `import java.time.ZoneId`

**Step 2: Implement in TelegramUserServiceImpl**

Add after `getAllActiveChatIds()`:

```kotlin
@Transactional(readOnly = true)
override suspend fun getUserZone(chatId: Long): ZoneId {
    val olsonCode = repository.findByChatId(chatId)?.olsonCode
    return ZoneId.of(olsonCode ?: "UTC")
}

@Transactional
override suspend fun updateTimezone(chatId: Long, olsonCode: String) {
    repository.updateOlsonCode(chatId, olsonCode)
    logger.info { "Updated timezone for chatId=$chatId to $olsonCode" }
}

@Transactional(readOnly = true)
override suspend fun getAuthorizedUsersWithZones(): List<Pair<Long, ZoneId>> =
    repository.findAllByStatus(UserStatus.ACTIVE.name)
        .filter { it.chatId != null }
        .map { user -> user.chatId!! to ZoneId.of(user.olsonCode ?: "UTC") }
```

Add import to impl file: `import java.time.ZoneId`

**Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt
git commit -m "feat: add timezone service methods (FA-18)"
```

---

### Task 5: RecordingEntityRepository — switch to Instant-based queries

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`

**Step 1: Replace findByCamIdAndDateAndTimeRange**

Remove the old method (lines 98–115) and add:

```kotlin
@Query(
    """
    SELECT *
    FROM recordings
    WHERE cam_id = :camId
      AND record_timestamp >= :startInstant - INTERVAL '10 seconds'
      AND record_timestamp <= :endInstant
      AND file_path IS NOT NULL
    ORDER BY record_timestamp ASC
    """,
)
suspend fun findByCamIdAndInstantRange(
    @Param("camId") camId: String,
    @Param("startInstant") startInstant: Instant,
    @Param("endInstant") endInstant: Instant,
): List<RecordingEntity>
```

**Step 2: Replace findCamerasWithRecordings**

Remove the old method (lines 117–134) and add:

```kotlin
@Query(
    """
    SELECT cam_id, COUNT(*) as recordings_count
    FROM recordings
    WHERE record_timestamp >= :startInstant - INTERVAL '10 seconds'
      AND record_timestamp <= :endInstant
      AND file_path IS NOT NULL
      AND cam_id IS NOT NULL
    GROUP BY cam_id
    ORDER BY cam_id
    """,
)
suspend fun findCamerasWithRecordings(
    @Param("startInstant") startInstant: Instant,
    @Param("endInstant") endInstant: Instant,
): List<CameraRecordingCountDto>
```

**Step 3: Clean up imports**

Remove unused imports `java.time.LocalDate` and `java.time.LocalTime` if they are no longer used elsewhere in the file (check: they are not used in any other query after this change).

**Step 4: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt
git commit -m "feat: switch export queries to record_timestamp Instant range (FA-18)"
```

---

### Task 6: VideoExportService + VideoExportServiceImpl — update signatures

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt`

**Step 1: Update VideoExportService interface**

Replace existing content with:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import java.nio.file.Path
import java.time.Instant

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
    ): Path

    suspend fun cleanupExportFile(path: Path)
}
```

**Step 2: Update VideoExportServiceImpl**

Replace the class content. Key changes:
- `findCamerasWithRecordings(date, startTime, endTime)` → `findCamerasWithRecordings(startInstant, endInstant)`
- `exportVideo(date, startTime, endTime, camId)` → `exportVideo(startInstant, endInstant, camId)`
- Repository call: `findByCamIdAndDateAndTimeRange(camId, date, startTime, endTime)` → `findByCamIdAndInstantRange(camId, startInstant, endInstant)`
- Log message: update to use `$startInstant-$endInstant` instead of date/time params

Full updated class:

```kotlin
package ru.zinin.frigate.analyzer.core.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class VideoExportServiceImpl(
    private val recordingRepository: RecordingEntityRepository,
    private val videoMergeHelper: VideoMergeHelper,
    private val tempFileHelper: TempFileHelper,
) : VideoExportService {
    override suspend fun findCamerasWithRecordings(
        startInstant: Instant,
        endInstant: Instant,
    ): List<CameraRecordingCountDto> = recordingRepository.findCamerasWithRecordings(startInstant, endInstant)

    override suspend fun exportVideo(
        startInstant: Instant,
        endInstant: Instant,
        camId: String,
    ): Path {
        val recordings = recordingRepository.findByCamIdAndInstantRange(camId, startInstant, endInstant)

        if (recordings.isEmpty()) {
            throw IllegalStateException("No recordings found for camId=$camId, range=$startInstant-$endInstant")
        }

        val existingFiles =
            recordings.mapNotNull { recording ->
                val path = recording.filePath?.let { Path.of(it) }
                if (path != null && withContext(Dispatchers.IO) { Files.exists(path) }) {
                    path
                } else {
                    logger.warn { "Recording file not found: ${recording.filePath} (id=${recording.id})" }
                    null
                }
            }

        if (existingFiles.isEmpty()) {
            throw IllegalStateException("All recording files are missing from disk")
        }

        logger.info { "Exporting ${existingFiles.size} recordings for camId=$camId, range=$startInstant-$endInstant" }

        var mergedFile = videoMergeHelper.mergeVideos(existingFiles)

        try {
            val fileSize = withContext(Dispatchers.IO) { Files.size(mergedFile) }
            if (fileSize > VideoMergeHelper.COMPRESS_THRESHOLD_BYTES) {
                logger.info { "Merged file is ${fileSize / 1024 / 1024}MB, compressing..." }
                val compressedFile = videoMergeHelper.compressVideo(mergedFile)
                tempFileHelper.deleteIfExists(mergedFile)
                mergedFile = compressedFile

                val compressedSize = withContext(Dispatchers.IO) { Files.size(mergedFile) }
                if (compressedSize > VideoMergeHelper.MAX_FILE_SIZE_BYTES) {
                    tempFileHelper.deleteIfExists(mergedFile)
                    throw IllegalStateException(
                        "Video too large even after compression: ${compressedSize / 1024 / 1024}MB",
                    )
                }
            }

            return mergedFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(mergedFile)
            throw e
        }
    }

    override suspend fun cleanupExportFile(path: Path) {
        tempFileHelper.deleteIfExists(path)
    }
}
```

**Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt
git commit -m "feat: update VideoExportService signatures to use Instant (FA-18)"
```

---

### Task 7: FrigateAnalyzerBot — /timezone command + /export update

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

This is the largest task. Make changes in order.

**Step 1: Add imports**

Add these imports to the existing import block:
```kotlin
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.zone.ZoneRulesException
```

**Step 2: Add /timezone to DEFAULT_COMMANDS**

Replace the current `DEFAULT_COMMANDS` list (lines 75–80):
```kotlin
private val DEFAULT_COMMANDS =
    listOf(
        BotCommand("start", "Начать работу с ботом"),
        BotCommand("help", "Помощь"),
        BotCommand("export", "Выгрузить видео"),
        BotCommand("timezone", "Часовой пояс"),
    )
```

**Step 3: Register onCommand("timezone")**

Inside `buildBehaviourWithLongPolling { ... }`, add after `onCommand("export")`:
```kotlin
onCommand("timezone") { message ->
    handleTimezone(message)
}
```

**Step 4: Add handleTimezone method**

Add as a new private method inside `FrigateAnalyzerBot` class, after `handleUsers`:

```kotlin
private suspend fun BehaviourContext.handleTimezone(message: CommonMessage<TextContent>) {
    val role = authorizationFilter.getRole(message)
    if (role == null) {
        bot.reply(message, authorizationFilter.getUnauthorizedMessage())
        return
    }

    val chatId = message.chat.id
    val currentZone = userService.getUserZone(chatId.chatId.long)

    val tzKeyboard =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton("Калининград (UTC+2)", "tz:Europe/Kaliningrad")
                        +CallbackDataInlineKeyboardButton("Москва (UTC+3)", "tz:Europe/Moscow")
                    }
                    row {
                        +CallbackDataInlineKeyboardButton("Екатеринбург (UTC+5)", "tz:Asia/Yekaterinburg")
                        +CallbackDataInlineKeyboardButton("Омск (UTC+6)", "tz:Asia/Omsk")
                    }
                    row {
                        +CallbackDataInlineKeyboardButton("Красноярск (UTC+7)", "tz:Asia/Krasnoyarsk")
                        +CallbackDataInlineKeyboardButton("Иркутск (UTC+8)", "tz:Asia/Irkutsk")
                    }
                    row {
                        +CallbackDataInlineKeyboardButton("Якутск (UTC+9)", "tz:Asia/Yakutsk")
                        +CallbackDataInlineKeyboardButton("Владивосток (UTC+10)", "tz:Asia/Vladivostok")
                    }
                    row {
                        +CallbackDataInlineKeyboardButton("Ввести вручную", "tz:manual")
                        +CallbackDataInlineKeyboardButton("Отмена", "tz:cancel")
                    }
                },
        )

    sendTextMessage(chatId, "Ваш текущий часовой пояс: $currentZone\nВыберите часовой пояс:", replyMarkup = tzKeyboard)

    val callback =
        waitDataCallbackQuery()
            .filter { it.data.startsWith("tz:") && (it as? MessageDataCallbackQuery)?.message?.chat?.id == chatId }
            .first()
    answer(callback)

    when {
        callback.data == "tz:cancel" -> {
            sendTextMessage(chatId, "Отменено.")
        }
        callback.data == "tz:manual" -> {
            sendTextMessage(chatId, "Введите Olson ID часового пояса (например: Europe/Moscow, Asia/Tokyo):")
            val inputMsg =
                waitTextMessage()
                    .filter { it.chat.id == chatId }
                    .first()
            val input = inputMsg.content.text.trim()
            try {
                val zone = ZoneId.of(input)
                userService.updateTimezone(chatId.chatId.long, zone.id)
                val offset = zone.rules.getOffset(Instant.now())
                sendTextMessage(chatId, "Часовой пояс сохранён: ${zone.id} (UTC$offset)")
            } catch (e: ZoneRulesException) {
                sendTextMessage(chatId, "Неизвестный часовой пояс. Попробуйте снова или выберите из списка.")
            }
        }
        else -> {
            val olsonCode = callback.data.removePrefix("tz:")
            val zone = ZoneId.of(olsonCode)
            userService.updateTimezone(chatId.chatId.long, olsonCode)
            val offset = zone.rules.getOffset(Instant.now())
            sendTextMessage(chatId, "Часовой пояс сохранён: $olsonCode (UTC$offset)")
        }
    }
}
```

**Step 5: Update handleExport — load user timezone and convert inputs**

In `handleExport`, immediately after the role check and before `var userNotified = false`, add:
```kotlin
val userZone = userService.getUserZone(chatId.chatId.long)
```

In Step 2 of the dialog (time range prompt), replace:
```kotlin
sendTextMessage(chatId, "Введите диапазон времени (например: 09:15-09:20, макс. 5 минут) или /cancel:")
```
with:
```kotlin
sendTextMessage(chatId, "Введите диапазон времени (например: 9:15-9:20, макс. 5 минут)\nВремя в вашем часовом поясе: $userZone\nИли /cancel:")
```

After `val (startTime, endTime) = timeRange` and duration validation, add Instant conversion before the camera search:
```kotlin
val startInstant = LocalDateTime.of(date, startTime).atZone(userZone).toInstant()
val endInstant = LocalDateTime.of(date, endTime).atZone(userZone).toInstant()
```

Replace:
```kotlin
val cameras = videoExportService.findCamerasWithRecordings(date, startTime, endTime)
if (cameras.isEmpty()) {
    sendTextMessage(chatId, "Записей за $date $startTime-$endTime не найдено.")
```
with:
```kotlin
val cameras = videoExportService.findCamerasWithRecordings(startInstant, endInstant)
if (cameras.isEmpty()) {
    sendTextMessage(chatId, "Записей за $date $startTime-$endTime не найдено.")
```

Change the dialog return value from:
```kotlin
Triple(date, startTime to endTime, camId)
```
to:
```kotlin
Triple(startInstant, endInstant, camId)
```

After the dialog, replace:
```kotlin
val (date, timePair, camId) = dialogResult
val (startTime, endTime) = timePair
```
with:
```kotlin
val (startInstant, endInstant, camId) = dialogResult
```

Replace both `videoExportService.exportVideo(date, startTime, endTime, camId)` calls with:
```kotlin
videoExportService.exportVideo(startInstant, endInstant, camId)
```

For the filename, replace:
```kotlin
val fileName =
    "export_${camId}_${date}_$startTime-$endTime.mp4"
        .replace(":", "-")
```
with:
```kotlin
val localDate = startInstant.atZone(userZone).toLocalDate()
val localStart = startInstant.atZone(userZone).toLocalTime()
val localEnd = endInstant.atZone(userZone).toLocalTime()
val fileName = "export_${camId}_${localDate}_${localStart}-${localEnd}.mp4".replace(":", "-")
```

**Step 6: Update /help to include /timezone**

In `handleHelp`, add after the `/export` line:
```kotlin
appendLine("/timezone - Настроить часовой пояс")
```

**Step 7: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat: add /timezone command and timezone-aware /export (FA-18)"
```

---

### Task 8: TelegramNotificationServiceImpl — per-user timezone

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`

**Step 1: Update sendRecordingNotification**

Replace the current implementation:

```kotlin
override suspend fun sendRecordingNotification(
    recording: RecordingDto,
    visualizedFrames: List<VisualizedFrameData>,
) {
    if (recording.detectionsCount == 0) {
        logger.debug { "No detections found, skipping notification for ${recording.filePath}" }
        return
    }

    val usersWithZones = userService.getAuthorizedUsersWithZones()
    if (usersWithZones.isEmpty()) {
        logger.debug { "No active subscribers found, skipping notification" }
        return
    }

    usersWithZones.forEach { (chatId, zone) ->
        val message = formatRecordingMessage(recording, zone)
        val task =
            NotificationTask(
                uuidGeneratorHelper.generateV1(),
                chatId,
                message,
                visualizedFrames,
            )
        notificationQueue.enqueue(task)
    }

    logger.debug { "Enqueued notification for ${usersWithZones.size} subscribers" }
}
```

**Step 2: Update formatRecordingMessage to accept ZoneId**

Change signature from `private fun formatRecordingMessage(recording: RecordingDto): String` to:
```kotlin
private fun formatRecordingMessage(recording: RecordingDto, zone: ZoneId): String
```

Replace both `.atZone(ClockConfig.MOSCOW_ZONE_ID)` usages with `.atZone(zone)`.

Remove the import `import ru.zinin.frigate.analyzer.common.config.ClockConfig` (no longer used in this file).

Add import: `import java.time.ZoneId`

**Step 3: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt
git commit -m "feat: use per-user timezone in notifications (FA-18)"
```

---

### Task 9: Build and format

**Step 1: Run ktlintFormat**

```bash
./gradlew ktlintFormat
```

Commit any formatting changes:
```bash
git add -A
git commit -m "style: apply ktlintFormat to timezone support code (FA-18)"
```

(If no changes, skip commit.)

**Step 2: Run full build**

Use `/build` command (dispatches build-runner agent).

**Step 3: Fix any errors**

Common issues to watch for:
- Unused imports (LocalDate/LocalTime removed from RecordingEntityRepository)
- `DialogResult` type changed from `Triple<LocalDate, Pair<LocalTime,LocalTime>, String>` to `Triple<Instant, Instant, String>` — ensure all usage sites updated
- `ZoneRulesException` needs `java.time.zone.ZoneRulesException` import (not `java.time.ZoneId.ZoneRulesException`)
- `chatId.chatId.long` — `chatId` in the bot is `ChatId` type from ktgbotapi; `.chatId` returns `RawChatId`, `.long` gets the Long value

**Step 4: Commit fixes if needed**

```bash
git add -A
git commit -m "fix: resolve build issues for timezone support (FA-18)"
```

---

## Summary of changes

| Module | File | Change |
|--------|------|--------|
| — | `docker/liquibase/migration/1.0.1.xml` | Create: add `olson_code` column |
| — | `docker/liquibase/migration/master_frigate_analyzer.xml` | Include new migration |
| telegram | `entity/TelegramUserEntity.kt` | + `olsonCode: String?` field |
| telegram | `repository/TelegramUserRepository.kt` | + `findByChatId`, `updateOlsonCode` |
| telegram | `service/TelegramUserService.kt` | + `getUserZone`, `updateTimezone`, `getAuthorizedUsersWithZones` |
| telegram | `service/impl/TelegramUserServiceImpl.kt` | Implement 3 new methods |
| service | `repository/RecordingEntityRepository.kt` | Replace 2 queries with Instant-based |
| telegram | `service/VideoExportService.kt` | Signatures: `LocalDate/LocalTime` → `Instant` |
| core | `service/VideoExportServiceImpl.kt` | Signatures + repo calls updated |
| telegram | `bot/FrigateAnalyzerBot.kt` | + `/timezone` command, update `/export` |
| telegram | `service/impl/TelegramNotificationServiceImpl.kt` | Per-user timezone, remove hardcoded MSK |
