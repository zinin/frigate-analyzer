# /status REST + Telegram command — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace REST `/statistics` with `/status` (recordings + cameras + detect servers) and add OWNER-only Telegram `/status` command rendering the same snapshot in HTML.

**Architecture:** New `StatusService` is the single source of truth for system snapshot — used both by `StatusController` (JSON) and `StatusCommandHandler` (HTML via `StatusMessageFormatter`). Camera signal status is read from `SignalLossMonitorTask.snapshotStates()` (in-memory state machine). When `signal-loss.enabled=false`, cameras section is empty + `monitoringEnabled=false`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3 + WebFlux, R2DBC, Kotlin Coroutines, ktgbotapi (HTMLParseMode), JUnit 5, AssertJ, MockK.

**Spec:** `docs/superpowers/specs/2026-05-25-status-telegram-design.md`
**Branch:** `feat/status-command`

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraStatusDto.kt` | `CameraStatusDto` + `CameraState` enum (HEALTHY/OFFLINE) |
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatusResponse.kt` | `StatusResponse` + `CamerasSection` |
| Delete | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt` | Old wrapper — move `RecordingsStatistics`/`CameraStatistics`/`DetectServerStatistics`/`ServerLoad`/`ServerStatus` into new files before deleting |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt` | Add `fun snapshotStates(): Map<String, CameraSignalState>` |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt` | Build `StatusResponse` from repo + load-balancer + monitor snapshot, with sorting |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusController.kt` | REST `GET /status` |
| Delete | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatisticsController.kt` | Replaced by StatusController |
| Modify | `modules/telegram/src/main/resources/messages_en.properties` | New `status.*` and `command.status.description` keys |
| Modify | `modules/telegram/src/main/resources/messages_ru.properties` | Same keys in Russian |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt` | Render `StatusResponse` to HTML with `<pre>` tables |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt` | OWNER-only `/status` handler (in `core`, not `telegram` — `telegram` does not depend on `core`) |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt` | Disable `WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS` for ISO-8601 contract |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt` | Unit tests for collect logic |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt` | Integration test GET /status with explicit ISO-8601 assertions |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskSnapshotTest.kt` | snapshotStates() defensive-copy + mutation-after-snapshot tests |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt` | HTML format + escape + padding + i18n + tz + id-non-duplication |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt` | Command metadata + collaboration |

**Reused unchanged:** `RecordingEntityRepository` (all 5 stats methods), `DetectServerLoadBalancer.getAllServersStatistics()`, `RecordingsStatistics`, `CameraStatistics`, `DetectServerStatistics`, `ServerLoad`, `ServerStatus`, `SignalLossMessageFormatter` (for `formatDuration`), `MessageResolver`, `AuthorizationFilter`, `HelpCommandHandler` (auto-picks up owner commands).

---

### Task 1: Move existing model classes out of StatisticsResponse.kt

Prep work — split the soon-to-be-deleted `StatisticsResponse.kt` file so each remaining type lives in its own file. Required because new `StatusResponse.kt` reuses these types and `StatisticsResponse.kt` itself will be deleted.

**Files:**
- Read: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/DetectServerStatistics.kt`
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt` (reduce to single `data class StatisticsResponse` + delete in Task 8)

- [ ] **Step 1: Create RecordingsStatistics.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

data class RecordingsStatistics(
    val total: Long,
    val processed: Long,
    val unprocessed: Long,
    val byCameras: List<CameraStatistics>,
    val processingRatePerMinute: Double,
)

data class CameraStatistics(
    val camId: String,
    val recordingsCount: Long,
    val recordingsProcessed: Long,
    val detectionsCount: Long,
)
```

- [ ] **Step 2: Create DetectServerStatistics.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

data class DetectServerStatistics(
    val id: String,
    val status: ServerStatus,
    val frameRequests: ServerLoad,
    val frameExtractionRequests: ServerLoad,
    val visualizeRequests: ServerLoad,
    val videoVisualizeRequests: ServerLoad,
)

data class ServerLoad(
    val current: Int,
    val maximum: Int,
)

enum class ServerStatus {
    ALIVE,
    DEAD,
}
```

- [ ] **Step 3: Reduce StatisticsResponse.kt to just the wrapper**

Replace entire file with:

```kotlin
package ru.zinin.frigate.analyzer.model.response

data class StatisticsResponse(
    val recordings: RecordingsStatistics,
    val detectServers: List<DetectServerStatistics>,
)
```

- [ ] **Step 4: Compile check** — delegate to build-runner agent:

```
./gradlew :frigate-analyzer-model:compileKotlin
```

Expected: success. If ktlint complains, run `./gradlew ktlintFormat` and retry.

- [ ] **Step 5: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/
git commit -m "refactor(model): split StatisticsResponse.kt into per-type files

Prep for /status response — RecordingsStatistics and DetectServerStatistics
will be reused; StatisticsResponse wrapper will be removed later."
```

---

### Task 2: Create CameraStatusDto and CameraState enum

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraStatusDto.kt`

- [ ] **Step 1: Create CameraStatusDto.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.dto

import java.time.Duration
import java.time.Instant

data class CameraStatusDto(
    val camId: String,
    val state: CameraState,
    val lastSeenAt: Instant,
    val offlineFor: Duration?,
)

enum class CameraState {
    HEALTHY,
    OFFLINE,
}
```

- [ ] **Step 2: Compile check** — delegate to build-runner agent:

```
./gradlew :frigate-analyzer-model:compileKotlin
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraStatusDto.kt
git commit -m "feat(model): add CameraStatusDto and CameraState enum"
```

---

### Task 3: Create StatusResponse and CamerasSection

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatusResponse.kt`

- [ ] **Step 1: Create StatusResponse.kt**

```kotlin
package ru.zinin.frigate.analyzer.model.response

import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto

data class StatusResponse(
    val recordings: RecordingsStatistics,
    val cameras: CamerasSection,
    val detectServers: List<DetectServerStatistics>,
)

data class CamerasSection(
    val monitoringEnabled: Boolean,
    val items: List<CameraStatusDto>,
)
```

- [ ] **Step 2: Compile check** — delegate to build-runner agent:

```
./gradlew :frigate-analyzer-model:compileKotlin
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatusResponse.kt
git commit -m "feat(model): add StatusResponse with CamerasSection"
```

---

### Task 4: Add snapshotStates() to SignalLossMonitorTask

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskSnapshotTest.kt`

- [ ] **Step 1: Write failing test SignalLossMonitorTaskSnapshotTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SignalLossMonitorTaskSnapshotTest {
    @Test
    fun `snapshotStates returns defensive copy after tick`() =
        runBlocking {
            val now = Instant.parse("2026-04-25T10:00:00Z")
            val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
            val repo = mockk<RecordingEntityRepository>()
            val notify = mockk<TelegramNotificationService>(relaxed = true)
            val props =
                SignalLossProperties(
                    enabled = true,
                    threshold = Duration.ofMinutes(3),
                    pollInterval = Duration.ofSeconds(30),
                    activeWindow = Duration.ofMinutes(30),
                    startupGrace = Duration.ZERO,
                )
            coEvery { repo.findLastRecordingPerCamera(any()) } returns
                listOf(
                    LastRecordingPerCameraDto("cam1", now.minusSeconds(10)),
                    LastRecordingPerCameraDto("cam2", now.minusSeconds(600)),
                )

            val task = SignalLossMonitorTask(props, repo, notify, fixedClock)
            task.init()
            task.tick()

            val snapshot = task.snapshotStates()

            assertThat(snapshot.keys).containsExactlyInAnyOrder("cam1", "cam2")
            assertThat(snapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)
            assertThat(snapshot["cam2"]).isInstanceOf(CameraSignalState.SignalLost::class.java)
        }
}
```

- [ ] **Step 2: Run test, expect compile failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests SignalLossMonitorTaskSnapshotTest
```

Expected: FAIL (`snapshotStates` unresolved reference).

- [ ] **Step 3: Add snapshotStates() to SignalLossMonitorTask**

Open `SignalLossMonitorTask.kt`. After the `tick()` method and helper privates, add:

```kotlin
/**
 * Returns a snapshot of the current camera state map.
 *
 * Used by `StatusService` to expose camera signal status to /status REST and Telegram command
 * without exposing the mutable internal `ConcurrentHashMap`. Safe to call from any thread; does
 * not block the `@Scheduled fixedDelay` tick.
 *
 * **Semantics:** weakly-consistent snapshot via `ConcurrentHashMap.toMap()`. May observe a state
 * in which some entries have been updated by a concurrent `tick()` and others have not — no
 * global atomicity. Acceptable for diagnostic / monitoring use (`/status`). The returned map is
 * an immutable copy; mutating the original `state` after this call does not affect the returned
 * map, and vice versa.
 */
fun snapshotStates(): Map<String, CameraSignalState> = state.toMap()
```

- [ ] **Step 3a: Add defensive-copy verification test**

Extend `SignalLossMonitorTaskSnapshotTest.kt` with a second test that proves the snapshot is
detached from the internal state — modify the internal map (via another `tick()` that changes
state) and assert the previously-taken snapshot is unchanged:

```kotlin
@Test
fun `snapshotStates is decoupled from subsequent state mutations`() =
    runBlocking {
        val now = Instant.parse("2026-04-25T10:00:00Z")
        val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
        val repo = mockk<RecordingEntityRepository>()
        val notify = mockk<TelegramNotificationService>(relaxed = true)
        val props =
            SignalLossProperties(
                enabled = true,
                threshold = Duration.ofMinutes(3),
                pollInterval = Duration.ofSeconds(30),
                activeWindow = Duration.ofMinutes(30),
                startupGrace = Duration.ZERO,
            )
        coEvery { repo.findLastRecordingPerCamera(any()) } returnsMany
            listOf(
                listOf(LastRecordingPerCameraDto("cam1", now.minusSeconds(10))),
                listOf(LastRecordingPerCameraDto("cam1", now.minusSeconds(600))),
            )

        val task = SignalLossMonitorTask(props, repo, notify, fixedClock)
        task.init()
        task.tick()
        val firstSnapshot = task.snapshotStates()
        assertThat(firstSnapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)

        task.tick()
        // The first snapshot must NOT observe the second-tick transition to SignalLost.
        assertThat(firstSnapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)
        // But a fresh snapshot reflects the new state.
        assertThat(task.snapshotStates()["cam1"]).isInstanceOf(CameraSignalState.SignalLost::class.java)
    }
```

- [ ] **Step 4: Run tests, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests SignalLossMonitorTaskSnapshotTest
```

Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskSnapshotTest.kt
git commit -m "feat(core): expose SignalLossMonitorTask.snapshotStates() for /status"
```

---

### Task 5: Create StatusService

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt`

- [ ] **Step 1: Write failing test StatusServiceTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.task.CameraSignalState
import ru.zinin.frigate.analyzer.core.task.SignalLossMonitorTask
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatisticsDto
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.ServerLoad
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class StatusServiceTest {
    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val recordings =
        mockk<RecordingEntityRepository>().apply {
            coEvery { countAll() } returns 100L
            coEvery { countProcessed() } returns 90L
            coEvery { countUnprocessed() } returns 10L
            coEvery { getStatisticsByCameras() } returns
                listOf(
                    CameraStatisticsDto("cam1", 50, 50, 5),
                    CameraStatisticsDto("cam2", 50, 40, 3),
                )
            coEvery { getProcessingRatePerMinuteLast5Minutes() } returns 2.5
        }

    private val lb =
        mockk<DetectServerLoadBalancer>().apply {
            every { getAllServersStatistics() } returns
                listOf(
                    DetectServerStatistics(
                        id = "srv-b",
                        status = ServerStatus.ALIVE,
                        frameRequests = ServerLoad(1, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.DEAD,
                        frameRequests = ServerLoad(0, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                )
        }

    private fun monitorProvider(monitor: SignalLossMonitorTask?): ObjectProvider<SignalLossMonitorTask> =
        mockk<ObjectProvider<SignalLossMonitorTask>>().apply {
            every { ifAvailable } returns monitor
        }

    @Test
    fun `collect returns monitoringEnabled=false when monitor bean absent`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.cameras.monitoringEnabled).isFalse()
            assertThat(resp.cameras.items).isEmpty()
        }

    @Test
    fun `collect returns monitoringEnabled=true with empty items when snapshot empty`() =
        runBlocking {
            val monitor =
                mockk<SignalLossMonitorTask>().apply {
                    every { snapshotStates() } returns emptyMap()
                }
            val service = StatusService(recordings, lb, monitorProvider(monitor), clock)
            val resp = service.collect()
            assertThat(resp.cameras.monitoringEnabled).isTrue()
            assertThat(resp.cameras.items).isEmpty()
        }

    @Test
    fun `collect maps Healthy and SignalLost to HEALTHY and OFFLINE`() =
        runBlocking {
            val monitor =
                mockk<SignalLossMonitorTask>().apply {
                    every { snapshotStates() } returns
                        mapOf(
                            "cam1" to CameraSignalState.Healthy(now.minusSeconds(5)),
                            "cam2" to CameraSignalState.SignalLost(now.minusSeconds(600), notificationSent = true),
                        )
                }
            val service = StatusService(recordings, lb, monitorProvider(monitor), clock)

            val resp = service.collect()

            assertThat(resp.cameras.monitoringEnabled).isTrue()
            assertThat(resp.cameras.items.map { it.camId }).containsExactly("cam2", "cam1")
            val cam2 = resp.cameras.items[0]
            assertThat(cam2.state).isEqualTo(CameraState.OFFLINE)
            assertThat(cam2.offlineFor).isEqualTo(Duration.ofSeconds(600))
            val cam1 = resp.cameras.items[1]
            assertThat(cam1.state).isEqualTo(CameraState.HEALTHY)
            assertThat(cam1.offlineFor).isNull()
        }

    @Test
    fun `collect sorts detect servers DEAD first then alphabetical`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.detectServers.map { it.id }).containsExactly("srv-a", "srv-b")
        }

    @Test
    fun `collect populates recordings counters and rate`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.recordings.total).isEqualTo(100L)
            assertThat(resp.recordings.processed).isEqualTo(90L)
            assertThat(resp.recordings.unprocessed).isEqualTo(10L)
            assertThat(resp.recordings.processingRatePerMinute).isEqualTo(2.5)
            assertThat(resp.recordings.byCameras.map { it.camId }).containsExactly("cam1", "cam2")
        }
}
```

- [ ] **Step 2: Run test, expect compile failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusServiceTest
```

Expected: FAIL (`StatusService` unresolved).

- [ ] **Step 3: Implement StatusService**

```kotlin
package ru.zinin.frigate.analyzer.core.service

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.task.CameraSignalState
import ru.zinin.frigate.analyzer.core.task.SignalLossMonitorTask
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class StatusService(
    private val recordingRepository: RecordingEntityRepository,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    // Absent when application.signal-loss.enabled=false — see SignalLossMonitorTask @ConditionalOnProperty.
    private val signalLossMonitorTask: ObjectProvider<SignalLossMonitorTask>,
    private val clock: Clock,
) {
    suspend fun collect(): StatusResponse {
        val recordings = buildRecordings()
        val cameras = buildCameras(Instant.now(clock))
        val servers =
            detectServerLoadBalancer
                .getAllServersStatistics()
                .sortedWith(compareBy({ if (it.status == ServerStatus.DEAD) 0 else 1 }, { it.id }))
        return StatusResponse(
            recordings = recordings,
            cameras = cameras,
            detectServers = servers,
        )
    }

    private suspend fun buildRecordings(): RecordingsStatistics {
        val total = recordingRepository.countAll()
        val processed = recordingRepository.countProcessed()
        val unprocessed = recordingRepository.countUnprocessed()
        // Two near-identical types with the same positional fields: `CameraStatisticsDto`
        // (`model.dto`, SQL projection from RecordingEntityRepository) → `CameraStatistics`
        // (`model.response`, JSON contract). Mapping is mandatory to avoid leaking the
        // SQL/R2DBC layer into the response. SQL already orders by `cam_id ASC`, so no
        // additional sort is needed here (see SUGGESTION-4 invariant).
        val byCameras =
            recordingRepository.getStatisticsByCameras().map { dto ->
                CameraStatistics(
                    camId = dto.camId,
                    recordingsCount = dto.recordingsCount,
                    recordingsProcessed = dto.recordingsProcessed,
                    detectionsCount = dto.detectionsCount,
                )
            }
        val rate = recordingRepository.getProcessingRatePerMinuteLast5Minutes()
        return RecordingsStatistics(
            total = total,
            processed = processed,
            unprocessed = unprocessed,
            byCameras = byCameras,
            processingRatePerMinute = rate,
        )
    }

    private fun buildCameras(now: Instant): CamerasSection {
        val monitor = signalLossMonitorTask.ifAvailable
        if (monitor == null) {
            return CamerasSection(monitoringEnabled = false, items = emptyList())
        }
        val items =
            monitor
                .snapshotStates()
                .map { (camId, state) -> toDto(camId, state, now) }
                .sortedWith(compareBy({ if (it.state == CameraState.OFFLINE) 0 else 1 }, { it.camId }))
        return CamerasSection(monitoringEnabled = true, items = items)
    }

    private fun toDto(
        camId: String,
        state: CameraSignalState,
        now: Instant,
    ): CameraStatusDto =
        when (state) {
            is CameraSignalState.Healthy ->
                CameraStatusDto(
                    camId = camId,
                    state = CameraState.HEALTHY,
                    lastSeenAt = state.lastSeenAt,
                    offlineFor = null,
                )

            is CameraSignalState.SignalLost ->
                CameraStatusDto(
                    camId = camId,
                    state = CameraState.OFFLINE,
                    lastSeenAt = state.lastSeenAt,
                    offlineFor = Duration.between(state.lastSeenAt, now).coerceAtLeast(Duration.ZERO),
                )
        }
}
```

- [ ] **Step 4: Run test, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusServiceTest
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt
git commit -m "feat(core): add StatusService aggregating recordings, cameras, servers"
```

---

### Task 6: Create StatusController

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusController.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt`

- [ ] **Step 1: Write failing integration test StatusControllerTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.core.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import ru.zinin.frigate.analyzer.core.IntegrationTestBase

// Note: `IntegrationTestBase` spins up Docker Compose (PostgreSQL + Liquibase) in its
// `companion object init {}`. This test inherits that dependency for parity with all
// other endpoint tests in the project. The ISO-8601 contract is verified independently
// by the DB-free `JacksonConfigurationTest` (Step 1a), so the structural assertions
// below need only confirm the endpoint exists and produces the expected JSON shape.
@AutoConfigureWebTestClient
class StatusControllerTest : IntegrationTestBase() {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `GET status returns 200 with expected structure and ISO-8601 datetimes`() {
        // application.yaml sets `spring.webflux.base-path: /frigate-analyzer` so the test
        // must include the base path to reflect the real external contract.
        webTestClient
            .get()
            .uri("/frigate-analyzer/status")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.recordings.total").isNumber
            .jsonPath("$.recordings.processed").isNumber
            .jsonPath("$.recordings.unprocessed").isNumber
            .jsonPath("$.recordings.byCameras").isArray
            .jsonPath("$.recordings.processingRatePerMinute").isNumber
            .jsonPath("$.cameras.monitoringEnabled").isBoolean
            .jsonPath("$.cameras.items").isArray
            .jsonPath("$.detectServers").isArray
    }
}
```

`@AutoConfigureWebTestClient` is required because `IntegrationTestBase` is
`@SpringBootTest(webEnvironment = RANDOM_PORT)` without WebTestClient autoconfiguration.

If the test DB happens to contain a HEALTHY camera at fixture-prep time, also assert that
`lastSeenAt` is an ISO-8601 string and `offlineFor` (if OFFLINE camera present) matches the
`PT...` pattern. Skip these assertions for cases where the cameras list might be empty —
add a dedicated `JacksonConfigurationTest` instead (Step 1a below).

- [ ] **Step 1a: Add JacksonConfigurationTest for ISO-8601 contract**

Add a focused unit test that does not depend on DB state, asserting our `ObjectMapper` serialises
`Instant` and `Duration` as ISO-8601 strings (this catches `WRITE_DATES_AS_TIMESTAMPS`
regressions independently of `/status` test data):

```kotlin
package ru.zinin.frigate.analyzer.core.config

import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class JacksonConfigurationTest {
    private val mapper = JacksonConfiguration().objectMapper()

    @Test
    fun `Instant serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Instant.parse("2026-04-25T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-04-25T10:00:00Z\"")
    }

    @Test
    fun `Duration serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Duration.ofMinutes(7))
        assertThat(json).isEqualTo("\"PT7M\"")
    }
}
```

- [ ] **Step 2: Run test, expect failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusControllerTest
```

Expected: FAIL — endpoint not found (404), or compile error since `StatusController` doesn't exist.

- [ ] **Step 3: Implement StatusController**

```kotlin
package ru.zinin.frigate.analyzer.core.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.model.response.StatusResponse

private val logger = KotlinLogging.logger {}

@Tag(name = "StatusController", description = "API for retrieving system status")
@RequestMapping("/status")
@RestController
class StatusController(
    private val statusService: StatusService,
) {
    @Operation(summary = "Get system status", method = "GET")
    @ApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = StatusResponse::class))],
        description = "Recordings, cameras signal status, detect servers",
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getStatus(): StatusResponse {
        logger.debug { "Fetching /status" }
        return statusService.collect()
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusControllerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusController.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt
git commit -m "feat(core): add GET /status REST endpoint"
```

---

### Task 7: Add i18n keys for /status

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`

**Note on placeholder indices and id duplication:** `camId` and server `id` are NOT passed as
MessageFormat arguments — the formatter prepends them once before the i18n-rendered fragment
(`escape(camPadded)` / `escape(idPadded)`). So all placeholders in `status.cameras.line.*` and
`status.servers.line.*` are renumbered starting at `{0}`.

**Note on encoding:** `messages_ru.properties` is UTF-8 with raw Cyrillic — that is the existing
file convention (see `common.cancel=Отмена`). Add the new Russian keys the same way; do NOT
convert to `\uXXXX` escapes.

- [ ] **Step 1: Append to messages_en.properties**

Append at the end of the file (after the existing `/notifications` block):

```properties

# /status
command.status.description=System status
status.title=Frigate Analyzer status
status.section.recordings=Recordings
status.section.byCamera=By camera
status.section.cameras=Cameras
status.section.servers=Detect servers
status.recordings.label.total=Total
status.recordings.label.processed=Processed
status.recordings.label.unprocessed=Unprocessed
status.recordings.label.rate=Rate (5 min)
status.recordings.value.processed={0} ({1}%)
status.recordings.value.rate={0} rec/min
status.byCamera.header.cam=cam
status.byCamera.header.rec=rec
status.byCamera.header.proc=proc
status.byCamera.header.det=det
status.cameras.line.online=online ({0} ago)
status.cameras.line.offline=offline {0} (last {1})
status.cameras.empty=No cameras observed yet
status.cameras.disabled=Monitoring disabled (set SIGNAL_LOSS_ENABLED=true to enable)
status.servers.line.alive=ALIVE  frame {0}/{1}  ext {2}/{3}  vis {4}/{5}  vvis {6}/{7}
status.servers.line.dead=DEAD
status.empty=(none)
```

- [ ] **Step 2: Append to messages_ru.properties (UTF-8 with raw Cyrillic — NO \uXXXX escapes)**

```properties

# /status
command.status.description=Статус системы
status.title=Статус Frigate Analyzer
status.section.recordings=Записи
status.section.byCamera=По камерам
status.section.cameras=Камеры
status.section.servers=Серверы детекции
status.recordings.label.total=Всего
status.recordings.label.processed=Обработано
status.recordings.label.unprocessed=Не обработано
status.recordings.label.rate=Скорость (5 мин)
status.recordings.value.processed={0} ({1}%)
status.recordings.value.rate={0} зап/мин
status.byCamera.header.cam=кам
status.byCamera.header.rec=зап
status.byCamera.header.proc=обр
status.byCamera.header.det=дет
status.cameras.line.online=онлайн ({0} назад)
status.cameras.line.offline=оффлайн {0} (последняя {1})
status.cameras.empty=Нет наблюдаемых камер
status.cameras.disabled=Мониторинг отключён (установите SIGNAL_LOSS_ENABLED=true)
status.servers.line.alive=ALIVE  frame {0}/{1}  ext {2}/{3}  vis {4}/{5}  vvis {6}/{7}
status.servers.line.dead=DEAD
status.empty=(нет)
```

- [ ] **Step 3: Commit**

```bash
git add modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/main/resources/messages_ru.properties
git commit -m "i18n(telegram): add status.* keys for /status command"
```

---

### Task 8: Delete StatisticsController and StatisticsResponse

Now that `StatusController` is live, the old `StatisticsController` can be removed safely.

**Files:**
- Delete: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatisticsController.kt`
- Delete: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt`

- [ ] **Step 1: Search for stale references (broad scope)**

Run from repo root — include source, config, docs, scripts, and Java files (for OpenAPI/Swagger):

```bash
grep -rn "StatisticsController\|StatisticsResponse\|/statistics" \
    --include="*.kt" --include="*.kts" --include="*.java" \
    --include="*.yaml" --include="*.yml" --include="*.properties" \
    --include="*.md" --include="*.sh" --include="*.json" \
    --exclude-dir=build --exclude-dir=.gradle .
```

Expected categories:
- Declarations inside the two soon-to-be-deleted files — ignore.
- Historical references in `docs/incidents/**` — leave as historical record (no need to rewrite past incident docs).
- Any operational scripts or active docs referencing `/statistics` — update to `/status` or remove.
- OpenAPI/Swagger `@Schema(implementation = StatisticsResponse::class)` in Java/Kotlin — must be 0 hits outside the deleted files.

If unexpected hits appear in active code/config — stop and investigate before deleting.

- [ ] **Step 2: Delete the files**

```bash
git rm modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatisticsController.kt
git rm modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt
```

- [ ] **Step 3: Compile check (per-module, not root)** — delegate to build-runner agent:

```
./gradlew :frigate-analyzer-model:compileKotlin :frigate-analyzer-core:compileKotlin :frigate-analyzer-telegram:compileKotlin
```

`./gradlew compileKotlin` at root only triggers the root project's task and would miss
breakage inside submodules. The explicit list above compiles each subproject.

Expected: success.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor: remove /statistics endpoint superseded by /status"
```

---

### Task 9: Create StatusMessageFormatter

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt`

- [ ] **Step 1: Write failing test StatusMessageFormatterTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerLoad
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusMessageFormatterTest {
    private val msg =
        mockk<MessageResolver>().apply {
            // Identity-style stubs — return key for plain keys, "{val}" for parametric.
            every { get(any(), "en") } answers { firstArg<String>() }
            every { get(any(), "en", *anyVararg()) } answers {
                val key = firstArg<String>()
                val args = thirdArg<Array<*>>().joinToString(",")
                "$key[$args]"
            }
            // Duration buckets used by SignalLossMessageFormatter
            every { get("signal.duration.seconds", "en", any()) } answers {
                "${arg<Array<*>>(2)[0]} sec"
            }
            every { get("signal.duration.minutes", "en", any()) } answers {
                "${arg<Array<*>>(2)[0]} min"
            }
            every { get("signal.duration.hours", "en", any(), any()) } answers {
                "${arg<Array<*>>(2)[0]} h ${arg<Array<*>>(2)[1]} min"
            }
            every { get("signal.duration.days", "en", any(), any()) } answers {
                "${arg<Array<*>>(2)[0]} d ${arg<Array<*>>(2)[1]} h"
            }
        }
    private val duration = SignalLossMessageFormatter(msg)
    private val formatter = StatusMessageFormatter(msg, duration)

    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val zone = ZoneId.of("UTC")

    private fun snapshot(
        camerasEnabled: Boolean = true,
        cameras: List<CameraStatusDto> = emptyList(),
    ): StatusResponse =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 100L,
                    processed = 90L,
                    unprocessed = 10L,
                    byCameras =
                        listOf(
                            CameraStatistics("cam1", 50, 50, 5),
                            CameraStatistics("cam2", 50, 40, 3),
                        ),
                    processingRatePerMinute = 2.5,
                ),
            cameras = CamerasSection(monitoringEnabled = camerasEnabled, items = cameras),
            detectServers =
                listOf(
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.DEAD,
                        frameRequests = ServerLoad(0, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                ),
        )

    @Test
    fun `format escapes HTML special chars in camId`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam<&>",
                    state = CameraState.HEALTHY,
                    lastSeenAt = now.minusSeconds(2),
                    offlineFor = null,
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        assertTrue(out.contains("cam&lt;&amp;&gt;"), "expected escaped camId in: $out")
        assertFalse(out.contains("cam<&>"), "raw < & > leaked: $out")
    }

    @Test
    fun `format renders disabled monitoring marker when monitoringEnabled=false`() {
        val out = formatter.format(snapshot(camerasEnabled = false), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.cameras.disabled"), "missing disabled marker in: $out")
    }

    @Test
    fun `format renders empty marker when monitoringEnabled=true and items empty`() {
        val out = formatter.format(snapshot(camerasEnabled = true, cameras = emptyList()), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.cameras.empty"), "missing empty marker in: $out")
    }

    @Test
    fun `format produces non-empty HTML with all four section titles`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam1",
                    state = CameraState.OFFLINE,
                    lastSeenAt = now.minusSeconds(600),
                    offlineFor = Duration.ofSeconds(600),
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.title"))
        assertTrue(out.contains("status.section.recordings"))
        assertTrue(out.contains("status.section.byCamera"))
        assertTrue(out.contains("status.section.cameras"))
        assertTrue(out.contains("status.section.servers"))
        assertTrue(out.contains("<pre>"))
    }

    @Test
    fun `format respects user timezone in offline last-seen rendering`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam1",
                    state = CameraState.OFFLINE,
                    lastSeenAt = Instant.parse("2026-04-25T15:31:22Z"),
                    offlineFor = Duration.ofMinutes(7),
                ),
            )
        val moscow = ZoneId.of("Europe/Moscow") // UTC+3
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = moscow, now = now)
        assertTrue(out.contains("18:31:22"), "expected zone-shifted time in: $out")
    }

    @Test
    fun `format renders DEAD servers using dead line key`() {
        val out = formatter.format(snapshot(), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.servers.line.dead"), "missing dead line key in: $out")
        assertEquals(1, out.split("status.servers.line.dead").size - 1)
    }

    @Test
    fun `format does not duplicate camId or server id`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam-unique",
                    state = CameraState.OFFLINE,
                    lastSeenAt = now.minusSeconds(120),
                    offlineFor = Duration.ofSeconds(120),
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        // camId should appear exactly once (no MessageFormat duplication)
        assertEquals(1, out.split("cam-unique").size - 1, "camId duplicated in: $out")
        // server id "srv-a" (DEAD in snapshot()) should appear exactly once
        assertEquals(1, out.split("srv-a").size - 1, "server id duplicated in: $out")
    }

    @Test
    fun `format escapes HTML special chars in byCameras camId`() {
        // Build a snapshot whose byCameras list contains a camId with HTML special chars.
        val snap =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 1,
                        processed = 1,
                        unprocessed = 0,
                        byCameras =
                            listOf(
                                CameraStatistics(camId = "cam<&>", recordingsCount = 1, recordingsProcessed = 1, detectionsCount = 0),
                            ),
                        processingRatePerMinute = 0.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers = emptyList(),
            )
        val out = formatter.format(snap, language = "en", zone = zone, now = now)
        assertTrue(out.contains("cam&lt;&amp;&gt;"), "expected escaped camId in byCameras: $out")
        assertFalse(out.contains("cam<&>"), "raw < & > leaked in byCameras: $out")
    }

    @Test
    fun `format escapes HTML special chars in server id`() {
        val snap =
            StatusResponse(
                recordings = RecordingsStatistics(0, 0, 0, emptyList(), 0.0),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers =
                    listOf(
                        DetectServerStatistics(
                            id = "srv<a>",
                            status = ServerStatus.ALIVE,
                            frameRequests = ServerLoad(1, 4),
                            frameExtractionRequests = ServerLoad(0, 2),
                            visualizeRequests = ServerLoad(0, 1),
                            videoVisualizeRequests = ServerLoad(0, 1),
                        ),
                    ),
            )
        val out = formatter.format(snap, language = "en", zone = zone, now = now)
        assertTrue(out.contains("srv&lt;a&gt;"), "expected escaped server id: $out")
        assertFalse(out.contains("srv<a>"), "raw < > leaked in server id: $out")
    }
}
```

**Plus add a real-bundle i18n integration test** (catches placeholder-index regressions of the
kind that almost shipped in CRITICAL-2). Sibling test class in the same file
`StatusMessageFormatterTest.kt`, using `kotlin.test` API (the `telegram` module's test
dependencies are only `kotlin.test.junit5`, `coroutines.test`, and `mockk` — no AssertJ,
no `spring-boot-starter-test`). The `MessageSource` setup mirrors `MessageResolverTest.kt:8-17`:

```kotlin
class StatusMessageFormatterI18nTest {
    private val messageSource =
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        }
    private val realMessageResolver = MessageResolver(messageSource)
    private val duration = SignalLossMessageFormatter(realMessageResolver)
    private val formatter = StatusMessageFormatter(realMessageResolver, duration)

    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun sampleSnapshot(): StatusResponse =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 0,
                    processed = 0,
                    unprocessed = 0,
                    byCameras = emptyList(),
                    processingRatePerMinute = 0.0,
                ),
            cameras =
                CamerasSection(
                    monitoringEnabled = true,
                    items =
                        listOf(
                            CameraStatusDto(
                                camId = "cam1",
                                state = CameraState.OFFLINE,
                                lastSeenAt = Instant.parse("2026-04-25T09:53:00Z"),
                                offlineFor = Duration.ofMinutes(7),
                            ),
                        ),
                ),
            detectServers =
                listOf(
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.ALIVE,
                        frameRequests = ServerLoad(1, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                ),
        )

    @Test
    fun `real bundle EN renders offline line with both duration and last-seen`() {
        val out = formatter.format(sampleSnapshot(), language = "en", zone = zone, now = now)

        // Catch CRITICAL-2 (iter-1) regressions: ensure offline line has duration AND last-seen.
        assertTrue(out.contains("offline 7 min"), "expected `offline 7 min` in: $out")
        assertTrue(out.contains("(last 09:53:00)"), "expected `(last 09:53:00)` in: $out")
        // Catch server-id placeholder shift: ALIVE line must contain "frame 1/4", not "frame srv-a/1".
        assertTrue(out.contains("frame 1/4"), "expected `frame 1/4` in: $out")
        assertFalse(out.contains("frame srv-a"), "server id leaked into ALIVE placeholders: $out")
    }

    @Test
    fun `real bundle RU renders offline line with duration and last-seen`() {
        val out = formatter.format(sampleSnapshot(), language = "ru", zone = zone, now = now)

        // Russian offline text — keys: status.cameras.line.offline=оффлайн {0} (последняя {1})
        // SignalLossMessageFormatter.formatDuration уже даёт "7 мин" для Duration.ofMinutes(7).
        assertTrue(out.contains("оффлайн 7 мин"), "expected `оффлайн 7 мин` in: $out")
        assertTrue(out.contains("(последняя 09:53:00)"), "expected `(последняя 09:53:00)` in: $out")
        // ALIVE line is language-agnostic (technical labels frame/ext/vis/vvis stay in English).
        assertTrue(out.contains("frame 1/4"), "expected `frame 1/4` in: $out")
        assertFalse(out.contains("frame srv-a"), "server id leaked into ALIVE placeholders: $out")
        // Section titles localised.
        assertTrue(out.contains("Статус Frigate Analyzer"), "expected RU title in: $out")
    }
}
```

Imports for this class (sibling-class file `StatusMessageFormatterTest.kt`, append to the
existing imports):

```kotlin
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertFalse
```

Mocked-MessageResolver unit tests remain useful for structural assertions (escape, padding,
sections, DEAD vs ALIVE). Together they form a layered defence: mocked = fast structural checks,
real bundle = i18n contract validation.

- [ ] **Step 2: Run test, expect compile failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-telegram:test --tests "StatusMessageFormatter*Test"
```

Expected: FAIL — `StatusMessageFormatter` unresolved.

- [ ] **Step 3: Implement StatusMessageFormatter**

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusMessageFormatter(
    private val msg: MessageResolver,
    private val duration: SignalLossMessageFormatter,
) {
    fun format(
        snapshot: StatusResponse,
        language: String,
        zone: ZoneId,
        now: Instant,
    ): String =
        buildString {
            appendLine("📊 <b>${escape(msg.get("status.title", language))}</b>")
            appendLine()
            appendRecordings(snapshot.recordings, language)
            appendLine()
            appendByCamera(snapshot.recordings.byCameras, language)
            appendLine()
            appendCameras(snapshot.cameras, language, zone, now)
            appendLine()
            appendServers(snapshot.detectServers, language)
        }.trimEnd()

    private fun StringBuilder.appendRecordings(
        r: RecordingsStatistics,
        language: String,
    ) {
        appendLine("📹 <b>${escape(msg.get("status.section.recordings", language))}</b>")
        val pct =
            if (r.total > 0) {
                "%.1f".format(Locale.ROOT, r.processed.toDouble() * 100.0 / r.total.toDouble())
            } else {
                "0.0"
            }
        val rateFormatted = "%.1f".format(Locale.ROOT, r.processingRatePerMinute)
        val rows =
            listOf(
                msg.get("status.recordings.label.total", language) to r.total.toString(),
                msg.get("status.recordings.label.processed", language) to
                    msg.get("status.recordings.value.processed", language, r.processed.toString(), pct),
                msg.get("status.recordings.label.unprocessed", language) to r.unprocessed.toString(),
                msg.get("status.recordings.label.rate", language) to
                    msg.get("status.recordings.value.rate", language, rateFormatted),
            )
        val labelWidth = rows.maxOf { it.first.length }
        val valueWidth = rows.maxOf { it.second.length }
        appendPreBlock(
            rows.map { (l, v) ->
                "${l.padEnd(labelWidth + 1)} ${v.padStart(valueWidth)}"
            },
        )
    }

    private fun StringBuilder.appendByCamera(
        cams: List<CameraStatistics>,
        language: String,
    ) {
        appendLine("📹 <b>${escape(msg.get("status.section.byCamera", language))}</b>")
        if (cams.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.empty", language))))
            return
        }
        val headers =
            listOf(
                msg.get("status.byCamera.header.cam", language),
                msg.get("status.byCamera.header.rec", language),
                msg.get("status.byCamera.header.proc", language),
                msg.get("status.byCamera.header.det", language),
            )
        val rows =
            cams.map { c ->
                listOf(
                    c.camId,
                    c.recordingsCount.toString(),
                    c.recordingsProcessed.toString(),
                    c.detectionsCount.toString(),
                )
            }
        // Compute column widths on RAW (pre-escape) lengths. In <pre>, `&lt;` renders as `<`
        // (1 visual char) but the escaped string is longer in char count — so padding must use
        // raw lengths and escape must be applied AFTER padding, not before.
        val widths =
            (0 until 4).map { col ->
                (rows.map { it[col].length } + headers[col].length).max()
            }
        val lines = mutableListOf<String>()
        lines.add(formatRow(headers, widths, leftAlignCol = 0))
        rows.forEach { row ->
            val padded =
                row.mapIndexed { i, c ->
                    if (i == 0) c.padEnd(widths[i]) else c.padStart(widths[i])
                }
            // Only camId (column 0) is user-derived — escape it; numeric columns are safe.
            val cells = padded.mapIndexed { i, c -> if (i == 0) escape(c) else c }
            lines.add(cells.joinToString(" | "))
        }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendCameras(
        cameras: CamerasSection,
        language: String,
        zone: ZoneId,
        now: Instant,
    ) {
        appendLine("📷 <b>${escape(msg.get("status.section.cameras", language))}</b>")
        if (!cameras.monitoringEnabled) {
            appendPreBlock(listOf(escape(msg.get("status.cameras.disabled", language))))
            return
        }
        if (cameras.items.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.cameras.empty", language))))
            return
        }
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.forLanguageTag(language))
        val camWidth = cameras.items.maxOf { it.camId.length }
        val lines =
            cameras.items.map { item ->
                val camPadded = item.camId.padEnd(camWidth)
                when (item.state) {
                    CameraState.HEALTHY -> {
                        val ago =
                            duration.formatDuration(
                                Duration.between(item.lastSeenAt, now).coerceAtLeast(Duration.ZERO),
                                language,
                            )
                        // camId is rendered ONCE outside MessageFormat (renumbered keys: {0}=ago).
                        // No escape() on `line` — it's a composition of trusted sources:
                        //   i18n template (our properties), formatDuration() output (digits + "min"/"h"),
                        //   DateTimeFormatter result (digits + ":"). User-derived `camPadded` is escaped above.
                        val line = msg.get("status.cameras.line.online", language, ago)
                        "🟢 ${escape(camPadded)}  $line"
                    }

                    CameraState.OFFLINE -> {
                        // `StatusService.toDto()` guarantees offlineFor != null for OFFLINE.
                        // Fail loud here if the contract is broken rather than silently masking the bug.
                        val offlineFor =
                            requireNotNull(item.offlineFor) {
                                "offlineFor must not be null for OFFLINE camera ${item.camId} " +
                                    "(StatusService.toDto contract violation)"
                            }.coerceAtLeast(Duration.ZERO)
                        val lastSeen = item.lastSeenAt.atZone(zone).format(timeFormatter)
                        // Renumbered keys: {0}=duration, {1}=lastSeen.
                        val line =
                            msg.get(
                                "status.cameras.line.offline",
                                language,
                                duration.formatDuration(offlineFor, language),
                                lastSeen,
                            )
                        "🔴 ${escape(camPadded)}  $line"
                    }
                }
            }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendServers(
        servers: List<DetectServerStatistics>,
        language: String,
    ) {
        appendLine("🖥️ <b>${escape(msg.get("status.section.servers", language))}</b>")
        if (servers.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.empty", language))))
            return
        }
        val idWidth = servers.maxOf { it.id.length }
        val lines =
            servers.map { s ->
                val idPadded = s.id.padEnd(idWidth)
                // Renumbered keys: server id rendered ONCE via escape(idPadded); ALIVE template
                // uses {0..7} for frame/ext/vis/vvis current/max; DEAD template has no placeholders.
                val tail =
                    when (s.status) {
                        ServerStatus.ALIVE ->
                            msg.get(
                                "status.servers.line.alive",
                                language,
                                s.frameRequests.current,
                                s.frameRequests.maximum,
                                s.frameExtractionRequests.current,
                                s.frameExtractionRequests.maximum,
                                s.visualizeRequests.current,
                                s.visualizeRequests.maximum,
                                s.videoVisualizeRequests.current,
                                s.videoVisualizeRequests.maximum,
                            )

                        ServerStatus.DEAD ->
                            msg.get("status.servers.line.dead", language)
                    }
                val marker = if (s.status == ServerStatus.ALIVE) "🟢" else "🔴"
                // No escape() on `tail` — composed from trusted i18n + integers.
                // User-derived `idPadded` is escaped above.
                "$marker ${escape(idPadded)}  $tail"
            }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendPreBlock(lines: List<String>) {
        append("<pre>")
        append(lines.joinToString("\n"))
        appendLine("</pre>")
    }

    private fun formatRow(
        cells: List<String>,
        widths: List<Int>,
        leftAlignCol: Int,
    ): String =
        cells
            .mapIndexed { i, c -> if (i == leftAlignCol) c.padEnd(widths[i]) else c.padStart(widths[i]) }
            .joinToString(" | ")

    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
```

- [ ] **Step 4: Run test, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-telegram:test --tests "StatusMessageFormatter*Test"
```

Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt
git commit -m "feat(telegram): add StatusMessageFormatter for /status HTML rendering"
```

---

### Task 10: Create StatusCommandHandler (in `core` module)

**Important:** `StatusCommandHandler` lives in `modules/core` (NOT `modules/telegram`). The
`telegram` module does NOT depend on `core`, but `core → telegram`, so the handler is placed in
`core` to import `StatusService`. `CommandHandler` interface is imported from `telegram`.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt`

- [ ] **Step 1: Write failing test StatusCommandHandlerTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.core.bot.handler

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StatusCommandHandlerTest {
    private val statusService = mockk<StatusService>()
    private val formatter = mockk<StatusMessageFormatter>()
    private val userService = mockk<TelegramUserService>()
    private val clock = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC)
    private val handler = StatusCommandHandler(statusService, formatter, userService, clock)

    @Test
    fun `handler has correct command metadata`() {
        assertThat(handler.command).isEqualTo("status")
        assertThat(handler.requiredRole).isEqualTo(UserRole.OWNER)
        assertThat(handler.ownerOnly).isTrue()
        assertThat(handler.order).isEqualTo(8)
    }
}
```

Behavior-level integration is exercised in `StatusMessageFormatterTest` and `StatusServiceTest`.
We do not unit-test the actual `reply()` call because it goes through ktgbotapi's
`BehaviourContext`, which is hard to mock in isolation. Manual sanity check covers this —
see Task 12. Note we also do NOT add an error-path test asserting `common.error.generic`:
`FrigateAnalyzerBot.registerRoutes()` wraps handler dispatch in try/catch at the router level
(see existing handlers like `VersionCommandHandler` which have no local try/catch).

- [ ] **Step 2: Run test, expect compile failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusCommandHandlerTest
```

Expected: FAIL — `StatusCommandHandler` unresolved.

- [ ] **Step 3: Implement StatusCommandHandler in `core/bot/handler`**

```kotlin
package ru.zinin.frigate.analyzer.core.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusCommandHandler(
    private val statusService: StatusService,
    private val formatter: StatusMessageFormatter,
    private val userService: TelegramUserService,
    private val clock: Clock,
) : CommandHandler {
    override val command: String = "status"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 8

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val snapshot = statusService.collect()
        val zone = userService.getUserZone(message.chat.id.chatId.long)
        val language = user?.languageCode ?: "en"
        val text =
            formatter.format(
                snapshot = snapshot,
                language = language,
                zone = zone,
                now = Instant.now(clock),
            )
        sendTextMessage(
            message.chat,
            text,
            parseMode = HTMLParseMode,
            replyParameters = ReplyParameters(message.metaInfo),
        )
    }
}
```

User zone comes from `TelegramUserService.getUserZone(chatId)` — same pattern used by
`TimezoneCommandHandler` and `ExportCommandHandler`. `TelegramUserDto` does NOT contain
`olsonCode`, only `languageCode`. `order = 8` is the first unused slot after Notifications=7
(value 6 is taken by `LanguageCommandHandler`). `Clock` is injected so tests see the same
`now` as `StatusService`.

**Send-API choice:** `sendTextMessage(chat, ..., replyParameters = ReplyParameters(message.metaInfo))`
is the same pattern used by `TimezoneCommandHandler` and `TelegramNotificationSender` (verified to
work with the project's ktgbotapi version). The simpler `reply(message, text, parseMode = ...)`
overload is **not used anywhere in the project with `parseMode`** — `VersionCommandHandler` uses
`reply(message, text)` without parseMode. To avoid a runtime-only contingency, we adopt the
known-good API up front.

- [ ] **Step 4: Run test, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusCommandHandlerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt
git commit -m "feat(core): add /status command handler (OWNER only, in core module)"
```

---

### Task 10b: Configure ObjectMapper for ISO-8601 (Instant/Duration)

`JacksonConfiguration.objectMapper()` does NOT currently disable
`WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS`, so `Instant`/`Duration` in
`StatusResponse` would be serialised as numeric timestamps instead of the ISO-8601 strings
promised by the design. Fix:

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt`

- [ ] **Step 1: Update JacksonConfiguration**

```kotlin
package ru.zinin.frigate.analyzer.core.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

- [ ] **Step 2: JacksonConfigurationTest already added in Task 6 Step 1a** — run it now:

```
./gradlew :frigate-analyzer-core:test --tests JacksonConfigurationTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfigurationTest.kt
git commit -m "fix(core): serialise Instant/Duration as ISO-8601 strings (not numeric timestamps)"
```

---

### Task 11: Full build + ktlint

Verify the whole project compiles and tests pass.

- [ ] **Step 1: Run ktlint format** — delegate to build-runner agent:

```
./gradlew ktlintFormat
```

Expected: success. Stage any formatting changes.

- [ ] **Step 2: Stage any formatting changes**

```bash
git status --short
git add -u
```

If there are changes (e.g. import reordering), commit:

```bash
git commit -m "style: apply ktlintFormat to /status changes"
```

If nothing changed, skip the commit step.

- [ ] **Step 3: Full build** — delegate to build-runner agent:

```
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

If failures occur:
- Compile errors → fix and re-run
- Test failures → fix the underlying issue (not the test) and re-run
- ktlint check failures → run `./gradlew ktlintFormat` and re-stage

---

### Task 12: Manual sanity check

Verify the feature end-to-end against a running app. This is **non-optional** per project rule: "If you can't test the UI, say so explicitly rather than claiming success."

- [ ] **Step 1: Start the app locally**

Outside the agentic flow. Run with telegram + signal-loss enabled, pointing at a dev DB:

```bash
./gradlew :frigate-analyzer-core:bootRun
```

- [ ] **Step 2: REST sanity check**

```bash
curl -sS http://localhost:8080/frigate-analyzer/status | jq .
```

Expected response structure:
- `recordings.total`, `recordings.processed`, `recordings.unprocessed`, `recordings.byCameras[]`, `recordings.processingRatePerMinute`
- `cameras.monitoringEnabled = true`, `cameras.items[]` (sorted OFFLINE first, then by camId)
- `detectServers[]` (sorted DEAD first, then by id)

Also verify the old endpoint returns 404:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/frigate-analyzer/statistics
# expected: 404
```

- [ ] **Step 3: Telegram sanity check**

In the Telegram chat with the bot, as OWNER:

1. Send `/status`. Expect a single HTML-formatted message with the four sections (Recordings, By camera, Cameras, Detect servers). `<pre>` blocks should render in monospaced font with aligned columns.
2. Confirm camera with simulated signal loss appears with 🔴 and "offline X" + last-seen time in the user's timezone (set via `/timezone` first if needed).
3. As a non-owner USER, send `/status`. Expect "This command is available to the owner only." reply.

- [ ] **Step 4: Help check**

Send `/help` as OWNER. Verify `/status` appears under "👑 Owner commands:" section.

- [ ] **Step 5: Signal-loss disabled check**

Restart app with `SIGNAL_LOSS_ENABLED=false`. Send `/status`. Expect the Cameras section to show "Monitoring disabled (set SIGNAL_LOSS_ENABLED=true to enable)".

- [ ] **Step 6: Report**

Stop the app. If any step failed, fix and re-run from Step 1. If all six steps passed, proceed to Task 13.

---

### Task 13: Code review

Per project CLAUDE.md: "After implementation: run superpowers:code-reviewer agent first. Fix critical comments, repeat until clean."

- [ ] **Step 1: Run code review**

Invoke `superpowers:requesting-code-review` skill targeting the branch diff against `master`. Address all CRITICAL and HIGH-severity findings inline (apply fixes, re-run affected tests, commit).

- [ ] **Step 2: Re-run build after fixes** — delegate to build-runner agent:

```
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Final status**

```bash
git log --oneline master..HEAD
git diff --stat master..HEAD
```

Verify the branch contains a coherent set of commits ready for PR review.

Note: per global CLAUDE.md, before creating a PR you must `git rm` files in `docs/superpowers/` and commit — those design/plan docs are not meant to appear in the PR diff.

---

## Self-Review (updated after review iter-1)

### Iter-1 review impact summary

Auto-fixes applied from external review (22 issues):

- CRITICAL: olsonCode → `TelegramUserService.getUserZone()` (Task 10); placeholder-index renumbering across `status.cameras.line.*` / `status.servers.line.*` keys + code (Task 7, 9); handler moved to `core` module (Task 10); `order = 8` (Task 10); Jackson `WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS` disabled (Task 10b); `messages_ru.properties` encoding note corrected to UTF-8 (Task 7); REST `/status` explicitly documented as public (design).
- CONCERN: ISO-8601 assertions added via dedicated `JacksonConfigurationTest` (Task 6 Step 1a); defensive-copy mutation test added (Task 4 Step 3a); router-level try/catch documented (Task 10 — no local error handling needed); test gaps closed (id-non-duplication test, OFFLINE-after-clock-skew via `coerceAtLeast`); `Clock` injected into handler for `now` consistency (Task 10); per-module compileKotlin in Task 8; broader grep scope in Task 8.
- SUGGESTION: env-var form in `status.cameras.disabled` text; `status.empty` i18n key replacing hardcoded `"(none)"`; `coerceAtLeast(Duration.ZERO)` in formatter fallback.

Deferred to disputed-issue discussion (see iter-1 review log): WebTestClient strategy, 4096-char Telegram limit, HTML double-escape audit scope, formatter test strategy (mocks vs real bundle), `lastUpdatedAt` field addition.

### Spec coverage

| Spec requirement | Task | Status |
|---|---|---|
| Replace REST `/statistics` with `/status` | Task 6 (create), Task 8 (delete) | ✓ |
| Add `cameras` field with HEALTHY/OFFLINE/lastSeenAt/offlineFor | Task 2 + Task 3 + Task 5 | ✓ |
| Telegram OWNER-only `/status` command | Task 10 | ✓ |
| HTML format with `<pre>` tables | Task 9 | ✓ |
| `SignalLossMonitorTask.snapshotStates()` | Task 4 | ✓ |
| `StatusService` shared between REST and Telegram | Task 5 + 6 + 10 | ✓ |
| Sorting: OFFLINE first, DEAD first | Task 5 (`compareBy` predicates) | ✓ |
| i18n ru+en keys `status.*` | Task 7 | ✓ |
| `signal-loss.enabled=false` → `monitoringEnabled=false` + disabled marker | Task 5 (logic) + Task 9 (render) + Task 12 step 5 (sanity) | ✓ |
| HTML escape camId / id | Task 9 (`escape()` helper + test) | ✓ |
| Use `user.olsonCode` for time formatting | Task 10 + Task 9 (test for tz) | ✓ |
| `command.status.description` in i18n | Task 7 | ✓ |
| Tests: StatusServiceTest, StatusControllerTest, snapshotStates test, StatusMessageFormatterTest, StatusCommandHandlerTest | Tasks 4–10 | ✓ |
| No new env vars or DB migrations | n/a (out of scope) | ✓ |

### Placeholder scan

- No "TBD", "TODO", "fill in later", "implement appropriately" found.
- Every code step contains full code, not "similar to above".
- Task 10 Step 4 has a fallback for ktgbotapi `reply` signature variation — this is documented contingency, not a placeholder.

### Type consistency

- `StatusService.collect(): StatusResponse` — consistent across Tasks 5, 6, 10.
- `StatusMessageFormatter.format(snapshot, language, zone, now)` — consistent in Tasks 9 and 10.
- `CameraStatusDto(camId, state, lastSeenAt, offlineFor)` — consistent in Tasks 2, 5, 9, 10.
- `SignalLossMonitorTask.snapshotStates(): Map<String, CameraSignalState>` — consistent in Tasks 4 and 5.
- `CamerasSection(monitoringEnabled, items)` — consistent in Tasks 3, 5, 9.
- i18n keys defined in Task 7 are used verbatim in Task 9 formatter — names match (`status.cameras.line.online`, `status.cameras.line.offline`, `status.servers.line.alive`, `status.servers.line.dead`, etc.).

No inconsistencies found.
