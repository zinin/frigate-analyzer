# Merged Design Review — Iteration 1

- Design: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md`
- Reviewers: codex-executor (gpt-5.5, xhigh), ccs-executor (glm), ollama-executor (ollama-kimi), ollama-executor (ollama-deepseek)
- Skipped (in-flight at user request): ollama-executor (ollama-minimax)

---

## codex-executor (gpt-5.5, xhigh)

### Critical Issues

- design §2 / §4.3 и plan Step 10.1: ключевое утверждение "`/actuator/health` DOWN → docker healthcheck unhealthy → `restart: unless-stopped` рестартует контейнер" неверно для обычного Docker Compose. Healthcheck сам по себе только меняет статус контейнера на `unhealthy`; restart policy не рестартует такой контейнер. Без autoheal sidecar/systemd/Kubernetes liveness/явного `exitProcess` permanent failure не self-heal'ится.

- design §5.2, plan Step 10.1: `OUT_OF_SERVICE` используется как "transient", но Actuator по умолчанию мапит `OUT_OF_SERVICE` в HTTP 503, как и `DOWN`. Текущий docker healthcheck с `curl -f` будет считать transient backoff healthcheck failure. Если появится внешний autoheal, он сможет рестартовать контейнер на кратких transient ошибках раньше, чем наступит `HEALTH_STALENESS`.

- plan Step 5.2, строки 675-710: `key.reset()` вызывается только после успешной обработки всех событий. Если `Files.readAttributes`, `recordingFileHelper.parse` или `createRecording` бросит исключение, key не reset'ится. Для Java `WatchService` это может выключить дальнейшую доставку событий по этой директории, то есть supervisor "жив", health может стать UP, но watcher уже пропускает новые записи. `reset()` нужен в `finally`, с rethrow исходной ошибки после reset.

- design §4.1 / plan Step 6.1, строки 1188-1193 плюс Step 5.2, строки 728-735: `ensureWatchService()` мутирует общий `registeredDirs` во время регистрации. Если `registerAllDirs` упадёт посередине, локальный `WatchService` не закрывается, а `registeredDirs` остаётся частично заполненным ключами от orphan watch service. Следующая попытка через `computeIfAbsent` не перерегистрирует эти директории на новый `WatchService`, и события будут теряться. Нужна регистрация во временную map + `use/try/finally close` до атомарной публикации состояния.

- design §4.1, строки 191-200 / plan Step 6.1, строки 1202-1212: "успешная итерация" включает empty poll. Для исходного инцидента с постоянной ошибкой DB на `createRecording`: файл пришёл → ошибка → backoff → несколько empty poll'ов → `consecutiveFailures=0`, health снова UP. Если новые файлы приходят раз в минуту, постоянная DB-поломка может никогда не перейти в DOWN. Нужно отделить "loop heartbeat" от "успешно обработали событие/DB path healthy" или не сбрасывать failure state пустыми poll'ами.

- design §5.2, строки 348-350 / plan Step 6.1, строки 1244-1250: если первая успешная итерация никогда не случилась (`registerAllDirs` падает из-за mount/permission/root missing), health навсегда остаётся `OUT_OF_SERVICE`, а не становится `DOWN` после `HEALTH_STALENESS`. Это ломает permanent startup failure сценарий.

- plan Step 7.6, строки 1506-1510 и plan Step 6.1, строки 1238-1240: тесты active health states вызывают `task.runSupervised()` напрямую, но `supervisorJob` при этом остаётся `null`; `computeHealth()` всегда вернёт `DOWN`. План говорит, что непокрываема только stale-ветка, но фактически непокрываемы все ветки, зависящие от active job. Тесты должны запускать через `start()` или иметь явный test seam для job/state.

- plan Step 7.2/7.4/7.5: supervisor tests с `advanceUntilIdle()` и mock loop, который после успеха возвращает сразу, могут зависнуть. После первой успешной итерации в `runSupervised()` нет suspend point, и цикл становится tight loop в test dispatcher. Нужен mock, который после нужного числа итераций suspends/throws `CancellationException`, либо `backgroundScope` + контролируемая отмена без `advanceUntilIdle()` на бесконечной задаче.

- plan Step 2.3, строки 226-239: `owner.chatId` nullable в реальном `TelegramUserDto`, а `SimpleTextNotificationTask.chatId` требует `Long`. Код не скомпилируется без null-check/`?: return`/`!!`. Это не покрыто предупреждением про гипотетическую сигнатуру DTO.

- plan Step 5.5, строки 887-918: тест "registers new directory" использует mock `WatchService`, но implementation вызывает реальный `Path.register(watchService, ENTRY_CREATE)`. С mock watch service это, скорее всего, упадёт provider mismatch'ем. Для этого теста нужен реальный `FileSystems.getDefault().newWatchService()` и закрытие в `finally`.

### Concerns

- plan Step 6.1: `Files.walk(start)` в `registerAllDirs` не закрывается. Это уже есть в старом коде, но новый supervisor будет чаще пересоздавать watcher после ошибок, значит leak файловых дескрипторов станет заметнее. Использовать `Files.walk(start).use { ... }`.

- design §4.3 / plan Step 6.1: state читается health indicator'ом как набор отдельных `@Volatile` полей, а `supervisorJob` вообще не volatile. Возможны несогласованные snapshots: свежий `consecutiveFailures`, старый `lastSuccessfulIterationAt`, stale `supervisorJob=null`. Лучше единый immutable state в `AtomicReference`.

- plan Step 6.1, строки 1179-1183: catch `Throwable` будет проглатывать `OutOfMemoryError`, `StackOverflowError`, `LinkageError` и другие fatal errors. Для supervisor обычно ловят `Exception` или явно rethrow'ят `VirtualMachineError`/`ThreadDeath`.

- design §4.1: перенос старта с `ApplicationReadyEvent` на `@PostConstruct` меняет порядок запуска. Watcher начнёт работать до полного ready-состояния приложения и до старта `FirstTimeScanTask`. Возможно нормально, но это изменение семантики нужно осознанно зафиксировать.

- plan Step 3.3: `runBlocking` в `ApplicationReadyEvent` без timeout может задержать readiness, если `findActiveByUsername` или `notificationQueue.enqueue` зависнет. Startup notification не должна блокировать старт сервиса бесконечно.

- plan Step 11.2, строка 1905: manual smoke предлагает `docker exec ... touch /mnt/data/...`, но volume в `docker/deploy/docker-compose.yml` смонтирован `:ro`. Проверка не сработает; файл нужно создавать на host-side mount или временно запускать с rw volume.

- plan Step 2.1: verify через `SimpleTextNotificationTask(createdAt = any())` хрупкий. Лучше capture slot и проверка полей, иначе MockK может сравнивать data class целиком с динамическим `createdAt`.

- plan Step 6.2-6.4 / Task 8: план всё ещё допускает commit после удаления `WatchRecordsTask.run()` до исправления `ApplicationListener`. Нужно переставить Task 8 перед commit Task 6 или объединить их, иначе ветка будет заведомо некомпилируемой.

### Suggestions

- Явно выбрать механизм рестарта: `exitProcess` после sustained DOWN, autoheal sidecar, systemd watchdog или Kubernetes liveness. HealthIndicator сам по себе не self-healing механизм в Docker Compose.

- Ввести state вроде `lastLoopHeartbeatAt`, `lastFailureAt`, `lastEventProcessedAt`, `lastSuccessfulRegistrationAt`, `failurePhaseStartedAt`. Тогда health сможет различать idle watcher, startup failure, repeated event-processing failure и hung iteration.

- Для `runIteration` сделать структуру: poll key → process events with captured failure → always reset/remove key → then rethrow captured failure to supervisor.

- Добавить обязательные тесты: exception before `key.reset()` still resets key; partial `registerAllDirs` failure does not poison `registeredDirs`; startup failure becomes DOWN after threshold; persistent DB failure separated by empty polls still becomes unhealthy.

- Для `sendOwnerMessage` обработать active owner with `chatId == null` как WARN + no-op. Это лучше, чем `!!`, потому что DTO тип уже говорит о nullable lifecycle.

### Questions

- Есть ли в реальном deployment внешний autoheal/watchdog, который рестартует `unhealthy` контейнеры? Если нет, текущий design не закрывает self-healing часть.

- Должен ли empty poll считаться "успехом" для DB/IO health, или только как heartbeat watcher loop'а?

- Какой HTTP status ожидается для transient `OUT_OF_SERVICE`: 200, чтобы docker healthcheck не флапал, или 503, чтобы внешний watchdog мог реагировать?

- Нужно ли сохранять старт через `ApplicationReadyEvent` внутри `WatchRecordsTask`, чтобы не менять порядок относительно текущего `ApplicationListener`?

---

## ccs-executor (glm)

### Critical Issues

**1. `CreateRecordingRequest` — три неверных имени полей. План не скомпилируется.**

plan Step 5.2 (строка ~694), код создаёт `CreateRecordingRequest`:
```kotlin
CreateRecordingRequest(
    fullPath.toAbsolutePath().toString(),
    attrs.creationTime().toInstant(),
    recordingFile.camId,
    recordingFile.date,
    recordingFile.time,
    recordingFile.timestamp,
)
```
Реальная сигнатура `CreateRecordingRequest`:
```kotlin
data class CreateRecordingRequest(
    val filePath: String,
    val fileCreationTimestamp: Instant,  // НЕ creationTimestamp
    val camId: String,
    val recordDate: LocalDate,           // НЕ date
    val recordTime: LocalTime,           // НЕ time
    val recordTimestamp: Instant,
)
```
**Три поля неверны:** `creationTimestamp` → `fileCreationTimestamp`, `date` → `recordDate`, `time` → `recordTime`. Это не предупреждение-гипотеза — это код из имплементационной части плана (Step 5.2), который будет напрямую вставлен в `WatchRecordsLoop.kt`.

**2. `TelegramUserDto` — полная некомпилируемая сигнатура в тесте Step 2.1.**

plan Step 2.1 (строка ~157) конструирует с полями `activatedAt`, `invitedAt`, `timezone` — их **нет** в реальном DTO. Реальная сигнатура:
```kotlin
data class TelegramUserDto(
    val id: UUID, val username: String,
    val chatId: Long?,               // nullable, не Long
    val userId: Long?,
    val firstName: String?, val lastName: String?,
    val status: UserStatus,          // ОТСУТСТВУЕТ в тесте
    val creationTimestamp: Instant,   // вместо activatedAt
    val activationTimestamp: Instant?,// вместо invitedAt
    val languageCode: String? = null,
    val notificationsRecordingEnabled: Boolean = true,
    val notificationsSignalEnabled: Boolean = true,
)
```
**4 ошибки:** отсутствует обязательное поле `status: UserStatus`; `activatedAt` → `creationTimestamp`; `invitedAt` → `activationTimestamp` (nullable); поле `timezone` не существует.

**3. `chatId: Long?` (nullable) vs `SimpleTextNotificationTask.chatId: Long` (non-nullable) — NPE/compile error.**

plan Step 2.3 (строка ~236): `chatId = owner.chatId` — `Long?` → `Long` TYPE MISMATCH. Нужен null-check: `owner.chatId ?: return` (паттерн из `FrigateAnalyzerBot.kt:93-94`).

**4. MockK `throwsMany` / `andThenMany` — несуществующий API. Тесты Steps 7.2-7.5 не скомпилируются.**

plan Step 7.2 (строка ~1385) использует `throwsMany listOf(...)`. MockK 1.14.9 не имеет такой функции. Корректный API:
```kotlin
coEvery { ... } throws RuntimeException("boom1") andThenThrows RuntimeException("boom2") andThen IterationResult(...)
```
Пять тестов в Task 7 (Steps 7.2, 7.3, 7.5, 7.6) используют этот синтаксис и все не скомпилируются.

**5. `CoroutineScope` никогда не отменяется — утечка `limitedParallelism(1)` dispatcher'а.**

design §4.1, plan Step 6.1: `scope = CoroutineScope(SupervisorJob() + dispatcher + ...)`. В `shutdown()` отменяется `supervisorJob` (child), но не сам `scope`. `SupervisorJob()` scope'а — отдельный объект. Dispatcher не закрывается. При многократных Spring context refresh'ах (тесты) утечка.

Исправление: добавить `scope.cancel()` после `join()`.

**6. `registerAllDirs` partial failure — «мёртвая» часть директорий до следующего `ClosedWatchServiceException`.**

design §4.1 `ensureWatchService()`: если `loop.registerAllDirs()` бросает (например, `IOException`), `watchService` уже присвоен, карта `registeredDirs` — частично заполнена. На следующей итерации `ensureWatchService()` видит `watchService != null` и пропускает повторную регистрацию. Незарегистрированные директории выпадают из мониторинга.

Исправление: try-catch с `ws.close()` и `throw e`.

### Concerns

**7. Тест stale-ветки `computeHealth` полностью заглушён — не покрыта критическая строка state-таблицы.**

plan Step 7.6 проверяет DOWN-status, но реально проверяет ветку "supervisor not active", а не "stale: no success for X". Строка «stale» — единственная триггерящая docker restart. Именно эта ветка была причиной обоих инцидентов. Она остаётся непроверенной.

**8. Порядок Task 6 → Task 8 → Task 7 — запутанная нумерация.**

В автоматизированном execution (subagent-driven) это почти гарантированно приведёт к ошибке. План стоит перенумеровать: Task 6 → 7 (ApplicationListener) → 8 (supervisor tests).

**9. `TelegramNotificationServiceImpl` — добавление `telegramProperties` в конструктор ломает все существующие тесты, без явного списка файлов в плане.**

В кодовой базе есть `TelegramNotificationServiceImplSignalLossTest` и `TelegramNotificationServiceImplTest`. План должен явно перечислить.

**10. `runBlocking` в `StartupTelegramNotifier.onReady()` — блокирует поток Spring event dispatch.**

`@EventListener(ApplicationReadyEvent)` синхронный. `runBlocking` заблокирует поток на время отправки (network call → Telegram API).

**11. `nextBackoff` использует `>=` для cap — двойной guard.**

При текущих константах работает, но `minOf(current.multipliedBy(2), MAX_BACKOFF)` достаточно само по себе — внешний `if` избыточен.

**12. `@Volatile` на `currentBackoff: Duration` — безопасно?**

`Duration` value class поверх `Long`. На JVM `@Volatile` на `Long` корректно. В данном случае безопасно (не generic-контекст).

### Suggestions

**13. Обернуть `registerAllDirs` в try-catch внутри `ensureWatchService` (усиление critical #6).**

**14. Добавить `chatId` null-check в `sendOwnerMessage` (усиление critical #3).**

**15. Consider `@ConditionalOnBean` для `StartupTelegramNotifier`.**

`GitProperties`/`BuildProperties` — optional beans. Если они не настроены, Spring не сможет создать `StartupTelegramNotifier`, и `@ConditionalOnProperty` не спасёт. Добавить `@ConditionalOnBean(GitProperties::class, BuildProperties::class)`.

**16. Рассмотреть fire-and-forget для startup-уведомления вместо `runBlocking`.**

### Questions

**Q1.** Есть ли полный список файлов тестов, которые нужно обновить после добавления `telegramProperties`? Автоматизированный агент может не найти все.

**Q2.** Верно ли, что пустой poll (0 событий) считается «successful iteration»?

**Q3.** Проверено ли, что `shutdown()` действительно вызывается перед созданием нового bean'а в Spring test lifecycle? (усиление critical #5).

**Q4.** Имеет ли `RecordingFileDto` exact поля (`camId`, `date`, `time`, `timestamp`)?

---

## ollama-executor (ollama-kimi)

### Critical Issues

**1. `supervisorJob` не помечен `@Volatile` — data race между writer и HealthIndicator-reader**

design §4.1, plan Step 6.1: `private var supervisorJob: Job? = null` — обычное поле. `computeHealth()` читает его из потока actuator'а, пишет из `start()`/`shutdown()`. Без `volatile` health-индикатор может видеть устаревший `null`.

**2. MockK `coVerify` с data class, содержащим `any()` внутри конструктора — не работает**

plan Step 2.1 (строка 173–185): `any()` внутри data class constructor передаётся как обычное значение, не matcher. Тест упадёт. Решение: `match { it.id == ownerId && ... }`.

**3. Несоответствие полей `TelegramUserDto` в тесте плана — тест не скомпилируется**

(см. CCS #2)

**4. Неверные имена полей `CreateRecordingRequest` в плане — код не скомпилируется**

(см. CCS #1)

**5. Добавление `telegramProperties` в конструктор `TelegramNotificationServiceImpl` ломает все существующие тесты без явного шага их починки**

(см. CCS #9)

**6. `TelegramUserDto.chatId` nullable (`Long?`), а `SimpleTextNotificationTask.chatId` — `Long` (non-null)**

(см. CCS #3)

### Concerns

**1. `SignalLossMonitorTask` НЕ использует `CoroutineScope(SupervisorJob + dispatcher)` — design обманывает о "alignment with codebase"**

design §3: утверждение "aligned with rest of codebase (`SignalLossMonitorTask` uses same idiom)" неверно. Реальный `SignalLossMonitorTask` использует `@Scheduled(fixedDelay)` + `suspend fun tick()`. Supervisor-Scope — новый паттерн.

**2. `runBlocking` в `StartupTelegramNotifier.onReady()` блокирует поток публикации событий**

(см. CCS #10)

**3. Plan ordering: Task 6 ломает компиляцию до Task 8**

(см. CCS #8)

**4. Тест "stale beyond staleness" в plan Step 7.6 — фактически no-op**

(см. CCS #7)

**5. `RecordingFileDto` — data class, а в тесте plan'а мокается через `mockk { }`**

plan Step 5.1: `recordingFileHelper.parse` возвращает `RecordingFileDto` (data class). `mockk { every { camId } returns ... }` хрупко — лучше реальный экземпляр.

**6. `GitProperties`/`BuildProperties` инжектируются напрямую, хотя `VersionCommandHandler` использует `ObjectProvider`**

design §4.4, plan Step 3.3: в codebase есть прецедент защитного `ObjectProvider<BuildProperties>`. `StartupTelegramNotifier` использует прямую инъекцию — несогласованно.

### Suggestions

**1. Добавить `@Volatile` (или `AtomicReference`) для `watchService`**

**2. Явно обработать `key.reset() == false` в `WatchRecordsLoop` для `ClosedWatchServiceException`**

**3. Вынести `registeredDirs` из `WatchRecordsTask` в параметр `WatchRecordsLoop`?**

(текущий design ок, но альтернатива упомянута)

**4. Добавить тест на `ensureWatchService` failing repeatedly**

### Questions

**1.** Что произойдёт, если `BuildProperties` bean отсутствует в тестовом профиле? (`NoSuchBeanDefinitionException`?)

**2.** Почему `registeredDirs` очищается только при `ClosedWatchServiceException`, а не при любом `Throwable`?

**3.** `HEALTH_STALENESS = 2m` при `MAX_BACKOFF = 60s` — гарантия DOWN при continuous failure? Граница тонкая.

**4.** Не останется ли orphaned `task-1` thread от старого `@Async` после deploy'а?

---

## ollama-executor (ollama-deepseek)

### Critical Issues

**1. Plan test code won't compile: `CreateRecordingRequest` parameter names are wrong (plan §5.1, §5.2)**

(см. CCS #1)

**2. Plan test code won't compile: `TelegramUserDto` constructor не совпадает с реальностью (plan §2.1)**

(см. CCS #2)

**3. `SignalLossMonitorTask` НЕ использует `CoroutineScope(SupervisorJob + dispatcher)` паттерн — design §1 вводит в заблуждение**

(см. kimi #1 concerns)

**4. Resource leak: `ensureWatchService()` теряет WatchService при падении `registerAllDirs` (design §4.1, `ensureWatchService`)**

(см. CCS #6)

### Concerns

**5. Сломанный compile для существующих тестов `TelegramNotificationServiceImpl` при добавлении `telegramProperties` в конструктор (plan §2.5)**

(см. CCS #9)

**6. `@Volatile` поля дают visibility, но не атомарность — `computeHealth` может вернуть inconsistent snapshot**

(см. codex concern о snapshot consistency)

**7. Нет теста на `@PreDestroy.shutdown()` (plan §7)**

Весь `shutdown()` — `supervisorJob?.cancel()`, `runBlocking { join() }`, `closeWatchServiceQuietly()` — остаётся без unit-тестов.

**8. `ConcurrentHashMap.entries.removeIf` внутри `cleanupExpiredDirs` — thread-safety нюанс**

`removeIf` атомарен относительно других структурных модификаций, но не держит блокировку на время всего обхода. На практике безопасно (один writer-thread в dedicated dispatcher), но стоит отметить в комментарии.

**9. `HEALTH_STALENESS=2m` vs время итерации**

Если `runIteration` выполняется дольше 60 секунд, между итерациями > 60s, staleness=2m оставляет запас всего в одну дополнительную итерацию. Возможен ложный DOWN при медленном loop'е.

**10. `consecutiveFailures: Int` overflow**

Через ~94 года непрерывных failures. Практически неважно, но `Long` семантически честнее.

### Suggestions

**11. `ThrowMany` MockK DSL — синтаксис может не существовать (plan §7.2)**

(см. CCS #4 critical)

**12. `WatchRecordsTaskHealthIndicator` должен реализовывать только `HealthIndicator`, а не `ReactiveHealthIndicator`**

`computeHealth()` — чистая функция без I/O, без suspend. `Mono.fromSupplier` излишне. Простой `HealthIndicator` — Spring Actuator сам адаптирует в WebFlux через `HealthIndicatorReactiveAdapter`.

**13. `@ConditionalOnProperty` на `StartupTelegramNotifier` — edge case с тестовым профилем**

`@ConditionalOnProperty` проверяет конфиг, а не профиль. Если запустить с `--spring.profiles.active=test` и `telegram.enabled=true`, startup-уведомление отправится даже в тестах. Test-profile gating есть в `WatchRecordsTask.start()`, но не в `StartupTelegramNotifier`.

**14. `NoOpTelegramNotificationService` имеет `@ConditionalOnProperty(..., matchIfMissing = true)` — потенциальный конфликт бинов**

Вне scope этого PR, observation о codebase.

**15. Plan task ordering: Task 6 → 8 → 7 закодирован в тексте, но не в нумерации**

(см. CCS #8)

**16. `runBlocking` в `@EventListener` StartupTelegramNotifier — возможен deadlock с Spring event publishing thread**

(см. CCS #10)

### Questions

**Q1:** Почему `TelegramNotificationServiceImpl.sendOwnerMessage` не делает defensive-проверку `if (!telegramProperties.enabled) return`, полагаясь только на `@ConditionalOnProperty`?

**Q2:** Возможно ли покрыть stale-ветку через `ApplicationContextRunner` (как `SignalLossConfigConflictIntegrationTest`)?

**Q3:** Почему `POLL_PERIOD_MS: Long = 500L` — top-level `const val` в `WatchRecordsLoop.kt`, а остальные константы — `private` внутри `WatchRecordsTask`?

**Q4:** `WatchService.close()` не идемпотентен — повторный вызов на closed бросает `ClosedWatchServiceException`. Обёрнуто в `runCatching`, но правильно ли это с точки зрения семантики cleanup?
