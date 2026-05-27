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

Production impact ≈ 0 (любая транзитная inconsistency самокорректируется за ms). Решение
рефакторить, а не задокументировать как acceptable, мотивируется четырьмя соображениями
вместе (не только «ревьюеры флагают»):

1. **Реальная (хоть и редкая) гонка в WatchRecordsTask.** Три счётчика
   (`consecutiveEventFailures`, `consecutiveRegistrationFailures`, `consecutiveFailures`)
   обновляются в разных transition-методах. Reader может увидеть арифметически
   непоследовательный набор; за этим следуют ложные branches в state machine
   `computeHealth`. Production impact мал, но это не «никогда не происходит» — это
   «происходит на ms-окне и моментально самокорректируется».
2. **Удаление JMM-комментария в ActiveExportRegistry.** Текущий код содержит длинный
   объяснительный блок (`attachCancellable` lines 114-128) о том, почему cross-field
   ordering работает «на нашем CPU». Это явный технический долг, который AtomicReference
   полностью устраняет.
3. **Унифицированная дисциплина в коде.** Без явного pattern'а каждый новый writer/reader
   решает заново, безопасны ли его reads. С AtomicReference + `state.get()`-in-каждом-методе
   правило одно и проверяется review-визуально.
4. **Дешёвая страховка от будущих регрессий.** AtomicReference добавляет CAS-семантику
   бесплатно (на JVM allocation ~100 bytes/update пренебрежим при наших rates). Если в
   будущем появится второй writer (например, периодическая задача, обновляющая metric'у), —
   гарантии не сломаются.

Признаём, что аргумент **не** «production падает» — production не падает. Аргумент —
«codebase health и устранение defense-in-depth-долга». Для одного из четырёх классов
(`ServerState`, 2 поля, single-writer scheduler thread) overhead заметен, и решение
включить его в scope обсуждалось — оставлено для униформности паттерна, см. §6.

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

> **Почему даже single-field writes идут через `updateAndGet`, а не `state.set(...)`?**
> (1) Унифицированная дисциплина — все writers в коде выглядят одинаково,
> review-визуально нет «привилегированных» путей. (2) Защита от появления второго
> writer'а в будущем: если когда-то кто-то добавит второй concurrent writer для того
> же поля, `updateAndGet` сохранит CAS-семантику, а `state.set` потеряет конкурентную
> запись. (3) Transform легко расширить (например, добавить cross-field invariant в
> tail-bump) без изменения call-site.

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

> **Visibility note.** `successesSinceLastFailure` was previously declared as `private @Volatile var` on the task. Moving it into `internal data class WatchTaskState` effectively widens its visibility from `private` to `internal` (it becomes readable across the module via `stateForTesting`). The field is observational — no production code outside the task reads it — but the widening is intentional and should be noted in review.

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
    текущую логику `maybeResetBackoff` (инкремент `successesSinceLastFailure` и reset
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

    /**
     * Atomic snapshot for multi-field reads. Use this (NOT the individual convenience
     * getters [cancellable]/[state]) whenever the read needs pair-consistency between
     * `cancellable` and `state` (e.g., "cancel only if state == CANCELLING && cancellable != null").
     * Single-field reads should keep using the convenience getters.
     */
    internal fun snapshot(): EntryState = stateRef.get()
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

**Единое правило для convenience getter'ов (across all 4 sites):**

> **Convenience getter допустим ТОЛЬКО для single-field reads.**
> Любой код (production или тестовый), читающий ≥2 поля из одного state-объекта в одной
> логической операции (например, для invariant-check или conditional branching), ОБЯЗАН
> использовать snapshot accessor — `server.snapshot()`, `entry.snapshot()`,
> `task.stateForTesting`, `sup.stateForTesting`, — и далее работать с локальной immutable
> копией.

Это правило enforced review-уровневой дисциплиной (нет runtime-проверки). Production
call-сайты на момент рефактора, читающие ≥2 поля одного state-объекта:

- `ActiveExportRegistry.attachCancellable` (cancellable + state) — `updateState` + read returned snapshot.
- `WatchRecordsTask.computeHealth`, `TelegramBotSupervisor.computeHealth` — `val s = state.get()` в начале.

Production call-сайты, читающие одно поле через convenience getter (acceptable):

- `DetectServerLoadBalancer` (line 86, 121): `server.alive` для status enum и log line.
- `ServerSelectionStrategy` (line 15): `it.alive` в filter.
- `ServerState.canAcceptRequest` (внутри класса): `alive` + counter — формально 2 reads,
  но counter уже atomic и pair-consistency не критична для load-balancing decision.
  Документировано в §10 Known limitations.
- `CancelExportHandlerTest`, `ActiveExportRegistryTest`: `entry.state == CANCELLING` для assertions.
- `ExportExecutor` (line 115, 268), `QuickExportHandler` (line 186, 300): `entry.state` reads
  в логике cancellation propagation — single-field, OK через convenience getter.

Если в будущем потребуется multi-field read из Entry или ServerState — добавляется новый
call-сайт через `entry.snapshot()`/`server.snapshot()`. Convenience getter'ы остаются для
single-field reads.

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

> **Формальное обоснование atomic read/write гарантии.** Корректность атомарного snapshot'а
> опирается не на эмпирический stress-тест, а на JMM-спецификацию: `AtomicReference.get()` /
> `updateAndGet` / `getAndUpdate` устанавливают happens-before между writer'ом и reader'ом
> по JLS §17.4 (volatile semantics для underlying field + CAS guarantee для RMW). Reader,
> делающий ровно один `state.get()` и далее работающий с локальной immutable `data class`-копией,
> видит self-consistent набор полей — это свойство `data class`-immutability, а не свойство
> AtomicReference: после публикации reference reader не может «доехать» до полуобновлённой
> копии, потому что её попросту не существует (transform создал новую instance и атомарно
> заменил reference). Concurrency stress-тест добавил бы только эмпирическую корреляцию;
> формальная гарантия уже даётся JMM.

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
- **Throwable safe-publication**. `Throwable` формально НЕ строго-immutable (`initCause`,
  `setStackTrace`, `addSuppressed` могут мутировать instance post-construction). Однако в
  этом codebase: (1) мы никогда не вызываем `initCause`/`setStackTrace`/`addSuppressed` на
  пойманных exception'ах перед записью в `state`; (2) `lastFailure` всегда сохраняется ровно
  один раз в момент catch, после чего instance treated as effectively-immutable. AtomicReference
  safe-publishes сам Throwable-reference (writer's writes happen-before reader's read через
  CAS). Copy-by-reference в `.copy()` безопасно при этих условиях. Если когда-нибудь появится
  код, мутирующий пойманный Throwable, рассмотреть переход на `FailureSnapshot(className,
  sanitizedMessage, at)` data class.
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

### 10.1 Invariant enforcement

Concurrency invariant поддерживается **review-уровневой дисциплиной**: «все writes — через
`updateAndGet`/`getAndUpdate`», «`state.set(...)` — только в `stateForTesting`-setter». Нет
runtime-проверки; добавление прямого writer-а в будущем — баг review-уровня. KDoc на поле
`state` фиксирует invariant.

### 10.2 Residual non-atomic reads (вне `state` snapshot)

Atomic snapshot покрывает **только** поля внутри `XState`-`data class`. Существуют reader'ы,
которые читают `state.get()` **и** соседнюю переменную/коллекцию **отдельно**. Эти reads
формально могут увидеть несогласованную пару, но pair-inconsistency либо самокорректируется
за следующий tick, либо не влияет на observable behavior:

- **`TelegramBotSupervisor.computeHealth`** + **`WatchRecordsTask.computeHealth`** читают
  `state.get()` **и** `supervisorJob?.isActive`. Job — lifecycle handle с собственными atomic
  semantics; writes — однократные (`start()` в `@PostConstruct`/`@EventListener`), readers —
  per-health-poll. Транзитная inconsistency «job stopped, но state ещё со старыми metric'ами»
  возможна на ms-окне, но computeHealth branch 1 (`!job.isActive → DOWN`) корректен в любом
  случае.
- **`WatchRecordsTask.computeHealth`** дополнительно читает `registeredDirs.size` /
  `registeredDirs.isEmpty()` отдельно от `state` snapshot. `ConcurrentHashMap` имеет atomic
  semantics, но пара (`s.lastSuccessfulRegistrationAt != null` + `registeredDirs.isEmpty()`)
  читается из двух источников. Branch 3.5 («registry collapsed after successful start»)
  обрабатывает корректно эту пару — но формально это 2-source read.
- **`ServerState.canAcceptRequest`** читает `alive` (через `healthRef.get().alive`) **и**
  отдельный `AtomicInteger`-counter (`getCurrentCount(requestType)`). Counter уже atomic,
  но пара (alive, counter-value) — 2 read'а. Для load-balancing decision pair-consistency
  не критична: false positive («думаем сервер alive, а он только что markServerDead» или
  наоборот) самокорректируется на следующем request'е через retry/route-to-другой-server.

Эти residual non-atomic reads признаны acceptable и **не** мигрируются в `XState` — они
либо относятся к lifecycle handles (Job, WatchService), либо уже thread-safe-collections,
либо counter-pairs где pair-consistency не нужна. Расширение `XState` на эти поля
противоречило бы scope (§3.2).

### 10.3 WatchRecordsTask: где пишется `currentBackoff`

В WatchRecordsTask `currentBackoff` пишется ровно в двух местах: (a) `runSupervised` —
tail bump после `delay`; (b) `onPollCompleted` → `maybeResetBackoffTransform` — reset
на success path при достижении `SUCCESSES_TO_RESET_BACKOFF`. `onLoopFailure` **не**
трогает `currentBackoff` (этим занимается caller в `runSupervised`). Все touch'и
централизуются через `updateAndGet { it.copy(currentBackoff = ...) }`, включая tail bump.
Поведение state machine не меняется.

### 10.4 Alternatives considered and rejected

**`AtomicReferenceFieldUpdater` (ARFU)** — теоретически saves ~100 bytes per update,
устраняет outer-class allocation для AtomicReference wrapper. Отвергнут, потому что:
(1) allocation overhead при наших rates (≤10 events/sec в WatchRecordsTask, ≤1 per
5-60s в supervisor) пренебрежимо мал даже для embedded JVM; (2) ARFU требует
reflection-based setup в `companion object` с raw `Class<*>`-литералами, ломая
type-safety, которую даёт `AtomicReference<XState>`; (3) теряется data class
`copy(...)` ergonomics — transform превращается из `state.updateAndGet { it.copy(...) }`
в `updater.updateAndGet(this) { ... }` с explicit-typed lambda; (4) код становится менее
читабельным, что противоречит цели рефакторинга (унификация дисциплины через простой
patterns).

**`ReadWriteLock` / `StampedLock`** — теоретически подходит для «many readers, one
writer»-сценария ServerState. Отвергнут, потому что: (1) AtomicReference fundamentally
lock-free — нет parker'ов, нет thread contention в hot path; (2) CAS contention на
наших rates близок к нулю (один writer на 5-30s); (3) explicit locks вводят дополнительный
interleaving risk (deadlock при отсутствии lock-ordering, hold-while-yield в long reader);
(4) для `StampedLock` optimistic read complicated — нужно validate-and-retry pattern,
который воспроизводит то же поведение, что и `AtomicReference.get()` бесплатно;
(5) reader API хочет single-statement `state.get()`, а не `lock.read { state.snapshot() }`.

### 10.5 Future work

**Domain-specific update methods.** Текущий API предлагает generic
`entry.updateState(transform)` / `task.state.updateAndGet { ... }`. В будущем имеет смысл
ограничить writers domain-specific методами (`entry.markCancelling()`,
`task.onPollCompleted(...)`), чтобы каждый writer был явно типизирован и review мог
проверять transition rules от call-site. Не делаем сейчас, потому что: (1) рефакторинг
уже инвазивный (8/11 полей + 4 класса) — каждый дополнительный layer увеличивает risk
regression'а; (2) generic API даёт максимальную гибкость при первом проходе; (3) если
future writers покажут паттерны злоупотреблений (например, side-effect в transform), —
тогда добавляем domain-specific wrappers поверх.

### 10.6 ktlint / corrective formatting

Если `ktlint` или Kotlin compiler требуют определённой формы lambda — corrective formatting
применяется по результату `ktlintCheck` в каждом коммите (не open question, просто стандартная
итерация).
