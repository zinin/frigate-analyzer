# Design: `WatchRecordsTask` supervisor + health + startup notification

- **Date:** 2026-05-23
- **Branch:** `fix/watch-records-supervisor`
- **Related incidents:**
  - [docs/incidents/2026-05-17-postgres-corruption.md](../../incidents/2026-05-17-postgres-corruption.md) — первое срабатывание (PG `XX002`, индекс corruption, ~30 ч простоя)
  - [docs/incidents/2026-05-23-sata-cable-corruption.md](../../incidents/2026-05-23-sata-cable-corruption.md) — второе срабатывание (тот же сценарий, ~7 ч простоя)
- **Source prompt:** [docs/tasks/watch-records-task-supervisor-prompt.md](../../tasks/watch-records-task-supervisor-prompt.md)

## 1. Контекст и проблема

`WatchRecordsTask.run()` под `@Async` — это голый `while (!stopped.get())` без supervisor. Первое же необработанное исключение из тела цикла (`R2dbcException`, `IOException` из `Files.readAttributes`, `IllegalArgumentException` из `recordingFileHelper.parse` и т. д.) ловит `SimpleAsyncUncaughtExceptionHandler`, поток `task-1` умирает навсегда, Spring `@Async` его не воскрешает. Никакого retry, никакого алёрта, никакого health-сигнала. Pipeline после этого работает в пустоту: `FrameExtractorProducer` находит 0 unprocessed (БД не пополняется) → нет уведомлений. Внешне всё «healthy».

Тот же сценарий уже стрельнул дважды. Цель — гарантировать, что следующая транзиентная ошибка PG/IO превратится в **одну строку в логе + автоматический retry с backoff + видимый health-сигнал**, а не в молчаливый многочасовой outage. Self-healing уровня «контейнер автоматически рестартует на permanent failure» в этой задаче **не реализуется** (см. §2 non-goals — `restart: unless-stopped` в обычном docker compose unhealthy-контейнеры не рестартует; чтобы это исправить, нужен либо `System.exit` из приложения, либо autoheal sidecar — оба решения вне scope).

**Замечание о паттерне:** Это новый паттерн для проекта (существующий `SignalLossMonitorTask` использует `@Scheduled(fixedDelay)` + `suspend tick()` — он не разделяет coroutine supervisor + dedicated dispatcher idiom, который мы здесь вводим).

## 2. Goals / Non-goals

**Goals (что делаем в этом design'е):**

1. Любое non-cancellation исключение в теле итерации **не убивает supervisor**: логируется, делается exponential backoff, цикл продолжает работать.
2. При `ClosedWatchServiceException` / любой другой смерти `WatchService` — он **пересоздаётся** в следующей итерации, регистрации восстанавливаются.
3. Spring HealthIndicator `watchRecordsTask` отдаёт реальное состояние writer'а; постоянная неработоспособность → DOWN. Docker healthcheck помечает контейнер `unhealthy` — это **пассивный сигнал** для оператора (`docker ps`, `curl /actuator/health`), **не trigger автоматического рестарта**. Оператор замечает `unhealthy` или отсутствие новых recording-уведомлений и делает `docker restart` вручную (см. §2 non-goals).
4. На каждый старт контейнера в Telegram владельцу бота приходит одно сообщение «запущен» — это работает только при `docker restart`/deploy (ручном или после OOM); косвенный сигнал «приложение перезапустилось».
5. TDD: failing tests первыми, потом имплементация.

**Non-goals (что **не** делаем — оставляем на будущее):**

- Telegram-алёрт при первом же exception / при переходе в backoff.
- **Автоматический self-healing через рестарт контейнера.** Это требует либо `System.exit(...)` из приложения при sustained DOWN, либо autoheal sidecar в docker-compose (см. [iter-1 review §D1](2026-05-23-watch-records-supervisor-review-iter-1.md) — оба варианта явно отклонены пользователем). HealthIndicator используется только как **пассивный** indicator (`/actuator/health`, `docker ps`), мониторится оператором вручную. Trade-off принят сознательно: scope этой PR ограничен «supervisor + видимость», не «self-healing».
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
└── WatchRecordsTaskHealthIndicator.kt   (sync HealthIndicator, читает state из WatchRecordsTask; см. iter-1 §D9)

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
| `modules/core/.../core/application/ApplicationListener.kt` | Убрать вызов `watchRecordsTask.run()` (теперь lifecycle через `@EventListener(ApplicationReadyEvent)` — iter-1 §D6). Убрать обработку `ContextClosedEvent` для shutdown (теперь `@PreDestroy`). Test-profile-skip переезжает внутрь `WatchRecordsTask.start()`. **См. iter-2 CRITICAL-3:** окончательная схема старта (`@EventListener` vs explicit-call) определяется при обсуждении iter-2 disputed-issue. |
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

Spring `@Component`. Lifecycle через `@EventListener(ApplicationReadyEvent::class)` / `@PreDestroy` (iter-1 §D6).

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
- State для health (все `@Volatile` для безопасного чтения из HealthIndicator-bean). **Раздельный учёт пяти разных состояний** (см. iter-1 review §D2 — обоснование расширенного state):
  - `lastSuccessfulPollAt: Instant?` — heartbeat watcher loop'а. Обновляется при любом не-throwing `poll()` (включая empty). Сигнализирует, что supervisor-coroutine жива и крутится.
  - `lastEventProcessedAt: Instant?` — null до первого реально обработанного `ENTRY_CREATE`-события. Обновляется только когда хотя бы одно событие было успешно обработано (создан Recording / зарегистрирована новая директория). Empty poll этим **не** обновляет.
  - `consecutiveEventFailures: Long` — счётчик подряд провалов обработки события (`IOException` в `Files.readAttributes`, `IllegalArgumentException` в `parse`, `R2dbcException` в `createRecording`). Empty poll этим **не** сбрасывает. Сбрасывается в 0 только когда `eventsProcessed > 0`.
  - `lastSuccessfulRegistrationAt: Instant?` — null до первой успешной регистрации `WatchService` + всех директорий. Обновляется внутри `ensureWatchService()` после успешного `loop.registerAllDirs(...)`.
  - `consecutiveRegistrationFailures: Long` — счётчик подряд провалов регистрации (`registerAllDirs` бросил, `watchServiceFactory` бросил). Сбрасывается при успешной регистрации.
  - `consecutiveFailures: Long` — суммарный счётчик подряд провалов **итерации в целом** (event-failures + registration-failures + ClosedWatchServiceException). Используется для backoff progression. Сбрасывается при полностью успешной итерации (`eventsProcessed > 0`, либо empty poll **после** того как был хотя бы один success — нужно, чтобы backoff не «застрял» в idle).
  - `successesSinceLastFailure: Int` — для логики `SUCCESSES_TO_RESET_BACKOFF`. Инкрементируется только во время failure-периода (`currentBackoff > INITIAL_BACKOFF`).
  - `currentBackoff: Duration` — текущая величина backoff'а.
  - `lastFailure: Throwable?` — последний пойманный exception (для health details).
  - `startupAt: Instant` — время вызова `start()` (для `STARTUP_GRACE`-окна в health).

Жизненный цикл:

```kotlin
@EventListener(ApplicationReadyEvent::class)
fun start() {
    if (springProfileHelper.isTestProfile()) {
        logger.info { "Test profile detected. Watch records task skipped." }
        return
    }
    startupAt = Instant.now(clock)
    supervisorJob = scope.launch { runSupervised() }
}

@PreDestroy
fun shutdown() {
    logger.info { "Shutting down watch records..." }
    supervisorJob?.cancel()
    runBlocking { supervisorJob?.join() }
    // INVARIANT (iter-2 CONCERN-7): scope.cancel() must follow supervisorJob.join().
    // Any future children of `scope` must individually join before this line —
    // scope.cancel() cancels them all without waiting.
    scope.cancel()
    registeredDirs.values.forEach { it.cancel() }
    registeredDirs.clear()
    runCatching { watchService?.close() }
    logger.info { "Watch records shut down." }
}
```

> **Note:** `scope.cancel()` releases the dedicated `Dispatchers.IO.limitedParallelism(1)` view so multiple Spring context refreshes (tests) do not leak slots.

> **iter-1 review §D6:** Старт через `@EventListener(ApplicationReadyEvent)`, а не `@PostConstruct` — сохраняет порядок относительно `FirstTimeScanTask` (который тоже стартует из `ApplicationReadyEvent` в `ApplicationListener`). Гарантирует, что `FirstTimeScanTask` отработает до того, как watcher примет первое событие — иначе один и тот же файл мог бы пройти и через scan, и через ENTRY_CREATE. `@PreDestroy` остаётся как есть — Spring корректно вызывает destroy-callback'и при shutdown context'а независимо от того, кто стартовал bean.

Константы (захардкожено в файле):

```kotlin
private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private const val SUCCESSES_TO_RESET_BACKOFF: Int = 5
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(2)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)  // окно от start() до того, как «нет успешной регистрации» приводит к DOWN
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L       // после стольких подряд registration-failures — DOWN независимо от STARTUP_GRACE
```

> **iter-1 review §D8:** `HEALTH_STALENESS=2m` оставлен — с расширенным state (§D2) staleness применяется к `lastEventProcessedAt` (обновляется ТОЛЬКО при processed > 0), а не к heartbeat'у poll'а. При потоке frigate-recordings (с несколькими камерами файлы пишутся непрерывно) 2 минуты без обработанного события + растущий `consecutiveEventFailures` — достаточный сигнал. `MAX_BACKOFF=60s` влияет на частоту retry, не на staleness-окно. Если в production эта граница окажется слишком чувствительной — поднимем до 3m отдельной правкой.

Supervisor loop:

```kotlin
// iter-2 CRITICAL-1 (D2): обновлено к 3-методной transition модели.
// onPollCompleted(events, failures) разделяет poll-heartbeat от event-processing.
// onRegistrationFailure(t) / onRegistrationSuccess() — для startup-failure detection.
// onIterationFailureLegacy(t) — для ClosedWatchServiceException и generic loop failures.
private suspend fun runSupervised() {
    currentBackoff = INITIAL_BACKOFF
    var lastCleanup = Instant.now(clock)
    while (currentCoroutineContext().isActive) {
        try {
            ensureWatchService()  // вызывает onRegistrationSuccess/Failure внутри
            val result = loop.runIteration(watchService!!, registeredDirs, lastCleanup)
            lastCleanup = result.lastCleanupAt
            onPollCompleted(result.eventsProcessed, result.eventFailures)
            if (result.eventFailures > 0) {
                delay(currentBackoff.toMillis())
                currentBackoff = nextBackoff(currentBackoff)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClosedWatchServiceException) {
            logger.warn { "WatchService closed; will recreate next iteration" }
            closeWatchServiceQuietly()
            registeredDirs.clear()
            onIterationFailureLegacy(e)
            delay(currentBackoff.toMillis())
            currentBackoff = nextBackoff(currentBackoff)
        } catch (e: Exception) {
            logger.error(e) { "WatchRecordsTask iteration failed; backing off for $currentBackoff" }
            onIterationFailureLegacy(e)
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

State-машина транзишн-методов — см. §5.2.1 для финальных сигнатур после iter-1 D2 + iter-2 CRITICAL-1. Здесь схема:
- `onPollCompleted(events, failures)` — обновляет `lastSuccessfulPollAt`; при `events > 0` обновляет `lastEventProcessedAt`, сбрасывает `consecutiveEventFailures`/`consecutiveFailures`, вызывает `maybeResetBackoff()`; при `failures > 0` инкрементирует `consecutiveEventFailures`/`consecutiveFailures`. Empty poll трогает только `lastSuccessfulPollAt`.
- `onRegistrationSuccess()` — обновляет `lastSuccessfulRegistrationAt`, сбрасывает `consecutiveRegistrationFailures`.
- `onRegistrationFailure(t)` — инкрементирует `consecutiveRegistrationFailures` + `consecutiveFailures`, обновляет `lastFailure`.
- `onIterationFailureLegacy(t)` — для `ClosedWatchServiceException` и generic loop failures: только `consecutiveFailures++` + `lastFailure = t`.
- `maybeResetBackoff()` — инкрементирует `successesSinceLastFailure`, при достижении `SUCCESSES_TO_RESET_BACKOFF` сбрасывает `currentBackoff = INITIAL_BACKOFF`.
- `nextBackoff(current) = minOf(current.multipliedBy(2), MAX_BACKOFF)`.

Полный код — в plan Step 6.1 (sole source of truth).

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

**Sync `HealthIndicator`** — Spring Boot Actuator на WebFlux-стеке автоматически адаптирует через `HealthIndicatorReactiveAdapter`. `computeHealth()` — pure sync function без I/O/suspend, обёртка в `Mono.fromSupplier` была бы лишним indirection'ом (iter-1 review §D9).

> **Snapshot consistency (iter-1 review §D4):** Health-индикатор читает несколько `@Volatile` полей последовательно. Между чтениями single-writer supervisor может изменить значения — теоретически возможен inconsistent snapshot (например, свежий `consecutiveEventFailures`, старый `lastEventProcessedAt`) на ~100мс. Для single-user проекта без external alerting (Prometheus/Alertmanager) это невидимо — следующий `/actuator/health` poll вернёт согласованную картину. Решение принято: используем `@Volatile`-поля без обёртки в `AtomicReference<SupervisorState>` — обвязка не оправдана выгодой. Если в будущем появится active scraping (Prometheus), пересмотрим: тогда `AtomicReference` стоит ввести.

```kotlin
@Component
class WatchRecordsTaskHealthIndicator(
    private val task: WatchRecordsTask,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health = task.computeHealth(Instant.now(clock))
}
```

В `WatchRecordsTask` — реализация по 8-ветвевой таблице §5.2 (iter-1 §D2 + iter-2 CRITICAL-1). Полный код computeHealth() — см. plan Step 6.1 (sole source of truth для кода). Здесь — только сигнатура и схема details:

```kotlin
fun computeHealth(now: Instant): Health {
    // details: lastSuccessfulPollAt, lastEventProcessedAt, lastSuccessfulRegistrationAt,
    // consecutiveEventFailures, consecutiveRegistrationFailures, consecutiveFailures,
    // currentBackoff, registeredDirs.size, optional lastFailure
    // Затем 8 веток в priority-порядке (см. §5.2):
    // 1: supervisorJob == null || !job.isActive          → DOWN  "supervisor not active"
    // 2: registration не было И (попыток ≥ THRESHOLD ИЛИ grace истёк) → DOWN  "startup failed: ..."
    // 3: registration не было (grace ещё не истёк)        → OUT_OF_SERVICE "registering..."
    // 4: event-failures И stale event-processing          → DOWN  "stale: events failing for ..."
    // 5: event-failures И НЕ stale                        → OUT_OF_SERVICE "transient event-processing failures"
    // 6: consecutiveFailures > 0 (общий backoff branch)   → OUT_OF_SERVICE "in backoff after N failures"
    // 7: lastEventProcessedAt == null И > 2×HEALTH_STALENESS после startup → UP "idle: camera offline?"
    // 8: всё ок                                            → UP "healthy"
}
```

> **iter-2 CRITICAL-4 (D6/D9 follow-up):** `WatchRecordsTaskHealthIndicator` гейтится `@Profile("!test")`. В test profile `WatchRecordsTask.start()` короткозамкнут (early return), `supervisorJob = null` → indicator вернул бы DOWN → агрегированный `/actuator/health` стал бы DOWN → `FrigateAnalyzerApplicationTests.actuatorHealth()` упал бы (ожидает UP). Тот же паттерн что у `StartupTelegramNotifier`.

Bean name `watchRecordsTaskHealthIndicator` → Spring Actuator показывает как `watchRecordsTask` в `/actuator/health.components`.

### 4.4 `StartupTelegramNotifier`

> **iter-1 review §D7:** Bean регистрируется только если `application.telegram.enabled=true` И не активен профиль `test` И существуют bean'ы `GitProperties`/`BuildProperties`. Это убирает риск `NoSuchBeanDefinitionException` при отсутствии actuator git-info / spring-boot build-info (например, в минимальном test context'е). Альтернатива через `ObjectProvider<...>` дала бы более тонкую runtime-обработку, но `@ConditionalOnBean` достаточно — если bean'ов нет, не регистрируем notifier целиком.

```kotlin
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(GitProperties::class, BuildProperties::class)
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
            runBlocking {
                withTimeout(STARTUP_NOTIFICATION_TIMEOUT.toMillis()) {
                    telegramNotificationService.sendOwnerMessage(text)
                }
            }
        }.onFailure { logger.warn(it) { "Failed to send startup notification" } }
    }

    private companion object {
        val STARTUP_NOTIFICATION_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
```

`@ConditionalOnProperty` гарантирует, что bean не регистрируется при `TELEGRAM_ENABLED=false` — никакой попытки отправки, никакого шума в логах.

> **iter-1 review §D5:** `runBlocking + withTimeout(5s)` выбран как trade-off между «попытаться отправить до сигнала о ready» и «не заблокировать `ApplicationReadyEvent` бесконечно». На практике `sendOwnerMessage` вызывает только `notificationQueue.enqueue(...)` (без сетевого вызова — отправка асинхронная в queue-consumer'е), так что обычный path занимает микросекунды. Timeout страхует от регрессии: если в будущем `enqueue` станет блокирующим (заполнился buffer), мы не зависнем при старте.

### 4.5 `TelegramNotificationService.sendOwnerMessage(text)`

Контракт:
- Получатель — владелец бота (`application.telegram.owner`).
- Реализация в `TelegramNotificationServiceImpl` — отправка через ту же инфру, что используют signal-loss-методы (см. `SignalLossMessageFormatter` + `TelegramNotificationQueue` / `TelegramNotificationSender`). Конкретный механизм (queue vs прямой sender) уточняется на этапе writing-plans после чтения `TelegramNotificationServiceImpl` целиком.

> **iter-2 CONCERN-4 (acknowledged):** Добавление `sendOwnerMessage` смешивает user-facing notification API с operator/admin channel в одном интерфейсе. Принято как небольшой trade-off — notification queue / sender infrastructure общая, выделение `OwnerNotificationService` / `AdminNotificationPort` сейчас даст один-методный facade поверх той же реализации. Если в будущем admin-сигналов добавится (например, alert при backoff > N), то рефакторим в отдельный интерфейс.
- `NoOpTelegramNotificationService.sendOwnerMessage(text)` — пустое тело (как другие методы там).

## 5. Поведение supervisor — state-таблицы

### 5.1 Reaction на тип исключения

| Тип исключения | Что делаем |
|---|---|
| `CancellationException` | Пробрасываем без модификации. Никакого backoff, никакого ERROR-лога, никакой записи в `lastFailure`. |
| `ClosedWatchServiceException` | WARN-лог, `closeWatchServiceQuietly()`, `registeredDirs.clear()`, `watchService = null`. Backoff делаем. В следующей итерации `ensureWatchService()` создаст новый. |
| `Throwable` (всё остальное: `R2dbcException`, `IOException`, `IllegalArgumentException`, ...) | **Per-event:** ловятся внутри `WatchRecordsLoop.runIteration` (iter-2 CRITICAL-1), считаются в `IterationResult.eventFailures`, `onPollCompleted` инкрементирует `consecutiveEventFailures`, supervisor делает backoff если `eventFailures > 0`. **Из ensureWatchService (registration):** ERROR-лог + `onRegistrationFailure(e)` (внутри ensureWatchService body) + supervisor catches → backoff. **Generic loop failure (вне per-event и вне registration):** ERROR-лог + `onIterationFailureLegacy(e)` + backoff. `watchService` остаётся живой — продолжаем poll в следующей итерации. |

### 5.2 Health status state-таблица (расширенная — см. iter-1 review §D2, Variant A)

Проверяется **в указанном порядке**, первая совпавшая ветка выигрывает:

| # | Условие | Status | Reason |
|---|---|---|---|
| 1 | `supervisorJob == null` или `!supervisorJob.isActive` | DOWN | `supervisor not active` |
| 2 | `lastSuccessfulRegistrationAt == null` И (`consecutiveRegistrationFailures >= STARTUP_FAILURE_THRESHOLD` ИЛИ `now − startupAt > STARTUP_GRACE`) | DOWN | `startup failed: no successful registration after N attempts / X duration` |
| 3 | `lastSuccessfulRegistrationAt == null` (но grace ещё не истёк) | OUT_OF_SERVICE | `registering... attempts=N` |
| 4 | `consecutiveEventFailures > 0` И `lastEventProcessedAt != null` И `now − lastEventProcessedAt > HEALTH_STALENESS` | DOWN | `stale: events failing for X (last processed at Y)` |
| 5 | `consecutiveEventFailures > 0` И (`lastEventProcessedAt == null` ИЛИ `now − lastEventProcessedAt ≤ HEALTH_STALENESS`) | OUT_OF_SERVICE | `transient event-processing failures (N consecutive)` |
| 6 | `consecutiveFailures > 0` (registration OK, event ok, но что-то ещё в loop) | OUT_OF_SERVICE | `in backoff after N consecutive iteration failures`. _iter-2 QUESTION-1: фактически fires transiently во время `ClosedWatchServiceException`-recovery (≤1 cycle до пересоздания watch service). Намеренно оставлено отдельной веткой — даёт оператору явный signal "watcher hiccup, recovering"._ |
| 7 | `lastEventProcessedAt == null` И `now − startupAt > 2 × HEALTH_STALENESS` | UP | `idle: registered but no events yet (camera offline?)` — это **UP с пометкой**, не unhealthy. _iter-2 QUESTION-2: `lastSuccessfulPollAt != null` опущен — `supervisorJob.isActive` (ветка 1) гарантирует, что после startup grace хотя бы один poll-итерация уже была._ |
| 8 | (всё остальное — норма) | UP | `healthy` |

**Ключевое отличие от исходной таблицы:**
- Empty poll'ы НЕ маскируют persistent event-failure: ветка #4/#5 смотрит на `consecutiveEventFailures` и `lastEventProcessedAt`, обновляемые **только** при попытке обработать реальное событие.
- Startup failure теперь корректно переходит из OUT_OF_SERVICE в DOWN (ветка #2 vs #3) — по достижении `STARTUP_FAILURE_THRESHOLD` повторов либо по истечении `STARTUP_GRACE`-окна.
- Сценарий «камеры выключены — событий нет долго» отделён от «события приходят, но проваливаются»: ветка #7 даёт UP с пояснением, ветка #4 — DOWN.

### 5.2.1 Transition-methods после расширения

Schematic (точная сигнатура — на impl-фазе):

```kotlin
private fun onPollCompleted(eventsProcessed: Int, eventFailures: Int) {
    lastSuccessfulPollAt = Instant.now(clock)
    if (eventsProcessed > 0) {
        lastEventProcessedAt = Instant.now(clock)
        consecutiveEventFailures = 0
        consecutiveFailures = 0   // полный сброс на «реально что-то сделали»
    }
    if (eventFailures > 0) {
        consecutiveEventFailures += eventFailures
        consecutiveFailures++     // backoff-progression
    }
    // empty poll (events=0, failures=0): только lastSuccessfulPollAt обновлён — счётчики НЕ трогаем
}

private fun onRegistrationSuccess() {
    lastSuccessfulRegistrationAt = Instant.now(clock)
    consecutiveRegistrationFailures = 0
}

private fun onRegistrationFailure(t: Throwable) {
    consecutiveRegistrationFailures++
    consecutiveFailures++
    lastFailure = t
}
```

`IterationResult` расширяется до `data class IterationResult(val eventsProcessed: Int, val eventFailures: Int, val lastCleanupAt: Instant)`. `WatchRecordsLoop` теперь **ловит per-event exception'ы внутри** и считает их, а не пробрасывает наверх (за исключением `ClosedWatchServiceException` — он пробрасывается, supervisor пересоздаёт watch service). Полная спецификация loop-API — на impl-фазе.

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
