# Unprocessable Video Handling Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Корректно обрабатывать невалидные видеофайлы (400/422/413 от сервера), прекращая бесполезные ретраи, и инкрементировать `process_attempts` при каждой попытке обработки.

**Architecture:** Новое исключение `UnprocessableVideoException` пробрасывается из `retryWithTimeout` без ретрая. `FrameExtractorProducer` ловит его и помечает запись как обработанную с `error_message`. Поле `process_attempts` инкрементируется при каждой попытке, а не только при успехе.

**Tech Stack:** Kotlin, Spring Data R2DBC, Liquibase, MapStruct

---

### Task 1: Liquibase-миграция — добавить `error_message`

**Files:**
- Create: `docker/liquibase/migration/1.0.2.xml`
- Modify: `docker/liquibase/migration/master_frigate_analyzer.xml:10`

**Step 1: Создать миграцию**

Файл `docker/liquibase/migration/1.0.2.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-5.0.xsd">
    <changeSet author="zinin" id="20260307-01-add-error-message-to-recordings">
        <comment>Add error_message field to recordings for unprocessable video tracking</comment>
        <sql>
            ALTER TABLE recordings
              ADD COLUMN error_message VARCHAR(65536);
        </sql>
    </changeSet>
</databaseChangeLog>
```

**Step 2: Подключить миграцию в master changelog**

В `docker/liquibase/migration/master_frigate_analyzer.xml` добавить после строки 10:
```xml
    <include file="1.0.2.xml" relativeToChangelogFile="true"/>
```

**Step 3: Commit**
```bash
git add docker/liquibase/migration/1.0.2.xml docker/liquibase/migration/master_frigate_analyzer.xml
git commit -m "feat: add error_message column to recordings table"
```

---

### Task 2: Обновить Entity, DTO и Mapper

**Files:**
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/RecordingEntity.kt:42` — добавить поле `errorMessage`
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingDto.kt:22` — добавить поле `errorMessage`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/mapper/RecordingMapper.kt:18` — добавить `@Mapping` для `errorMessage`

**Step 1: Добавить поле в RecordingEntity**

После строки 42 (`analyzedFramesCount`) добавить:
```kotlin
    @Column("error_message")
    var errorMessage: String?,
```

**Step 2: Добавить поле в RecordingDto**

После строки 22 (`analyzedFramesCount`) добавить:
```kotlin
    var errorMessage: String?,
```

**Step 3: Добавить маппинг в RecordingMapper**

В метод `toEntity` после строки 18 добавить:
```kotlin
    @Mapping(target = "errorMessage", ignore = true)
```

**Step 4: Обновить тесты, использующие RecordingEntity/RecordingDto**

Во всех местах, где создаются `RecordingEntity` или `RecordingDto`, добавить `errorMessage = null`. Найти через:
```bash
grep -rn "RecordingEntity(" modules/*/src/test/ --include="*.kt"
grep -rn "RecordingDto(" modules/*/src/test/ --include="*.kt"
```

**Step 5: Commit**
```bash
git add modules/model/ modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/mapper/
git commit -m "feat: add errorMessage field to RecordingEntity, RecordingDto and mapper"
```

---

### Task 3: Создать `UnprocessableVideoException`

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/UnprocessableVideoException.kt`

**Step 1: Создать исключение**

```kotlin
package ru.zinin.frigate.analyzer.model.exception

class UnprocessableVideoException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

**Step 2: Commit**
```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/exception/UnprocessableVideoException.kt
git commit -m "feat: add UnprocessableVideoException for 400/422/413 responses"
```

---

### Task 4: Добавить методы в репозиторий и сервис

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt` — добавить `incrementProcessAttempts` и `markProcessedWithError`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/RecordingEntityService.kt` — добавить методы в интерфейс
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/RecordingEntityServiceImpl.kt` — реализовать методы

**Step 1: Добавить запросы в `RecordingEntityRepository`**

```kotlin
    @Modifying
    @Query(
        """
        UPDATE recordings
        SET process_attempts = process_attempts + 1
        WHERE id = :id
        """,
    )
    suspend fun incrementProcessAttempts(
        @Param("id") id: UUID,
    ): Long

    @Modifying
    @Query(
        """
        UPDATE recordings
        SET process_timestamp = :processTimestamp,
            process_attempts = process_attempts + 1,
            error_message = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun markProcessedWithError(
        @Param("id") id: UUID,
        @Param("processTimestamp") processTimestamp: Instant,
        @Param("errorMessage") errorMessage: String,
    ): Long
```

**Step 2: Добавить методы в интерфейс `RecordingEntityService`**

```kotlin
    suspend fun incrementProcessAttempts(id: UUID)

    suspend fun markProcessedWithError(id: UUID, errorMessage: String)
```

**Step 3: Реализовать в `RecordingEntityServiceImpl`**

```kotlin
    @Transactional
    override suspend fun incrementProcessAttempts(id: UUID) {
        repository.incrementProcessAttempts(id)
    }

    @Transactional
    override suspend fun markProcessedWithError(id: UUID, errorMessage: String) {
        repository.markProcessedWithError(id, Instant.now(clock), errorMessage)
        logger.info { "Recording $id marked as failed: $errorMessage" }
    }
```

**Step 4: Commit**
```bash
git add modules/service/
git commit -m "feat: add incrementProcessAttempts and markProcessedWithError repository methods"
```

---

### Task 5: Обработка 400/422/413 в `DetectService`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt:179-182` — парсить коды ответа в `extractFramesRemote`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt:412-416` — пробрасывать `UnprocessableVideoException` в `retryWithTimeout`

**Step 1: В `retryWithTimeout` добавить проброс `UnprocessableVideoException`**

В блоке `try` внутри `while(true)` (строка 410-420), добавить catch для `UnprocessableVideoException` перед общим `catch (e: Exception)`:
```kotlin
                    } catch (e: UnprocessableVideoException) {
                        throw e // Don't retry on client errors (400/422/413)
                    } catch (e: CancellationException) {
```

Добавить import:
```kotlin
import ru.zinin.frigate.analyzer.model.exception.UnprocessableVideoException
```

**Step 2: В `extractFramesRemote` обработать коды ответа**

Заменить блок catch (строки 179-182):
```kotlin
        } catch (e: WebClientResponseException) {
            logger.warn { "Frame extraction failed on server ${acquired.id}: ${e.message} (filePath=$filePath, recordingId=$recordingId)" }
            val statusCode = e.statusCode.value()
            if (statusCode in listOf(400, 413, 422)) {
                val detail = try {
                    val body = e.responseBodyAsString
                    val detailRegex = """"detail"\s*:\s*"([^"]+)"""".toRegex()
                    detailRegex.find(body)?.groupValues?.get(1) ?: body
                } catch (_: Exception) {
                    e.message ?: "Unknown client error"
                }
                throw UnprocessableVideoException(detail, e)
            }
            throw e
        } catch (e: Exception) {
            logger.warn { "Frame extraction failed on server ${acquired.id}: ${e.message} (filePath=$filePath, recordingId=$recordingId)" }
            throw e
        }
```

Добавить import:
```kotlin
import org.springframework.web.reactive.function.client.WebClientResponseException
import ru.zinin.frigate.analyzer.model.exception.UnprocessableVideoException
```

**Step 3: Commit**
```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt
git commit -m "feat: handle 400/422/413 as UnprocessableVideoException, skip retry"
```

---

### Task 6: Обработка в `FrameExtractorProducer`

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/FrameExtractorProducer.kt:89-128` — добавить обработку `UnprocessableVideoException` и инкремент `process_attempts`

**Step 1: Обновить `processRecording`**

Заменить try-catch блок (строки 89-128):
```kotlin
        try {
            recordingEntityService.incrementProcessAttempts(record.id)

            val response = extractFramesFromVideo(record)
            logger.info { "Extracted ${response.frames.size} frames for recording ${record.id}" }

            if (response.frames.isEmpty()) {
                recordingEntityService.saveProcessingResult(
                    SaveProcessingResultRequest(record.id),
                )
                logger.info { "Recording ${record.id} has no frames, marked as processed" }
                return
            }

            val decoder = Base64.getDecoder()
            val frameDataList =
                response.frames.mapIndexed { index, frame ->
                    val frameBytes = decoder.decode(frame.imageBase64)
                    FrameData(record.id, index, frameBytes)
                }

            recordingTracker.registerRecording(record.id, frameDataList)

            for (frameData in frameDataList) {
                channel.send(FrameTask(frameData.recordId, frameData.frameIndex, frameData.frameBytes))
            }

            logger.debug { "Sent ${frameDataList.size} frame tasks for recording ${record.id}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnprocessableVideoException) {
            logger.warn { "Recording ${record.id} has unprocessable video: ${e.message}" }
            recordingEntityService.markProcessedWithError(record.id, e.message ?: "Unknown error")
        } catch (e: NoSuchFileException) {
            logger.warn(e) {
                "Recording ${record.id} file missing (${record.filePath}); deleting recording"
            }
            recordingEntityService.deleteRecording(record.id)
        } catch (e: Exception) {
            logger.error(e) { "Failed to process recording ${record.id}" }
        }
```

Добавить import:
```kotlin
import ru.zinin.frigate.analyzer.model.exception.UnprocessableVideoException
```

**Step 2: Commit**
```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/pipeline/frame/FrameExtractorProducer.kt
git commit -m "feat: handle UnprocessableVideoException, increment process_attempts on each attempt"
```

---

### Task 7: Обновить тесты и собрать проект

**Step 1: Обновить тесты, добавив `errorMessage = null` где нужно**

Найти все места:
```bash
grep -rn "RecordingEntity(" modules/*/src/test/ --include="*.kt"
grep -rn "RecordingDto(" modules/*/src/test/ --include="*.kt"
```

**Step 2: Добавить тест для `incrementProcessAttempts` в `RecordingEntityRepositoryTest`**

```kotlin
    @Test
    fun `should increment process_attempts without changing other fields`() {
        runBlocking {
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)

            repository.incrementProcessAttempts(saved.id!!)

            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertEquals(1, updated!!.processAttempts)
            assertNull(updated.processTimestamp)
        }
    }
```

**Step 3: Добавить тест для `markProcessedWithError` в `RecordingEntityRepositoryTest`**

```kotlin
    @Test
    fun `should mark recording as processed with error message`() {
        runBlocking {
            val entity = createRecordingEntity(processAttempts = 0, analyzeTime = 0)
            val saved = repository.save(entity)
            val processTimestamp = Instant.now()

            repository.markProcessedWithError(saved.id!!, processTimestamp, "File contains no video stream")

            val updated = repository.findById(saved.id!!)
            assertNotNull(updated)
            assertNotNull(updated!!.processTimestamp)
            assertEquals(1, updated.processAttempts)
            assertEquals("File contains no video stream", updated.errorMessage)
        }
    }
```

**Step 4: Запустить билд**

Использовать skill `build` для сборки проекта. При ошибках ktlint — `./gradlew ktlintFormat`, затем повторить.

**Step 5: Commit**
```bash
git add -A
git commit -m "test: add tests for incrementProcessAttempts and markProcessedWithError"
```
