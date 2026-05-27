# Atomic snapshot for `@Volatile` runtime state

Status: draft (awaiting user review).
Date: 2026-05-27.
Branch: `refactor/atomic-state-snapshot`.
Background: `docs/health-volatile-snapshot-issue.md`.

## 1. Контекст и цель

Несколько supervisor-классов в проекте держат runtime state как набор `@Volatile var`-полей и читают
их в HealthIndicator (или в коллбэке другой coroutine) **без atomic snapshot**. Reader видит
inconsistent комбинации полей, потому что writer обновляет их раздельными store-операциями.
Конкретные сценарии и места задокументированы в `docs/health-volatile-snapshot-issue.md`.

Production impact ≈ 0 (любая транзитная inconsistency самокорректируется за ms). Однако:
- 4 из 6 внешних ревьюеров последнего PR (`fix/telegram-bot-supervisor`) независимо указали на это.
- WatchRecordsTask имеет 3 разных счётчика, обновляемых в разных моментах итерации — reader
  может видеть арифметически непоследовательный набор.
- ActiveExportRegistry уже содержит явный JMM-комментарий, объясняющий, почему single-writer
  ordering работает «на текущей платформе» — это технический долг.

Цель этого рефакторинга — закрепить **atomic snapshot read/write pattern** в 4 классах единым
подходом, устранить inconsistent read как класс проблем, удалить JMM-объяснения, упростить
тестирование.

## 2. Scope

В scope (рефакторинг):

| Класс | Файл |
|-------|------|
| `TelegramBotSupervisor` | `modules/telegram/src/main/kotlin/.../telegram/bot/supervisor/TelegramBotSupervisor.kt` |
| `WatchRecordsTask` | `modules/core/src/main/kotlin/.../core/task/WatchRecordsTask.kt` |
| `ActiveExportRegistry.Entry` | `modules/telegram/src/main/kotlin/.../telegram/bot/handler/export/ActiveExportRegistry.kt` |
| `ServerState` | `modules/core/src/main/kotlin/.../core/loadbalancer/ServerState.kt` |

Out of scope:
- Public API контрактов (`computeHealth(now)`, `start()`, `shutdown()`, `attachCancellable`, `release`,
  `markCancelling`, `canAcceptRequest`).
- Lock-ordering invariant в `ActiveExportRegistry` (см. `.claude/rules/telegram-export.md` §lock-ordering).
- `AtomicInteger` counters в `ServerState` — уже атомарные.
- Concurrency smoke-тесты (StandardTestDispatcher single-threaded, race не воспроизводится).
- Удаление существующих `synchronized(entry)` блоков в `markCancelling`/`release` —
  они нужны для координации с `release`-ordering, а не для state-mutation.

## 3. Архитектура

### 3.1 Общий паттерн

Каждый из 4 классов получает следующую структуру:

```kotlin
internal data class XState(
    // immutable value-поля runtime state
)

/**
 * Single source of truth for runtime metrics. ALL writes MUST go through
 * [updateAndGet] / [getAndUpdate]; direct `state.set(...)` is reserved for test
 * fixtures via [stateForTesting]. Reader code MUST do exactly one `state.get()`
 * at the top of any method that touches more than one field.
 */
private val state = AtomicReference(XState())
```

Writers:
- Single-field touch: `state.updateAndGet { it.copy(field = newValue) }`.
- Multi-field transition: `state.updateAndGet { it.copy(a = ..., b = ..., c = ...) }`.
- Конкатенированные transitions: один `updateAndGet` с composition внутри transform.
- `state.set(...)` — **только** в setter `stateForTesting` (test fixture).

Readers (атомарность гарантируется тем, что в одном методе делается ровно один `state.get()`):
```kotlin
fun compute(...): X {
    val s = state.get()
    // далее все ветки работают с s.field1, s.field2, ...
}
```

### 3.2 Что НЕ попадает в State

- **`supervisorJob: Job?`** в TelegramBotSupervisor и WatchRecordsTask — lifecycle handle с
  собственным внутренним состоянием (`isActive`, `isCancelled`). Single-writer (`start()` пишет
  один раз). Остаётся отдельным `@Volatile var`.
- **`registeredDirs: ConcurrentHashMap<Path, WatchKey>`** в WatchRecordsTask — уже thread-safe,
  reader использует только `.size`. Не в snapshot.
- **`watchService: WatchService?`** в WatchRecordsTask — единственный writer/reader (supervisor
  coroutine). Не observability-relevant, не в snapshot.
- **`processingFrame*Count: AtomicInteger`** в ServerState — уже атомарные, не в HealthSnapshot.

## 4. Спецификация по классам

### 4.1 `TelegramBotSupervisor`

```kotlin
internal data class SupervisorState(
    val startupAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val lastPollingStartAt: Instant? = null,
    val lastStableAt: Instant? = null,
    val lastFailure: Throwable? = null,
    val lastFailureAt: Instant? = null,
    val consecutiveFailures: Long = 0,
    val currentBackoff: Duration = INITIAL_BACKOFF,
)

private val state = AtomicReference(SupervisorState())

@Volatile internal var supervisorJob: Job? = null  // остаётся вне State

internal var stateForTesting: SupervisorState
    get() = state.get()
    set(value) { state.set(value) }
```

Изменения writers:
- `start()`: `state.updateAndGet { it.copy(startupAt = Instant.now(clock)) }`.
- `runSupervised()` (per-iteration):
  - В начале: `state.updateAndGet { it.copy(lastAttemptAt = attemptStart, lastPollingStartAt = null) }`.
  - После `runner.run` exit: `state.updateAndGet { it.copy(lastPollingStartAt = null) }`.
  - В точке `lastPollingStartAt = Instant.now(clock)` (после успешного getMe/register): `state.updateAndGet { it.copy(lastPollingStartAt = ...) }`.
- `onAttemptEnded(...)`:
  - Все 3 ветки (`success && duration >= STABLE`, `success && duration < STABLE`, failure) — каждая
    один `state.updateAndGet { it.copy(...) }` с полным set релевантных полей.

Изменения reader (`computeHealth(now: Instant): Health`):
- `val s = state.get()` в начале.
- Все 7 branches читают `s.startupAt`, `s.consecutiveFailures`, etc.
- `baseBuilder()` принимает `s` или становится lambda над `s`.
- Логика state machine **не меняется** — только источник чтения.

Удаление прямых полей:
- Существующие `@Volatile internal var consecutiveFailures: Long`, `lastFailure: Throwable?` и
  остальные 6 полей **исчезают** (они теперь — части `SupervisorState`). Tests читают через
  `sup.stateForTesting.field`. Мы **не** добавляем convenience getter'ы поверх — это сознательное
  решение, см. §5.1.

### 4.2 `WatchRecordsTask`

```kotlin
internal data class WatchTaskState(
    val startupAt: Instant? = null,
    val lastSuccessfulPollAt: Instant? = null,
    val lastEventProcessedAt: Instant? = null,
    val lastSuccessfulRegistrationAt: Instant? = null,
    val consecutiveEventFailures: Long = 0,
    val consecutiveRegistrationFailures: Long = 0,
    val consecutiveFailures: Long = 0,
    val successesSinceLastFailure: Int = 0,
    val currentBackoff: Duration = INITIAL_BACKOFF,
    val lastFailure: Throwable? = null,
    val lastFailureAt: Instant? = null,
)

private val state = AtomicReference(WatchTaskState())

@Volatile internal var supervisorJob: Job? = null
```

Изменения writers:
- `start()`: `state.updateAndGet { it.copy(startupAt = Instant.now(clock)) }`.
- `onPollCompleted(eventsProcessed, eventFailures)`:
  - Один `state.updateAndGet { s -> ... }`, transform является pure-функцией. Шаблон:
    ```kotlin
    val now = Instant.now(clock)
    state.updateAndGet { s ->
        var n = s.copy(lastSuccessfulPollAt = now)
        if (eventsProcessed > 0) {
            n = n.copy(
                lastEventProcessedAt = now,
                consecutiveEventFailures = 0,
                consecutiveFailures = 0,
            )
            n = maybeResetBackoffTransform(n)
        }
        if (eventFailures > 0) {
            n = n.copy(
                consecutiveEventFailures = n.consecutiveEventFailures + eventFailures,
                consecutiveFailures = n.consecutiveFailures + 1,
                successesSinceLastFailure = 0,
            )
        } else if (eventsProcessed == 0) {
            n = n.copy(consecutiveFailures = 0)
        }
        n
    }
    ```
    `maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState` — pure-функция, инкапсулирует
    текущую логику `maybeResetBackoff` (декремент `successesSinceLastFailure` и reset
    `currentBackoff` при достижении порога).
- `onRegistrationSuccess()`, `onRegistrationFailure(t)`, `onLoopFailure(t)`:
  - Каждый — один `state.updateAndGet { it.copy(...) }`.

Изменения reader (`computeHealth(now)`):
- `val s = state.get()` в начале.
- Все 8 branches переписаны с полей на `s.`. Включая фоллбэк `staleReference = s.lastEventProcessedAt ?: s.lastSuccessfulRegistrationAt`.
- `registeredDirs.size` читается отдельно (ConcurrentHashMap atomic).

Test fixture: `stateForTesting` setter/getter как в §4.1.

### 4.3 `ActiveExportRegistry.Entry`

```kotlin
class Entry(
    val exportId: UUID,
    val chatId: Long,
    val mode: ExportMode,
    val recordingId: UUID?,
    val job: Job,
) {
    private val stateRef = AtomicReference(EntryState())

    internal data class EntryState(
        val cancellable: CancellableJob? = null,
        val state: State = State.ACTIVE,
    )

    // Convenience read-only API (sохраняется — это используется в production call-сайтах,
    // не только в тестах).
    val cancellable: CancellableJob? get() = stateRef.get().cancellable
    val state: State get() = stateRef.get().state

    internal fun updateState(transform: (EntryState) -> EntryState): EntryState =
        stateRef.updateAndGet(transform)

    internal fun getAndUpdateState(transform: (EntryState) -> EntryState): EntryState =
        stateRef.getAndUpdate(transform)
}
```

Изменения writers:
- `attachCancellable(exportId, cancellable)`:
  ```kotlin
  val entry = byExportId[exportId] ?: return
  val updated = entry.updateState { it.copy(cancellable = cancellable) }
  if (updated.state == State.CANCELLING) {
      exportScope.launch { ... cancellable.cancel() ... }
  }
  ```
  Удалить старый JMM-комментарий «// Local invariant: write cancellable BEFORE reading state ...»
  (lines 114-128) — happens-before теперь покрывается AtomicReference.
- `markCancelling(exportId)`:
  Сохраняется `synchronized(entry)` (для координации с `release` через CHM `computeIfPresent` —
  lock-ordering invariant). Для детекции «мы только что транзишнули `ACTIVE → CANCELLING`»
  используется `getAndUpdate` (возвращает previous state):
  ```kotlin
  internal fun getAndUpdateState(transform: (EntryState) -> EntryState): EntryState =
      stateRef.getAndUpdate(transform)

  fun markCancelling(exportId: UUID): Entry? {
      var snapshot: Entry? = null
      byExportId.computeIfPresent(exportId) { _, entry ->
          synchronized(entry) {
              val before = entry.getAndUpdateState {
                  if (it.state == State.CANCELLING) it else it.copy(state = State.CANCELLING)
              }
              if (before.state != State.CANCELLING) snapshot = entry
          }
          entry
      }
      return snapshot
  }
  ```
  Идемпотентный повторный вызов: transform возвращает `it` без изменений, `before.state ==
  CANCELLING`, `snapshot` остаётся `null` — точно как в текущей реализации.

Изменения readers:
- Существующие call-сайты типа `registry.get(exportId)!!.cancellable` работают без изменений
  (convenience getter).
- `entry.state == State.CANCELLING` тоже работает.

Lock-ordering invariant (§lock-ordering в `.claude/rules/telegram-export.md`) сохраняется
полностью.

### 4.4 `ServerState`

```kotlin
data class ServerState(
    val id: String,
    val properties: DetectServerProperties,
    val processingFrameRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingFrameExtractionRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVideoVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
) {
    private val healthRef = AtomicReference(HealthSnapshot())

    data class HealthSnapshot(
        val alive: Boolean = false,
        val lastCheckTimestamp: Instant = Instant.EPOCH,
    )

    val alive: Boolean get() = healthRef.get().alive
    val lastCheckTimestamp: Instant get() = healthRef.get().lastCheckTimestamp
    fun snapshot(): HealthSnapshot = healthRef.get()
    fun updateHealth(transform: (HealthSnapshot) -> HealthSnapshot): HealthSnapshot =
        healthRef.updateAndGet(transform)

    fun canAcceptRequest(requestType: RequestType): Boolean = alive && getCurrentCount(requestType) < getMaxCount(requestType)
    // ... остальные методы без изменений
}
```

Изменения writers — в `ServerHealthMonitor` (или где сейчас выставляются `server.alive = ...`):
```kotlin
// БЫЛО:
server.alive = true
server.lastCheckTimestamp = Instant.now()

// СТАЛО:
server.updateHealth { it.copy(alive = true, lastCheckTimestamp = Instant.now()) }
```

Изменения readers:
- `canAcceptRequest` и ranker используют convenience getter `server.alive` без изменений.
- Где нужна согласованность пары (alive + lastCheckTimestamp): `val h = server.snapshot(); h.alive && h.lastCheckTimestamp.isAfter(...)`.

`data class equals/hashCode` пересчитываются на основе constructor-only полей (`id`, `properties`,
counters). `healthRef` — `private val`, не входит в `equals/hashCode` (Kotlin data class не
включает не-constructor-параметры).

## 5. Test ergonomics

### 5.1 Hybrid подход к convenience getter'ам

| Класс | Convenience getter'ы | Обоснование |
|-------|----------------------|-------------|
| TelegramBotSupervisor | **нет** | Существующие `@Volatile`-поля убираются вместе с переходом на State. Tests читают через `stateForTesting`. Force consistent snapshot reads. |
| WatchRecordsTask | **нет** | То же. |
| ActiveExportRegistry.Entry | **есть** (`cancellable`, `state`) | Уже public API, есть production call-сайты типа `registry.get(...)!!.cancellable` и `entry.state == CANCELLING`. Удаление слишком инвазивно. |
| ServerState | **есть** (`alive`, `lastCheckTimestamp`) | Уже public API, активно используется в `canAcceptRequest` и ranker. |

Принцип: для supervisor классов — не добавляем (свежий API, проще закрепить snapshot-pattern);
для Entry/ServerState — сохраняем (existing public API с production call-сайтами; для
multi-field consistency — `entry.updateState`/`server.snapshot()`).

### 5.2 Изменения в тестах

| Файл | Изменения |
|------|-----------|
| `TelegramBotSupervisorTest` | ~30 direct field writes (`sup.startupAt = ...`, `sup.consecutiveFailures = ...`, etc.) → ~10 строк `sup.stateForTesting = SupervisorState(...)`. Reads — переписать `sup.field` → `sup.stateForTesting.field` (или `val s = sup.stateForTesting`). |
| `WatchRecordsTaskTest` | helper-функция `buildTask(...)` свернуть из 9 параметров → 1 (`state: WatchTaskState`). Reads — то же что выше. |
| `WatchRecordsTaskHealthIndicatorTest` | (если есть direct field writes) — то же. |
| `TelegramBotSupervisorHealthIndicatorTest` | (если есть direct field writes) — то же. |
| `ActiveExportRegistryTest` | Минимальные изменения. Если найдутся `entry.cancellable = ...` writes — переписать на `entry.updateState { it.copy(cancellable = ...) }`. |
| ServerState consumer тесты (`ServerHealthMonitor`, ranker) | Writers через `server.updateHealth { ... }`. Reads без изменений. |

### 5.3 Новые тесты — нет

Existing coverage уже покрывает state transitions; обновлённые тесты будут гарантировать, что
transition-методы атомарно ставят все связанные поля (через `s = stateForTesting` они становятся
видны как одна snapshot). Concurrency smoke-тесты не добавляем — без реального многопоточного
executor'а они ничего не доказывают.

## 6. Порядок миграции

Все 4 класса — независимые рефакторинги. Реализация — **один PR**, коммитами по классам; каждый
коммит проходит build+tests изолированно.

Порядок от простого к сложному:

1. **ServerState** (~30-50 LoC). Самый маленький. 2 поля → `HealthSnapshot`, AtomicReference в data
   class. Convenience getter'ы остаются. Writer — одна точка в health monitor.
2. **ActiveExportRegistry.Entry** (~40-60 LoC). 2 поля per-Entry. Главное — упрощение
   `attachCancellable` (удалить JMM-комментарий). `markCancelling` сохраняет `synchronized(entry)`.
3. **TelegramBotSupervisor** (~100-130 LoC). 8 полей SupervisorState. Перепись `onAttemptEnded`
   (3 transitions → 3 разных `updateAndGet`), `runSupervised`, `computeHealth`. Тесты — заменить
   присваивания на `stateForTesting`. Убрать convenience getter'ы (если были) и обновить
   reads в тестах.
4. **WatchRecordsTask** (~130-160 LoC). 11 полей. Самая сложная transition-логика
   (`onPollCompleted` с composition). `maybeResetBackoffTransform` pure-function.
   `computeHealth` 8 branches переписаны.

Если slow review — можно разбить на 2 PR (ServerState+Entry, потом supervisor classes). По умолчанию
— один PR.

## 7. Error handling

- **`updateAndGet { transform }` exceptions**. Transform-функции — pure (читают closure, конструируют
  `it.copy(...)`). Если когда-нибудь добавится side-effect — это баг review-уровня (KDoc на
  `state` явно требует pure transform).
- **Throwable safe-publication**. `Throwable.message`, `cause`, `stackTrace` — final fields,
  safe-published вместе с `*State` через AtomicReference. Copy-by-reference в `.copy()` безопасно
  (Throwable immutable post-construction).
- **Allocation overhead**. Каждый `updateAndGet` — одна new instance data class (~100 bytes). На
  10 events/sec в WatchRecordsTask — пренебрежимо для GC. В supervisor — ещё реже (≤1 update / 5-60s).
- **`state.set(...)` использование**. Допустим только в setter `stateForTesting`. KDoc и code
  review закрепляют.

## 8. Build & verification

- `./gradlew build` проходит чисто (за вычетом тривиальных fix-ов после первой компиляции).
- `./gradlew ktlintCheck` / `ktlintFormat` для форматирования.
- Per CLAUDE.md: после имплементации — `superpowers:code-reviewer` agent, fix critical comments,
  потом `build-runner` agent.
- Manual: запустить локально (Telegram opt., supervisor рефактор виден через unit-тесты), curl
  `/actuator/health` — проверить consistency `consecutiveFailures` и `lastFailureAt` в
  `watchRecordsTask` и `telegramBotSupervisor` (если enabled).

## 9. External code review

Per CLAUDE.md и опыта с `fix/telegram-bot-supervisor` — после реализации запускается external
review (`/code-review` или `codex-code-review`). Этот рефакторинг — прямой ответ на 4 из 6
review-замечаний прошлого PR, новых flag'ов на эту тему быть не должно. Возможны замечания на
новый код (data class layout, naming, тесты). Закладываем 1 итерацию review fixes.

## 10. Known limitations

- Concurrency invariant поддерживается **review-уровневой дисциплиной**: «все writes — через
  `updateAndGet`/`getAndUpdate`», «`state.set(...)` — только в `stateForTesting`-setter». Нет
  runtime-проверки; добавление прямого writer-а в будущем — баг review-уровня. KDoc на поле
  `state` фиксирует invariant.
- В WatchRecordsTask `currentBackoff` обновляется в нескольких местах: `runSupervised` (tail
  bump после `delay`), `onPollCompleted/maybeResetBackoff` (success path), `onLoopFailure` (если
  применимо). Все touch'и централизуются через `updateAndGet { it.copy(currentBackoff = ...) }`
  включая tail bump. Поведение state machine не меняется.
- Если `ktlint` или Kotlin compiler требуют определённой формы lambda — corrective formatting
  применяется по результату `ktlintCheck` в каждом коммите (не open question, просто стандартная
  итерация).
