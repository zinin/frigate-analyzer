# WatchRecordsTask Supervisor + Health + Startup Notification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрыть архитектурный bug `WatchRecordsTask` (silent death на любом необработанном исключении), повторно срабатывавший в инцидентах [2026-05-17](../../incidents/2026-05-17-postgres-corruption.md) и [2026-05-23](../../incidents/2026-05-23-sata-cable-corruption.md), путём введения coroutine-supervisor с retry/backoff, Spring `HealthIndicator` (отвечающий за docker auto-restart на permanent ошибки), и одного startup-уведомления владельцу бота (косвенный сигнал о частоте рестартов).

**Architecture:** `WatchRecordsTask` переписан как Spring `@Component` с `@PostConstruct`/`@PreDestroy` — запускает coroutine на dedicated `Dispatchers.IO.limitedParallelism(1)` scope. Одна итерация цикла вынесена в `WatchRecordsLoop` (stateless logic). Supervisor catch'ает любое не-Cancellation исключение, делает exponential backoff (5s → 60s, reset после 5 успехов), при `ClosedWatchServiceException` пересоздаёт `WatchService`. `WatchRecordsTaskHealthIndicator` отдаёт UP/OUT_OF_SERVICE/DOWN на основе времени последней успешной итерации — попадает в общий `/actuator/health`, при DOWN docker healthcheck помечает контейнер unhealthy и `restart: unless-stopped` рестартует. `StartupTelegramNotifier` на `ApplicationReadyEvent` шлёт владельцу одно сообщение через расширенный `TelegramNotificationService.sendOwnerMessage(text)`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3 (WebFlux, Actuator), Kotlinx Coroutines, JUnit 5, MockK 1.14.9, kotlinx-coroutines-test (virtual time).

**Spec reference:** [`docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md`](../specs/2026-05-23-watch-records-supervisor-design.md). Каждый task ссылается на конкретные секции spec'а.

---

## File Structure

### Modify

| File | Что меняется |
|---|---|
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt` | Rewrite: убрать `@Async`, поставить `@PostConstruct`/`@PreDestroy`, добавить coroutine supervisor, state-поля для health, метод `computeHealth(now)`. Pure-функции `extractDateFromPath` / `isWithinWatchPeriod` переезжают в `WatchRecordsLoop.kt`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/ApplicationListener.kt` | Убрать вызов `watchRecordsTask.run()` из `initializeApplication()` и весь handler `ContextClosedEvent` (lifecycle теперь через `@PostConstruct`/`@PreDestroy`). |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt` | Добавить `suspend fun sendOwnerMessage(text: String)`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` | Реализовать `sendOwnerMessage` через `userService.findActiveByUsername(properties.owner)` + `SimpleTextNotificationTask` + `notificationQueue.enqueue`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt` | Пустой `override suspend fun sendOwnerMessage(text: String)`. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt` | Pure-fn тесты переезжают в `WatchRecordsLoopTest.kt`. На их месте — supervisor tests. |
| `.claude/rules/pipeline.md` | Раздел `## File Watching` — описать supervisor, health-indicator, startup-notification. |

### Create

| File | Назначение |
|---|---|
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt` | Stateless logic одной итерации цикла. Метод `runIteration(...)` возвращает `IterationResult`. Метод `registerAllDirs(...)`. Pure-функции `extractDateFromPath`/`isWithinWatchPeriod` (переезжают из WatchRecordsTask.kt). |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicator.kt` | `@Component`-bean, `ReactiveHealthIndicator`. Делегирует логику в `task.computeHealth(now)`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt` | `@Component`+`@ConditionalOnProperty(application.telegram.enabled=true)`. `@EventListener(ApplicationReadyEvent::class)` шлёт одно сообщение владельцу. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt` | Unit-тесты iteration в изоляции + переезд pure-fn тестов. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt` | Unit-тест ReactiveHealthIndicator. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt` | Unit-тесты `onReady()`. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplOwnerMessageTest.kt` | Unit-тест `sendOwnerMessage` impl-а. |

### Do NOT touch

- `docker/deploy/docker-compose.yml`, `application.yaml`, `RecordsWatcherProperties.kt`, `.env.example`, `.claude/rules/configuration.md`, `CLAUDE.md` (root) — конфиг hardcoded, healthcheck остаётся как есть.
- `FirstTimeScanTask`, `SignalLossMonitorTask`, pipeline/facade — out of scope.

---

## Task 1: Расширить `TelegramNotificationService` методом `sendOwnerMessage`

> **Context:** spec §4.5 (Sink — `TelegramNotificationService.sendOwnerMessage(text)`).

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt`

- [ ] **Step 1.1: Добавить метод в интерфейс**

В `TelegramNotificationService.kt` после `sendCameraSignalRecovered(...)` добавить:

```kotlin
    /**
     * Send a plain text message to the bot owner (defined by application.telegram.owner).
     * No-op when telegram is disabled or the owner has not activated the bot yet.
     * Used for system-level admin signals such as the startup notification.
     */
    suspend fun sendOwnerMessage(text: String)
```

- [ ] **Step 1.2: Добавить пустой override в NoOp impl**

В `NoOpTelegramNotificationService.kt` после `sendCameraSignalRecovered(...)`:

```kotlin
    override suspend fun sendOwnerMessage(text: String) {
        logger.debug { "Telegram notifications disabled, skipping owner message" }
    }
```

- [ ] **Step 1.3: Запустить build и убедиться, что компилируется**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-telegram:compileKotlin :frigate-analyzer-telegram:compileTestKotlin
```

Expected: BUILD SUCCESSFUL. Если `TelegramNotificationServiceImpl` не имеет `sendOwnerMessage` — компиляция упадёт ("Class ... is not abstract and does not implement abstract member ..."). Это ожидаемо — продолжаем Task 2.

**Не коммитить пока — Task 1 и Task 2 идут вместе одним коммитом, иначе master ломается.**

---

## Task 2: Реализация `sendOwnerMessage` в `TelegramNotificationServiceImpl` (TDD)

> **Context:** spec §4.5; пример `SimpleTextNotificationTask` использует `signalLossFormatter`-вариант в impl. Owner discovery — через `userService.findActiveByUsername(telegramProperties.owner)`.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplOwnerMessageTest.kt`

- [ ] **Step 2.1: Написать failing test (owner active → enqueues SimpleTextNotificationTask)**

Создать `TelegramNotificationServiceImplOwnerMessageTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.SimpleTextNotificationTask
import ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationQueue
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID

class TelegramNotificationServiceImplOwnerMessageTest {
    private val userService = mockk<TelegramUserService>()
    private val notificationQueue = mockk<TelegramNotificationQueue>()
    private val uuidGeneratorHelper = mockk<UUIDGeneratorHelper>()
    private val msg = mockk<MessageResolver>(relaxed = true)
    private val signalLossFormatter = mockk<SignalLossMessageFormatter>(relaxed = true)
    private val rateLimiterProvider =
        mockk<org.springframework.beans.factory.ObjectProvider<ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter>>(relaxed = true)
    private val appSettings = mockk<AppSettingsService>(relaxed = true)
    private val telegramProperties =
        TelegramProperties(enabled = true, botToken = "x", owner = "alice")

    private val service =
        TelegramNotificationServiceImpl(
            userService = userService,
            notificationQueue = notificationQueue,
            uuidGeneratorHelper = uuidGeneratorHelper,
            msg = msg,
            signalLossFormatter = signalLossFormatter,
            rateLimiterProvider = rateLimiterProvider,
            appSettings = appSettings,
            telegramProperties = telegramProperties,
        )

    @Test
    fun `sendOwnerMessage enqueues SimpleTextNotificationTask to owner chat`() =
        runTest {
            val ownerId = UUID.randomUUID()
            every { uuidGeneratorHelper.generateV1() } returns ownerId
            coEvery { userService.findActiveByUsername("alice") } returns
                TelegramUserDto(
                    id = UUID.randomUUID(),
                    username = "alice",
                    chatId = 42L,
                    userId = 1L,
                    firstName = null,
                    lastName = null,
                    status = ru.zinin.frigate.analyzer.telegram.model.UserStatus.ACTIVE,
                    creationTimestamp = Instant.now(),
                    activationTimestamp = Instant.now(),
                )
            coEvery { notificationQueue.enqueue(any()) } just Runs

            service.sendOwnerMessage("Hello, admin!")

            coVerify(exactly = 1) {
                notificationQueue.enqueue(
                    match<SimpleTextNotificationTask> {
                        it.id == ownerId && it.chatId == 42L && it.text == "Hello, admin!"
                    },
                )
            }
        }

    @Test
    fun `sendOwnerMessage does nothing when owner is not active yet`() =
        runTest {
            coEvery { userService.findActiveByUsername("alice") } returns null

            service.sendOwnerMessage("Hello, admin!")

            coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
        }
}
```

- [ ] **Step 2.2: Запустить failing test**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramNotificationServiceImplOwnerMessageTest"
```

Expected: компиляция упадёт — `TelegramNotificationServiceImpl` не имеет нового конструкторского параметра `telegramProperties` и не реализует `sendOwnerMessage`. Это OK — добавим реализацию.

- [ ] **Step 2.3: Имплементация**

В `TelegramNotificationServiceImpl.kt`:

1. Добавить в конструктор последним параметром:

```kotlin
    private val telegramProperties: TelegramProperties,
```

(импорт: `import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties`)

2. После метода `sendCameraSignalRecovered(...)` добавить:

```kotlin
    // Critical fix: TelegramUserDto.chatId is nullable.
    override suspend fun sendOwnerMessage(text: String) {
        val owner = userService.findActiveByUsername(telegramProperties.owner)
        if (owner == null) {
            logger.warn {
                "Cannot send owner message: owner '${telegramProperties.owner}' has not activated the bot yet"
            }
            return
        }
        val chatId = owner.chatId
        if (chatId == null) {
            logger.warn {
                "Cannot send owner message: owner '${telegramProperties.owner}' has no chatId (bot not started yet?)"
            }
            return
        }
        notificationQueue.enqueue(
            SimpleTextNotificationTask(
                id = uuidGeneratorHelper.generateV1(),
                chatId = chatId,
                text = text,
            ),
        )
        logger.debug { "Enqueued owner message to chat $chatId" }
    }
```

- [ ] **Step 2.4: Запустить тест — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramNotificationServiceImplOwnerMessageTest"
```

Expected: BUILD SUCCESSFUL, 2 tests pass. Если ktlint в build-цикле падает — `./gradlew ktlintFormat` и retry.

- [ ] **Step 2.5: Запустить полный test set telegram-модуля (регрессия)**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-telegram:test
```

Expected: BUILD SUCCESSFUL, все тесты telegram-модуля проходят.

**Регрессия — known impact:** После добавления `telegramProperties` параметром конструктора следующие EXISTING тесты упадут на этапе компиляции и потребуют обновления конструктор-вызовов:

- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`

Добавить в **оба** setup'а: `telegramProperties = mockk<TelegramProperties>(relaxed = true)` (или реальный `TelegramProperties(enabled = true, botToken = "test", owner = "test")`).

Если в проекте появятся ещё файлы, инстанцирующие `TelegramNotificationServiceImpl(...)` — найти через `grep -l "TelegramNotificationServiceImpl(" modules/telegram/src/test/` и обновить аналогично.

- [ ] **Step 2.6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplOwnerMessageTest.kt
git commit -m "feat(telegram): add TelegramNotificationService.sendOwnerMessage

Plain text channel to bot owner — used for system-level admin signals.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.5"
```

---

## Task 3: `StartupTelegramNotifier` (TDD)

> **Context:** spec §4.4. Stateless bean — `@EventListener(ApplicationReadyEvent::class)` шлёт одно сообщение с version+commit+buildTime+started.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt`

- [ ] **Step 3.1: Написать failing test (sends message with version+commit+time)**

Создать `StartupTelegramNotifierTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StartupTelegramNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val gitProperties = mockk<GitProperties>()
    private val buildProperties = mockk<BuildProperties>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T15:14:00Z"), ZoneOffset.UTC)

    private val notifier =
        StartupTelegramNotifier(
            telegramNotificationService = telegramNotificationService,
            gitProperties = gitProperties,
            buildProperties = buildProperties,
            clock = clock,
        )

    @Test
    fun `onReady sends owner message with version commit buildTime and started`() {
        every { gitProperties.commitId } returns "abc1234567890def"
        every { buildProperties.version } returns "1.2.3"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } just Runs

        notifier.onReady()

        coVerify(exactly = 1) {
            telegramNotificationService.sendOwnerMessage(
                match { text ->
                    text.contains("Frigate Analyzer запущен") &&
                        text.contains("Version: 1.2.3") &&
                        text.contains("Commit: abc12345") &&
                        text.contains("Build time: 2026-05-20T10:00:00Z") &&
                        text.contains("Started: 2026-05-23T15:14:00Z")
                },
            )
        }
    }

    @Test
    fun `onReady swallows exception from sendOwnerMessage`() {
        every { gitProperties.commitId } returns "abc1234"
        every { buildProperties.version } returns "1.0.0"
        every { buildProperties.time } returns Instant.now()
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws RuntimeException("boom")

        // Должно НЕ кидаться:
        notifier.onReady()
    }
}
```

- [ ] **Step 3.2: Запустить failing test**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.application.StartupTelegramNotifierTest"
```

Expected: компиляция падает — класс `StartupTelegramNotifier` не существует.

- [ ] **Step 3.3: Имплементация**

Создать `StartupTelegramNotifier.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartupTelegramNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val gitProperties: GitProperties,
    private val buildProperties: BuildProperties,
    private val clock: Clock,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        val text =
            buildString {
                appendLine("🟢 Frigate Analyzer запущен")
                appendLine("Version: ${buildProperties.version}")
                appendLine("Commit: ${gitProperties.commitId.take(8)}")
                appendLine("Build time: ${buildProperties.time}")
                append("Started: ${Instant.now(clock)}")
            }
        runCatching {
            runBlocking { telegramNotificationService.sendOwnerMessage(text) }
        }.onFailure { e ->
            logger.warn(e) { "Failed to send startup notification" }
        }
    }
}
```

- [ ] **Step 3.4: Запустить тесты — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.application.StartupTelegramNotifierTest"
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 3.5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt
git commit -m "feat(core): startup telegram notification on ApplicationReadyEvent

Sends owner one message containing version+commit+buildTime+started on each
startup. Indirect signal for container restart frequency.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.4"
```

---

## Task 4: `WatchRecordsLoop` skeleton + первый тест (empty poll)

> **Context:** spec §4.2. Stateless iteration class; `runIteration` принимает state параметрами, возвращает `IterationResult`. Сейчас весь loop живёт внутри `WatchRecordsTask.run()` — нужно физически переехать в новый класс **с пустым телом**, а потом TDD-циклами заполнять.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt`

- [ ] **Step 4.1: Написать failing test (empty poll → no events processed)**

Создать `WatchRecordsLoopTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WatchRecordsLoopTest {
    private val recordingEntityHelper = mockk<RecordingEntityHelper>()
    private val recordingFileHelper = mockk<RecordingFileHelper>()
    private val properties =
        RecordsWatcherProperties(
            folder = Path.of("/mnt/data/frigate/recordings"),
            watchPeriod = Duration.ofDays(1),
            cleanupInterval = Duration.ofHours(1),
        )
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private val loop =
        WatchRecordsLoop(
            recordsWatcherProperties = properties,
            recordingEntityHelper = recordingEntityHelper,
            recordingFileHelper = recordingFileHelper,
            clock = clock,
        )

    @Test
    fun `runIteration returns zero processed events when poll times out`() =
        runTest {
            val watchService = mockk<WatchService>()
            every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns null
            val dirs = ConcurrentHashMap<Path, WatchKey>()
            val lastCleanup = Instant.now(clock)

            val result = loop.runIteration(watchService, dirs, lastCleanup)

            assertEquals(0, result.processedEvents)
            assertEquals(lastCleanup, result.lastCleanupAt)
        }
}
```

- [ ] **Step 4.2: Запустить failing test**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsLoopTest"
```

Expected: компиляция падает — нет класса `WatchRecordsLoop` / нет `IterationResult`.

- [ ] **Step 4.3: Создать skeleton `WatchRecordsLoop`**

Создать `WatchRecordsLoop.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

data class IterationResult(
    val processedEvents: Int,
    val lastCleanupAt: Instant,
)

@Component
class WatchRecordsLoop(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val recordingEntityHelper: RecordingEntityHelper,
    private val recordingFileHelper: RecordingFileHelper,
    private val clock: Clock,
) {
    suspend fun runIteration(
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
        lastCleanupAt: Instant,
    ): IterationResult {
        val key = watchService.poll(POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        var processed = 0
        // TODO Task 5: обработка key.pollEvents()
        // TODO Task 5: cleanup if Duration.between(lastCleanupAt, now) >= cleanupInterval
        return IterationResult(processedEvents = processed, lastCleanupAt = lastCleanupAt)
    }

    private companion object {
        const val POLL_PERIOD_MS: Long = 500L
    }
}
```

Конвенция: класс-specific константы хранятся в `private companion object` (а не как `const val POLL_PERIOD_MS` на top-level) — это избегает утечки имени `POLL_PERIOD_MS` в package-scope и упрощает test-stubbing если когда-нибудь понадобится. `WatchRecordsTask` использует тот же подход (см. Task 6 — `INITIAL_BACKOFF` / `MAX_BACKOFF` / `SUCCESSES_TO_RESET_BACKOFF` / `HEALTH_STALENESS` — рекомендуется привести их к тому же style'у если они сейчас на top-level).

**Намеренно**: оставляем `TODO Task 5:` — следующие TDD-циклы их закроют. Это допустимо в плановом TODO внутри одной серии задач (не placeholder, а скоупный маркер).

- [ ] **Step 4.4: Запустить тест — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsLoopTest"
```

Expected: BUILD SUCCESSFUL, 1 test pass.

- [ ] **Step 4.5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt
git commit -m "feat(records-watcher): introduce WatchRecordsLoop (skeleton + empty-poll test)

Stateless iteration class — supervisor will live in WatchRecordsTask.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.2"
```

---

## Task 5: `WatchRecordsLoop` — обработка событий, cleanup, propagation исключений (TDD)

> **Context:** spec §4.2, §7.2 (5 tests). Заполняем skeleton actual логикой по одному TDD-циклу на тест.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt`

- [ ] **Step 5.1: Failing test #2 — `ENTRY_CREATE` для .mp4 файла → `createRecording` вызван**

Добавить в `WatchRecordsLoopTest.kt`:

```kotlin
    @Test
    fun `runIteration creates recording for new mp4 file`() =
        runTest {
            val watchService = mockk<WatchService>()
            val key = mockk<WatchKey>()
            val event = mockk<java.nio.file.WatchEvent<Path>>()
            val dir = Path.of("/mnt/data/frigate/recordings/2026-05-23/12/cam1")
            val fileName = Path.of("cam1-2026-05-23-12.14.27.mp4")

            every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns key
            every { key.watchable() } returns dir
            every { key.reset() } returns true
            every { event.kind() } returns java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
            every { event.context() } returns fileName
            every { key.pollEvents() } returns listOf(event)

            // Replace Files.* static calls via mockkStatic, OR use a tempdir + real path.
            // Для простоты теста — используем tempdir в реальной FS:
            val tmpDir = java.nio.file.Files.createTempDirectory("wrl-test")
            try {
                val realDir = tmpDir.resolve("2026-05-23/12/cam1")
                java.nio.file.Files.createDirectories(realDir)
                val realFile = realDir.resolve(fileName.toString())
                java.nio.file.Files.createFile(realFile)
                every { key.watchable() } returns realDir
                every { event.context() } returns fileName

                val parsedRequest =
                    ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest(
                        filePath = realFile.toAbsolutePath().toString(),
                        fileCreationTimestamp = Instant.parse("2026-05-23T12:14:27Z"),
                        camId = "cam1",
                        recordDate = java.time.LocalDate.of(2026, 5, 23),
                        recordTime = java.time.LocalTime.of(12, 14, 27),
                        recordTimestamp = Instant.parse("2026-05-23T12:14:27Z"),
                    )
                io.mockk.coEvery { recordingEntityHelper.createRecording(any()) } returns java.util.UUID.randomUUID()
                // Use a real RecordingFileDto instance instead of mockk — DTO is a tiny data class,
                // mocking its property getters adds noise without any value.
                // Signature: RecordingFileDto(basePath, camId, date, time, timestamp) — see
                // modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingFileDto.kt
                io.mockk.every { recordingFileHelper.parse(any()) } returns
                    ru.zinin.frigate.analyzer.model.dto.RecordingFileDto(
                        basePath = realDir.toAbsolutePath().toString(),
                        camId = "cam1",
                        date = java.time.LocalDate.of(2026, 5, 23),
                        time = java.time.LocalTime.of(12, 14, 27),
                        timestamp = Instant.parse("2026-05-23T12:14:27Z"),
                    )

                val result = loop.runIteration(watchService, ConcurrentHashMap(), Instant.now(clock))

                assertEquals(1, result.processedEvents)
                io.mockk.coVerify(exactly = 1) { recordingEntityHelper.createRecording(any()) }
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
```

- [ ] **Step 5.2: Imp body — обработать ENTRY_CREATE для файла**

В `WatchRecordsLoop.runIteration(...)` заменить тело на:

```kotlin
    suspend fun runIteration(
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
        lastCleanupAt: Instant,
    ): IterationResult {
        val key = watchService.poll(POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        var processed = 0
        if (key != null) {
            val dir = key.watchable() as Path
            var iterationError: Throwable? = null
            try {
                for (event in key.pollEvents()) {
                    if (event.kind() != java.nio.file.StandardWatchEventKinds.ENTRY_CREATE) continue
                    @Suppress("UNCHECKED_CAST")
                    val ev = event as java.nio.file.WatchEvent<Path>
                    val fullPath = dir.resolve(ev.context())
                    logger.info { "New file created: $fullPath" }

                    if (java.nio.file.Files.isDirectory(fullPath)) {
                        if (isWithinWatchPeriod(fullPath, recordsWatcherProperties.folder, recordsWatcherProperties.watchPeriod, clock)) {
                            registerAllDirs(fullPath, watchService, registeredDirs)
                        } else {
                            logger.info { "Skipping old directory: $fullPath" }
                        }
                    } else {
                        val attrs = java.nio.file.Files.readAttributes(fullPath, java.nio.file.attribute.BasicFileAttributes::class.java)
                        val recordingFile = recordingFileHelper.parse(fullPath)
                        val recordingId =
                            recordingEntityHelper.createRecording(
                                ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest(
                                    filePath = fullPath.toAbsolutePath().toString(),
                                    fileCreationTimestamp = attrs.creationTime().toInstant(),
                                    camId = recordingFile.camId,
                                    recordDate = recordingFile.date,
                                    recordTime = recordingFile.time,
                                    recordTimestamp = recordingFile.timestamp,
                                ),
                            )
                        logger.info { "Recording id: $recordingId" }
                    }
                    processed++
                }
            } catch (e: Throwable) {
                iterationError = e
            } finally {
                if (!key.reset()) {
                    registeredDirs.remove(dir)
                }
            }
            if (iterationError != null) throw iterationError
        }

        // TODO Step 5.5: cleanup if Duration.between(lastCleanupAt, now) >= cleanupInterval
        return IterationResult(processedEvents = processed, lastCleanupAt = lastCleanupAt)
    }
```

И добавить `registerAllDirs` + переехавшие pure-функции внизу класса (одним блоком — чтобы pure-функции уже были на месте для следующих тестов):

```kotlin
    fun registerAllDirs(
        start: Path,
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
    ): Int {
        var registered = 0
        var skipped = 0
        java.nio.file.Files.walk(start).use { stream ->
            stream
                .filter { java.nio.file.Files.isDirectory(it) }
                .forEach { dir ->
                    if (isWithinWatchPeriod(dir, recordsWatcherProperties.folder, recordsWatcherProperties.watchPeriod, clock)) {
                        registeredDirs.computeIfAbsent(dir) {
                            val key = dir.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE)
                            registered++
                            key
                        }
                    } else {
                        skipped++
                    }
                }
        }
        logger.info { "Registered $registered directories, skipped $skipped old directories." }
        return registered
    }
}

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(
    path: Path,
    rootFolder: Path,
): java.time.LocalDate? {
    val relativePath = if (path.startsWith(rootFolder)) rootFolder.relativize(path) else path
    for (i in relativePath.nameCount - 1 downTo 0) {
        val name = relativePath.getName(i).toString()
        if (DATE_PATTERN.matches(name)) {
            return try {
                java.time.LocalDate.parse(name)
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
        }
    }
    return null
}

internal fun isWithinWatchPeriod(
    path: Path,
    rootFolder: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return true
    val cutoff = java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).minusDays(watchPeriod.toDays())
    return !date.isBefore(cutoff)
}
```

**Эти top-level функции** переедут из `WatchRecordsTask.kt` (см. Task 6.3). Pure-fn тесты на них тоже переедут (см. Step 5.4 ниже).

- [ ] **Step 5.3: Запустить тест — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsLoopTest"
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 5.4: Перенести pure-fn тесты из `WatchRecordsTaskTest.kt` в `WatchRecordsLoopTest.kt`**

В `WatchRecordsLoopTest.kt` добавить (скопировать как есть из текущего `WatchRecordsTaskTest.kt`):

```kotlin
    companion object {
        private val ROOT = Path.of("/mnt/data/frigate/recordings")
    }

    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(java.time.LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(java.time.LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(java.time.LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        org.junit.jupiter.api.Assertions.assertNull(extractDateFromPath(Path.of("/mnt/data/frigate/recordings"), ROOT))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        org.junit.jupiter.api.Assertions.assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        org.junit.jupiter.api.Assertions.assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        org.junit.jupiter.api.Assertions.assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings")
        org.junit.jupiter.api.Assertions.assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for exact cutoff date`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        org.junit.jupiter.api.Assertions.assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for one day before cutoff`() {
        val fixedClock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-13")
        org.junit.jupiter.api.Assertions.assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), fixedClock))
    }

    @Test
    fun `extractDateFromPath returns null for invalid date like 2026-02-30`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-30")
        org.junit.jupiter.api.Assertions.assertNull(extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath ignores date-like segments in root path`() {
        val rootWithDate = Path.of("/data/2024-01-15/frigate/recordings")
        val path = Path.of("/data/2024-01-15/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(java.time.LocalDate.of(2026, 2, 15), extractDateFromPath(path, rootWithDate))
    }
```

И **удалить тот же блок из `WatchRecordsTaskTest.kt`** (там пока остаются только эти тесты — после удаления файл станет почти пустым, оставить только class-skeleton без @Test'ов; supervisor tests добавим в Task 8).

- [ ] **Step 5.5: Failing test #3 — new directory → `registerAllDirs` called, `createRecording` НЕ вызван**

Использовать **реальный** `WatchService` из tempdir — мокать `WatchService`/`WatchKey`/`WatchEvent` слишком хрупко и не отлавливает реальные NIO contract'ы.

```kotlin
    @Test
    fun `runIteration registers new directory and does not call createRecording`() =
        runTest {
            val tmpDir = java.nio.file.Files.createTempDirectory("wrl-test-dir")
            val watchService = java.nio.file.FileSystems.getDefault().newWatchService()
            try {
                // Регистрируем корень — будем ловить ENTRY_CREATE для новых поддиректорий
                tmpDir.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE)

                val loopWithTmpRoot =
                    WatchRecordsLoop(
                        recordsWatcherProperties =
                            RecordsWatcherProperties(
                                folder = tmpDir,
                                watchPeriod = Duration.ofDays(365),
                                cleanupInterval = Duration.ofHours(1),
                            ),
                        recordingEntityHelper = recordingEntityHelper,
                        recordingFileHelper = recordingFileHelper,
                        clock = clock,
                    )

                // Создаём поддиректорию ПОСЛЕ регистрации — WatchService увидит ENTRY_CREATE
                java.nio.file.Files.createDirectory(tmpDir.resolve("2026-05-23"))

                // Дать ОС время доставить событие в WatchService
                Thread.sleep(200)

                val result = loopWithTmpRoot.runIteration(watchService, ConcurrentHashMap(), Instant.now(clock))

                io.mockk.coVerify(exactly = 0) { recordingEntityHelper.createRecording(any()) }
                assertEquals(1, result.processedEvents)
            } finally {
                watchService.close()
                tmpDir.toFile().deleteRecursively()
            }
        }
```

Тест работает на реальном NIO `WatchService` — `Files.isDirectory(...)` / `Files.walk(...)` получают реальные пути, `key.pollEvents()` возвращает реальные `WatchEvent`. Проверяет `processedEvents` count и что `recordingEntityHelper.createRecording` НЕ был вызван.

Запустить, проверить что green (имплементация в Step 5.2 уже это покрывает). Если красный — отлаживать.

- [ ] **Step 5.6: Failing test #4 — `parse` бросает `IllegalArgumentException` → пробрасывается**

```kotlin
    @Test
    fun `runIteration propagates parse exception`() =
        runTest {
            val watchService = mockk<WatchService>()
            val key = mockk<WatchKey>()
            val event = mockk<java.nio.file.WatchEvent<Path>>()
            val tmpDir = java.nio.file.Files.createTempDirectory("wrl-test-parse")
            try {
                val realDir = tmpDir.resolve("2026-05-23/12/cam1")
                java.nio.file.Files.createDirectories(realDir)
                val realFile = realDir.resolve("bogus.mp4")
                java.nio.file.Files.createFile(realFile)
                every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns key
                every { key.watchable() } returns realDir
                every { key.reset() } returns true
                every { event.kind() } returns java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
                every { event.context() } returns Path.of("bogus.mp4")
                every { key.pollEvents() } returns listOf(event)
                io.mockk.every { recordingFileHelper.parse(any()) } throws IllegalArgumentException("bogus filename")

                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        loop.runIteration(watchService, ConcurrentHashMap(), Instant.now(clock))
                    }
                }
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
```

Запустить — должен пройти без изменений в коде (текущий `runIteration` не ловит исключения).

- [ ] **Step 5.7: Failing test #5 — cleanup interval прошёл → cleanup отработал, lastCleanupAt обновлён**

```kotlin
    @Test
    fun `runIteration runs cleanup when interval elapsed`() =
        runTest {
            val watchService = mockk<WatchService>()
            every { watchService.poll(any<Long>(), any<TimeUnit>()) } returns null
            val tmpDir = java.nio.file.Files.createTempDirectory("wrl-test-cleanup")
            try {
                val oldDirPath = tmpDir.resolve("2025-01-01")  // далеко за watchPeriod
                java.nio.file.Files.createDirectories(oldDirPath)
                val oldKey = mockk<WatchKey>(relaxed = true)
                val dirs = ConcurrentHashMap<Path, WatchKey>().apply { put(oldDirPath, oldKey) }
                val staleLastCleanup = Instant.parse("2026-05-23T10:00:00Z")
                // clock = 2026-05-23T12:00:00Z, cleanupInterval = 1h → 2h elapsed → cleanup should fire
                val loopWithTmpRoot =
                    WatchRecordsLoop(
                        recordsWatcherProperties =
                            RecordsWatcherProperties(
                                folder = tmpDir,
                                watchPeriod = Duration.ofDays(1),
                                cleanupInterval = Duration.ofHours(1),
                            ),
                        recordingEntityHelper = recordingEntityHelper,
                        recordingFileHelper = recordingFileHelper,
                        clock = clock,
                    )

                val result = loopWithTmpRoot.runIteration(watchService, dirs, staleLastCleanup)

                org.junit.jupiter.api.Assertions.assertEquals(0, dirs.size, "Old dir should be cleaned up")
                io.mockk.verify { oldKey.cancel() }
                org.junit.jupiter.api.Assertions.assertNotEquals(staleLastCleanup, result.lastCleanupAt, "lastCleanupAt should advance")
            } finally {
                tmpDir.toFile().deleteRecursively()
            }
        }
```

Запустить — упадёт (cleanup ещё не имплементирован). Закрываем имплементацией:

- [ ] **Step 5.8: Добавить cleanup в `runIteration`**

В `WatchRecordsLoop.runIteration`, перед `return IterationResult(...)`, заменить `// TODO Step 5.5: cleanup...` блок на:

```kotlin
        val now = Instant.now(clock)
        val newLastCleanup =
            if (Duration.between(lastCleanupAt, now) >= recordsWatcherProperties.cleanupInterval) {
                cleanupExpiredDirs(registeredDirs)
                now
            } else {
                lastCleanupAt
            }
        return IterationResult(processedEvents = processed, lastCleanupAt = newLastCleanup)
    }

    // Single-writer invariant: cleanupExpiredDirs() and event processing run on the same
    // dedicated dispatcher thread (Dispatchers.IO.limitedParallelism(1)). ConcurrentHashMap
    // is used only so the HealthIndicator bean can safely read .size from another thread.
    private fun cleanupExpiredDirs(registeredDirs: ConcurrentMap<Path, WatchKey>) {
        var removed = 0
        registeredDirs.entries.removeIf { (dir, watchKey) ->
            if (!isWithinWatchPeriod(dir, recordsWatcherProperties.folder, recordsWatcherProperties.watchPeriod, clock)) {
                watchKey.cancel()
                removed++
                true
            } else {
                false
            }
        }
        if (removed > 0) {
            logger.info { "Cleanup: removed $removed expired watch keys. Active watches: ${registeredDirs.size}" }
        }
    }
```

- [ ] **Step 5.9: Запустить все loop-тесты — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsLoopTest"
```

Expected: BUILD SUCCESSFUL, все 5 (loop) + 11 (pure-fn) тестов проходят.

- [ ] **Step 5.10: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt
git commit -m "feat(records-watcher): fill WatchRecordsLoop with iteration logic

Iteration covers: ENTRY_CREATE for .mp4 → createRecording; new directory →
registerAllDirs; parse exception propagation; periodic cleanup of expired
watch keys. Pure-fn tests migrated from WatchRecordsTaskTest.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.2, §7.2"
```

---

## Task 6: Rewrite `WatchRecordsTask` — lifecycle (`@PostConstruct`/`@PreDestroy`), supervisor skeleton

> **Context:** spec §4.1 (полностью). Параметры testability — `dispatcher` и `watchServiceFactory` с дефолтами. После этого Task'а класс компилируется и стартует, но supervisor пока пустой — backoff/exception handling добавим в Task 8.

**Files:**
- Modify (rewrite): `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`

- [ ] **Step 6.1: Полная замена содержимого `WatchRecordsTask.kt`**

Записать (целиком, overwrite):

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.health.Health
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private const val SUCCESSES_TO_RESET_BACKOFF: Int = 5
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(2)

@Component
class WatchRecordsTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val loop: WatchRecordsLoop,
    private val clock: Clock,
    private val springProfileHelper: SpringProfileHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("watch-records"))
    @Volatile internal var supervisorJob: Job? = null

    private var watchService: WatchService? = null
    internal val registeredDirs: ConcurrentHashMap<Path, WatchKey> = ConcurrentHashMap()

    @Volatile internal var lastSuccessfulIterationAt: Instant? = null
    @Volatile internal var consecutiveFailures: Long = 0
    @Volatile private var successesSinceLastFailure: Int = 0
    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF
    @Volatile internal var lastFailure: Throwable? = null

    @PostConstruct
    fun start() {
        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. Watch records task skipped." }
            return
        }
        logger.info { "Starting watch records in folder: ${recordsWatcherProperties.folder}" }
        supervisorJob = scope.launch { runSupervised() }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down watch records..." }
        supervisorJob?.cancel()
        runBlocking { supervisorJob?.join() }
        scope.cancel()
        registeredDirs.values.forEach { it.cancel() }
        registeredDirs.clear()
        closeWatchServiceQuietly()
        logger.info { "Watch records shut down." }
    }

    internal suspend fun runSupervised() {
        currentBackoff = INITIAL_BACKOFF
        var lastCleanup = Instant.now(clock)
        while (currentCoroutineContext().isActive) {
            try {
                ensureWatchService()
                val result = loop.runIteration(watchService!!, registeredDirs, lastCleanup)
                lastCleanup = result.lastCleanupAt
                onIterationSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedWatchServiceException) {
                logger.warn { "WatchService closed; will recreate next iteration" }
                closeWatchServiceQuietly()
                registeredDirs.clear()
                onIterationFailure(e)
                delay(currentBackoff.toMillis())
                currentBackoff = nextBackoff(currentBackoff)
            } catch (e: Exception) {
                logger.error(e) { "WatchRecordsTask iteration failed; backing off for $currentBackoff" }
                onIterationFailure(e)
                delay(currentBackoff.toMillis())
                currentBackoff = nextBackoff(currentBackoff)
            }
        }
    }

    private fun ensureWatchService() {
        if (watchService == null) {
            val ws = watchServiceFactory()
            try {
                registeredDirs.clear()  // guarantee clean state
                loop.registerAllDirs(recordsWatcherProperties.folder, ws, registeredDirs)
            } catch (e: Exception) {
                runCatching { ws.close() }
                registeredDirs.clear()
                throw e  // supervisor catches → backoff → retry from scratch
            }
            watchService = ws
            logger.info { "Watch service created; registered ${registeredDirs.size} directories." }
        }
    }

    private fun closeWatchServiceQuietly() {
        runCatching { watchService?.close() }
        watchService = null
    }

    private fun onIterationSuccess() {
        lastSuccessfulIterationAt = Instant.now(clock)
        consecutiveFailures = 0
        if (currentBackoff > INITIAL_BACKOFF) {
            successesSinceLastFailure++
            if (successesSinceLastFailure >= SUCCESSES_TO_RESET_BACKOFF) {
                logger.info { "Backoff reset after $successesSinceLastFailure consecutive successes" }
                currentBackoff = INITIAL_BACKOFF
                successesSinceLastFailure = 0
            }
        }
    }

    private fun onIterationFailure(t: Throwable) {
        consecutiveFailures++
        successesSinceLastFailure = 0
        lastFailure = t
    }

    private fun nextBackoff(current: Duration): Duration =
        minOf(current.multipliedBy(2), MAX_BACKOFF)

    fun computeHealth(now: Instant): Health {
        val builder =
            Health.Builder()
                .withDetail("lastSuccessfulIterationAt", lastSuccessfulIterationAt?.toString() ?: "never")
                .withDetail("consecutiveFailures", consecutiveFailures)
                .withDetail("currentBackoff", currentBackoff.toString())
                .withDetail("registeredDirs", registeredDirs.size)
        lastFailure?.let {
            builder.withDetail(
                "lastFailure",
                "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
            )
        }

        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }
        val last = lastSuccessfulIterationAt
        return when {
            last == null && consecutiveFailures == 0 ->
                builder.up().withDetail("reason", "starting up").build()
            last == null && consecutiveFailures > 0 ->
                builder
                    .outOfService()
                    .withDetail("reason", "no successful iteration yet, in backoff")
                    .build()
            Duration.between(last, now) > HEALTH_STALENESS && consecutiveFailures > 0 ->
                builder
                    .down()
                    .withDetail("reason", "stale: no success for ${Duration.between(last, now)}")
                    .build()
            consecutiveFailures > 0 ->
                builder
                    .outOfService()
                    .withDetail("reason", "in backoff after $consecutiveFailures consecutive failures")
                    .build()
            else -> builder.up().build()
        }
    }
}
```

Это **полная замена**: все top-level pure-fn (extractDateFromPath/isWithinWatchPeriod) физически уехали в WatchRecordsLoop.kt при Task 5, в этот файл они НЕ копируются.

- [ ] **Step 6.2: Запустить compile**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:compileKotlin
```

Expected: BUILD SUCCESSFUL. Падение на `ApplicationListener.kt` (там вызов `watchRecordsTask.run()` который теперь не существует) исправляется следующим Task 7 (ApplicationListener cleanup) — порядок Task 6 → Task 7 → Task 8 (already in this order) гарантирует, что ветка компилируется после каждого commit'а.

- [ ] **Step 6.3: Запустить существующий test set (только pure-fn в WatchRecordsTaskTest — если ещё не пустой)**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.*"
```

Expected: loop tests pass; WatchRecordsTaskTest либо empty (если из него уже удалили pure-fn в Task 5.4 и ещё не добавили supervisor tests), либо все ещё green pure-fn (если в неудачном порядке выполнения). Если что-то падает — отлаживать.

- [ ] **Step 6.4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt
git commit -m "refactor(records-watcher): rewrite WatchRecordsTask as coroutine supervisor

Replaces @Async + while(!stopped.get()) with @PostConstruct/@PreDestroy +
SupervisorJob coroutine. Catches non-cancellation throwables, exponential
backoff (5s→60s, reset after 5 successes), recreates WatchService on
ClosedWatchServiceException. computeHealth() exposes UP/OUT_OF_SERVICE/DOWN.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.1, §5"
```

---

## Task 7: Обновить `ApplicationListener` (cleanup)

> **Context:** spec §3 — `ApplicationListener` больше не отвечает за lifecycle WatchRecordsTask. Запуск через @PostConstruct в самом классе, shutdown через @PreDestroy.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/ApplicationListener.kt`

- [ ] **Step 7.1: Убрать вызов `watchRecordsTask.run()` и обработку `ContextClosedEvent`**

Заменить содержимое файла на:

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import ru.zinin.frigate.analyzer.core.task.FirstTimeScanTask

private val logger = KotlinLogging.logger {}

@Component
class ApplicationListener(
    val gitProperties: GitProperties,
    val buildProperties: BuildProperties,
    val firstTimeScanTask: FirstTimeScanTask,
    val recordsWatcherProperties: RecordsWatcherProperties,
    val springProfileHelper: SpringProfileHelper,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun initializeApplication() {
        logger.info { "Application started." }

        logger.info { "Git version: ${gitProperties.getCommitId()}" }
        logger.info { "Git commit time: ${gitProperties.getCommitTime()}" }
        logger.info { "Build version: ${buildProperties.getVersion()}" }
        logger.info { "Build time: ${buildProperties.getTime()}" }

        // WatchRecordsTask now starts itself via @PostConstruct (see WatchRecordsTask.start).

        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. First time scan task skipped." }
        } else if (!recordsWatcherProperties.disableFirstScan) {
            firstTimeScanTask.run()
        } else {
            logger.info { "First time scan task skipped." }
        }

        logger.info { "Tasks started." }
    }
}
```

Изменения:
- Удалили зависимость `watchRecordsTask: WatchRecordsTask` из конструктора.
- Удалили вызов `watchRecordsTask.run()`.
- Удалили `@EventListener(ContextClosedEvent::class) fun shutdownApplication()` целиком (lifecycle WatchRecordsTask теперь через `@PreDestroy` внутри самого класса).

- [ ] **Step 7.2: Запустить compile + полный test core-модуля**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:compileKotlin :frigate-analyzer-core:test
```

Expected: BUILD SUCCESSFUL. Если падает на `FrigateAnalyzerApplicationTests` (Spring context test) — скорее всего из-за `@PostConstruct fun start()` пытающегося создать `WatchService` в тестовом профиле. Решение: убедиться, что `springProfileHelper.isTestProfile()` возвращает `true` в test-контексте (это уже было в pre-existing коде — должно работать). Если нет — отлаживать.

- [ ] **Step 7.3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/ApplicationListener.kt
git commit -m "refactor(application): move WatchRecordsTask lifecycle ownership into task

Listener no longer triggers watchRecordsTask.run() or shutdown — that's now
done by @PostConstruct/@PreDestroy inside WatchRecordsTask itself.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §3, §4.1"
```

---

## Task 8: `WatchRecordsTask` supervisor tests (TDD)

> **Context:** spec §7.1 (5 tests). Используем `kotlinx-coroutines-test` virtual time для мгновенного `delay()`. Конструктор инжектит `StandardTestDispatcher` и mock `watchServiceFactory`. Tests дёргают `runSupervised()` напрямую внутри `runTest{}`.

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt`

- [ ] **Step 8.1: Очистить `WatchRecordsTaskTest.kt` (если есть остатки) и подготовить skeleton**

Полностью переписать файл:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRecordsTaskTest {
    private val loop = mockk<WatchRecordsLoop>(relaxed = true)
    private val springProfileHelper = mockk<SpringProfileHelper> { every { isTestProfile() } returns false }
    private val properties =
        RecordsWatcherProperties(
            folder = Path.of("/tmp/wrt-test"),
            watchPeriod = Duration.ofDays(1),
            cleanupInterval = Duration.ofHours(1),
        )
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private fun newTask(
        watchServiceFactory: () -> WatchService = { mockk(relaxed = true) },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(),
    ) =
        WatchRecordsTask(
            recordsWatcherProperties = properties,
            loop = loop,
            clock = clock,
            springProfileHelper = springProfileHelper,
            dispatcher = dispatcher,
            watchServiceFactory = watchServiceFactory,
        )

    // tests follow in subsequent steps...
}
```

- [ ] **Step 8.2: Failing test #1 — supervisor survives RuntimeException, returns to UP after success**

Добавить в class:

```kotlin
    @Test
    fun `supervisor survives RuntimeException and continues looping`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            // First two iterations throw, third succeeds, fourth signals "test done" to exit loop cleanly.
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when (iterations) {
                    1 -> throw RuntimeException("boom1")
                    2 -> throw RuntimeException("boom2")
                    3 -> IterationResult(processedEvents = 0, lastCleanupAt = Instant.now(clock))
                    else -> throw CancellationException("test done")  // self-terminate loop
                }
            }

            val job = launch { task.runSupervised() }

            // Allow: fail (backoff 5s), fail (backoff 10s), succeed, CancellationException exits.
            advanceUntilIdle()  // safe — loop self-terminates via CancellationException
            job.join()

            assertNotNull(task.lastSuccessfulIterationAt, "Expected at least one successful iteration")
            assertEquals(0, task.consecutiveFailures, "Counter should reset on success")
            assertTrue(task.lastFailure is RuntimeException)
        }
```

Run, expect green (supervisor logic уже на месте от Task 6).

- [ ] **Step 8.3: Failing test #2 — `ClosedWatchServiceException` triggers WatchService recreation**

```kotlin
    @Test
    fun `ClosedWatchServiceException triggers watchService recreation`() =
        runTest {
            var factoryCallCount = 0
            val watchService = mockk<WatchService>(relaxed = true)
            val task =
                newTask(
                    watchServiceFactory = {
                        factoryCallCount++
                        watchService
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when (iterations) {
                    1 -> throw ClosedWatchServiceException()
                    2 -> IterationResult(processedEvents = 0, lastCleanupAt = Instant.now(clock))
                    else -> throw CancellationException("test done")
                }
            }

            val job = launch { task.runSupervised() }
            advanceUntilIdle()
            job.join()

            assertTrue(factoryCallCount >= 2, "WatchService should be recreated after ClosedWatchServiceException; got $factoryCallCount calls")
            assertNotNull(task.lastSuccessfulIterationAt)
        }
```

- [ ] **Step 8.4: Failing test #3 — `CancellationException` propagates, loop exits cleanly**

```kotlin
    @Test
    fun `cancel exits loop cleanly without registering as failure`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            coEvery { loop.runIteration(any(), any(), any()) } returns
                IterationResult(processedEvents = 0, lastCleanupAt = Instant.now(clock))

            val job = launch { task.runSupervised() }
            advanceTimeBy(1_000)
            yield()
            job.cancel()
            advanceUntilIdle()

            assertEquals(0, task.consecutiveFailures, "Cancel should NOT increment failures")
            assertTrue(job.isCancelled)
        }
```

- [ ] **Step 8.5: Failing test #4 — backoff resets after N consecutive successes**

```kotlin
    @Test
    fun `backoff resets after 5 consecutive successes after failures`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            val successResult = IterationResult(processedEvents = 0, lastCleanupAt = Instant.now(clock))
            // 3 failures (backoff: 5s→10s→20s→40s), then 5 successes (resets backoff to 5s),
            // then CancellationException to exit the loop cleanly.
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when {
                    iterations <= 3 -> throw RuntimeException("e$iterations")
                    iterations <= 8 -> successResult
                    else -> throw CancellationException("test done")
                }
            }

            val job = launch { task.runSupervised() }
            advanceUntilIdle()  // safe — loop self-terminates after 9 iterations
            job.join()

            // After 5 consecutive successes following failures, currentBackoff should be reset to INITIAL_BACKOFF
            assertEquals(Duration.ofSeconds(5), task.currentBackoff, "Backoff should reset to INITIAL_BACKOFF=5s")
            assertEquals(0, task.consecutiveFailures)
        }
```

- [ ] **Step 8.6: Failing test #5 — `computeHealth` covers all 5 states**

```kotlin
    @Test
    fun `computeHealth returns DOWN when supervisor not started`() {
        val task = newTask()
        val now = Instant.parse("2026-05-23T12:05:00Z")
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertEquals("supervisor not active", health.details["reason"])
    }

    @Test
    fun `computeHealth returns UP when starting up (no failures yet)`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                kotlinx.coroutines.delay(Long.MAX_VALUE / 2)
                IterationResult(0, Instant.now(clock))
            }
            val job = launch { task.runSupervised() }
            yield()  // let coroutine start; loop is blocked inside delay
            val now = Instant.parse("2026-05-23T12:00:01Z")
            val health = task.computeHealth(now)
            assertEquals(Status.UP, health.status)
            assertEquals("starting up", health.details["reason"])
            job.cancel()
        }

    @Test
    fun `computeHealth returns OUT_OF_SERVICE in backoff after no successful iteration yet`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                throw RuntimeException("always fails")
            }
            val job = launch { task.runSupervised() }
            advanceTimeBy(100)  // first failure happens
            yield()
            val now = Instant.parse("2026-05-23T12:00:00.001Z")
            val health = task.computeHealth(now)
            assertEquals(Status.OUT_OF_SERVICE, health.status)
            assertTrue(health.details["reason"].toString().contains("no successful iteration yet"))
            job.cancel()
        }

    // Helper: build a task and inject a real `Job()` with isActive=true into supervisorJob
    // so computeHealth's active-job branches can be exercised without launching a real coroutine.
    // Requires `supervisorJob` to be declared `@Volatile internal var` (see Step 6.1).
    private fun taskWithActiveJob(
        last: Instant?,
        fails: Long,
        lastFailure: Throwable? = null,
    ): WatchRecordsTask {
        val task = newTask()
        task.supervisorJob = kotlinx.coroutines.Job().apply { /* fresh, isActive=true */ }
        task.lastSuccessfulIterationAt = last
        task.consecutiveFailures = fails
        task.lastFailure = lastFailure
        return task
    }

    @Test
    fun `computeHealth returns DOWN when stale beyond staleness`() {
        val task =
            taskWithActiveJob(
                last = Instant.parse("2026-05-23T11:00:00Z"),
                fails = 3,
                lastFailure = RuntimeException("PG XX000"),
            )
        val now = Instant.parse("2026-05-23T12:05:00Z")  // 1h05m since last success > 2m staleness
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertTrue(
            health.details["reason"].toString().contains("stale"),
            "Expected 'stale' reason when active job + stale lastSuccessful + failures > 0; got ${health.details["reason"]}",
        )
    }

    @Test
    fun `computeHealth returns OUT_OF_SERVICE in backoff with fresh successful iteration`() {
        val task =
            taskWithActiveJob(
                last = Instant.parse("2026-05-23T12:00:00Z"),
                fails = 1,
                lastFailure = RuntimeException("transient"),
            )
        val now = Instant.parse("2026-05-23T12:00:05Z")  // 5s after last success < staleness=2m
        val health = task.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, health.status)
    }
```

**Test seam:** `supervisorJob` is declared `@Volatile internal var` (see Step 6.1 / design D-FIX-2) specifically to allow the `taskWithActiveJob(...)` helper to inject a `Job()` instance whose `isActive` returns `true`. This lets us exercise `computeHealth`'s active-job branches (stale → DOWN; fresh + failures → OUT_OF_SERVICE) without needing to launch a real coroutine.

- [ ] **Step 8.7: Lifecycle test — `shutdown()` cancels supervisor, closes watchService, clears registeredDirs**

Добавить test, который покрывает `@PreDestroy.shutdown()` поведение после `@PostConstruct.start()`:

```kotlin
    @Test
    fun `shutdown cancels supervisorJob closes watchService and clears registeredDirs`() =
        runTest {
            val watchService = mockk<WatchService>(relaxed = true)
            val task =
                newTask(
                    watchServiceFactory = { watchService },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                kotlinx.coroutines.delay(Long.MAX_VALUE / 2)  // park inside loop
                IterationResult(0, Instant.now(clock))
            }
            // Prime registeredDirs to mimic post-start state
            task.registeredDirs[Path.of("/tmp/dir1")] = mockk(relaxed = true)
            task.registeredDirs[Path.of("/tmp/dir2")] = mockk(relaxed = true)

            task.start()  // would normally come from @PostConstruct
            yield()
            assertTrue(task.supervisorJob != null && task.supervisorJob!!.isActive)

            task.shutdown()

            assertEquals(0, task.registeredDirs.size, "registeredDirs should be cleared after shutdown")
            assertTrue(task.supervisorJob!!.isCancelled, "supervisorJob should be cancelled")
            io.mockk.verify { watchService.close() }
        }
```

- [ ] **Step 8.8: Additional mandatory tests covering CRITICAL-4/5/8 fixes**

Добавить три теста-сценария:

1. **`exception during event processing still resets key (CRITICAL-5 follow-up)`** — verify that когда `loop.runIteration` бросает exception из тела обработки event'а, `key.reset()` всё равно отрабатывает (try/finally в Step 5.2). Тест использует реальный tempdir + реальный WatchService и мок recordingFileHelper, бросающий exception.

2. **`partial registerAllDirs failure leaves watchService=null for next-iteration retry (CRITICAL-4 follow-up)`** — verify, что когда `loop.registerAllDirs` throws (например, `IOException`) из `ensureWatchService()`, локальный `ws` закрывается, `watchService` поле остаётся `null`, `registeredDirs.clear()` отработал, и следующий iteration пересоздаёт всё с нуля.

3. **`ensureWatchService failing 3x in a row triggers backoff progression and DOWN after HEALTH_STALENESS (CRITICAL-8 follow-up — pending architectural decision in iter-1 review)`** — verify backoff progression 5s→10s→20s + что после HEALTH_STALENESS период `computeHealth()` flip'ает в DOWN.

> **Note (architectural):** Тест #3 depends on resolution of disputed health-state architecture in iter-1 review §D2. Если решение — оставить текущую модель (DOWN только когда `last != null && stale`), тест должен manually прокрутить как минимум одну успешную итерацию перед серией провалов. Если решение поменяется (DOWN сразу после N consecutive failures without success), тест упрощается. Финализировать пo результатам итерации.

- [ ] **Step 8.9: Запустить все WatchRecordsTaskTest — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsTaskTest"
```

Expected: BUILD SUCCESSFUL, все supervisor + health tests pass.

- [ ] **Step 8.10: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt
git commit -m "test(records-watcher): unit tests for supervisor and computeHealth

Covers: RuntimeException survival + retry/backoff, ClosedWatchServiceException
recreation, CancellationException propagation, backoff reset after N successes,
computeHealth state matrix, shutdown lifecycle, follow-ups for CRITICAL-4/5.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §7.1"
```

---

## Task 9: `WatchRecordsTaskHealthIndicator` (TDD)

> **Context:** spec §4.3. ReactiveHealthIndicator делегирует в `task.computeHealth(now)`.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicator.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt`

- [ ] **Step 9.1: Failing test**

Создать `WatchRecordsTaskHealthIndicatorTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Status
import reactor.test.StepVerifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WatchRecordsTaskHealthIndicatorTest {
    private val task = mockk<WatchRecordsTask>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)
    private val indicator = WatchRecordsTaskHealthIndicator(task, clock)

    @Test
    fun `health delegates to computeHealth and wraps in Mono`() {
        val expectedHealth = Health.Builder().up().withDetail("reason", "starting up").build()
        every { task.computeHealth(Instant.parse("2026-05-23T12:00:00Z")) } returns expectedHealth

        StepVerifier.create(indicator.health())
            .assertNext { actual ->
                assertEquals(Status.UP, actual.status)
                assertEquals("starting up", actual.details["reason"])
            }
            .verifyComplete()
    }
}
```

- [ ] **Step 9.2: Запустить failing test**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsTaskHealthIndicatorTest"
```

Expected: компиляция падает — класс не существует.

- [ ] **Step 9.3: Имплементация**

Создать `WatchRecordsTaskHealthIndicator.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant

@Component
class WatchRecordsTaskHealthIndicator(
    private val task: WatchRecordsTask,
    private val clock: Clock,
) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> = Mono.fromSupplier { task.computeHealth(Instant.now(clock)) }
}
```

- [ ] **Step 9.4: Запустить тест — green**

Делегировать через build-runner:

```
Build target: ./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.task.WatchRecordsTaskHealthIndicatorTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9.5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicator.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt
git commit -m "feat(records-watcher): expose health via ReactiveHealthIndicator

Bean name watchRecordsTaskHealthIndicator → 'watchRecordsTask' component
inside /actuator/health. DOWN propagates to aggregated status → docker
healthcheck unhealthy → restart: unless-stopped self-heals.
Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §4.3"
```

---

## Task 10: Обновить `.claude/rules/pipeline.md`

> **Context:** spec §8 (документация). Изменения только в разделе `## File Watching`.

**Files:**
- Modify: `/opt/github/zinin/frigate-analyzer/.claude/rules/pipeline.md`

- [ ] **Step 10.1: Заменить раздел `## File Watching`**

Найти в `.claude/rules/pipeline.md` текущий блок:

```
## File Watching

| Task | Purpose |
|------|---------|
| WatchRecordsTask | Monitors Frigate folder via Java WatchService |
| FirstTimeScanTask | Initial scan on startup (disable: `DISABLE_FIRST_SCAN=true`) |

WatchRecordsTask uses selective watching to limit monitored directories:
- Only directories within the configured `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` directory structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup task removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

WatchRecordsTask parses `.mp4` filenames to extract camera ID, date, time, timestamp.
```

Заменить на:

```
## File Watching

| Task | Purpose |
|------|---------|
| WatchRecordsTask | Coroutine supervisor that drives WatchRecordsLoop; owns lifecycle, backoff, health state |
| WatchRecordsLoop | Stateless logic of a single iteration: poll + handle ENTRY_CREATE + periodic cleanup |
| WatchRecordsTaskHealthIndicator | ReactiveHealthIndicator that exposes task state via `/actuator/health` |
| StartupTelegramNotifier | Sends owner one Telegram message on ApplicationReadyEvent (indirect restart-frequency signal) |
| FirstTimeScanTask | Initial scan on startup (disable: `DISABLE_FIRST_SCAN=true`) |

### Selective watching

WatchRecordsLoop uses selective watching to limit monitored directories:
- Only directories within `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

WatchRecordsLoop parses `.mp4` filenames to extract camera ID, date, time, timestamp.

### Supervision

WatchRecordsTask runs the loop on a dedicated `Dispatchers.IO.limitedParallelism(1)` coroutine (lifecycle via `@PostConstruct`/`@PreDestroy`):
- Any non-cancellation exception is logged at ERROR, then exponential backoff (5s → 60s, capped). `currentBackoff` resets to 5s after `SUCCESSES_TO_RESET_BACKOFF=5` consecutive successes after a failure.
- `ClosedWatchServiceException` triggers WatchService recreation in the next iteration (`registeredDirs` is cleared and re-registered from scratch).
- `CancellationException` propagates cleanly — no backoff, no failure-bookkeeping.

All supervision thresholds (`INITIAL_BACKOFF`, `MAX_BACKOFF`, `SUCCESSES_TO_RESET_BACKOFF`, `HEALTH_STALENESS`) are hardcoded constants in `WatchRecordsTask.kt` — by intent (single-deployment project, no operator-tuning expected).

### Health & self-healing

WatchRecordsTaskHealthIndicator exposes `watchRecordsTask` component in `/actuator/health` with one of:
- **UP** — supervisor running normally, last successful iteration within `HEALTH_STALENESS=2m`, or just started up.
- **OUT_OF_SERVICE** — in backoff after one or more consecutive failures (transient).
- **DOWN** — supervisor coroutine not active, OR no successful iteration for longer than `HEALTH_STALENESS` while failures keep happening (permanent).

`/actuator/health` aggregation propagates DOWN → docker healthcheck returns non-200 → after `retries=3 × interval=30s ≈ 90s` docker marks unhealthy → `restart: unless-stopped` recreates the container. Self-healing for permanent errors (PG corruption, IO errors, etc).

### Startup notification

StartupTelegramNotifier listens for `ApplicationReadyEvent` and sends the bot owner one plain-text message containing version, commit hash, build time, and current timestamp. Indirect signal for an operator: if startup messages arrive frequently, the container is restarting often — investigate logs and `/actuator/health`. Gated by `@ConditionalOnProperty(application.telegram.enabled=true)` — no-op when Telegram is disabled. Failures during send are caught and logged at WARN; they do NOT prevent application startup.
```

- [ ] **Step 10.2: Commit**

```bash
git add .claude/rules/pipeline.md
git commit -m "docs: update pipeline rules for supervisor + health + startup notification

Refs: docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md §8"
```

---

## Task 11: Full build + ktlint + manual sanity

> **Context:** spec §9 step 4-5. Перед external code review нужно убедиться, что весь проект собирается и lint-проходит.

- [ ] **Step 11.1: Полная сборка всего проекта**

Делегировать через build-runner:

```
Build target: ./gradlew build
```

Expected: BUILD SUCCESSFUL.

Если ktlint падает — `./gradlew ktlintFormat` и retry.
Если test падает в test-профиле из-за `@PostConstruct fun start()` пытающегося реально создать WatchService — убедиться что `springProfileHelper.isTestProfile()` возвращает `true` (проверь `@ActiveProfiles` в `FrigateAnalyzerApplicationTests`).

- [ ] **Step 11.2: Manual sanity на локальном Docker (optional но рекомендуется)**

Этот шаг — для оператора, не для CI. Если есть локальный Docker setup:

```bash
cd docker/deploy
./deploy-up.sh

# В отдельном терминале — наблюдать логи:
docker logs -f deploy-frigate-analyzer-1
```

Ожидаемое поведение:
1. В Telegram владельцу приходит startup-сообщение.
2. В логах: `Watch service created; registered N directories.`
3. `curl http://localhost:8080/frigate-analyzer/actuator/health` → `{"status":"UP", ..., "components": {"watchRecordsTask":{"status":"UP", ...}}}`.

Тест на supervisor (вручную):
```bash
# Скопировать кривой файл в один из watched directories (триггерит parse exception).
# ВАЖНО: на host'е, НЕ внутри контейнера — volume смонтирован read-only (:ro)
# в frigate-analyzer контейнере, поэтому изнутри `docker exec ... touch` упадёт с EROFS.
touch /path/to/host/mount/$(date +%Y-%m-%d)/garbage.txt
# (или подобный — не .mp4)

# Проверить лог:
docker logs deploy-frigate-analyzer-1 | tail -20
```

Ожидаемое:
- ERROR в логе с stack trace
- `WatchRecordsTask iteration failed; backing off for PT5S`
- Через ~5 сек — продолжение iterations
- `/actuator/health` → `outOfService` для `watchRecordsTask` в момент backoff'а, обратно `UP` после следующего успеха

После проверки — `docker compose down`.

- [ ] **Step 11.3: External code review (optional, но spec этого хочет)**

Запустить `superpowers:code-reviewer` agent (или внешние — `codex`, `gemini`, `ccs`):

```
Use Skill: superpowers:requesting-code-review (или external-code-review для multi-agent)
```

Зафиксить критичные комментарии. Build → если ktlint падает после фиксов, `./gradlew ktlintFormat` + retry.

---

## Task 12: Подготовка к PR — удалить spec/plan из diff

> **Context:** global CLAUDE.md (`/home/zinin/.claude/CLAUDE.md`) требует: «Before creating a PR: `git rm` all files from `docs/superpowers/` and commit — plan documents must NOT appear in the PR diff». Документы остаются в git history ветки.

- [ ] **Step 12.1: Удалить spec и plan из tracked files**

```bash
git rm docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md
git rm docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md
```

- [ ] **Step 12.2: Commit**

```bash
git commit -m "chore: remove design/plan docs from tree before PR

Spec and plan remain accessible in branch git history; they must not
appear in the merged PR diff per repo convention."
```

- [ ] **Step 12.3: Финальная проверка `git log` и `git diff master..HEAD`**

```bash
git log --oneline master..HEAD
git diff --stat master..HEAD
```

Expected: видны все feature-коммиты + финальный chore. Diff включает: `WatchRecordsTask.kt`, `WatchRecordsLoop.kt`, `WatchRecordsTaskHealthIndicator.kt`, `StartupTelegramNotifier.kt`, `ApplicationListener.kt`, `TelegramNotificationService.kt` (+ impls), 5 тестовых файлов, `.claude/rules/pipeline.md`. НЕТ `docs/superpowers/*` файлов.

- [ ] **Step 12.4: Push и создать PR (только когда user даст добро)**

**Не push'ить и не создавать PR без явного указания пользователя.** Когда придёт «push» / «создай PR» от пользователя:

```bash
git push -u origin fix/watch-records-supervisor
gh pr create --title "fix(records-watcher): coroutine supervisor + health + startup notification" \
  --body "$(cat <<'EOF'
## Summary

Closes the architectural bug in WatchRecordsTask that triggered identical silent
failures in [docs/incidents/2026-05-17-postgres-corruption.md](docs/incidents/2026-05-17-postgres-corruption.md)
(~30h downtime) and [docs/incidents/2026-05-23-sata-cable-corruption.md](docs/incidents/2026-05-23-sata-cable-corruption.md)
(~7h downtime).

- `WatchRecordsTask` rewritten as coroutine supervisor (@PostConstruct/@PreDestroy);
  catches non-cancellation exceptions, exponential backoff (5s → 60s, reset after 5
  successes), recreates WatchService on ClosedWatchServiceException.
- New `WatchRecordsTaskHealthIndicator` exposes UP/OUT_OF_SERVICE/DOWN under
  `/actuator/health.components.watchRecordsTask`. Permanent failures eventually
  flip aggregated status to DOWN → docker healthcheck unhealthy → `restart:
  unless-stopped` self-heals.
- New `StartupTelegramNotifier` sends bot owner one message on every ApplicationReadyEvent
  (indirect restart-frequency signal).

## Test plan

- [x] `./gradlew test` passes for all modules
- [x] `./gradlew ktlintCheck` passes
- [ ] Manual smoke: drop non-mp4 file into watched dir, verify ERROR + backoff + recovery in logs
- [ ] Manual smoke: `curl /actuator/health` shows `watchRecordsTask` component
- [ ] Manual smoke: startup message arrives in Telegram

EOF
)"
```

---

## Self-review checklist (выполнить перед началом execution)

- [ ] **Spec coverage:** Все секции spec'а имеют покрывающий task — Task 1-2 (§4.5), Task 3 (§4.4), Task 4-5 (§4.2, §7.2), Task 6 (§4.1), Task 7 (§3), Task 8 (§7.1), Task 9 (§4.3), Task 10 (§8). Acceptance criteria mapping в §8 spec'а проверен.
- [ ] **Placeholder scan:** Нет TBD/TODO без конкретной step-ссылки. `TODO Task 5: ...` маркеры внутри `WatchRecordsLoop.kt` после Step 4.3 — это намеренные in-progress маркеры внутри одного TDD-серии (закрываются в Step 5.2 / 5.8), не настоящие placeholders. Допустимо.
- [ ] **Type consistency:** `IterationResult(processedEvents, lastCleanupAt)` — одинаковая сигнатура везде. `computeHealth(now: Instant): Health` — одинаковая в task и в тестах. `WatchRecordsLoop` параметры одинаковы во всех вызовах. Метод `userService.findActiveByUsername(...)` — единственный owner-discovery механизм.
