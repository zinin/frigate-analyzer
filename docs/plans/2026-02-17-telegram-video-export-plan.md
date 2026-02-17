# Telegram Video Export Command — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `/export` Telegram bot command that lets users select date, time range, and camera via inline buttons/text, then merges Frigate recording segments with ffmpeg and sends the resulting video.

**Architecture:** Waiter-based dialog flow inside `onCommand("export")` using ktgbotapi's `waitDataCallbackQuery`/`waitText` expectations. `VideoExportService` interface in telegram module, implementation in core module (which has access to both service repositories and ApplicationProperties). `VideoMergeHelper` in core handles ffmpeg concat/compress.

**Tech Stack:** Kotlin, ktgbotapi (v30.0.2 — inline keyboards, callback queries, waiters), Spring Data R2DBC, ffmpeg (ProcessBuilder), coroutines

**Design doc:** `docs/plans/2026-02-17-telegram-video-export-design.md`

**Note:** Design doc specified in-memory ConcurrentHashMap state machine. Plan uses ktgbotapi waiter pattern instead — same UX, much less code. Waiters suspend the coroutine until user responds, with timeout for cleanup.

---

### Task 1: Add CameraRecordingCountDto

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraRecordingCountDto.kt`

**Step 1: Create DTO**

```kotlin
package ru.zinin.frigate.analyzer.model.dto

data class CameraRecordingCountDto(
    val camId: String,
    val recordingsCount: Long,
)
```

Reference existing DTO: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraStatisticsDto.kt` for naming/style conventions.

**Step 2: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraRecordingCountDto.kt
git commit -m "feat: add CameraRecordingCountDto for video export"
```

---

### Task 2: Add repository queries for recording lookup

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`

**Step 1: Add query to find recordings by date, time range, and camId**

Add to `RecordingEntityRepository` interface:

```kotlin
@Query(
    """
    SELECT *
    FROM recordings
    WHERE cam_id = :camId
      AND record_date = :recordDate
      AND record_time >= :startTime
      AND record_time <= :endTime
      AND file_path IS NOT NULL
    ORDER BY record_time ASC
    """,
)
suspend fun findByCamIdAndDateAndTimeRange(
    @Param("camId") camId: String,
    @Param("recordDate") recordDate: LocalDate,
    @Param("startTime") startTime: LocalTime,
    @Param("endTime") endTime: LocalTime,
): List<RecordingEntity>
```

Add required imports: `java.time.LocalDate`, `java.time.LocalTime`.

**Step 2: Add query to find available cameras for a date/time range**

```kotlin
@Query(
    """
    SELECT cam_id, COUNT(*) as recordings_count
    FROM recordings
    WHERE record_date = :recordDate
      AND record_time >= :startTime
      AND record_time <= :endTime
      AND file_path IS NOT NULL
    GROUP BY cam_id
    ORDER BY cam_id
    """,
)
suspend fun findCamerasWithRecordings(
    @Param("recordDate") recordDate: LocalDate,
    @Param("startTime") startTime: LocalTime,
    @Param("endTime") endTime: LocalTime,
): List<CameraRecordingCountDto>
```

Add import for `CameraRecordingCountDto`.

**Step 3: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt
git commit -m "feat: add recording queries for date/time range and camera lookup"
```

---

### Task 3: Create VideoExportService interface

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt`

**Step 1: Create interface**

The interface lives in the telegram module so the bot can depend on it. Implementation will be in core module (which has access to repositories and config).

```kotlin
package ru.zinin.frigate.analyzer.telegram.service

import ru.zinin.frigate.analyzer.model.dto.CameraRecordingCountDto
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime

interface VideoExportService {
    suspend fun findCamerasWithRecordings(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
    ): List<CameraRecordingCountDto>

    suspend fun exportVideo(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        camId: String,
    ): Path
}
```

**Step 2: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt
git commit -m "feat: add VideoExportService interface for video export"
```

---

### Task 4: Create VideoMergeHelper

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt`

Reference existing ffmpeg pattern: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/VideoServiceImpl.kt` (ProcessBuilder usage).
Reference existing temp file pattern: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`.

**Step 1: Create VideoMergeHelper**

```kotlin
package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
class VideoMergeHelper(
    private val applicationProperties: ApplicationProperties,
    private val tempFileHelper: TempFileHelper,
) {
    suspend fun mergeVideos(filePaths: List<Path>): Path {
        require(filePaths.isNotEmpty()) { "filePaths must not be empty" }

        if (filePaths.size == 1) {
            return copyToTemp(filePaths.first())
        }

        val concatFile = tempFileHelper.createTempFile("concat-", ".txt")
        try {
            withContext(Dispatchers.IO) {
                Files.write(
                    concatFile,
                    filePaths.map { "file '${it.toAbsolutePath()}'" },
                )
            }

            val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
            try {
                runFfmpeg(
                    listOf(
                        applicationProperties.ffmpegPath.toString(),
                        "-hide_banner",
                        "-f", "concat",
                        "-safe", "0",
                        "-i", concatFile.toString(),
                        "-c", "copy",
                        "-y",
                        outputFile.toString(),
                    ),
                )
                return outputFile
            } catch (e: Exception) {
                tempFileHelper.deleteIfExists(outputFile)
                throw e
            }
        } finally {
            tempFileHelper.deleteIfExists(concatFile)
        }
    }

    suspend fun compressVideo(inputPath: Path): Path {
        val outputFile = tempFileHelper.createTempFile("compressed-", ".mp4")
        try {
            runFfmpeg(
                listOf(
                    applicationProperties.ffmpegPath.toString(),
                    "-hide_banner",
                    "-i", inputPath.toString(),
                    "-vcodec", "libx264",
                    "-crf", "28",
                    "-preset", "fast",
                    "-acodec", "aac",
                    "-y",
                    outputFile.toString(),
                ),
            )
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    private suspend fun copyToTemp(source: Path): Path {
        val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
        try {
            withContext(Dispatchers.IO) {
                Files.copy(source, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    private suspend fun runFfmpeg(command: List<String>) {
        logger.debug { "Running ffmpeg: ${command.joinToString(" ")}" }

        val exitCode = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            if (logger.isTraceEnabled()) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { logger.trace { "ffmpeg: $it" } }
                }
            }

            process.waitFor()
        }

        if (exitCode != 0) {
            throw RuntimeException("ffmpeg exited with code $exitCode")
        }
    }

    companion object {
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
        const val COMPRESS_THRESHOLD_BYTES = 45L * 1024 * 1024 // 45 MB
    }
}
```

**Step 2: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/VideoMergeHelper.kt
git commit -m "feat: add VideoMergeHelper for ffmpeg concat and compress"
```

---

### Task 5: Create VideoExportServiceImpl

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt`

**Step 1: Create implementation**

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
import java.time.LocalDate
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

@Service
class VideoExportServiceImpl(
    private val recordingRepository: RecordingEntityRepository,
    private val videoMergeHelper: VideoMergeHelper,
    private val tempFileHelper: TempFileHelper,
) : VideoExportService {
    override suspend fun findCamerasWithRecordings(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
    ): List<CameraRecordingCountDto> = recordingRepository.findCamerasWithRecordings(date, startTime, endTime)

    override suspend fun exportVideo(
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        camId: String,
    ): Path {
        val recordings = recordingRepository.findByCamIdAndDateAndTimeRange(camId, date, startTime, endTime)

        if (recordings.isEmpty()) {
            throw IllegalStateException("No recordings found for camId=$camId, date=$date, time=$startTime-$endTime")
        }

        // Filter to existing files
        val existingFiles = recordings.mapNotNull { recording ->
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

        logger.info { "Exporting ${existingFiles.size} recordings for camId=$camId, date=$date, time=$startTime-$endTime" }

        // Step 1: Merge
        var mergedFile = videoMergeHelper.mergeVideos(existingFiles)

        try {
            // Step 2: Compress if needed
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
}
```

**Step 2: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt
git commit -m "feat: add VideoExportServiceImpl for recording lookup, merge and compress"
```

---

### Task 6: Add /export command handler to FrigateAnalyzerBot

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

This is the main task. The export command uses ktgbotapi's waiter pattern for multi-step dialog.

**Step 1: Add required imports**

Add these imports to `FrigateAnalyzerBot.kt`:

```kotlin
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideo
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitText
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
```

**Step 2: Add VideoExportService dependency**

Update constructor to inject `VideoExportService`:

```kotlin
class FrigateAnalyzerBot(
    private val bot: TelegramBot,
    private val authorizationFilter: AuthorizationFilter,
    private val userService: TelegramUserService,
    private val properties: TelegramProperties,
    private val videoExportService: VideoExportService,
) {
```

**Step 3: Add /export command registration**

Add to `DEFAULT_COMMANDS` and `OWNER_COMMANDS` companion object:

Replace `DEFAULT_COMMANDS` with:
```kotlin
private val DEFAULT_COMMANDS =
    listOf(
        BotCommand("start", "Начать работу с ботом"),
        BotCommand("help", "Помощь"),
        BotCommand("export", "Выгрузить видео"),
    )
```

**Step 4: Register onCommand("export") in buildBehaviourWithLongPolling**

Add inside `buildBehaviourWithLongPolling { ... }` block, after the existing `onCommand("users")`:

```kotlin
onCommand("export") { message ->
    handleExport(message)
}
```

**Step 5: Implement handleExport method**

Add the `handleExport` private method. This is the core dialog flow using waiters.

```kotlin
private suspend fun BehaviourContext.handleExport(message: CommonMessage<TextContent>) {
    val role = authorizationFilter.getRole(message)
    if (role == null) {
        bot.reply(message, authorizationFilter.getUnauthorizedMessage())
        return
    }

    val chatId = message.chat.id

    withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
        // Step 1: Date selection
        val dateKeyboard = InlineKeyboardMarkup(
            keyboard = matrix {
                row {
                    +CallbackDataInlineKeyboardButton("Сегодня", "export:today")
                    +CallbackDataInlineKeyboardButton("Вчера", "export:yesterday")
                }
                row {
                    +CallbackDataInlineKeyboardButton("Ввести дату", "export:custom")
                    +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                }
            },
        )

        sendTextMessage(chatId, "Выберите дату:", replyMarkup = dateKeyboard)

        val dateCallback = waitDataCallbackQuery(
            filter = { it.data.startsWith("export:") },
        ).first()
        answer(dateCallback)

        if (dateCallback.data == "export:cancel") {
            sendTextMessage(chatId, "Экспорт отменён.")
            return@withTimeoutOrNull
        }

        val date: LocalDate = when (dateCallback.data) {
            "export:today" -> LocalDate.now()
            "export:yesterday" -> LocalDate.now().minusDays(1)
            "export:custom" -> {
                sendTextMessage(chatId, "Введите дату (формат: YYYY-MM-DD):")
                val dateText = waitText(
                    filter = { it.chat.id == chatId },
                ).first()
                try {
                    LocalDate.parse(dateText.content.text.trim())
                } catch (e: DateTimeParseException) {
                    sendTextMessage(chatId, "Неверный формат даты. Используйте YYYY-MM-DD. Экспорт отменён.")
                    return@withTimeoutOrNull
                }
            }
            else -> return@withTimeoutOrNull
        }

        // Step 2: Time range input
        sendTextMessage(chatId, "Введите диапазон времени (например: 09:15-09:20, макс. 5 минут):")
        val timeText = waitText(
            filter = { it.chat.id == chatId },
        ).first()

        val timeRange = parseTimeRange(timeText.content.text.trim())
        if (timeRange == null) {
            sendTextMessage(chatId, "Неверный формат. Используйте HH:MM-HH:MM. Экспорт отменён.")
            return@withTimeoutOrNull
        }

        val (startTime, endTime) = timeRange
        val durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime)
        if (durationMinutes > MAX_EXPORT_DURATION_MINUTES || durationMinutes <= 0) {
            sendTextMessage(chatId, "Диапазон должен быть от 1 до $MAX_EXPORT_DURATION_MINUTES минут. Экспорт отменён.")
            return@withTimeoutOrNull
        }

        // Step 3: Camera selection
        val cameras = videoExportService.findCamerasWithRecordings(date, startTime, endTime)
        if (cameras.isEmpty()) {
            sendTextMessage(chatId, "Записей за $date $startTime-$endTime не найдено.")
            return@withTimeoutOrNull
        }

        val cameraKeyboard = InlineKeyboardMarkup(
            keyboard = matrix {
                cameras.forEach { cam ->
                    row {
                        +CallbackDataInlineKeyboardButton(
                            "${cam.camId} (${cam.recordingsCount})",
                            "export:cam:${cam.camId}",
                        )
                    }
                }
                row {
                    +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                }
            },
        )

        sendTextMessage(chatId, "Выберите камеру:", replyMarkup = cameraKeyboard)

        val camCallback = waitDataCallbackQuery(
            filter = { it.data.startsWith("export:") },
        ).first()
        answer(camCallback)

        if (camCallback.data == "export:cancel") {
            sendTextMessage(chatId, "Экспорт отменён.")
            return@withTimeoutOrNull
        }

        val camId = camCallback.data.removePrefix("export:cam:")

        // Step 4: Export video
        try {
            val videoPath = videoExportService.exportVideo(date, startTime, endTime, camId)
            try {
                val fileName = "export_${camId}_${date}_${startTime}-${endTime}.mp4"
                    .replace(":", "-")
                bot.sendVideo(
                    chatId,
                    videoPath.toFile().readBytes().asMultipartFile(fileName),
                )
            } finally {
                // Clean up temp file - non-critical, TempFileHelper auto-cleans old files
                try {
                    java.nio.file.Files.deleteIfExists(videoPath)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to delete temp file: $videoPath" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Video export failed" }
            sendTextMessage(chatId, "Ошибка экспорта видео: ${e.message}")
        }
    } ?: sendTextMessage(message.chat.id, "Время ожидания истекло. Попробуйте снова /export.")
}

private fun parseTimeRange(input: String): Pair<LocalTime, LocalTime>? {
    val parts = input.split("-", limit = 2)
    if (parts.size != 2) return null
    return try {
        val formatter = DateTimeFormatter.ofPattern("H:mm")
        val start = LocalTime.parse(parts[0].trim(), formatter)
        val end = LocalTime.parse(parts[1].trim(), formatter)
        start to end
    } catch (e: DateTimeParseException) {
        null
    }
}
```

**Step 6: Add companion object constants**

Add to the existing companion object:

```kotlin
private const val EXPORT_TIMEOUT_MS = 600_000L // 10 minutes
private const val MAX_EXPORT_DURATION_MINUTES = 5L
```

**Step 7: Update handleHelp to include /export**

In `handleHelp`, add the export command to help text. After the `/help` line:

```kotlin
appendLine("/export - Выгрузить видео с камеры")
```

**Step 8: Add BehaviourContext import**

Since `handleExport` is an extension function on `BehaviourContext`, add:

```kotlin
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
```

**Step 9: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat: add /export command with waiter-based dialog for video export"
```

---

### Task 7: Verify waitDataCallbackQuery API compatibility

**Context:** The plan uses `waitDataCallbackQuery` from ktgbotapi expectations. This needs verification against the actual library version (v30.0.2).

**Step 1: Check ktgbotapi expectations API**

Look for available waiter functions in the project's classpath:
- `dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery`
- If not found, check for `waitCallbackQuery` and filter by data presence

Reference Context7: `/insanusmokrassar/ktgbotapi` for up-to-date API examples.

**Step 2: If waitDataCallbackQuery is not available, fallback approach**

Replace waiter-based callback handling with manual `onDataCallbackQuery` + in-memory state:

```kotlin
// In buildBehaviourWithLongPolling:
onDataCallbackQuery { query ->
    if (query.data.startsWith("export:")) {
        handleExportCallback(query)
    }
}
```

With a simple state map:
```kotlin
private val exportStates = ConcurrentHashMap<Long, ExportState>()

data class ExportState(
    val step: ExportStep,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val createdAt: Instant = Instant.now(),
)

enum class ExportStep { WAITING_DATE, WAITING_CUSTOM_DATE, WAITING_TIME, WAITING_CAMERA }
```

**Step 3: Build and verify compilation**

Run: `/build` (delegates to build-runner agent)

Expected: BUILD SUCCESSFUL

---

### Task 8: Build and format

**Step 1: Run ktlintFormat**

```bash
./gradlew ktlintFormat
```

**Step 2: Run full build**

```bash
./gradlew build
```

**Step 3: Fix any compilation or test errors**

If build fails, check:
- Import paths
- ktgbotapi API compatibility (see Task 7 fallback)
- Missing Spring bean wiring
- Type mismatches in repository queries

**Step 4: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: resolve build issues for video export feature"
```

---

## Summary of changes

| Module | File | Action |
|--------|------|--------|
| model | `dto/CameraRecordingCountDto.kt` | Create |
| service | `repository/RecordingEntityRepository.kt` | Add 2 queries |
| telegram | `service/VideoExportService.kt` | Create interface |
| telegram | `bot/FrigateAnalyzerBot.kt` | Add /export command, help text, imports |
| core | `helper/VideoMergeHelper.kt` | Create |
| core | `service/VideoExportServiceImpl.kt` | Create |

## Key risks

1. **`waitDataCallbackQuery` API** — might not exist in v30.0.2 or have different signature. Task 7 has fallback.
2. **ffmpeg concat** — assumes all recording segments have compatible codecs/formats (Frigate records consistently, so this should work).
3. **50MB Telegram limit** — compression with CRF 28 should handle most 5-min videos, but very high resolution sources might still exceed the limit.
