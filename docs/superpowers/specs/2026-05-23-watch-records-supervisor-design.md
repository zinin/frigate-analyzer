# Design: `WatchRecordsTask` supervisor + health + startup notification

- **Date:** 2026-05-23
- **Branch:** `fix/watch-records-supervisor`
- **Related incidents:**
  - [docs/incidents/2026-05-17-postgres-corruption.md](../../incidents/2026-05-17-postgres-corruption.md) — первое срабатывание (PG `XX002`, индекс corruption, ~30 ч простоя)
  - [docs/incidents/2026-05-23-sata-cable-corruption.md](../../incidents/2026-05-23-sata-cable-corruption.md) — второе срабатывание (тот же сценарий, ~7 ч простоя)
- **Source prompt:** [docs/tasks/watch-records-task-supervisor-prompt.md](../../tasks/watch-records-task-supervisor-prompt.md)

## 1. Контекст и проблема

`WatchRecordsTask.run()` под `@Async` — это голый `while (!stopped.get())` без supervisor. Первое же необработанное исключение из тела цикла (`R2dbcException`, `IOException` из `Files.readAttributes`, `IllegalArgumentException` из `recordingFileHelper.parse` и т. д.) ловит `SimpleAsyncUncaughtExceptionHandler`, поток `task-1` умирает навсегда, Spring `@Async` его не воскрешает. Никакого retry, никакого алёрта, никакого health-сигнала. Pipeline после этого работает в пустоту: `FrameExtractorProducer` находит 0 unprocessed (БД не пополняется) → нет уведомлений. Внешне всё «healthy».

Тот же сценарий уже стрельнул дважды. Цель — гарантировать, что следующая транзиентная ошибка PG/IO превратится в **одну строку в логе + автоматический restart**, а не в многочасовой outage с обнаружением вручную.

**Замечание о паттерне:** Это новый паттерн для проекта (существующий `SignalLossMonitorTask` использует `@Scheduled(fixedDelay)` + `suspend tick()` — он не разделяет coroutine supervisor + dedicated dispatcher idiom, который мы здесь вводим).

## 2. Goals / Non-goals

**Goals (что делаем в этом design'е):**

1. Любое non-cancellation исключение в теле итерации **не убивает supervisor**: логируется, делается exponential backoff, цикл продолжает работать.
2. При `ClosedWatchServiceException` / любой другой смерти `WatchService` — он **пересоздаётся** в следующей итерации, регистрации восстанавливаются.
3. Spring HealthIndicator `watchRecordsTask` отдаёт реальное состояние writer'а; постоянная неработоспособность → DOWN → docker healthcheck unhealthy → автоматический рестарт через `restart: unless-stopped`.
4. На каждый успешный старт контейнера в Telegram владельцу бота приходит одно сообщение «запущен» — пользователь по частоте этих сообщений видит, как часто рестартует контейнер.
5. TDD: failing tests первыми, потом имплементация.

**Non-goals (что **не** делаем — оставляем на будущее):**

- Telegram-алёрт при первом же exception / при переходе в backoff. Self-healing через docker-restart + startup-сообщения покрывают сценарий «частые ошибки».
- Расширение `RecordsWatcherProperties` параметрами supervisor/health. Все пороги — захардкоженные константы внутри `WatchRecordsTask` (`INITIAL_BACKOFF`, `MAX_BACKOFF`, `SUCCESSES_TO_RESET_BACKOFF`, `HEALTH_STALENESS`). Если когда-нибудь понадобится оператору тюнить — выносим тогда.
- Метрики Micrometer (`watch_records_task_*`). Можно добавить отдельной задачей позже.
- Liveness-group в actuator. Сейчас наш indicator идёт в общий `/actuator/health`. Если когда-нибудь R2DBC-ping станет валить общий health и приводить к ложным restart-ам, вынесем в liveness-group отдельной задачей.
- Hardware/PG side (`pg_checksums --enable`, `btrfs scrub`, REINDEX) — отдельный план в incident-репортах.
- Изменение контракта `WatchRecordsTask.shutdown()` (теперь это `@PreDestroy`, semantics — те же: остановить и закрыть всё).
- Касание `FirstTimeScanTask`.

## 3. Архитектурный обзор

```
modules/core/.../core/task/
├── WatchRecordsTask.kt              (supervisor + lifecycle + state для health)
├── WatchRecordsLoop.kt              (одна итерация: poll + обработка + cleanup)
└── WatchRecordsTaskHealthIndicator.kt   (ReactiveHealthIndicator, читает state из WatchRecordsTask)

modules/core/.../core/application/
└── StartupTelegramNotifier.kt       (@EventListener(ApplicationReadyEvent), вызывает TelegramNotificationService.sendOwnerMessage)

modules/telegram/.../service/
├── TelegramNotificationService.kt   (+ fun sendOwnerMessage(text: String))
└── service/impl/
    ├── TelegramNotificationServiceImpl.kt   (импл sendOwnerMessage через существующий queue/sender)
    └── NoOpTelegramNotificationService.kt   (пустой override)
```

### Изменения за пределами `modules/core/.../core/task/`:

| Файл | Что меняется |
|---|---|
| `modules/core/.../core/application/ApplicationListener.kt` | Убрать вызов `watchRecordsTask.run()` (теперь lifecycle через `@PostConstruct`). Убрать обработку `ContextClosedEvent` для shutdown (теперь `@PreDestroy`). Test-profile-skip переезжает внутрь `WatchRecordsTask.start()`. |
| `modules/telegram/.../service/TelegramNotificationService.kt` | Добавить `suspend fun sendOwnerMessage(text: String)`. |
| `modules/telegram/.../service/impl/TelegramNotificationServiceImpl.kt` | Имплементация `sendOwnerMessage` — отправка владельцу через ту же инфру, что использует signal-loss-уведомления. |
| `modules/telegram/.../service/impl/NoOpTelegramNotificationService.kt` | Пустой `override suspend fun sendOwnerMessage(text: String) {}`. |
| `.claude/rules/pipeline.md` | Раздел `## File Watching` — описать supervision, health-indicator, startup-notification. |

### Что **не** меняется

- `docker/deploy/docker-compose.yml` — healthcheck уже на `/actuator/health`, наш HealthIndicator туда подключится автоматически.
- `application.yaml`, `RecordsWatcherProperties.kt`, `docker/deploy/.env.example`, `.claude/rules/configuration.md` — констант в конфиге не выносим.
- `CLAUDE.md` (root) — команды не меняются.

## 4. Component design

### 4.1 `WatchRecordsTask` (supervisor)

Spring `@Component`. Lifecycle через `@PostConstruct` / `@PreDestroy`.

Конструкторские параметры (Spring DI + testability defaults):

```kotlin
@Component
class WatchRecordsTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val loop: WatchRecordsLoop,
    private val clock: Clock,
    private val springProfileHelper: SpringProfileHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
)
```

`dispatcher` и `watchServiceFactory` имеют дефолты — Spring их не инжектит, тесты подсовывают `StandardTestDispatcher` и mock-фабрику.

Владеет:

- `CoroutineScope(SupervisorJob + dispatcher + CoroutineName("watch-records"))` — dedicated scope, изолирован от остального приложения; в production `dispatcher = Dispatchers.IO.limitedParallelism(1)` гарантирует ровно одну ниточку для блокирующего `watchService.poll()`.
- `@Volatile internal var supervisorJob: Job? = null` — handle на запущенную корутину для cancel + join. `supervisorJob` is `internal var` purely to allow unit tests to inject a `Job` returned by `Job()` or a relaxed `mockk<Job>` with `isActive=true` when exercising `computeHealth`'s active-job branches.
- `watchService: WatchService?` — `var`, пересоздаётся при `ClosedWatchServiceException`.
- `registeredDirs: ConcurrentHashMap<Path, WatchKey>` — как сейчас.
- State для health (все `@Volatile` для безопасного чтения из HealthIndicator-bean):
  - `lastSuccessfulIterationAt: Instant?` — null до первой успешной итерации.
  - `consecutiveFailures: Long` — счётчик подряд провалов; сбрасывается в 0 при успехе.
  - `successesSinceLastFailure: Int` — для логики `SUCCESSES_TO_RESET_BACKOFF`. Инкрементируется только во время failure-периода (`currentBackoff > INITIAL_BACKOFF`).
  - `currentBackoff: Duration` — текущая величина backoff'а.
  - `lastFailure: Throwable?` — последний пойманный exception (для health details).

Жизненный цикл:

```kotlin
@PostConstruct
fun start() {
    if (springProfileHelper.isTestProfile()) {
        logger.info { "Test profile detected. Watch records task skipped." }
        return
    }
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
    runCatching { watchService?.close() }
    logger.info { "Watch records shut down." }
}
```

> **Note:** `scope.cancel()` releases the dedicated `Dispatchers.IO.limitedParallelism(1)` view so multiple Spring context refreshes (tests) do not leak slots.

Константы (захардкожено в файле):

```kotlin
private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private const val SUCCESSES_TO_RESET_BACKOFF: Int = 5
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(2)
```

Supervisor loop:

```kotlin
private suspend fun runSupervised() {
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
            watchService = null
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
```

> **Note:** `Throwable` would swallow `OutOfMemoryError`/`LinkageError`/`StackOverflowError` — we let those propagate to the JVM.

`ensureWatchService()` и `closeWatchServiceQuietly()`:

```kotlin
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
```

> **Atomic publication:** if `registerAllDirs` throws, the local `ws` is closed and `watchService` field is left null so the next iteration retries cleanly.

State-машина транзишн-методов:

```kotlin
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
```

### 4.2 `WatchRecordsLoop` (iteration)

Spring `@Component`. Stateless logic — одна итерация цикла. State (`lastCleanupAt`, `registeredDirs`) принимается параметрами и возвращается через `IterationResult`. Не знает про supervision. Бросает любые исключения наверх.

```kotlin
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
    ): IterationResult { ... }

    fun registerAllDirs(
        start: Path,
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
    ): Int { ... }
}

data class IterationResult(
    val processedEvents: Int,
    val lastCleanupAt: Instant,
)
```

Тело `runIteration` — те же шаги, что в текущем коде между `key = watchService.poll(...)` и обновлением `lastCleanup`. Никакого `runBlocking` внутри — это уже `suspend fun`, `recordingEntityHelper.createRecording(...)` вызывается напрямую через `.coAwait()`/uncoroutine-ный mapping (уже используется через runBlocking → переходим на прямой suspend).

> **Concurrency note:** `cleanupExpiredDirs` and event-processing run on a single dedicated dispatcher thread; `ConcurrentHashMap` is used for safe reads from the HealthIndicator only, not for concurrent writers.

Утилиты `extractDateFromPath` / `isWithinWatchPeriod` остаются top-level в файле (как сейчас) — они pure, переезд внутрь класса не даст профита.

> **Exception safety in `runIteration`:** After processing events, `key.reset()` runs in a `finally` block so a thrown exception from `Files.readAttributes` / `recordingFileHelper.parse` / `recordingEntityHelper.createRecording` still resets the key; the captured throwable is then rethrown to the supervisor. (Plan §5.2 carries the actual code change.)

### 4.3 `WatchRecordsTaskHealthIndicator`

`ReactiveHealthIndicator` (WebFlux-стек). Делегирует логику в `WatchRecordsTask.computeHealth(now)` — там вся state-таблица.

```kotlin
@Component
class WatchRecordsTaskHealthIndicator(
    private val task: WatchRecordsTask,
    private val clock: Clock,
) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> = Mono.fromSupplier { task.computeHealth(Instant.now(clock)) }
}
```

В `WatchRecordsTask`:

```kotlin
fun computeHealth(now: Instant): Health {
    val builder = Health.Builder()
        .withDetail("lastSuccessfulIterationAt", lastSuccessfulIterationAt?.toString() ?: "never")
        .withDetail("consecutiveFailures", consecutiveFailures)
        .withDetail("currentBackoff", currentBackoff.toString())
        .withDetail("registeredDirs", registeredDirs.size)
    lastFailure?.let {
        builder.withDetail("lastFailure", "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}")
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
            builder.outOfService().withDetail("reason", "no successful iteration yet, in backoff").build()
        Duration.between(last, now) > HEALTH_STALENESS && consecutiveFailures > 0 ->
            builder.down().withDetail("reason", "stale: no success for ${Duration.between(last, now)}").build()
        consecutiveFailures > 0 ->
            builder.outOfService().withDetail("reason", "in backoff after $consecutiveFailures consecutive failures").build()
        else -> builder.up().build()
    }
}
```

Bean name `watchRecordsTaskHealthIndicator` → Spring Actuator показывает как `watchRecordsTask` в `/actuator/health.components`.

### 4.4 `StartupTelegramNotifier`

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartupTelegramNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val gitProperties: GitProperties,
    private val buildProperties: BuildProperties,
    private val clock: Clock,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        val text = buildString {
            appendLine("🟢 Frigate Analyzer запущен")
            appendLine("Version: ${buildProperties.version}")
            appendLine("Commit: ${gitProperties.commitId.take(8)}")
            appendLine("Build time: ${buildProperties.time}")
            append("Started: ${Instant.now(clock)}")
        }
        runCatching {
            runBlocking { telegramNotificationService.sendOwnerMessage(text) }
        }.onFailure { logger.warn(it) { "Failed to send startup notification" } }
    }
}
```

`@ConditionalOnProperty` гарантирует, что bean не регистрируется при `TELEGRAM_ENABLED=false` — никакой попытки отправки, никакого шума в логах.

### 4.5 `TelegramNotificationService.sendOwnerMessage(text)`

Контракт:
- Получатель — владелец бота (`application.telegram.owner`).
- Реализация в `TelegramNotificationServiceImpl` — отправка через ту же инфру, что используют signal-loss-методы (см. `SignalLossMessageFormatter` + `TelegramNotificationQueue` / `TelegramNotificationSender`). Конкретный механизм (queue vs прямой sender) уточняется на этапе writing-plans после чтения `TelegramNotificationServiceImpl` целиком.
- `NoOpTelegramNotificationService.sendOwnerMessage(text)` — пустое тело (как другие методы там).

## 5. Поведение supervisor — state-таблицы

### 5.1 Reaction на тип исключения

| Тип исключения | Что делаем |
|---|---|
| `CancellationException` | Пробрасываем без модификации. Никакого backoff, никакого ERROR-лога, никакой записи в `lastFailure`. |
| `ClosedWatchServiceException` | WARN-лог, `closeWatchServiceQuietly()`, `registeredDirs.clear()`, `watchService = null`. Backoff делаем. В следующей итерации `ensureWatchService()` создаст новый. |
| `Throwable` (всё остальное: `R2dbcException`, `IOException`, `IllegalArgumentException`, ...) | ERROR-лог с stack trace, `onIterationFailure(e)`, backoff. `watchService` остаётся живой — продолжаем poll в следующей итерации. |

### 5.2 Health status state-таблица

| supervisorJob | lastSuccessfulIterationAt | consecutiveFailures | now − last vs HEALTH_STALENESS | Status | Reason |
|---|---|---|---|---|---|
| null или !isActive | * | * | * | DOWN | supervisor not active |
| active | null | 0 | n/a | UP | starting up |
| active | null | > 0 | n/a | OUT_OF_SERVICE | no successful iteration yet, in backoff |
| active | non-null | > 0 | > staleness | DOWN | stale: no success for X |
| active | non-null | > 0 | ≤ staleness | OUT_OF_SERVICE | in backoff after N consecutive failures |
| active | non-null | 0 | * | UP | (норма) |

### 5.3 Backoff progression (с дефолтами 5s / 60s / 5)

| Iteration outcome | currentBackoff после | successesSinceLastFailure |
|---|---|---|
| (start) | 5s | 0 |
| failure | 5s → delay → next=10s | 0 |
| failure | 10s → delay → next=20s | 0 |
| failure | 20s → delay → next=40s | 0 |
| failure | 40s → delay → next=60s (max) | 0 |
| failure | 60s → delay → next=60s (capped) | 0 |
| success | 60s | 1 |
| success | 60s | 2 |
| success | 60s | 3 |
| success | 60s | 4 |
| success | **5s (reset)** | 0 (тоже reset) |

Reset происходит когда `successesSinceLastFailure >= SUCCESSES_TO_RESET_BACKOFF` И `currentBackoff > INITIAL_BACKOFF` — мы пишем INFO-лог «Backoff reset after N consecutive successes».

## 6. Cancellation semantics

- `@PreDestroy.shutdown()` вызывает `supervisorJob?.cancel()` → внутри `runSupervised()` будет `CancellationException` на следующем suspend-point (`delay`, `loop.runIteration`). Catch для `CancellationException` пробрасывает дальше → `launch{}` нормально завершается.
- `runBlocking { supervisorJob?.join() }` ждёт фактической остановки. Это блокирующее в shutdown-фазе — нормально для `@PreDestroy`.
- `watchService.close()` после `join()` — гарантированно после того, как loop вышел из poll'а.
- Если кто-то снаружи отменит `scope` (что в текущем design'е не происходит), результат тот же.

## 7. Тесты (TDD)

Подход — failing tests первыми, потом имплементация. Все тесты — изолированные unit-тесты, никаких Spring-контекстов, никаких реальных WatchService.

### 7.1 `WatchRecordsTaskTest.kt` (supervisor)

Используется `runTest{}` + `StandardTestDispatcher` для virtual time (`delay(5s)` мгновенно проходит). Конструктор `WatchRecordsTask` получает dispatcher как необязательный параметр с дефолтом `Dispatchers.IO.limitedParallelism(1)`, в тестах — testDispatcher.

| # | Test | Что проверяет |
|---|---|---|
| 1 | `supervisor survives RuntimeException and continues looping` | `loop.runIteration` бросает `RuntimeException` дважды, потом возвращает результат. Job жив; `lastFailure` зафиксирован; `consecutiveFailures` проходит 0→1→2→0; `currentBackoff` 5s→10s→5s после reset. |
| 2 | `ClosedWatchServiceException triggers watchService recreation` | Loop бросает `ClosedWatchServiceException`; следующая итерация видит новый `WatchService`; `registeredDirs` пересоздан. Проверяется через инжект `WatchServiceFactory` (моки, не реальный FS). |
| 3 | `CancellationException is propagated and exits loop` | Внешний `supervisorJob.cancel()`; verify что `runSupervised` завершён; `lastFailure` не обновлён; `consecutiveFailures` не инкрементирован. |
| 4 | `backoff resets after N consecutive successes` | 3 ошибки подряд (backoff достигает 20s), потом 5 успехов; `currentBackoff` обратно на 5s после 5-го успеха; INFO-лог «Backoff reset» зафиксирован. |
| 5 | `computeHealth covers all 5 states` | Таблица: (job=null) → DOWN; (active, last=null, fails=0) → UP; (active, last=null, fails>0) → OUT_OF_SERVICE; (active, stale, fails>0) → DOWN; (active, last fresh, fails>0) → OUT_OF_SERVICE; (active, fails=0) → UP. |

**Архитектурный момент для testability:** `WatchRecordsTask` принимает в конструкторе:
- `dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)` — для test override
- `watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() }` — чтобы в тесте подсунуть mock factory

### 7.2 `WatchRecordsLoopTest.kt` (iteration в изоляции)

| # | Test | Что проверяет |
|---|---|---|
| 1 | `runIteration returns empty result when poll timeouts` | `watchService.poll(...)` возвращает null; `processedEvents=0`, `lastCleanupAt` не изменён (если рано). |
| 2 | `runIteration creates recording for new .mp4 file` | Mock-key с ENTRY_CREATE на `cam1-2026-05-23-12.14.27.mp4`; `recordingEntityHelper.createRecording(...)` вызван ровно один раз с правильным `CreateRecordingRequest`. |
| 3 | `runIteration registers new directory recursively` | Mock-key с ENTRY_CREATE на путь, который `Files.isDirectory`; `registerAllDirs` вызван; `createRecording` НЕ вызван. |
| 4 | `runIteration propagates parse exception` | `recordingFileHelper.parse` бросает `IllegalArgumentException`; exception проходит наружу. |
| 5 | `runIteration runs cleanup when interval elapsed` | `lastCleanupAt` старше `cleanupInterval`; expired keys удалены из `registeredDirs`; новый `lastCleanupAt` возвращён в результате. |

### 7.3 `WatchRecordsTaskHealthIndicatorTest.kt`

| # | Test | Что проверяет |
|---|---|---|
| 1 | `health() delegates to task.computeHealth(now)` | Mock `WatchRecordsTask.computeHealth(now)` возвращает фиксированный `Health.up().build()`; `health()` возвращает Mono с тем же объектом. |

### 7.4 `StartupTelegramNotifierTest.kt`

| # | Test | Что проверяет |
|---|---|---|
| 1 | `onReady sends owner message with version+commit+time` | `gitProperties`/`buildProperties` mocked; verify `telegramNotificationService.sendOwnerMessage(text)` вызван; text содержит version, commit (8 chars), build time, started. |
| 2 | `onReady swallows exception from sendOwnerMessage` | `sendOwnerMessage` бросает; `onReady` не падает; WARN залогирован. |

### 7.5 Существующие тесты

`WatchRecordsTaskTest.kt` уже содержит тесты на `extractDateFromPath` / `isWithinWatchPeriod`. Top-level pure функции остаются в файле `WatchRecordsLoop.kt` (или `WatchRecordsTask.kt` — на этапе implementation решим, где они логичнее). Существующие тесты переезжают рядом с этими функциями (новый класс / тот же класс — без потери покрытия).

## 8. Acceptance criteria mapping (с учётом сокращённого scope)

| AC из prompt'а | Покрытие в этом design'е |
|---|---|
| 1. Supervision (любое non-cancel исключение не убивает поток, backoff, cancel пробрасывается) | §4.1 supervisor loop + §5.1 reaction table + §6 cancellation |
| 2. Recreation of WatchService | §4.1 ensureWatchService + §5.1 ClosedWatchServiceException row |
| 3. Health indicator (UP/DOWN/OUT_OF_SERVICE) | §4.3 + §5.2 state-таблица |
| 4. Telegram alert на каждый backoff-переход | **OUT OF SCOPE** — решение по согласованию с пользователем (см. §2 non-goals). Заменено на startup-notification (§4.4). |
| 5. Конфигурация в RecordsWatcherProperties | **OUT OF SCOPE** — константы захардкожены (см. §2 non-goals). |
| 6. TDD-тесты | §7 |
| 7. No «while I'm here» refactoring | Соблюдено. Не трогаем `FirstTimeScanTask`, `SignalLossMonitorTask`, pipeline/facade. Касания за пределами `core/task/` — только минимально-необходимые (см. §3). |
| 8. Документация (.claude/rules/pipeline.md, CLAUDE.md) | §3 — обновляем только pipeline.md; CLAUDE.md не трогаем (команды не меняются). |
| 9. Git hygiene (`git add` после каждого изменения, отдельная branch, conventional commits, ссылки на оба incident report) | Соблюдается в writing-plans / implementation фазе. Branch `fix/watch-records-supervisor` уже создан. |
| 10. Code review (superpowers:code-reviewer) | На этапе implementation, после TDD-цикла. |

## 9. Workflow (от этого design'а к merge)

1. **Design doc** (этот файл) — коммит на `fix/watch-records-supervisor`.
2. **Implementation plan** (через `superpowers:writing-plans`) — коммит туда же (`docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md`).
3. **Implementation** через `superpowers:subagent-driven-development` или последовательно — серия коммитов по TDD-циклам (failing test → impl → green).
4. **Code review** через `superpowers:code-reviewer` после имплементации. Критичные комментарии — фиксим. Build → если ktlint падает, `./gradlew ktlintFormat` + retry.
5. **Manual sanity** на локальном Docker: имитировать ошибку (отвалить PG / положить файл с invalid filename), убедиться по логам что supervisor выжил, health → OUT_OF_SERVICE → восстановился.
6. **Перед PR:** `git rm docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md` отдельным коммитом — plan/spec остаются в branch history, но не идут в PR diff.
7. **PR** с body, ссылающимся на оба incident report.
