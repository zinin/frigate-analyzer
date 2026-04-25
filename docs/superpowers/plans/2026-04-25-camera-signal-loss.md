# Camera Signal Loss Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a polling-based detector that notifies Telegram subscribers when a Frigate camera stops writing recordings (signal loss) and when it resumes (recovery).

**Architecture:** A Spring `@Scheduled` task wakes every `pollInterval` (default 30s), queries `MAX(record_timestamp) per cam_id` from the `recordings` table for cameras active in the last 24h, and runs each through an in-memory state machine (`Healthy` ↔ `SignalLost`). State transitions enqueue text-only notifications via the existing `TelegramNotificationQueue`. State is in-memory; restart safety comes from a `startupGrace` window during which alerts are suppressed.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, Coroutines, R2DBC/PostgreSQL, JUnit 5, MockK, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-04-25-camera-signal-loss-design.md`

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt` | Projection DTO `(camId, lastRecordTimestamp)` |
| Modify | `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt` | New method `findLastRecordingPerCamera(activeSince)` |
| Modify | `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepositoryTest.kt` | Integration tests for new method |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt` | `@ConfigurationProperties` with `@PostConstruct` validation |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossPropertiesTest.kt` | Validation unit tests |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt` | Refactor data class → sealed interface with `RecordingNotificationTask` and `SimpleTextNotificationTask` |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt` | Dispatch on sealed type; add simple-text branch |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` | Use `RecordingNotificationTask`; add new methods |
| Modify | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt` | Update existing tests for renamed type |
| Modify | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt` | Update for sealed type |
| Modify | `modules/telegram/src/main/resources/messages_en.properties` | EN i18n keys for signal-loss/recovery/duration |
| Modify | `modules/telegram/src/main/resources/messages_ru.properties` | RU i18n keys |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatter.kt` | Build localized loss/recovery messages; format `Duration` in human form |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatterTest.kt` | Unit tests for formatter |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt` | Add 2 new interface methods |
| Modify | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt` | No-op implementations |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt` | Tests for new methods |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt` | Sealed state class |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt` | Main scheduled task |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt` | Parameterized state-machine + behavior tests |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt` | `@SpringBootTest` conflict-fail test |
| Modify | `modules/core/src/main/resources/application.yaml` | New `application.signal-loss` block |
| Modify | `.claude/rules/configuration.md` | Document new env-vars |

---

### Task 1: Repository — `LastRecordingPerCameraDto` and `findLastRecordingPerCamera`

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`
- Modify: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepositoryTest.kt`

- [ ] **Step 1: Create the projection DTO**

```kotlin
// modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt
package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant

data class LastRecordingPerCameraDto(
    val camId: String,
    val lastRecordTimestamp: Instant,
)
```

- [ ] **Step 2: Write failing integration tests**

Append to `RecordingEntityRepositoryTest.kt` inside the existing test class. Mirror the conventions used by other tests in that file (look at how `findCamerasWithRecordings` is tested if it has tests; otherwise follow the project pattern of inserting via `repository.save(makeEntity(...))`).

```kotlin
@Test
fun `findLastRecordingPerCamera returns max record_timestamp per camera`() = runBlocking {
    val now = Instant.parse("2026-04-25T10:00:00Z")
    repository.save(recording(camId = "cam_a", recordTs = now.minusSeconds(120)))
    repository.save(recording(camId = "cam_a", recordTs = now.minusSeconds(60)))   // newest cam_a
    repository.save(recording(camId = "cam_b", recordTs = now.minusSeconds(30)))   // newest cam_b
    repository.save(recording(camId = "cam_b", recordTs = now.minusSeconds(300)))

    val result = repository.findLastRecordingPerCamera(now.minusSeconds(3600))

    assertThat(result).hasSize(2)
    val byCamId = result.associateBy { it.camId }
    assertThat(byCamId["cam_a"]?.lastRecordTimestamp).isEqualTo(now.minusSeconds(60))
    assertThat(byCamId["cam_b"]?.lastRecordTimestamp).isEqualTo(now.minusSeconds(30))
}

@Test
fun `findLastRecordingPerCamera excludes recordings with null cam_id`() = runBlocking {
    val now = Instant.parse("2026-04-25T10:00:00Z")
    repository.save(recording(camId = null, recordTs = now.minusSeconds(60)))
    repository.save(recording(camId = "cam_a", recordTs = now.minusSeconds(30)))

    val result = repository.findLastRecordingPerCamera(now.minusSeconds(3600))

    assertThat(result).extracting<String> { it.camId }.containsExactly("cam_a")
}

@Test
fun `findLastRecordingPerCamera excludes recordings older than activeSince`() = runBlocking {
    val now = Instant.parse("2026-04-25T10:00:00Z")
    val activeSince = now.minusSeconds(3600)
    repository.save(recording(camId = "old_cam", recordTs = activeSince.minusSeconds(1)))
    repository.save(recording(camId = "fresh_cam", recordTs = activeSince.plusSeconds(1)))

    val result = repository.findLastRecordingPerCamera(activeSince)

    assertThat(result).extracting<String> { it.camId }.containsExactly("fresh_cam")
}

@Test
fun `findLastRecordingPerCamera returns empty list for empty table`() = runBlocking {
    val result = repository.findLastRecordingPerCamera(Instant.parse("2026-04-25T10:00:00Z"))
    assertThat(result).isEmpty()
}

private fun recording(camId: String?, recordTs: Instant): RecordingEntity =
    RecordingEntity(
        id = UUID.randomUUID(),
        creationTimestamp = recordTs,
        filePath = "/tmp/${UUID.randomUUID()}.mp4",
        fileCreationTimestamp = recordTs,
        camId = camId,
        recordDate = LocalDate.ofInstant(recordTs, ZoneOffset.UTC),
        recordTime = LocalTime.ofInstant(recordTs, ZoneOffset.UTC),
        recordTimestamp = recordTs,
        startProcessingTimestamp = null,
        processTimestamp = null,
        processAttempts = 0,
        detectionsCount = null,
        analyzeTime = null,
        analyzedFramesCount = null,
        errorMessage = null,
    )
```

If the existing test file already has a private factory like `makeRecording(...)`, reuse it instead of adding `recording(...)` — adapt parameter names accordingly. Read the file first to see what helpers exist.

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :frigate-analyzer-service:test --tests "*RecordingEntityRepositoryTest.findLastRecordingPerCamera*"
```

Expected: 4 failures with "unresolved reference: findLastRecordingPerCamera".

- [ ] **Step 4: Add the repository method**

Add to `RecordingEntityRepository.kt` (place near `findCamerasWithRecordings`):

```kotlin
@Query(
    """
    SELECT cam_id as cam_id,
           MAX(record_timestamp) as last_record_timestamp
    FROM recordings
    WHERE record_timestamp >= :activeSince
      AND cam_id IS NOT NULL
    GROUP BY cam_id
    ORDER BY cam_id
    """,
)
suspend fun findLastRecordingPerCamera(
    @Param("activeSince") activeSince: Instant,
): List<LastRecordingPerCameraDto>
```

Add the import: `import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto`.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :frigate-analyzer-service:test --tests "*RecordingEntityRepositoryTest.findLastRecordingPerCamera*"
```

Expected: 4 passes.

- [ ] **Step 6: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepositoryTest.kt
git commit -m "feat(repo): add findLastRecordingPerCamera for signal-loss detector"
```

---

### Task 2: `SignalLossProperties` + validation

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossPropertiesTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossPropertiesTest.kt
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class SignalLossPropertiesTest {
    @Test
    fun `valid properties pass validation`() {
        val props = SignalLossProperties(
            enabled = true,
            threshold = Duration.ofMinutes(3),
            pollInterval = Duration.ofSeconds(30),
            activeWindow = Duration.ofHours(24),
            startupGrace = Duration.ofMinutes(5),
        )
        props.validate()  // no exception
        assertThat(props.threshold).isEqualTo(Duration.ofMinutes(3))
    }

    @ParameterizedTest
    @MethodSource("invalidConfigs")
    fun `invalid properties fail validation`(invalid: InvalidCase) {
        assertThatThrownBy { invalid.props.validate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(invalid.expectedFragment)
    }

    data class InvalidCase(val name: String, val props: SignalLossProperties, val expectedFragment: String) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun invalidConfigs(): List<InvalidCase> = listOf(
            InvalidCase(
                name = "threshold zero",
                props = base().copy(threshold = Duration.ZERO),
                expectedFragment = "threshold",
            ),
            InvalidCase(
                name = "pollInterval zero",
                props = base().copy(pollInterval = Duration.ZERO),
                expectedFragment = "pollInterval",
            ),
            InvalidCase(
                name = "pollInterval >= threshold",
                props = base().copy(pollInterval = Duration.ofMinutes(3), threshold = Duration.ofMinutes(3)),
                expectedFragment = "pollInterval",
            ),
            InvalidCase(
                name = "activeWindow <= threshold",
                props = base().copy(activeWindow = Duration.ofMinutes(3), threshold = Duration.ofMinutes(3)),
                expectedFragment = "activeWindow",
            ),
            InvalidCase(
                name = "startupGrace negative",
                props = base().copy(startupGrace = Duration.ofSeconds(-1)),
                expectedFragment = "startupGrace",
            ),
        )

        private fun base() = SignalLossProperties(
            enabled = true,
            threshold = Duration.ofMinutes(3),
            pollInterval = Duration.ofSeconds(30),
            activeWindow = Duration.ofHours(24),
            startupGrace = Duration.ofMinutes(5),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossPropertiesTest*"
```

Expected: compile error on `SignalLossProperties` (class does not yet exist).

- [ ] **Step 3: Create the properties class**

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt
package ru.zinin.frigate.analyzer.core.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "application.signal-loss")
data class SignalLossProperties(
    val enabled: Boolean = true,
    val threshold: Duration = Duration.ofMinutes(3),
    val pollInterval: Duration = Duration.ofSeconds(30),
    val activeWindow: Duration = Duration.ofHours(24),
    val startupGrace: Duration = Duration.ofMinutes(5),
) {
    fun validate() {
        check(!threshold.isZero && !threshold.isNegative) {
            "application.signal-loss.threshold must be positive, got $threshold"
        }
        check(!pollInterval.isZero && !pollInterval.isNegative) {
            "application.signal-loss.pollInterval must be positive, got $pollInterval"
        }
        check(pollInterval < threshold) {
            "application.signal-loss.pollInterval ($pollInterval) must be smaller than threshold ($threshold)"
        }
        check(activeWindow > threshold) {
            "application.signal-loss.activeWindow ($activeWindow) must be greater than threshold ($threshold)"
        }
        check(!startupGrace.isNegative) {
            "application.signal-loss.startupGrace must be non-negative, got $startupGrace"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossPropertiesTest*"
```

Expected: all 6 tests pass (1 happy path + 5 invalid cases).

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossPropertiesTest.kt
git commit -m "feat(config): SignalLossProperties with validation"
```

---

### Task 3: Refactor `NotificationTask` to sealed interface

**Why:** The current `NotificationTask` is a recording-specific data class. Signal-loss/recovery notifications are plain text (no photos, no recordingId, no AI description handle). Sealed interface lets the queue carry both types and `Sender` dispatch on subtype.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt`

This task is a **type-rename refactor** that must keep all existing tests green. No new behavior. We add the new `SimpleTextNotificationTask` subtype but `Sender` will still throw for it (we add the simple-text branch in Task 6, after we have a use case).

- [ ] **Step 1: Refactor `NotificationTask` into sealed interface**

Replace the current file contents with:

```kotlin
// modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt
package ru.zinin.frigate.analyzer.telegram.queue

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import java.time.Instant
import java.util.UUID

sealed interface NotificationTask {
    val id: UUID
    val chatId: Long
    val createdAt: Instant
}

data class RecordingNotificationTask(
    override val id: UUID,
    override val chatId: Long,
    val message: String,
    val visualizedFrames: List<VisualizedFrameData>,
    /** ID of the recording, used for callback data in inline export buttons. */
    val recordingId: UUID,
    val language: String? = null,
    /**
     * Shared Deferred across all recipients of the same recording — one AI request
     * fans out to N edits (one per recipient). Started in
     * TelegramNotificationServiceImpl.sendRecordingNotification AFTER subscriber
     * filtering, before enqueue of each task.
     * null — feature disabled / no frames / no subscribers.
     */
    val descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    override val createdAt: Instant = Instant.now(),
) : NotificationTask

data class SimpleTextNotificationTask(
    override val id: UUID,
    override val chatId: Long,
    val text: String,
    override val createdAt: Instant = Instant.now(),
) : NotificationTask
```

- [ ] **Step 2: Update `TelegramNotificationSender.send` signature and body**

Open `TelegramNotificationSender.kt`. Change the signature of `send` and wrap the entire existing body inside a `when` branch:

```kotlin
suspend fun send(task: NotificationTask) {
    when (task) {
        is RecordingNotificationTask -> sendRecording(task)
        is SimpleTextNotificationTask -> error("SimpleTextNotificationTask not yet supported (added in Task 6)")
    }
}

private suspend fun sendRecording(task: RecordingNotificationTask) {
    // PASTE THE EXISTING BODY OF send(task: NotificationTask) HERE, UNCHANGED
}
```

Within `sendRecording`, every `task.X` reference still works because `RecordingNotificationTask` keeps the same field names (`message`, `visualizedFrames`, `recordingId`, `language`, `descriptionHandle`, `chatId`).

Also: in the existing helper signatures `sendSinglePhoto(... task: NotificationTask)` and `sendMediaGroupMessages(... task: NotificationTask)` (lines 146 and 189), change the parameter type from `NotificationTask` to `RecordingNotificationTask`. The KDoc on `send` already references `NotificationTask.descriptionHandle` — update it to `RecordingNotificationTask.descriptionHandle`.

- [ ] **Step 3: Update `TelegramNotificationServiceImpl`**

In `TelegramNotificationServiceImpl.kt`:

- Change the import `import ru.zinin.frigate.analyzer.telegram.queue.NotificationTask` to `import ru.zinin.frigate.analyzer.telegram.queue.RecordingNotificationTask`.
- Change `NotificationTask(` (around line 54) to `RecordingNotificationTask(`. Field names are unchanged.

- [ ] **Step 4: Update existing tests for renamed type**

In `TelegramNotificationServiceImplTest.kt`:
- Replace `import ...NotificationTask` with `import ...RecordingNotificationTask`.
- Replace `NotificationTask` references with `RecordingNotificationTask`.
- The test name `sendRecordingNotification propagates recordingId to NotificationTask` may stay textually as-is (it is just a string), or rename to `... to RecordingNotificationTask` if desired — keep the change minimal: leave the string alone unless it improves readability.

In `TelegramNotificationSenderTest.kt`:
- Replace `NotificationTask(` (around line 124) with `RecordingNotificationTask(`. The mock factory `makeTask(...)` still returns the recording variant.

- [ ] **Step 5: Run all telegram-module tests**

```bash
./gradlew :frigate-analyzer-telegram:test
```

Expected: all existing tests pass (no behavior change).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt
git commit -m "refactor(telegram): NotificationTask -> sealed interface (RecordingNotificationTask + SimpleTextNotificationTask)"
```

---

### Task 4: Add i18n message keys

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`

- [ ] **Step 1: Append EN keys**

Append to `messages_en.properties`:

```properties

# Signal loss / recovery
notification.signal.loss.title=📡❌ Camera "{0}" lost signal
notification.signal.loss.last_recording=Last recording: {0} ({1} ago)
notification.signal.recovery.title=📡✅ Camera "{0}" is back online
notification.signal.recovery.downtime=Downtime: {0}

# Duration formatting (used by SignalLossMessageFormatter)
signal.duration.seconds={0} sec
signal.duration.minutes={0} min
signal.duration.hours={0} h {1} min
signal.duration.days={0} d {1} h
```

- [ ] **Step 2: Append RU keys**

Append to `messages_ru.properties`:

```properties

# Signal loss / recovery
notification.signal.loss.title=📡❌ Камера "{0}" потеряла сигнал
notification.signal.loss.last_recording=Последняя запись: {0} ({1} назад)
notification.signal.recovery.title=📡✅ Камера "{0}" снова на связи
notification.signal.recovery.downtime=Длительность простоя: {0}

# Duration formatting
signal.duration.seconds={0} сек
signal.duration.minutes={0} мин
signal.duration.hours={0} ч {1} мин
signal.duration.days={0} д {1} ч
```

(The Russian text translates to: «📡❌ Камера "{0}" потеряла сигнал», «Последняя запись: {0} ({1} назад)», «📡✅ Камера "{0}" снова на связи», «Длительность простоя: {0}», «{0} сек», «{0} мин», «{0} ч {1} мин», «{0} д {1} ч».)

- [ ] **Step 3: Commit**

```bash
git add modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/main/resources/messages_ru.properties
git commit -m "i18n(telegram): add signal-loss/recovery message keys"
```

---

### Task 5: `SignalLossMessageFormatter` (TDD)

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatter.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatterTest.kt`

The formatter has two responsibilities: render a `Duration` in human form via i18n, and build the full loss/recovery messages. It uses `MessageResolver` for all strings.

- [ ] **Step 1: Write failing tests**

```kotlin
// modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatterTest.kt
package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SignalLossMessageFormatterTest {
    private val msg = mockk<MessageResolver>(relaxed = false).apply {
        every { get("signal.duration.seconds", "en", any()) } answers { "${arg<Array<Any>>(2)[0]} sec" }
        every { get("signal.duration.minutes", "en", any()) } answers { "${arg<Array<Any>>(2)[0]} min" }
        every { get("signal.duration.hours", "en", any(), any()) } answers {
            "${arg<Array<Any>>(2)[0]} h ${arg<Array<Any>>(2)[1]} min"
        }
        every { get("signal.duration.days", "en", any(), any()) } answers {
            "${arg<Array<Any>>(2)[0]} d ${arg<Array<Any>>(2)[1]} h"
        }
        every { get("notification.signal.loss.title", "en", any()) } answers { "Camera \"${arg<Array<Any>>(2)[0]}\" lost signal" }
        every { get("notification.signal.loss.last_recording", "en", any(), any()) } answers {
            "Last recording: ${arg<Array<Any>>(2)[0]} (${arg<Array<Any>>(2)[1]} ago)"
        }
        every { get("notification.signal.recovery.title", "en", any()) } answers {
            "Camera \"${arg<Array<Any>>(2)[0]}\" is back online"
        }
        every { get("notification.signal.recovery.downtime", "en", any()) } answers {
            "Downtime: ${arg<Array<Any>>(2)[0]}"
        }
    }
    private val formatter = SignalLossMessageFormatter(msg)

    @ParameterizedTest
    @CsvSource(
        "0,    0 sec",
        "1,    1 sec",
        "59,   59 sec",
        "60,   1 min",
        "119,  1 min",
        "120,  2 min",
        "3599, 59 min",
        "3600, 1 h 0 min",
        "3660, 1 h 1 min",
        "7320, 2 h 2 min",
        "86399,23 h 59 min",
        "86400,1 d 0 h",
        "90000,1 d 1 h",
    )
    fun `formatDuration buckets seconds, minutes, hours, days`(
        seconds: Long,
        expected: String,
    ) {
        val result = formatter.formatDuration(Duration.ofSeconds(seconds), language = "en")
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `buildLossMessage formats title, last recording timestamp and gap`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-04-25T14:35:32Z")
        val lastSeen = Instant.parse("2026-04-25T14:32:18Z")  // gap = 3 min 14 s

        val result = formatter.buildLossMessage(
            camId = "front_door",
            lastSeenAt = lastSeen,
            now = now,
            zone = zone,
            language = "en",
        )

        assertThat(result).contains("Camera \"front_door\" lost signal")
        assertThat(result).contains("3 min")  // gap formatted as minutes
        // Timestamp format is locale-dependent; assert it contains the time element
        assertThat(result).contains("14:32")
    }

    @Test
    fun `buildRecoveryMessage formats title and downtime`() {
        val downtime = Duration.ofMinutes(12).plusSeconds(48)

        val result = formatter.buildRecoveryMessage(
            camId = "front_door",
            downtime = downtime,
            language = "en",
        )

        assertThat(result).contains("Camera \"front_door\" is back online")
        assertThat(result).contains("12 min")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "*SignalLossMessageFormatterTest*"
```

Expected: compile error on `SignalLossMessageFormatter`.

- [ ] **Step 3: Implement the formatter**

```kotlin
// modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatter.kt
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Component
class SignalLossMessageFormatter(
    private val msg: MessageResolver,
) {
    fun formatDuration(duration: Duration, language: String): String {
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        return when {
            totalSeconds < SECONDS_PER_MINUTE -> msg.get("signal.duration.seconds", language, totalSeconds)
            totalSeconds < SECONDS_PER_HOUR -> msg.get("signal.duration.minutes", language, totalSeconds / SECONDS_PER_MINUTE)
            totalSeconds < SECONDS_PER_DAY -> {
                val hours = totalSeconds / SECONDS_PER_HOUR
                val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
                msg.get("signal.duration.hours", language, hours, minutes)
            }
            else -> {
                val days = totalSeconds / SECONDS_PER_DAY
                val hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
                msg.get("signal.duration.days", language, days, hours)
            }
        }
    }

    fun buildLossMessage(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
        zone: ZoneId,
        language: String,
    ): String {
        val gap = Duration.between(lastSeenAt, now)
        val timeFormatter = DateTimeFormatter
            .ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.forLanguageTag(language))
        val lastSeenFormatted = lastSeenAt.atZone(zone).format(timeFormatter)
        val gapFormatted = formatDuration(gap, language)

        return buildString {
            appendLine(msg.get("notification.signal.loss.title", language, camId))
            appendLine(msg.get("notification.signal.loss.last_recording", language, lastSeenFormatted, gapFormatted))
        }
    }

    fun buildRecoveryMessage(
        camId: String,
        downtime: Duration,
        language: String,
    ): String {
        val downtimeFormatted = formatDuration(downtime, language)
        return buildString {
            appendLine(msg.get("notification.signal.recovery.title", language, camId))
            appendLine(msg.get("notification.signal.recovery.downtime", language, downtimeFormatted))
        }
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_DAY = 86400L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "*SignalLossMessageFormatterTest*"
```

Expected: all tests pass (13 parameterized + 2 message tests = 15).

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatter.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/SignalLossMessageFormatterTest.kt
git commit -m "feat(telegram): SignalLossMessageFormatter for loss/recovery messages"
```

---

### Task 6: Extend `TelegramNotificationService` with signal-loss methods + Sender simple-text branch

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`

- [ ] **Step 1: Write failing tests for the service methods**

```kotlin
// modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt
package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.UserZoneDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class TelegramNotificationServiceImplSignalLossTest {
    private val userService = mockk<TelegramUserService>()
    private val queue = mockk<TelegramNotificationQueue>(relaxed = true)
    private val uuid = mockk<UUIDGeneratorHelper>().apply {
        io.mockk.every { generateV1() } returns UUID.randomUUID()
    }
    private val msg = mockk<MessageResolver>(relaxed = true)
    private val formatter = mockk<SignalLossMessageFormatter>().apply {
        io.mockk.every { buildLossMessage(any(), any(), any(), any(), any()) } returns "loss-msg"
        io.mockk.every { buildRecoveryMessage(any(), any(), any()) } returns "recovery-msg"
    }
    private val service = TelegramNotificationServiceImpl(
        userService = userService,
        notificationQueue = queue,
        uuidGeneratorHelper = uuid,
        msg = msg,
        signalLossFormatter = formatter,
    )

    @Test
    fun `sendCameraSignalLost enqueues one SimpleTextNotificationTask per active user`() = runBlocking {
        coEvery { userService.getAuthorizedUsersWithZones() } returns listOf(
            UserZoneDto(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
            UserZoneDto(chatId = 200L, zone = ZoneId.of("Europe/Moscow"), language = "ru"),
        )
        val tasks = mutableListOf<SimpleTextNotificationTask>()
        coEvery { queue.enqueue(capture(tasks as MutableList<Any>)) } answers { /* unused */ }

        service.sendCameraSignalLost(
            camId = "front_door",
            lastSeenAt = Instant.parse("2026-04-25T14:32:18Z"),
            now = Instant.parse("2026-04-25T14:35:32Z"),
        )

        // Verify one task per recipient
        coVerify(exactly = 2) { queue.enqueue(any()) }
        assertThat(tasks).hasSize(2)
        assertThat(tasks.map { it.chatId }).containsExactlyInAnyOrder(100L, 200L)
        assertThat(tasks.map { it.text }).allMatch { it == "loss-msg" }
    }

    @Test
    fun `sendCameraSignalLost is no-op when no active users`() = runBlocking {
        coEvery { userService.getAuthorizedUsersWithZones() } returns emptyList()

        service.sendCameraSignalLost(
            camId = "front_door",
            lastSeenAt = Instant.now(),
            now = Instant.now(),
        )

        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `sendCameraSignalRecovered enqueues one SimpleTextNotificationTask per active user`() = runBlocking {
        coEvery { userService.getAuthorizedUsersWithZones() } returns listOf(
            UserZoneDto(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
        )
        val tasks = mutableListOf<SimpleTextNotificationTask>()
        coEvery { queue.enqueue(capture(tasks as MutableList<Any>)) } answers { /* unused */ }

        service.sendCameraSignalRecovered(
            camId = "front_door",
            downtime = Duration.ofMinutes(12).plusSeconds(48),
        )

        coVerify(exactly = 1) { queue.enqueue(any()) }
        assertThat(tasks.single().chatId).isEqualTo(100L)
        assertThat(tasks.single().text).isEqualTo("recovery-msg")
    }
}
```

Note on `UserZoneDto`: the existing code returns objects with at least `chatId`, `zone`, and `language` fields. If the actual class name or constructor differs, adapt the test (open `TelegramUserService.kt` and `getAuthorizedUsersWithZones()` definition before writing the test). The same caveat applies to all `userZone.X` accessors used in `TelegramNotificationServiceImpl`.

- [ ] **Step 2: Add interface methods**

In `TelegramNotificationService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import java.time.Duration
import java.time.Instant

interface TelegramNotificationService {
    suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)? = null,
    )

    /** Notify all active subscribers that camera [camId] has stopped writing recordings. */
    suspend fun sendCameraSignalLost(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
    )

    /** Notify all active subscribers that camera [camId] is writing recordings again. */
    suspend fun sendCameraSignalRecovered(
        camId: String,
        downtime: Duration,
    )
}
```

- [ ] **Step 3: Implement in `NoOpTelegramNotificationService`**

```kotlin
override suspend fun sendCameraSignalLost(camId: String, lastSeenAt: Instant, now: Instant) {
    logger.debug { "Telegram notifications disabled, skipping signal-loss for camera $camId" }
}

override suspend fun sendCameraSignalRecovered(camId: String, downtime: Duration) {
    logger.debug { "Telegram notifications disabled, skipping signal-recovery for camera $camId" }
}
```

Add the imports `import java.time.Duration` and `import java.time.Instant`.

- [ ] **Step 4: Implement in `TelegramNotificationServiceImpl`**

Add `signalLossFormatter: SignalLossMessageFormatter` to the constructor:

```kotlin
class TelegramNotificationServiceImpl(
    private val userService: TelegramUserService,
    private val notificationQueue: TelegramNotificationQueue,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
    private val msg: MessageResolver,
    private val signalLossFormatter: SignalLossMessageFormatter,
) : TelegramNotificationService {
```

Add new methods to the class:

```kotlin
override suspend fun sendCameraSignalLost(
    camId: String,
    lastSeenAt: Instant,
    now: Instant,
) {
    val usersWithZones = userService.getAuthorizedUsersWithZones()
    if (usersWithZones.isEmpty()) {
        logger.debug { "No active subscribers for signal-loss alert (cam=$camId)" }
        return
    }
    usersWithZones.forEach { userZone ->
        val lang = userZone.language ?: "en"
        val text = signalLossFormatter.buildLossMessage(
            camId = camId,
            lastSeenAt = lastSeenAt,
            now = now,
            zone = userZone.zone,
            language = lang,
        )
        notificationQueue.enqueue(
            SimpleTextNotificationTask(
                id = uuidGeneratorHelper.generateV1(),
                chatId = userZone.chatId,
                text = text,
            ),
        )
    }
    logger.info { "Enqueued signal-loss alert for camera $camId to ${usersWithZones.size} recipients" }
}

override suspend fun sendCameraSignalRecovered(
    camId: String,
    downtime: Duration,
) {
    val usersWithZones = userService.getAuthorizedUsersWithZones()
    if (usersWithZones.isEmpty()) {
        logger.debug { "No active subscribers for signal-recovery alert (cam=$camId)" }
        return
    }
    usersWithZones.forEach { userZone ->
        val lang = userZone.language ?: "en"
        val text = signalLossFormatter.buildRecoveryMessage(
            camId = camId,
            downtime = downtime,
            language = lang,
        )
        notificationQueue.enqueue(
            SimpleTextNotificationTask(
                id = uuidGeneratorHelper.generateV1(),
                chatId = userZone.chatId,
                text = text,
            ),
        )
    }
    logger.info { "Enqueued signal-recovery alert for camera $camId to ${usersWithZones.size} recipients" }
}
```

Add the imports:
```kotlin
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import java.time.Duration
import java.time.Instant
```

- [ ] **Step 5: Replace the `error(...)` branch in `TelegramNotificationSender.send`**

In `TelegramNotificationSender.kt`, replace the placeholder branch added in Task 3:

```kotlin
suspend fun send(task: NotificationTask) {
    when (task) {
        is RecordingNotificationTask -> sendRecording(task)
        is SimpleTextNotificationTask -> sendSimpleText(task)
    }
}

private suspend fun sendSimpleText(task: SimpleTextNotificationTask) {
    val chatIdObj = ChatId(RawChatId(task.chatId))
    RetryHelper.retryIndefinitely("Send simple text", task.chatId) {
        bot.sendTextMessage(
            chatId = chatIdObj,
            text = task.text,
        )
    }
}
```

Add the import: `import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask` (it's in the same package, so the import is implicit; no change needed).

- [ ] **Step 6: Update existing `TelegramNotificationServiceImplTest` for new constructor parameter**

The constructor of `TelegramNotificationServiceImpl` now has an extra parameter `signalLossFormatter`. Open `TelegramNotificationServiceImplTest.kt` and add a mock for it (relaxed is fine — existing tests don't exercise the new methods):

```kotlin
private val signalLossFormatter = io.mockk.mockk<ru.zinin.frigate.analyzer.telegram.service.impl.SignalLossMessageFormatter>(relaxed = true)
```

Add `signalLossFormatter = signalLossFormatter,` to every `TelegramNotificationServiceImpl(...)` constructor invocation in this test file.

- [ ] **Step 7: Run all telegram tests**

```bash
./gradlew :frigate-analyzer-telegram:test
```

Expected: all tests pass — old recording tests + new signal-loss tests.

- [ ] **Step 8: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt
git commit -m "feat(telegram): sendCameraSignalLost / sendCameraSignalRecovered + sender simple-text branch"
```

---

### Task 7: `CameraSignalState` sealed class

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt`

No tests — this is a pure declaration; usage tests come in Task 8.

- [ ] **Step 1: Create the sealed class**

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt
package ru.zinin.frigate.analyzer.core.task

import java.time.Instant

sealed class CameraSignalState {
    /** Most recent recording observed for this camera. */
    abstract val lastSeenAt: Instant

    data class Healthy(override val lastSeenAt: Instant) : CameraSignalState()

    data class SignalLost(
        /** Last recording BEFORE the loss — used to compute downtime on recovery. */
        override val lastSeenAt: Instant,
        /** When we emitted the loss notification. */
        val notifiedAt: Instant,
    ) : CameraSignalState()
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :frigate-analyzer-core:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt
git commit -m "feat(core): CameraSignalState sealed class for signal-loss detector"
```

---

### Task 8: `SignalLossMonitorTask` scaffold + state machine + tests

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt`

This is the largest task. We do TDD covering: 6 state-machine branches, cleanup, startup grace, repo-throw resilience, enqueue-throw idempotence.

The task is built atomically — there is no clean way to ship "half a state machine". Each test added below is one step; final implementation comes after all tests are red.

- [ ] **Step 1: Stub out `SignalLossMonitorTask` so tests can compile**

Create the file with the bare class skeleton — no logic:

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.signal-loss", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SignalLossMonitorTask(
    private val properties: SignalLossProperties,
    private val telegramProperties: TelegramProperties,
    private val repository: RecordingEntityRepository,
    private val notificationService: TelegramNotificationService,
    private val clock: Clock,
) {
    private val state = ConcurrentHashMap<String, CameraSignalState>()
    private lateinit var startedAt: Instant

    @PostConstruct
    fun init() {
        check(telegramProperties.enabled) {
            "application.signal-loss.enabled=true requires application.telegram.enabled=true " +
                "(SIGNAL_LOSS_ENABLED + TELEGRAM_ENABLED). Signal-loss alerts have nowhere to be sent."
        }
        properties.validate()
        startedAt = Instant.now(clock)
        logger.info {
            "SignalLossMonitorTask started: threshold=${properties.threshold}, pollInterval=${properties.pollInterval}, " +
                "activeWindow=${properties.activeWindow}, startupGrace=${properties.startupGrace}"
        }
    }

    @Scheduled(fixedDelayString = "#{@signalLossProperties.pollInterval.toMillis()}")
    fun tick() {
        // implemented in subsequent steps
    }
}
```

Note on `@Scheduled(fixedDelayString = ...)`: Spring needs the value resolvable at startup. Using a SpEL expression `#{@signalLossProperties.pollInterval.toMillis()}` lets us reference the bean's resolved Duration; alternatively we can hard-code `fixedDelayString = "\${application.signal-loss.poll-interval}"` and let Spring's `Duration` parser do the work. Use the SpEL form unless the project already has another pattern (grep `@Scheduled` in `modules/core` — `ServerHealthMonitor` is the canonical example; mirror what it does for consistency).

- [ ] **Step 2: Write the failing test scaffold**

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt
package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SignalLossMonitorTaskTest {
    private val properties = SignalLossProperties(
        enabled = true,
        threshold = Duration.ofMinutes(3),
        pollInterval = Duration.ofSeconds(30),
        activeWindow = Duration.ofHours(24),
        startupGrace = Duration.ofMinutes(5),
    )
    private val telegramProperties = TelegramProperties().apply { /* enabled defaults to true */ }
    private val repository = mockk<RecordingEntityRepository>()
    private val notifier = mockk<TelegramNotificationService>(relaxed = true)
    private val baseInstant = Instant.parse("2026-04-25T10:00:00Z")

    private fun task(now: Instant): SignalLossMonitorTask {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        return SignalLossMonitorTask(properties, telegramProperties, repository, notifier, clock).also { it.init() }
    }

    private fun mutableClockTask(initial: Instant): Pair<SignalLossMonitorTask, MutableClock> {
        val mutableClock = MutableClock(initial)
        val task = SignalLossMonitorTask(properties, telegramProperties, repository, notifier, mutableClock).also { it.init() }
        return task to mutableClock
    }

    @BeforeEach
    fun setUp() {
        io.mockk.clearMocks(repository, notifier)
    }

    // --- Branch (null, false): first sighting, healthy. No notification. ---
    @Test
    fun `first sighting healthy seeds state and emits no notification`() = runBlocking {
        val now = baseInstant.plus(properties.startupGrace).plusSeconds(1)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", now.minusSeconds(10)),
        )

        val (task, _) = mutableClockTask(now)
        task.tick()

        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
    }

    // --- Branch (null, true): first sighting, lost. LOSS notification (after grace). ---
    @Test
    fun `first sighting lost emits loss notification`() = runBlocking {
        val now = baseInstant.plus(properties.startupGrace).plusSeconds(1)
        val lastSeen = now.minus(properties.threshold).minusSeconds(10)  // gap > threshold
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )

        val (task, _) = mutableClockTask(now)
        task.tick()

        coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", lastSeen, now) }
    }

    // --- Branch (Healthy, false): keep healthy, advance lastSeenAt. ---
    @Test
    fun `healthy stays healthy when new recording arrives within threshold`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))

        // Tick 1: seed as Healthy
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
        )
        task.tick()

        // Tick 2: 30s later, new recording 5s ago — still healthy
        clock.advance(Duration.ofSeconds(30))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),
        )
        task.tick()

        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
    }

    // --- Branch (Healthy, true): transition to lost + emit. ---
    @Test
    fun `healthy transitions to lost and emits loss notification`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        val firstSeen = clock.instant().minusSeconds(10)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", firstSeen),
        )
        task.tick()  // Healthy seeded

        // Skip past threshold; same lastSeenAt — gap now exceeds threshold
        clock.advance(properties.threshold.plusSeconds(30))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", firstSeen),
        )
        task.tick()

        coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", firstSeen, clock.instant()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
    }

    // --- Branch (SignalLost, false): recovery. ---
    @Test
    fun `signal lost transitions back to healthy and emits recovery with downtime`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        val firstSeen = clock.instant().minusSeconds(10)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", firstSeen),
        )
        task.tick()  // Healthy seeded

        // Force loss
        clock.advance(properties.threshold.plusSeconds(30))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", firstSeen),
        )
        task.tick()  // Now SignalLost

        // New recording arrives
        clock.advance(Duration.ofMinutes(5))
        val newRec = clock.instant().minusSeconds(2)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", newRec),
        )
        task.tick()

        // Downtime = newRec - firstSeen
        coVerify(exactly = 1) { notifier.sendCameraSignalRecovered("cam_a", Duration.between(firstSeen, newRec)) }
    }

    // --- Branch (SignalLost, true): no-op. No repeat alerts. ---
    @Test
    fun `signal lost stays lost without repeat notification`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(30)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )
        task.tick()  // emits loss
        clock.advance(Duration.ofMinutes(2))
        task.tick()  // would re-emit if buggy

        coVerify(exactly = 1) { notifier.sendCameraSignalLost(any(), any(), any()) }
    }

    // --- Cleanup: camera fell out of activeWindow. ---
    @Test
    fun `camera removed from state when it falls out of active window`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
        )
        task.tick()  // Healthy

        // Subsequent tick: cam_a no longer in result (e.g., fell out of activeWindow)
        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns emptyList()
        task.tick()

        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }

        // Now cam_a comes back as new — should re-seed silently (null, false).
        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),
        )
        task.tick()
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
    }

    // --- Startup grace: no notifications during grace, state seeded. ---
    @Test
    fun `during startup grace state is seeded but no notifications are emitted`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant)  // exactly at start
        val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(30)  // would be lost
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )
        task.tick()  // inside grace

        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }

        // After grace, with the SAME data: now we should see the loss
        clock.advance(properties.startupGrace.plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )
        task.tick()
        coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", lastSeen, clock.instant()) }
    }

    // --- Resilience: repo throws. State unchanged, no propagation. ---
    @Test
    fun `tick swallows repository exception and leaves state unchanged`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
        )
        task.tick()  // Healthy seeded

        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } throws RuntimeException("DB exploded")
        task.tick()  // must not throw

        // Subsequent successful tick continues from the prior state
        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),
        )
        task.tick()
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
    }

    // --- Idempotence: enqueue throws. State stays in SignalLost; no repeat on next tick. ---
    @Test
    fun `tick keeps SignalLost state even when notifier throws on emit`() = runBlocking {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(30)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )
        coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } throws RuntimeException("queue full")
        task.tick()  // attempts emit, swallows

        // Next tick — same data — must NOT re-attempt notify
        clock.advance(Duration.ofSeconds(30))
        io.mockk.clearMocks(notifier)
        coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } returns Unit
        task.tick()
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
    }

    /** Simple mutable clock for tests; not thread-safe but we run tests serially. */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }
}
```

A few notes for the implementer:

- `TelegramProperties()` no-arg call — the actual class likely has required fields; if so, build it via the existing test-helper or directly with `TelegramProperties(enabled = true, ...)`. Open `TelegramProperties.kt` and adapt. The test only needs `enabled = true`.
- The test relies on `notifier.sendCameraSignalLost(camId, lastSeen, now)` and `sendCameraSignalRecovered(camId, downtime)` — these signatures match Task 6.

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossMonitorTaskTest*"
```

Expected: 9 failures (the `tick` body is empty).

- [ ] **Step 4: Implement `tick()` to make all tests pass**

Replace the `tick()` body in `SignalLossMonitorTask.kt`:

```kotlin
@Scheduled(fixedDelayString = "#{@signalLossProperties.pollInterval.toMillis()}")
fun tick() {
    try {
        val now = Instant.now(clock)
        val inGrace = now.isBefore(startedAt.plus(properties.startupGrace))
        val activeSince = now.minus(properties.activeWindow)

        val stats: List<LastRecordingPerCameraDto> = kotlinx.coroutines.runBlocking {
            repository.findLastRecordingPerCamera(activeSince)
        }
        val seenCamIds = stats.mapTo(mutableSetOf()) { it.camId }

        var lostCount = 0
        var healthyCount = 0

        for (stat in stats) {
            val gap = Duration.between(stat.lastRecordTimestamp, now)
            val overThreshold = gap > properties.threshold
            val prev = state[stat.camId]

            when {
                prev == null && !overThreshold -> {
                    state[stat.camId] = CameraSignalState.Healthy(stat.lastRecordTimestamp)
                    healthyCount++
                }

                prev == null && overThreshold -> {
                    state[stat.camId] = CameraSignalState.SignalLost(stat.lastRecordTimestamp, now)
                    lostCount++
                    if (!inGrace) emitLoss(stat.camId, stat.lastRecordTimestamp, now)
                }

                prev is CameraSignalState.Healthy && !overThreshold -> {
                    state[stat.camId] = CameraSignalState.Healthy(stat.lastRecordTimestamp)
                    healthyCount++
                }

                prev is CameraSignalState.Healthy && overThreshold -> {
                    state[stat.camId] = CameraSignalState.SignalLost(prev.lastSeenAt, now)
                    lostCount++
                    if (!inGrace) emitLoss(stat.camId, prev.lastSeenAt, now)
                }

                prev is CameraSignalState.SignalLost && !overThreshold -> {
                    val downtime = Duration.between(prev.lastSeenAt, stat.lastRecordTimestamp)
                    state[stat.camId] = CameraSignalState.Healthy(stat.lastRecordTimestamp)
                    healthyCount++
                    if (!inGrace) emitRecovery(stat.camId, downtime)
                }

                prev is CameraSignalState.SignalLost && overThreshold -> {
                    lostCount++
                    // no-op, no emit, keep notifiedAt
                }
            }
        }

        // Cleanup: cameras no longer in stats (fell out of activeWindow)
        val removed = state.keys.filter { it !in seenCamIds }
        removed.forEach { state.remove(it) }

        if (inGrace) {
            logger.debug { "Signal-loss tick (in grace): scanned ${stats.size}, lost=$lostCount, healthy=$healthyCount, removed=${removed.size}" }
        } else {
            logger.debug { "Signal-loss tick: scanned ${stats.size}, lost=$lostCount, healthy=$healthyCount, removed=${removed.size}" }
        }
    } catch (e: Exception) {
        logger.warn { "Signal-loss tick failed: ${e.message}" }
    }
}

private fun emitLoss(camId: String, lastSeen: Instant, now: Instant) {
    try {
        kotlinx.coroutines.runBlocking {
            notificationService.sendCameraSignalLost(camId, lastSeen, now)
        }
        logger.info { "Signal lost: camera=$camId, lastSeen=$lastSeen, gap=${Duration.between(lastSeen, now)}" }
    } catch (e: Exception) {
        logger.warn { "Failed to dispatch signal-loss notification for camera $camId: ${e.message}" }
    }
}

private fun emitRecovery(camId: String, downtime: Duration) {
    try {
        kotlinx.coroutines.runBlocking {
            notificationService.sendCameraSignalRecovered(camId, downtime)
        }
        logger.info { "Signal recovered: camera=$camId, downtime=$downtime" }
    } catch (e: Exception) {
        logger.warn { "Failed to dispatch signal-recovery notification for camera $camId: ${e.message}" }
    }
}
```

Add the import: `import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto` and `import java.time.Duration`.

Note on `runBlocking` inside a Spring `@Scheduled`: this IS the project pattern — `WatchRecordsTask.run()` uses `runBlocking { ... }` for the same reason (the underlying API is `suspend` but Spring's scheduler is not coroutine-aware). Mirror that style.

- [ ] **Step 5: Run all tests in the test class**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossMonitorTaskTest*"
```

Expected: all 9 tests pass.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt
git commit -m "feat(core): SignalLossMonitorTask with state machine, grace period, error resilience"
```

---

### Task 9: Configuration — `application.yaml` and `.claude/rules/configuration.md`

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`
- Modify: `.claude/rules/configuration.md`

- [ ] **Step 1: Add `application.signal-loss` block to `application.yaml`**

Insert after the existing `application.telegram:` block (or anywhere in `application:`; mirror existing indentation — 2 spaces):

```yaml
  signal-loss:
    enabled: ${SIGNAL_LOSS_ENABLED:true}
    threshold: ${SIGNAL_LOSS_THRESHOLD:3m}
    poll-interval: ${SIGNAL_LOSS_POLL_INTERVAL:30s}
    active-window: ${SIGNAL_LOSS_ACTIVE_WINDOW:24h}
    startup-grace: ${SIGNAL_LOSS_STARTUP_GRACE:5m}
```

- [ ] **Step 2: Register `SignalLossProperties` for binding**

The project must enable `@ConfigurationProperties` discovery. Check whether the project uses `@EnableConfigurationProperties(...)` somewhere (grep `@EnableConfigurationProperties` in `modules/core`). If yes, add `SignalLossProperties::class` to the existing list. If the project relies on `@ConfigurationPropertiesScan`, no change is needed (`SignalLossProperties` will be picked up by package scan).

If neither is in use, annotate `SignalLossProperties` itself with `@ConstructorBinding @ConfigurationProperties("application.signal-loss")` and add `@EnableConfigurationProperties(SignalLossProperties::class)` to the main `@Configuration` class (look for the main `@SpringBootApplication` class in `modules/core`).

- [ ] **Step 3: Update `.claude/rules/configuration.md`**

The `paths:` frontmatter for that rule loads when working with `**/application.yaml`. Append a new section to the table:

```markdown
## Signal Loss Detection

| Variable | Default | Purpose |
|----------|---------|---------|
| `SIGNAL_LOSS_ENABLED` | `true` | Master flag for the camera-signal-loss detector. Requires `TELEGRAM_ENABLED=true`. |
| `SIGNAL_LOSS_THRESHOLD` | `3m` | If `now - lastRecording > THRESHOLD` the signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | `30s` | Detector tick period. Must be smaller than `SIGNAL_LOSS_THRESHOLD`. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | `24h` | Window of "active" cameras. Cameras whose last recording is older are not monitored. |
| `SIGNAL_LOSS_STARTUP_GRACE` | `5m` | After startup, only seed state — no notifications. |
```

(Open `.claude/rules/configuration.md` first to mirror the existing section style; if the file uses headings like `### Signal Loss` instead, follow that.)

- [ ] **Step 4: Verify the application boots locally (dry run via context test in Task 10)**

No standalone command — context loading is verified in the integration test added in Task 10.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/resources/application.yaml \
        .claude/rules/configuration.md
# Plus any file edited in Step 2 if you needed @EnableConfigurationProperties wiring:
# git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/<wiring file>.kt
git commit -m "feat(config): wire signal-loss properties + document env-vars"
```

---

### Task 10: Integration test — Spring conflict-fail when telegram is disabled

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt`

This test confirms that `application.signal-loss.enabled=true && application.telegram.enabled=false` makes the application fail at startup with an actionable message (per the `ee5d925` pattern).

- [ ] **Step 1: Write the failing integration test**

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt
package ru.zinin.frigate.analyzer.core.task

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

class SignalLossConfigConflictIntegrationTest {
    /**
     * When signal-loss is enabled but telegram is disabled, the application context
     * MUST fail to start because there is no recipient for the alerts.
     */
    @Test
    fun `context fails to start when signal-loss is enabled and telegram is disabled`() {
        val app = SpringApplication(StubApp::class.java)
        app.setDefaultProperties(
            mapOf(
                "application.signal-loss.enabled" to "true",
                "application.telegram.enabled" to "false",
                // Provide minimum required props so other beans don't blow up first;
                // mirror what other @SpringBootTest tests in the project provide.
            ),
        )

        assertThatThrownBy { app.run() }
            .rootCause()
            .hasMessageContaining("application.signal-loss.enabled=true")
            .hasMessageContaining("application.telegram.enabled=true")
    }

    @SpringBootApplication
    class StubApp
}
```

If the actual `@SpringBootApplication` of the project is a more complex setup (DB, external services), instead use `@SpringBootTest(classes = [StubApp::class])` with `@TestPropertySource(properties = [...])` and `@Disabled`-style hooks — see how `IntegrationTestBase` is built in the project for reference, but try the lightweight `SpringApplication.run()` form first because it isolates the context to the conflict check only.

If using `SpringApplication.run()` causes issues with autoconfig that requires DB, fall back to a unit-style test that creates `SignalLossMonitorTask` directly:

```kotlin
@Test
fun `init throws when telegram is disabled`() {
    val task = SignalLossMonitorTask(
        properties = SignalLossProperties(),
        telegramProperties = TelegramProperties(enabled = false),
        repository = mockk(),
        notificationService = mockk(),
        clock = Clock.systemUTC(),
    )
    assertThatThrownBy { task.init() }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("application.signal-loss.enabled=true")
        .hasMessageContaining("application.telegram.enabled=true")
}
```

The unit-style test is sufficient because `init()` is the actual choke point. Pick the form that runs reliably in this codebase. Both verify the same invariant.

- [ ] **Step 2: Run the test**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossConfigConflictIntegrationTest*"
```

Expected: PASS (the `init()` already throws because Task 8 implemented the check).

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt
git commit -m "test(core): conflict-fail when signal-loss enabled with telegram disabled"
```

---

## Final Verification

After all tasks are complete, run the full build and lint pipeline. Per the project's CLAUDE.md, do not run `./gradlew build` directly during planning mode — invoke `superpowers:code-reviewer` first, fix critical comments, repeat until clean, then use `build-runner` agent for the build. On `ktlint` errors run `./gradlew ktlintFormat` and re-build.

```bash
# Conceptual final check (run via build-runner agent):
./gradlew ktlintCheck
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests green, no lint warnings.

## Definition of Done

- All 10 tasks committed.
- All listed unit + integration tests pass.
- `ktlintCheck` passes.
- `./gradlew build` passes.
- The full feature works end-to-end (manual smoke test if possible: with a running Frigate instance, stop one camera and observe the Telegram message after ~3 minutes; restart it and observe the recovery message).
