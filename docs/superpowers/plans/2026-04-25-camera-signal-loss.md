# Camera Signal Loss Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a polling-based detector that notifies Telegram subscribers when a Frigate camera stops writing recordings (signal loss) and when it resumes (recovery).

**Architecture:** A Spring `@Scheduled` task with a `suspend fun tick()` (Spring 6.1+ supports suspend in `@Scheduled`, no `runBlocking` required) wakes every `pollInterval` (default 30s), queries `MAX(record_timestamp) per cam_id` from the `recordings` table for cameras active in the last 24h, and runs each through a pure `decide()` state-machine function (`Healthy` ↔ `SignalLost`, with `notificationSent` flag for late-alert flow). State transitions enqueue text-only notifications via the existing `TelegramNotificationQueue` (suspend backpressure). State is in-memory; restart safety comes from a `startupGrace` window during which alerts are deferred (state seeded as `SignalLost(notificationSent=false)`); a late LOSS alert fires on the first tick after grace ends.

**IMPORTANT — review feedback applied:** Iter-1 review consolidated several decisions into this plan; if you read an earlier draft, note these changes:
- `tick()` is `suspend fun` (no `runBlocking` anywhere).
- Cleanup keeps `SignalLost` entries (only `Healthy` are removed).
- Conflict-fail check lives in a separate `SignalLossTelegramGuard` (mirrors `AiDescriptionTelegramGuard`).
- `@ConditionalOnProperty(matchIfMissing = false)` (default `true` in `application.yaml`, but missing-property contexts don't activate).
- `decide()` is a pure function for testability without mocks.
- `NotificationTask` sealed interface refactor includes an explicit audit step.

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
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/CameraSignalState.kt` | Sealed state class with `notificationSent` flag |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDecider.kt` | Pure `decide()` function: `(prev, observation, config) -> Decision(newState, event?)` |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDeciderTest.kt` | Parameterized table-driven tests for every row of the decision table |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt` | Main scheduled task: `suspend fun tick()`, holds state map, calls `decide()` and `TelegramNotificationService` |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt` | Behavior/integration tests: cleanup, grace, late-alert, repo throws, cancellation, skew |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuard.kt` | `@Component` mirroring `AiDescriptionTelegramGuard`: throws `IllegalStateException` if `signal-loss.enabled=true && telegram.enabled=false` |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuardTest.kt` | Unit tests for the guard |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt` | `@SpringBootTest` conflict-fail test (signal-loss=true + telegram=false → context fails) |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` | Add `SignalLossProperties::class` to `@EnableConfigurationProperties` (verify file location first) |
| Modify | `modules/core/src/main/resources/application.yaml` | New `application.signal-loss` block |
| Modify | `.claude/rules/configuration.md` | Document new env-vars (incl. `SIGNAL_LOSS_ACTIVE_WINDOW >= Frigate retention` guidance) |

---

### Task 1: Repository — `LastRecordingPerCameraDto` and `findLastRecordingPerCamera`

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`
- Modify: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepositoryTest.kt`

- [ ] **Step 1: Create the projection DTO**

Look at the existing `CameraRecordingCountDto` first to confirm the `@Column` annotation style — mirror it exactly.

```kotlin
// modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/LastRecordingPerCameraDto.kt
package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

data class LastRecordingPerCameraDto(
    @Column("cam_id")
    val camId: String,
    @Column("last_record_timestamp")
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
    SELECT cam_id AS cam_id,
           MAX(record_timestamp) AS last_record_timestamp
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

// Note (per iter-1 review): existing idx_recordings_record_timestamp covers the WHERE range scan.
// No new index migration is needed. Composite (cam_id, record_timestamp DESC) is deferred until
// measured to be necessary at scale (50+ cameras).
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
        props.validateCrossField()  // no exception
        assertThat(props.threshold).isEqualTo(Duration.ofMinutes(3))
    }

    @ParameterizedTest
    @MethodSource("invalidConfigs")
    fun `invalid properties fail validation`(invalid: InvalidCase) {
        assertThatThrownBy { invalid.props.validateCrossField() }
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

Style mirror: read `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/TelegramProperties.kt` first to see how `@Validated` + `@field:NotBlank/@field:Min` are applied. Apply the same per-field annotations here, plus a `@PostConstruct` for cross-field invariants (Bean Validation cannot express `pollInterval < threshold` concisely).

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/SignalLossProperties.kt
package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.annotation.PostConstruct
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "application.signal-loss")
data class SignalLossProperties(
    @field:NotNull val enabled: Boolean = true,
    @field:NotNull val threshold: Duration = Duration.ofMinutes(3),
    @field:NotNull val pollInterval: Duration = Duration.ofSeconds(30),
    @field:NotNull val activeWindow: Duration = Duration.ofHours(24),
    @field:NotNull @field:PositiveOrZero val startupGrace: Duration = Duration.ofMinutes(5),
) {
    @PostConstruct
    fun validateCrossField() {
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
    }
}
```

Note: `@Positive` on `Duration` works in Spring Boot 3+ but version-fragile. Using `@PositiveOrZero` only for `startupGrace` (the only field where zero is meaningful) and falling back to imperative `check(...)` for the others is the safest path. Adjust if `@Positive` works in this project's stack.

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

- [ ] **Step 0 (audit, BEFORE editing): enumerate every `NotificationTask` callsite**

Run:

```bash
grep -rn "NotificationTask" modules/ --include="*.kt" | grep -v "/build/"
```

Confirm the result matches the expected file list below. If you find additional usages NOT listed (e.g., a serializer, a JSON binding, a reflection helper), stop and ask — the refactor scope may be larger than this task assumes.

Expected callsites (from iter-1 review verification):
- `modules/telegram/src/main/kotlin/.../queue/NotificationTask.kt` (the type itself)
- `modules/telegram/src/main/kotlin/.../queue/TelegramNotificationQueue.kt:25,40`
- `modules/telegram/src/main/kotlin/.../queue/TelegramNotificationSender.kt:41,50,146,189`
- `modules/telegram/src/main/kotlin/.../service/impl/TelegramNotificationServiceImpl.kt:12,54`
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramNotificationServiceImplTest.kt:15,68,75,125`
- `modules/telegram/src/test/kotlin/.../queue/TelegramNotificationSenderTest.kt:124`

If the audit matches, proceed to Step 1. If it doesn't, the refactor scope must be re-evaluated before continuing.

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
        // Clamp negative gap to zero (clock skew where Frigate's record_timestamp is in the future).
        val gap = Duration.between(lastSeenAt, now).coerceAtLeast(Duration.ZERO)
        // Explicit pattern (NOT FormatStyle.MEDIUM) to avoid container-locale surprises.
        val timeFormatter = DateTimeFormatter
            .ofPattern(TIME_PATTERN)
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
        private const val TIME_PATTERN = "HH:mm:ss"
    }
}
```

Note on `formatDuration`: the bucketing already skips zero components implicitly — `< SECONDS_PER_MINUTE` shows seconds only, `< SECONDS_PER_HOUR` shows minutes only, etc. There is no "5 min 0 sec" output by construction. The `0 sec` case (downtime exactly 0) is rare/degenerate but the test below documents the behavior. If desired later, suppress it with a `< 1` check returning a "<1 sec" literal — out of scope for this iteration.

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
import ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo
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
            UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
            UserZoneInfo(chatId = 200L, zone = ZoneId.of("Europe/Moscow"), language = "ru"),
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
            UserZoneInfo(chatId = 100L, zone = ZoneId.of("UTC"), language = "en"),
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

Note on `UserZoneInfo` (verified by iter-1 review): the actual class is `ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo` (NOT `UserZoneDto` as an earlier draft incorrectly stated) with fields `(chatId: Long, zone: ZoneId, language: String?)`. Open `TelegramUserService.kt` and `getAuthorizedUsersWithZones()` definition before writing the test to reconfirm the constructor signature.

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
        /**
         * False during startup grace (deferred alert path). Flipped to true once the
         * LOSS notification has been dispatched. Used by the late-alert flow:
         * cameras dead before startup get alerted on the first tick AFTER grace ends.
         */
        val notificationSent: Boolean,
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

### Task 8a: Pure `decide()` function + parameterized table-driven tests

The state-machine logic is extracted as a pure function so it can be unit-tested without any mocks, clock injection, or coroutine machinery. This is one of the iter-1 review consolidation decisions.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDecider.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDeciderTest.kt`

- [ ] **Step 1: Define the data types and pure function**

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDecider.kt
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class Observation(val maxRecordTs: Instant, val now: Instant)

data class Config(val threshold: Duration, val inGrace: Boolean)

sealed class SignalLossEvent {
    data class Loss(val camId: String, val lastSeenAt: Instant, val gap: Duration) : SignalLossEvent()
    data class Recovery(val camId: String, val downtime: Duration) : SignalLossEvent()
}

data class Decision(val newState: CameraSignalState, val event: SignalLossEvent?)

/**
 * Pure state-machine decision. No I/O, no mutation, no clock.
 *
 * See spec section "decide() Decision Table" for the full transition matrix.
 */
fun decide(
    camId: String,
    prev: CameraSignalState?,
    obs: Observation,
    cfg: Config,
): Decision {
    val rawGap = Duration.between(obs.maxRecordTs, obs.now)
    val gap = if (rawGap.isNegative) {
        logger.warn { "Clock skew for camera $camId: maxRecordTs=${obs.maxRecordTs} > now=${obs.now}; treating gap as 0" }
        Duration.ZERO
    } else {
        rawGap
    }
    val overThreshold = gap > cfg.threshold

    return when {
        prev == null && !overThreshold -> Decision(
            newState = CameraSignalState.Healthy(obs.maxRecordTs),
            event = null,
        )

        prev == null && overThreshold && cfg.inGrace -> Decision(
            newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = false),
            event = null,
        )

        prev == null && overThreshold && !cfg.inGrace -> Decision(
            newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = true),
            event = SignalLossEvent.Loss(camId, obs.maxRecordTs, gap),
        )

        prev is CameraSignalState.Healthy && !overThreshold -> Decision(
            newState = CameraSignalState.Healthy(obs.maxRecordTs),
            event = null,
        )

        prev is CameraSignalState.Healthy && overThreshold && cfg.inGrace -> Decision(
            newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = false),
            event = null,
        )

        prev is CameraSignalState.Healthy && overThreshold && !cfg.inGrace -> Decision(
            newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = true),
            event = SignalLossEvent.Loss(camId, obs.maxRecordTs, gap),
        )

        prev is CameraSignalState.SignalLost && !overThreshold -> Decision(
            newState = CameraSignalState.Healthy(obs.maxRecordTs),
            event = SignalLossEvent.Recovery(camId, Duration.between(prev.lastSeenAt, obs.maxRecordTs)),
        )

        prev is CameraSignalState.SignalLost && overThreshold && !prev.notificationSent && !cfg.inGrace -> Decision(
            // LATE ALERT: deferred during grace, fires on first tick after grace ends
            newState = CameraSignalState.SignalLost(prev.lastSeenAt, notificationSent = true),
            event = SignalLossEvent.Loss(camId, prev.lastSeenAt, gap),
        )

        prev is CameraSignalState.SignalLost && overThreshold && !prev.notificationSent && cfg.inGrace -> Decision(
            // Still in grace, keep deferred
            newState = CameraSignalState.SignalLost(prev.lastSeenAt, notificationSent = false),
            event = null,
        )

        prev is CameraSignalState.SignalLost && overThreshold && prev.notificationSent -> Decision(
            // Already notified, no spam
            newState = prev,
            event = null,
        )

        else -> error("Unreachable: prev=$prev, overThreshold=$overThreshold, inGrace=${cfg.inGrace}")
    }
}
```

- [ ] **Step 2: Write parameterized table-driven tests**

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDeciderTest.kt
package ru.zinin.frigate.analyzer.core.task

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant

class SignalLossDeciderTest {
    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val threshold = Duration.ofMinutes(3)

    private fun cfg(inGrace: Boolean = false) = Config(threshold = threshold, inGrace = inGrace)

    @Test
    fun `null + healthy gap → Healthy, no event`() {
        val d = decide("cam_a", prev = null, obs = obs(now.minusSeconds(60)), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(now.minusSeconds(60)))
        assertThat(d.event).isNull()
    }

    @Test
    fun `null + lost gap + in grace → SignalLost(sent=false), no event`() {
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(CameraSignalState.SignalLost(maxRec, notificationSent = false))
        assertThat(d.event).isNull()
    }

    @Test
    fun `null + lost gap + not in grace → SignalLost(sent=true), Loss event`() {
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(CameraSignalState.SignalLost(maxRec, notificationSent = true))
        assertThat(d.event).isInstanceOf(SignalLossEvent.Loss::class.java)
        assertThat((d.event as SignalLossEvent.Loss).lastSeenAt).isEqualTo(maxRec)
    }

    @Test
    fun `Healthy + healthy gap → Healthy advanced, no event`() {
        val prev = CameraSignalState.Healthy(now.minusSeconds(120))
        val d = decide("cam_a", prev = prev, obs = obs(now.minusSeconds(10)), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(now.minusSeconds(10)))
        assertThat(d.event).isNull()
    }

    @Test
    fun `Healthy + lost gap + not in grace → SignalLost(sent=true) using maxRecordTs, Loss event with maxRecordTs`() {
        // KEY ASSERTION: lastSeenAt is the fresh maxRecordTs, not the stale prev.lastSeenAt
        val prevSeen = now.minusSeconds(600)  // 10 min ago
        val maxRec = now.minus(threshold).minusSeconds(30)  // 3min30s ago — newer than prev
        val prev = CameraSignalState.Healthy(prevSeen)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(CameraSignalState.SignalLost(maxRec, notificationSent = true))
        assertThat((d.event as SignalLossEvent.Loss).lastSeenAt).isEqualTo(maxRec)
        assertThat((d.event as SignalLossEvent.Loss).lastSeenAt).isNotEqualTo(prevSeen)
    }

    @Test
    fun `Healthy + lost gap + in grace → SignalLost(sent=false), no event`() {
        val prev = CameraSignalState.Healthy(now.minusSeconds(600))
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(CameraSignalState.SignalLost(maxRec, notificationSent = false))
        assertThat(d.event).isNull()
    }

    @Test
    fun `SignalLost(sent=true) + healthy gap → Healthy, Recovery event with downtime from prev_lastSeenAt`() {
        val prevSeen = now.minusSeconds(600)
        val maxRec = now.minusSeconds(10)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = true)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat((d.event as SignalLossEvent.Recovery).downtime)
            .isEqualTo(Duration.between(prevSeen, maxRec))
    }

    @Test
    fun `SignalLost(sent=true) + lost gap → no-op, no event`() {
        val prev = CameraSignalState.SignalLost(now.minusSeconds(600), notificationSent = true)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(prev)  // unchanged
        assertThat(d.event).isNull()
    }

    @Test
    fun `LATE ALERT: SignalLost(sent=false) + lost gap + not in grace → SignalLost(sent=true) + Loss`() {
        val prevSeen = now.minusSeconds(600)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = false)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(CameraSignalState.SignalLost(prevSeen, notificationSent = true))
        assertThat(d.event).isInstanceOf(SignalLossEvent.Loss::class.java)
        assertThat((d.event as SignalLossEvent.Loss).lastSeenAt).isEqualTo(prevSeen)
    }

    @Test
    fun `SignalLost(sent=false) + lost gap + in grace → still deferred, no event`() {
        val prev = CameraSignalState.SignalLost(now.minusSeconds(600), notificationSent = false)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(prev)  // unchanged
        assertThat(d.event).isNull()
    }

    @Test
    fun `boundary: gap exactly == threshold → considered healthy (strict greater-than)`() {
        val maxRec = now.minus(threshold)  // exactly threshold
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `clock skew: maxRecordTs in the future → gap clamped to 0, healthy`() {
        val maxRec = now.plusSeconds(5)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    private fun obs(maxRec: Instant) = Observation(maxRecordTs = maxRec, now = now)
}
```

- [ ] **Step 3: Run tests to verify they pass**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossDeciderTest*"
```

Expected: 12 tests pass.

- [ ] **Step 4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDecider.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossDeciderTest.kt
git commit -m "feat(core): pure decide() state-machine for signal-loss with table-driven tests"
```

---

### Task 8b: `SignalLossMonitorTask` (suspend tick) + behavior tests

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt`

- [ ] **Step 1: Implement `SignalLossMonitorTask` with suspend `tick()`**

```kotlin
// modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CancellationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(
    prefix = "application.signal-loss",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,  // important: do not activate in test contexts that omit the property
)
class SignalLossMonitorTask(
    private val properties: SignalLossProperties,
    private val repository: RecordingEntityRepository,
    private val notificationService: TelegramNotificationService,
    private val clock: Clock,
) {
    private val state = ConcurrentHashMap<String, CameraSignalState>()
    private lateinit var startedAt: Instant

    @PostConstruct
    fun init() {
        startedAt = Instant.now(clock)
        logger.info {
            "SignalLossMonitorTask started: threshold=${properties.threshold}, " +
                "pollInterval=${properties.pollInterval}, activeWindow=${properties.activeWindow}, " +
                "startupGrace=${properties.startupGrace}"
        }
    }

    @Scheduled(fixedDelayString = "\${application.signal-loss.poll-interval}")
    suspend fun tick() {
        try {
            val now = Instant.now(clock)
            val inGrace = now.isBefore(startedAt.plus(properties.startupGrace))
            val activeSince = now.minus(properties.activeWindow)

            val stats = repository.findLastRecordingPerCamera(activeSince)
            val seenCamIds = stats.mapTo(mutableSetOf()) { it.camId }
            val cfg = Config(threshold = properties.threshold, inGrace = inGrace)

            for (stat in stats) {
                val prev = state[stat.camId]
                val decision = decide(
                    camId = stat.camId,
                    prev = prev,
                    obs = Observation(stat.lastRecordTimestamp, now),
                    cfg = cfg,
                )
                state[stat.camId] = decision.newState

                when (val event = decision.event) {
                    is SignalLossEvent.Loss -> emitLoss(event)
                    is SignalLossEvent.Recovery -> emitRecovery(event)
                    null -> Unit
                }
            }

            // Cleanup: remove only Healthy entries that fell out of activeWindow.
            // SignalLost entries are KEPT so an eventual recovery can still be emitted.
            val removed = state.entries
                .filter { it.key !in seenCamIds && it.value is CameraSignalState.Healthy }
                .map { it.key }
            removed.forEach { state.remove(it) }

            val currentlyLost = state.values.count { it is CameraSignalState.SignalLost }
            val healthy = state.values.count { it is CameraSignalState.Healthy }
            logger.debug {
                "Signal-loss tick (inGrace=$inGrace): scanned=${stats.size}, " +
                    "currentlyLost=$currentlyLost, healthy=$healthy, removed=${removed.size}"
            }
        } catch (e: CancellationException) {
            throw e  // propagate cancellation; never swallowed
        } catch (e: Exception) {
            logger.warn { "Signal-loss tick failed: ${e.message}" }
        }
    }

    private suspend fun emitLoss(event: SignalLossEvent.Loss) {
        try {
            notificationService.sendCameraSignalLost(event.camId, event.lastSeenAt, Instant.now(clock))
            logger.info { "Signal lost: camera=${event.camId}, lastSeen=${event.lastSeenAt}, gap=${event.gap}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Failed to dispatch signal-loss notification for camera ${event.camId}: ${e.message}" }
        }
    }

    private suspend fun emitRecovery(event: SignalLossEvent.Recovery) {
        try {
            notificationService.sendCameraSignalRecovered(event.camId, event.downtime)
            logger.info { "Signal recovered: camera=${event.camId}, downtime=${event.downtime}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Failed to dispatch signal-recovery notification for camera ${event.camId}: ${e.message}" }
        }
    }
}
```

Notes:
- `@Scheduled(fixedDelayString = "\${application.signal-loss.poll-interval}")` — Spring Boot parses the `Duration` value (e.g. `30s`) automatically. Mirrors `ServerHealthMonitor.kt`.
- `suspend fun tick()` — requires Spring 6.1+ / Boot 3.2+. The project is on Boot 4.0.3, so this is supported. NO `runBlocking` anywhere.
- `emitLoss`/`emitRecovery` are member functions (they need `notificationService` and `clock`).
- `currentlyLost` is a gauge metric (count of cameras currently in `SignalLost` state), not new losses this tick — the rename clarifies the misleading `lostCount` from the earlier draft.

- [ ] **Step 2: Write the failing behavior tests**

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt
package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.clearMocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
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
    private val repository = mockk<RecordingEntityRepository>()
    private val notifier = mockk<TelegramNotificationService>(relaxed = true)
    private val baseInstant = Instant.parse("2026-04-25T10:00:00Z")

    private fun mutableClockTask(initial: Instant): Pair<SignalLossMonitorTask, MutableClock> {
        val mutableClock = MutableClock(initial)
        val task = SignalLossMonitorTask(properties, repository, notifier, mutableClock).also { it.init() }
        return task to mutableClock
    }

    @BeforeEach
    fun setUp() {
        clearMocks(repository, notifier)
    }

    @Test
    fun `cleanup keeps SignalLost but removes Healthy when camera falls out of activeWindow`() = runTest {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))

        // Tick 1: seed cam_a Healthy, cam_b will be SignalLost
        val cam_b_lastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
            LastRecordingPerCameraDto("cam_b", cam_b_lastSeen),
        )
        task.tick()

        // Tick 2: both cameras absent from stats (fell out of activeWindow)
        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns emptyList()
        task.tick()

        // cam_a (Healthy) → removed; cam_b (SignalLost) → KEPT
        // Public state inspection: expose private state via internal/test helper, OR re-tick with returning data
        // Here we verify behavior: cam_a re-entry triggers (null,false) silent init; cam_b recovery still works
        clock.advance(Duration.ofMinutes(1))
        val cam_b_recovered = clock.instant().minusSeconds(5)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),  // back, healthy
            LastRecordingPerCameraDto("cam_b", cam_b_recovered),                    // back, healthy
        )
        clearMocks(notifier)
        coEvery { notifier.sendCameraSignalRecovered(any(), any()) } returns Unit
        coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } returns Unit
        task.tick()

        // cam_a re-entered as fresh (null, false) → silent
        coVerify(exactly = 0) { notifier.sendCameraSignalLost("cam_a", any(), any()) }
        // cam_b re-entered with prev=SignalLost (preserved!) → Recovery emitted with correct downtime
        coVerify(exactly = 1) {
            notifier.sendCameraSignalRecovered("cam_b", Duration.between(cam_b_lastSeen, cam_b_recovered))
        }
    }

    @Test
    fun `late alert after grace ends for camera that was lost during grace`() = runTest {
        val (task, clock) = mutableClockTask(baseInstant)  // exactly at start
        val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )

        // Tick during grace: state seeded as SignalLost(sent=false), no notification
        task.tick()
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }

        // Advance past grace; same data
        clock.advance(properties.startupGrace.plusSeconds(1))
        task.tick()

        // LATE ALERT fires
        coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", lastSeen, clock.instant()) }

        // Subsequent tick (still lost) → no repeat
        clock.advance(Duration.ofMinutes(1))
        task.tick()
        coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", any(), any()) }
    }

    @Test
    fun `tick swallows repository exception and leaves state unchanged`() = runTest {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
        )
        task.tick()  // Healthy seeded

        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } throws RuntimeException("DB exploded")
        task.tick()  // must not throw

        // Subsequent tick continues from prior state
        clock.advance(Duration.ofMinutes(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),
        )
        task.tick()
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
    }

    @Test
    fun `tick re-throws CancellationException`() = runTest {
        val (task, _) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } throws CancellationException("shutdown")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { task.tick() } }
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `enqueue throw retains transition (no repeat next tick)`() = runTest {
        val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
        coEvery { repository.findLastRecordingPerCamera(any()) } returns listOf(
            LastRecordingPerCameraDto("cam_a", lastSeen),
        )
        coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } throws RuntimeException("queue full")
        task.tick()  // attempts emit, swallows; state is now SignalLost(sent=true)

        clock.advance(Duration.ofSeconds(30))
        clearMocks(notifier)
        coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } returns Unit
        task.tick()
        // sent=true was set despite the throw → next tick is no-op → no retry
        coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
    }

    /** Simple mutable clock for tests; not thread-safe but tests are serial. */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }
}
```

Notes:
- The bulk of state-machine correctness is covered by `SignalLossDeciderTest` (Task 8a). This file focuses on the IO surfaces: cleanup, late-alert flow, error resilience, cancellation, idempotence on enqueue failure.
- Tests use `runTest` (kotlinx-coroutines-test) for proper coroutine support.

- [ ] **Step 3: Run tests to verify they pass**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossMonitorTaskTest*"
```

Expected: all 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskTest.kt
git commit -m "feat(core): SignalLossMonitorTask with suspend tick, late-alert flow, error resilience"
```

---

### Task 8c: `SignalLossTelegramGuard` (conflict-fail)

Mirrors the existing `AiDescriptionTelegramGuard` (commit `ee5d925`). Single-purpose `@Component` that fails application context startup with a clear message when `signal-loss.enabled=true` AND `telegram.enabled=false`.

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuard.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuardTest.kt`

- [ ] **Step 1: Open `AiDescriptionTelegramGuard.kt` and mirror its structure**

Read `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/AiDescriptionTelegramGuard.kt` (the location is the existing one — adapt path if different). Copy its structure. The new guard's logic: if `application.signal-loss.enabled=true` and `application.telegram.enabled=false` → throw `IllegalStateException` from `@PostConstruct`.

Suggested skeleton (adapt to match the actual `AiDescriptionTelegramGuard` style — read it first):

```kotlin
// modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuard.kt
package ru.zinin.frigate.analyzer.telegram.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "application.signal-loss",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class SignalLossTelegramGuard(
    @Value("\${application.telegram.enabled:false}") private val telegramEnabled: Boolean,
) {
    @PostConstruct
    fun verify() {
        check(telegramEnabled) {
            "application.signal-loss.enabled=true requires application.telegram.enabled=true " +
                "(SIGNAL_LOSS_ENABLED + TELEGRAM_ENABLED). Signal-loss alerts have nowhere to be sent."
        }
    }
}
```

- [ ] **Step 2: Write tests**

```kotlin
// modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuardTest.kt
package ru.zinin.frigate.analyzer.telegram.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThatCode

class SignalLossTelegramGuardTest {
    @Test
    fun `verify throws when telegram disabled`() {
        val guard = SignalLossTelegramGuard(telegramEnabled = false)
        assertThatThrownBy { guard.verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("application.signal-loss.enabled=true")
            .hasMessageContaining("application.telegram.enabled=true")
    }

    @Test
    fun `verify accepts when telegram enabled`() {
        val guard = SignalLossTelegramGuard(telegramEnabled = true)
        assertThatCode { guard.verify() }.doesNotThrowAnyException()
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "*SignalLossTelegramGuardTest*"
```

Expected: 2 tests pass.

- [ ] **Step 4: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuard.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/config/SignalLossTelegramGuardTest.kt
git commit -m "feat(telegram): SignalLossTelegramGuard mirroring AiDescriptionTelegramGuard"
```

---
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

- [ ] **Step 2: Register `SignalLossProperties` in `@EnableConfigurationProperties`**

Open `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` (or wherever `@EnableConfigurationProperties(...)` is declared in this project — verified by iter-1 review that this annotation is used explicitly with a list). Add `SignalLossProperties::class` to the existing list. Without this Spring won't bind the YAML block to the bean, and `SignalLossMonitorTask` won't construct.

If `@EnableConfigurationProperties` is NOT used and `@ConfigurationPropertiesScan` covers the package, no change needed — but verify by reading the existing wiring first.

- [ ] **Step 3: Update `.claude/rules/configuration.md`**

The `paths:` frontmatter for that rule loads when working with `**/application.yaml`. Append a new section to the table:

```markdown
## Signal Loss Detection

| Variable | Default | Purpose |
|----------|---------|---------|
| `SIGNAL_LOSS_ENABLED` | `true` | Master flag. `@ConditionalOnProperty(matchIfMissing=false)` — production has it on by default via `application.yaml`, but missing-property test contexts won't activate the task. Requires `TELEGRAM_ENABLED=true` (enforced by `SignalLossTelegramGuard`). |
| `SIGNAL_LOSS_THRESHOLD` | `3m` | If `now - lastRecording > THRESHOLD` (strict) the signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | `30s` | Detector tick period. Must be smaller than `SIGNAL_LOSS_THRESHOLD`. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | `24h` | Window of "active" cameras. **Must be set to at least Frigate's recording retention.** Cameras whose last recording is older are not monitored. |
| `SIGNAL_LOSS_STARTUP_GRACE` | `5m` | After startup, alerts are deferred (state seeded as `SignalLost(notificationSent=false)`). The next tick after grace ends fires the late LOSS alert if the gap still holds. |
```

(Open `.claude/rules/configuration.md` first to mirror the existing section style; if the file uses headings like `### Signal Loss` instead, follow that.)

- [ ] **Step 4: Verify the application boots locally (dry run via context test in Task 10)**

No standalone command — context loading is verified in the integration test added in Task 10.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/resources/application.yaml \
        .claude/rules/configuration.md \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt
git commit -m "feat(config): wire signal-loss properties + document env-vars"
```

---

### Task 10: Integration test — Spring conflict-fail when telegram is disabled

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt`

This is the Spring-context-level integration test. The unit-level test for the guard is already in Task 8c; this one verifies that the guard actually fails the full Spring context startup with the configured property values.

- [ ] **Step 1: Write the integration test**

Look at how the project's existing `IntegrationTestBase` is set up (`modules/*/src/test/kotlin/.../IntegrationTestBase.kt`) before writing this. The project uses Testcontainers + Compose, which may make spinning up a full context heavy. If full context fails for unrelated reasons (DB requirement, etc.), use `@SpringBootTest(classes = [SignalLossTelegramGuard::class])` with explicit slice — we only need the guard bean and the two property values.

```kotlin
// modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt
package ru.zinin.frigate.analyzer.core.task

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import ru.zinin.frigate.analyzer.telegram.config.SignalLossTelegramGuard

class SignalLossConfigConflictIntegrationTest {
    /**
     * When signal-loss is enabled but telegram is disabled, the application context
     * MUST fail to start because SignalLossTelegramGuard's @PostConstruct throws.
     */
    @Test
    fun `context fails to start when signal-loss is enabled and telegram is disabled`() {
        val app = SpringApplication(StubApp::class.java)
        app.setDefaultProperties(
            mapOf(
                "application.signal-loss.enabled" to "true",
                "application.telegram.enabled" to "false",
            ),
        )

        assertThatThrownBy { app.run() }
            .rootCause()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("application.signal-loss.enabled=true")
            .hasMessageContaining("application.telegram.enabled=true")
    }

    @SpringBootApplication(scanBasePackageClasses = [SignalLossTelegramGuard::class])
    class StubApp
}
```

If `SpringApplication.run()` cannot construct the minimal context (auto-config trying to wire DB, etc.), fall back to a manual context approach using `AnnotationConfigApplicationContext` + explicit `register(SignalLossTelegramGuard::class.java)` and a `MockEnvironment`. The unit-level guard test in Task 8c is the primary regression guard; this integration test provides confidence that the wiring is correct.

- [ ] **Step 2: Run the test**

```bash
./gradlew :frigate-analyzer-core:test --tests "*SignalLossConfigConflictIntegrationTest*"
```

Expected: PASS — guard throws, root cause names both env-vars.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossConfigConflictIntegrationTest.kt
git commit -m "test(core): integration test for signal-loss + telegram-disabled conflict-fail"
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

- All 12 tasks committed (1, 2, 3, 4, 5, 6, 7, 8a, 8b, 8c, 9, 10).
- All listed unit + integration tests pass.
- `ktlintCheck` passes.
- `./gradlew build` passes.
- The full feature works end-to-end (manual smoke test if possible: with a running Frigate instance, stop one camera and observe the Telegram message after ~3 minutes; restart it and observe the recovery message).
- Existing test suites (`core` and `service` integration tests) remain green WITHOUT any patches to their `application.yaml` — verified by `matchIfMissing = false` semantics and the test config compatibility check from the spec.
