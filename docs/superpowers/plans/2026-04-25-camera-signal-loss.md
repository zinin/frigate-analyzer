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

✅ Done — see commit(s): `8d0e5d7`

---

### Task 2: `SignalLossProperties` + validation

✅ Done — see commit(s): `5fdd10f`, `c2eadf9`

---

### Task 3: Refactor `NotificationTask` to sealed interface

✅ Done — see commit(s): `519ad8a`

---

### Task 4: Add i18n message keys

✅ Done — see commit(s): `239c721`

---

### Task 5: `SignalLossMessageFormatter` (TDD)

✅ Done — see commit(s): `bc31872`, `8a4df7d`

---

### Task 6: Extend `TelegramNotificationService` with signal-loss methods + Sender simple-text branch

✅ Done — see commit(s): `ec0c410`, `0ebdc56`

---

### Task 7: `CameraSignalState` sealed class

✅ Done — see commit(s): `5eb2a86`

---

### Task 8a: Pure `decide()` function + parameterized table-driven tests

✅ Done — see commit(s): `cfd7c23`, `82c0d02`

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
