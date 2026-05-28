# Non-atomic `@Volatile` snapshot reads in health indicators

## Краткое описание проблемы

Внутри проекта несколько supervisor-классов держат своё mutable state как набор `@Volatile var`
полей (timestamps, counters, current job, last failure и т. д.) и читают их в **HealthIndicator**
через метод `computeHealth(now)` — отдельными `field` reads, без atomic snapshot.

Между этими reads другой thread (retry-loop coroutine на `Dispatchers.IO.limitedParallelism(1)`)
может обновить любое подмножество полей через `onAttemptEnded`-подобный writer. В результате
HealthIndicator может наблюдать **inconsistent state**: например, `lastFailureAt = now` уже
записано, но `consecutiveFailures` ещё не инкрементировано → попадает в branch state machine,
рассчитанный на другую комбинацию полей.

Worst case: транзитный JSON-вывод `/actuator/health` с несогласованными деталями
(`consecutiveFailures=0, lastFailureAt=just-now` или зеркальная пара). Сам **status** (`UP`/`OUT_OF_SERVICE`/`DOWN`)
может тоже временно отображать «не ту» ветку state machine, пока в одном тике actuator-а
читается полу-обновлённое состояние. Это самокорректируется при следующем тике (~интервал
docker healthcheck).

В **production** impact нулевой/околонулевой: алерты на длительный DOWN/UP не страдают
(транзиции writer-а — millisecond-уровень). Но это инцидент защиты-в-глубину: 4 из 6 внешних
ревьюеров `TelegramBotSupervisor` независимо это отметили.

## Где это есть

### 1. `WatchRecordsTask` (modules/core/src/main/kotlin/.../core/task/WatchRecordsTask.kt)

**12** `@Volatile var` полей (lines 58–86):

| Поле | Тип | Кто пишет |
|------|-----|-----------|
| `supervisorJob` | `Job?` | `start()` (один writer) |
| `lastSuccessfulPollAt` | `Instant?` | `runSupervised` (один writer) |
| `lastEventProcessedAt` | `Instant?` | `runSupervised` |
| `lastSuccessfulRegistrationAt` | `Instant?` | `runSupervised` |
| `consecutiveEventFailures` | `Long` | `runSupervised` |
| `consecutiveRegistrationFailures` | `Long` | `runSupervised` |
| `consecutiveFailures` | `Long` | `runSupervised` |
| `successesSinceLastFailure` | `Int` | `runSupervised` |
| `currentBackoff` | `Duration` | `runSupervised` |
| `lastFailure` | `Throwable?` | `runSupervised` |
| `lastFailureAt` | `Instant?` | `runSupervised` |
| `startupAt` | `Instant?` | `start()` |

Reader: `WatchRecordsTaskHealthIndicator.health()` → `task.computeHealth(Instant.now(clock))`.

### 2. `TelegramBotSupervisor` (modules/telegram/src/main/kotlin/.../telegram/bot/supervisor/TelegramBotSupervisor.kt)

**9** `@Volatile var` полей (lines 67–83):

| Поле | Тип | Кто пишет |
|------|-----|-----------|
| `supervisorJob` | `Job?` | `start()` |
| `startupAt` | `Instant?` | `start()` |
| `lastAttemptAt` | `Instant?` | `runSupervised` |
| `lastPollingStartAt` | `Instant?` | `runSupervised` (двойной write: null at iteration start + post-runner.run; non-null между) |
| `lastStableAt` | `Instant?` | `onAttemptEnded` |
| `lastFailure` | `Throwable?` | `onAttemptEnded` |
| `lastFailureAt` | `Instant?` | `onAttemptEnded` |
| `consecutiveFailures` | `Long` | `onAttemptEnded` |
| `currentBackoff` | `Duration` | `runSupervised` + `onAttemptEnded` |

Reader: `TelegramBotSupervisorHealthIndicator.health()` → `supervisor.computeHealth(Instant.now(clock))`.

### 3. `ActiveExportRegistry` (modules/telegram/src/main/kotlin/.../bot/handler/export/ActiveExportRegistry.kt)

**2** `@Volatile var` полей (lines 43–44):

| Поле | Тип | Кто пишет |
|------|-----|-----------|
| `cancellable` | `CancellableJob?` | export-handler (один writer per registration) |
| `state` | `State` (enum: ACTIVE/CANCELLING/DONE) | export-handler + cancel-handler |

Reader: `cancelExportHandler.handle()` (другая coroutine).

Уже есть **explicit JMM-комментарий** про write-order (lines 118–122):
> // With cancellable written first, the reader either sees the new cancellable (via @Volatile
> // semantics) … JMM note: @Volatile writes/reads on DIFFERENT fields don't strictly establish
> happens-before...

Автор сознательно полагается на single-writer ordering без atomic snapshot. Это **другой класс
проблемы** (cancellation propagation, не actuator health), но та же база — multi-volatile reads
без snapshot.

### 4. `ServerState` (modules/core/src/main/kotlin/.../core/loadbalancer/ServerState.kt)

**2** `@Volatile var` полей (lines 11–12): `alive`, `lastCheckTimestamp`.

Reader: load-balancer ranker (читает оба для решения, кому отдавать запрос). Risk похожий, но
последствие — minor mis-routing на 1 запрос (auto-recovery на следующий heartbeat).

## Конкретные сценарии inconsistent read

### Сценарий A: «failure recorded, counter ещё не bumped» (supervisor)

Writer (`onAttemptEnded`):
```
lastFailure = e           // T0
lastFailureAt = now       // T1
consecutiveFailures++     // T2
```

Reader (`computeHealth`) между T1 и T2:
```
val failedAt = lastFailureAt           // sees T1 write: just-now
val cf = consecutiveFailures           // sees pre-T2: 0
// → branch 6/7 check (cf > 0) skipped despite real failure
```

Branch 7 («just (re)connected») вместо branch 6 («in backoff»). Сообщение в JSON: «connecting…»
вместо «in backoff». Через ~1 ms (следующий read) — уже правильно.

### Сценарий B: «pollStart stamped, failedAt не cleared» (supervisor)

`lastPollingStartAt = now` ставится в начале iteration, до того как `lastFailureAt` может быть
обнулён (он не обнуляется — всегда хранит timestamp последнего failure). Если в test/clock-tick
случилось `pollStart == failedAt` (millisecond-level), invariant `pollStart.isAfter(failedAt)`
строго `>` — FALSE → branch 2 (UP) запрещён на лишний STABLE_THRESHOLD период.

(Это отдельный edge case, флагнутый как claude I1 на ревью PR `fix/telegram-bot-supervisor`.)

### Сценарий C: WatchRecordsTask — два consecutive\* counter'а

WatchRecordsTask имеет separate `consecutiveEventFailures` и `consecutiveRegistrationFailures` +
overall `consecutiveFailures`. Writer пишет их в разные моменты iteration. Reader может видеть
«event-failures = 3, registration-failures = 0, overall = 4» — арифметически непоследовательно
(overall — sum of components в текущей логике? проверить).

## Варианты решения

### A. AtomicReference<State> snapshot pattern

```kotlin
data class SupervisorState(
    val startupAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastPollingStartAt: Instant?,
    val lastStableAt: Instant?,
    val lastFailure: Throwable?,
    val lastFailureAt: Instant?,
    val consecutiveFailures: Long,
    val currentBackoff: Duration,
)

private val state = AtomicReference(SupervisorState.initial())
```

Writes:
```kotlin
state.updateAndGet { it.copy(consecutiveFailures = it.consecutiveFailures + 1, lastFailureAt = now) }
```

Reads:
```kotlin
val s = state.get()  // single atomic snapshot
// branch on s.lastFailureAt, s.consecutiveFailures, ...
```

**Плюсы:**
- Гарантирует consistency для health endpoint
- Single-writer scenarios → `updateAndGet` без contention overhead
- Чистая semantic — state is one immutable object

**Минусы:**
- ~+100/-50 LoC per supervisor класс
- Test infrastructure: тесты сейчас пишут `sup.consecutiveFailures = 1L` напрямую — нужно
  заменить на `sup.stateForTesting = sup.stateForTesting.copy(...)` или helper
- `supervisorJob: Job?` — отдельно от state (job — это объект lifecycle, не value)
- Дисциплина: все writes должны идти через `updateAndGet` (если кто-то добавит прямой write
  в новом коде — гонка возвращается)

### B. Mutex/synchronized block в writer + reader

```kotlin
private val stateMutex = Mutex()

suspend fun onAttemptEnded(...) {
    stateMutex.withLock {
        lastFailure = e
        lastFailureAt = now
        consecutiveFailures++
    }
}

fun computeHealth(now: Instant): Health = runBlocking {
    stateMutex.withLock {
        // existing logic, all reads under lock
    }
}
```

**Плюсы:** простое решение без data class refactor.

**Минусы:**
- `runBlocking` в health endpoint — серьёзно (blocking thread Spring Actuator)
- Несовместимо с current `Volatile` non-blocking approach (HealthIndicator должен возвращать
  быстро)
- Stronger coupling between writer и reader (lock contention)

### C. Local snapshot reads в начале `computeHealth` без atomicity

```kotlin
fun computeHealth(now: Instant): Health {
    val s = SupervisorState(
        startupAt, lastAttemptAt, lastPollingStartAt, lastStableAt,
        lastFailure, lastFailureAt, consecutiveFailures, currentBackoff,
    )
    // branch on s.*
}
```

**Плюсы:** минимальное изменение, делает «snapshot» явным в коде.

**Минусы:** не решает гонку — между чтениями 8 fields может произойти write. Это просто маскировка
проблемы. **Хуже чем B и A.**

### D. Документировать как acceptable (текущий статус)

Добавить комментарий в `computeHealth` с указанием:
- Reads non-atomic; transient inconsistency возможна
- Health endpoint — informational; self-corrects within milliseconds
- Production impact нулевой (no monitoring system алертит на ms-level flap)

**Плюсы:** нулевой код, сохраняет проектный паттерн.

**Минусы:** ревьюеры будут флагнуть это снова при следующем code review.

## Дополнительные размышления для brainstorm

1. **Что делать с `supervisorJob: Job?`** при варианте A? Это не value, а lifecycle handle.
   Возможно вынести его за `SupervisorState` и оставить отдельным `@Volatile var`.

2. **Что делать с `lastFailure: Throwable?`** при snapshot copy — copy-by-reference, не deep
   copy. Это OK, потому что Throwable immutable.

3. **WatchRecordsTask** имеет особенность — два разных типа failures (event vs registration) +
   агрегированный. Snapshot должен включать оба, иначе arithmetic inconsistency остаётся.

4. **Test ergonomics** — снимать ergonomics-удар можно так:
   ```kotlin
   internal var stateForTesting: SupervisorState
       get() = state.get()
       set(value) { state.set(value) }
   ```
   Это позволяет тестам писать `sup.stateForTesting = sup.stateForTesting.copy(consecutiveFailures = 1L)`.

5. **Consistency vs simplicity trade-off** — single-deployment проект, monitoring у владельца
   домашнее. Real-world cost транзитного inconsistent JSON ≈ ноль. Стоит ли усложнять?

6. **Если делать — где остановиться:**
   - Только `WatchRecordsTask` + `TelegramBotSupervisor` (health-exposed)
   - `ActiveExportRegistry` — другая природа проблемы (concurrency control, не observability)
   - `ServerState` — load-balancer, тоже observability но обычно не алертится

## Ссылки

- Review iter findings (вне репозитория): supervisor PR feedback от codex, ollama-minimax,
  ollama-kimi, ccs-glm.
- JMM background: https://shipilev.net/blog/2014/jmm-pragmatics/
- Java AtomicReference vs Volatile: https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html
- Kotlin coroutines memory model: https://github.com/Kotlin/kotlinx.coroutines/issues/2143

## Status

Не открытый bug — production impact ≈ 0. Это **technical-debt cleanup** для будущей итерации.
Решение через `/brainstorming` → дизайн → план → реализация.
