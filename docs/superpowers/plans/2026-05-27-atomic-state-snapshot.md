# Atomic state snapshot refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить набор `@Volatile var`-полей в 4 классах (`TelegramBotSupervisor`, `WatchRecordsTask`, `ActiveExportRegistry.Entry`, `ServerState`) на `AtomicReference<XState>`-snapshot, чтобы reader всегда видел consistent комбинацию полей.

**Architecture:** Per-class immutable Kotlin `data class XState`, инкапсулированный через `AtomicReference<XState>`. Writers — только `updateAndGet`/`getAndUpdate` с pure transform. Readers — ровно один `state.get()` в начале метода, дальнейшая работа — с локальной snapshot. `Job` и `ConcurrentHashMap` остаются вне State (lifecycle handles и thread-safe collections). Конструкция гарантирует, что любой reader атомарно видит self-consistent набор runtime-метрик.

**Tech Stack:** Kotlin 2.3.21, kotlinx.coroutines, Spring Boot 4.0.6, JUnit 5, kotlin-test, MockK. `java.util.concurrent.atomic.AtomicReference` (стандартная Java библиотека).

**Reference:** Spec `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`. Context document `docs/health-volatile-snapshot-issue.md`.

---

## File Structure

Production code (modify):
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt` — добавить `HealthSnapshot` + `AtomicReference`, заменить `@Volatile var alive/lastCheckTimestamp`.
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerHealthMonitor.kt` — 3 пары writes → `updateHealth { ... }`.
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt` — `Entry.EntryState` data class + `AtomicReference`, переписать `attachCancellable` и `markCancelling`, удалить JMM-комментарий.
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt` — добавить `SupervisorState` data class + `AtomicReference`, удалить 8 `@Volatile var`-полей, переписать `start`/`runSupervised`/`onAttemptEnded`/`computeHealth`/`baseBuilder`.
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt` — добавить `WatchTaskState` data class + `AtomicReference`, удалить 11 `@Volatile var`-полей, переписать `start`/`onPollCompleted`/`onRegistrationSuccess`/`onRegistrationFailure`/`onLoopFailure`/`maybeResetBackoff`/`computeHealth`/`baseBuilder`.

Test code (modify):
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` — 12 `server.alive = true` reads-after-write (через convenience getter остаются без изменений, writes через `updateHealth`).
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt` — 1 `.alive = true` write.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt` — 1 `.alive = true` write.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt` — ~30 direct field writes → `stateForTesting = SupervisorState(...)`; reads → `sup.stateForTesting.X`.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorHealthIndicatorTest.kt` — аналогично, если есть direct field writes.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt` — helper `buildTask(...)` свернуть в один `state: WatchTaskState`-параметр; reads → `task.stateForTesting.X`.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt` — аналогично.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistryTest.kt` — тесты не пишут internal state напрямую, проверить и обновить если найдутся `entry.cancellable = ...`/`entry.state = ...` (после grep — таких нет; reads `entry.state == CANCELLING` через convenience getter остаются).

Coверение spec'ом:
- Task 1 ↔ Spec §4.4 (ServerState)
- Task 2 ↔ Spec §4.3 (ActiveExportRegistry.Entry)
- Task 3 ↔ Spec §4.1 (TelegramBotSupervisor)
- Task 4 ↔ Spec §4.2 (WatchRecordsTask)
- Task 5 ↔ Spec §8-9 (final build, external review prep)

---

## Task 1: Refactor `ServerState` to AtomicReference health snapshot

✅ Done — see commits: `28fdabc` (refactor), `556c877` (KDoc + atomic toString follow-up).

---

## Task 2: Refactor `ActiveExportRegistry.Entry` to AtomicReference EntryState

✅ Done — see commits: `73da93c` (refactor), `1aada9b` (snapshot() KDoc disclosure follow-up).

---

## Task 3: Refactor `TelegramBotSupervisor` to AtomicReference SupervisorState

✅ Done — see commits: `df9caf7` (refactor), `8c82ced` (test comment polish follow-up).

---

## Task 4: Refactor `WatchRecordsTask` to AtomicReference WatchTaskState

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt`

### Step 4.1: Прочитать файлы и зафиксировать API

- [ ] Прочитать `WatchRecordsTask.kt`. 11 полей-кандидатов на удаление (lines 58, 66-86 кроме `supervisorJob`, `watchService`, `registeredDirs`): `startupAt`, `lastSuccessfulPollAt`, `lastEventProcessedAt`, `lastSuccessfulRegistrationAt`, `consecutiveEventFailures`, `consecutiveRegistrationFailures`, `consecutiveFailures`, `successesSinceLastFailure`, `currentBackoff`, `lastFailure`, `lastFailureAt`.
- [ ] Зафиксировать writes: `start` (line 103), `runSupervised` (lines 146-181 — `currentBackoff`), `ensureWatchService` (call onRegistration*), `onPollCompleted` (lines 216-237), `onRegistrationSuccess` (lines 239-243), `onRegistrationFailure` (lines 245-251), `onLoopFailure` (lines 253-259), `maybeResetBackoff` (lines 261-270).
- [ ] Зафиксировать reads: `computeHealth` (lines 275-389, 8 branches + base details).
- [ ] Прочитать `WatchRecordsTaskTest.kt`. Helper `buildTask(...)` (lines ~200-230) принимает 9 полей и присваивает по одному.

**Grep-инвентаризация (11 полей):**

```bash
# Полная инвентаризация по 11 полям
for field in startupAt lastSuccessfulPollAt lastEventProcessedAt lastSuccessfulRegistrationAt consecutiveEventFailures consecutiveRegistrationFailures consecutiveFailures successesSinceLastFailure currentBackoff lastFailure lastFailureAt; do
  echo "=== $field ==="
  git grep -nP "\.${field}\b" -- 'modules/**/*.kt'
done

# Assignments (отделить writers)
for field in startupAt lastSuccessfulPollAt lastEventProcessedAt lastSuccessfulRegistrationAt consecutiveEventFailures consecutiveRegistrationFailures consecutiveFailures successesSinceLastFailure currentBackoff lastFailure lastFailureAt; do
  echo "=== $field assignments ==="
  git grep -nP "\.${field}\s*=" -- 'modules/**/*.kt'
done

# Дополнительно: registeredDirs reads/writes — ConcurrentHashMap, в snapshot не попадает,
# но grep полезен для confidence
git grep -nP '\bregisteredDirs\b' -- 'modules/**/*.kt'
```

Ожидаемые reader-сайты вне `WatchRecordsTask.kt`:
- `WatchRecordsTaskTest.kt` — assertions на counter-поля, `task.lastFailure`, и т.д.
- `WatchRecordsTaskHealthIndicatorTest.kt` (если есть) — аналогично.

Ожидаемые writer-сайты вне `WatchRecordsTask.kt`:
- `WatchRecordsTaskTest.kt` через helper `buildTask(...)` (свернуть в `state: WatchTaskState`-параметр, см. Step 4.8).

Если grep найдёт production-writer вне `WatchRecordsTask.kt` — это баг spec'а. СТОП — обсудить.

### Step 4.2: Изменить `WatchRecordsTask.kt` — добавить `WatchTaskState`, удалить 11 полей

Импорт добавить:
```kotlin
import java.util.concurrent.atomic.AtomicReference
```

Заменить блок 11 `@Volatile`-полей (lines 66-86) на:

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

    /**
     * Single source of truth for runtime metrics. ALL writes MUST go through
     * [updateAndGet]; direct `state.set(...)` is reserved for test fixtures via [stateForTesting].
     * Reader code MUST do exactly one `state.get()` at the top of any method that touches
     * more than one field.
     */
    private val state = AtomicReference(WatchTaskState())

    /**
     * Test-fixture access for the task's runtime state. **DO NOT USE FROM PRODUCTION
     * CODE.** Direct `state.set(...)` bypasses the CAS discipline maintained by
     * [updateAndGet] — production writers MUST go through [updateAndGet]. Visibility
     * is `internal` to confine misuse to the test source set within this module;
     * review discipline enforces the rule (no runtime check).
     */
    internal var stateForTesting: WatchTaskState
        get() = state.get()
        set(value) { state.set(value) }
```

`@Volatile internal var supervisorJob: Job? = null` (line 58) остаётся.
`private var watchService: WatchService? = null` и `internal val registeredDirs` (lines 60-61) остаются.

### Step 4.3: Переписать `start()` — `startupAt` через `updateAndGet`

Заменить функцию `start()` (lines 92-105) на:

```kotlin
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. Watch records task skipped." }
            return
        }
        if (supervisorJob != null) {
            logger.warn { "WatchRecordsTask.start() invoked twice; ignoring duplicate." }
            return
        }
        logger.info { "Starting watch records in folder: ${recordsWatcherProperties.folder}" }
        state.updateAndGet { it.copy(startupAt = Instant.now(clock)) }
        supervisorJob = scope.launch { runSupervised() }
    }
```

### Step 4.4: Переписать `runSupervised()` — `currentBackoff` writes через `updateAndGet`

Заменить функцию `runSupervised()` (lines 145-183) на:

```kotlin
    internal suspend fun runSupervised() {
        state.updateAndGet { it.copy(currentBackoff = INITIAL_BACKOFF) }
        var lastCleanup = Instant.now(clock)
        while (currentCoroutineContext().isActive) {
            try {
                ensureWatchService()
                val result = loop.runIteration(watchService!!, registeredDirs, lastCleanup)
                lastCleanup = result.lastCleanupAt
                onPollCompleted(result.eventsProcessed, result.eventFailures)
                if (result.eventFailures > 0 && result.eventsProcessed == 0) {
                    // CRITICAL-2: single atomic RMW. getAndUpdate returns the PRE-bump
                    // snapshot, from which we read the effective backoff for the delay.
                    // No split read+update — eliminates the cross-snapshot race.
                    val effectiveBackoff = state
                        .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                        .currentBackoff
                    delay(effectiveBackoff.toMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedWatchServiceException) {
                logger.warn { "WatchService closed; will recreate next iteration" }
                closeWatchServiceQuietly()
                registeredDirs.clear()
                onLoopFailure(e)
                val effectiveBackoff = state
                    .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                    .currentBackoff
                delay(effectiveBackoff.toMillis())
            } catch (e: Exception) {
                onLoopFailure(e)
                val effectiveBackoff = state
                    .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                    .currentBackoff
                logger.error(e) {
                    "WatchRecordsTask iteration failed; backing off for ${effectiveBackoff.toMillis()}ms"
                }
                delay(effectiveBackoff.toMillis())
            }
        }
    }
```

**CRITICAL-2 rationale (per review iter-1 decision option A).** The previous shape had two
separate state operations per backoff cycle — `val backoff = state.get().currentBackoff;
delay(...); state.updateAndGet { nextBackoff(...) }` — which is a split snapshot violating
the single-snapshot discipline (and the log line additionally added a third `state.get()`).
The new shape uses `getAndUpdate` to atomically bump the backoff and recover the pre-bump
value in one call; the local `effectiveBackoff` is then used for both the `delay` and the
log line. Observable semantics preserved (delay for the OLD backoff, bump for next iteration);
race window between read and update closed. SUGGESTION-7 (proposed comment about single-writer
atomicity in runSupervised) is moot in this shape and not applied.

### Step 4.5: Переписать transition-методы — single `updateAndGet` каждый

Заменить функции `onPollCompleted`, `onRegistrationSuccess`, `onRegistrationFailure`, `onLoopFailure`, `maybeResetBackoff` (lines 216-270) на:

```kotlin
    /**
     * Result of [maybeResetBackoffTransform]. Bundles the new state with a side-effect-free
     * indication of whether a reset actually happened, so that the caller can log **outside**
     * `updateAndGet` (the transform itself must remain pure — `AtomicReference.updateAndGet`
     * may retry the lambda under CAS contention, and a side-effect-in-transform pattern
     * would produce duplicate log entries on retry).
     */
    internal data class BackoffResetResult(
        val state: WatchTaskState,
        val didReset: Boolean = false,
        /** Successes count at the moment of reset — used by the caller for the log message. */
        val nextSuccessesAtReset: Int = 0,
    )

    private fun onPollCompleted(
        eventsProcessed: Int,
        eventFailures: Int,
    ) {
        val now = Instant.now(clock)
        // Capture-on-every-retry: these vars are unconditionally assigned at the top of
        // each transform invocation, so their final value reflects the SUCCESSFUL CAS run.
        // Spec §7: transform must be pure; the only side-effect (logger.info) happens once
        // after updateAndGet returns.
        var didResetBackoff = false
        var resetAfterSuccesses = 0
        state.updateAndGet { s ->
            didResetBackoff = false
            resetAfterSuccesses = 0
            var n = s.copy(lastSuccessfulPollAt = now)
            if (eventsProcessed > 0) {
                val applied = applyEventsProcessedTransform(n, now)
                n = applied.state
                didResetBackoff = applied.didReset
                resetAfterSuccesses = applied.nextSuccessesAtReset
                // NOTE: maybeResetBackoffTransform increments successesSinceLastFailure.
                // When the same poll also brought event-failures (eventFailures > 0, handled
                // below), that increment is immediately zeroed by the failure branch — a
                // "wasted" increment. The didResetBackoff flag, however, is NOT cleared by
                // the failure branch: an actual backoff reset (currentBackoff -> INITIAL)
                // remains observable in the final state, so logging it is correct. This
                // is the same observable behavior the pre-refactor code produced (where the
                // logger.info was inside the reset path of maybeResetBackoff regardless of
                // any subsequent failure handling).
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
        // Side-effect log: fires exactly once per onPollCompleted invocation, AFTER the
        // CAS-loop has settled. Idempotent under retry: didResetBackoff is only true if
        // the committed transform did the reset.
        if (didResetBackoff) {
            logger.info { "Backoff reset after $resetAfterSuccesses consecutive successes" }
        }
    }

    /**
     * Pure transform applied when `eventsProcessed > 0`. Updates `lastEventProcessedAt`,
     * zeros `consecutiveEventFailures` and `consecutiveFailures`, then delegates to
     * [maybeResetBackoffTransform] for backoff-reset bookkeeping. Propagates the result
     * up so the caller can observe whether the backoff was reset.
     */
    private fun applyEventsProcessedTransform(
        s: WatchTaskState,
        now: Instant,
    ): BackoffResetResult {
        val withSuccess = s.copy(
            lastEventProcessedAt = now,
            consecutiveEventFailures = 0,
            consecutiveFailures = 0,
        )
        return maybeResetBackoffTransform(withSuccess)
    }

    private fun onRegistrationSuccess() {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                lastSuccessfulRegistrationAt = now,
                consecutiveRegistrationFailures = 0,
            )
        }
    }

    private fun onRegistrationFailure(t: Throwable) {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                consecutiveRegistrationFailures = it.consecutiveRegistrationFailures + 1,
                consecutiveFailures = it.consecutiveFailures + 1,
                successesSinceLastFailure = 0,
                lastFailure = t,
                lastFailureAt = now,
            )
        }
    }

    private fun onLoopFailure(t: Throwable) {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                consecutiveFailures = it.consecutiveFailures + 1,
                successesSinceLastFailure = 0,
                lastFailure = t,
                lastFailureAt = now,
            )
        }
    }

    /**
     * Pure transform: returns a new state with `successesSinceLastFailure` incremented and
     * `currentBackoff` reset to INITIAL_BACKOFF if the consecutive-success threshold is reached.
     * If `currentBackoff` is already INITIAL_BACKOFF, returns input unchanged with `didReset=false`.
     *
     * **No side-effects.** The caller (typically [onPollCompleted]) is responsible for
     * emitting the "Backoff reset" log line after the enclosing `updateAndGet` has settled.
     */
    internal fun maybeResetBackoffTransform(s: WatchTaskState): BackoffResetResult {
        if (s.currentBackoff <= INITIAL_BACKOFF) return BackoffResetResult(state = s)
        val nextSuccesses = s.successesSinceLastFailure + 1
        return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
            BackoffResetResult(
                state = s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0),
                didReset = true,
                nextSuccessesAtReset = nextSuccesses,
            )
        } else {
            BackoffResetResult(state = s.copy(successesSinceLastFailure = nextSuccesses))
        }
    }
```

**CRITICAL-1 rationale (per review iter-1 decision option A).** The previous shape of
`maybeResetBackoffTransform` placed `logger.info` inside the transform passed to
`state.updateAndGet`. That violated Spec §7 (pure transforms): `updateAndGet` may retry the
lambda under CAS contention, producing duplicate log entries on retry. The new shape:
(1) makes `maybeResetBackoffTransform` and `applyEventsProcessedTransform` fully pure
(`BackoffResetResult` carries the would-have-logged signal without side-effects);
(2) lifts the `logger.info` out into the caller, where it fires exactly once per
`onPollCompleted` invocation (after the CAS-loop has settled); (3) `internal` visibility
on `maybeResetBackoffTransform` enables direct parametrized unit testing (see Step 4.5b
added per SUGGESTION-5).

### Step 4.5b: Unit test `maybeResetBackoffTransform` (per SUGGESTION-5)

Теперь, когда helper полностью pure (returns `BackoffResetResult`, no logging), он trivially
parametrized-testable. Добавить тестовый класс или новый `@Test`-метод в `WatchRecordsTaskTest`:

```kotlin
@Test
fun `maybeResetBackoffTransform — no-op when currentBackoff already at INITIAL`() {
    val task = buildTask()  // any state; we call helper directly
    val input = WatchTaskState(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 99)
    val result = task.maybeResetBackoffTransform(input)
    assertEquals(BackoffResetResult(state = input), result)
}

@Test
fun `maybeResetBackoffTransform — increments successes below threshold`() {
    val task = buildTask()
    val input = WatchTaskState(
        currentBackoff = Duration.ofSeconds(10),
        successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 2,
    )
    val result = task.maybeResetBackoffTransform(input)
    assertEquals(
        BackoffResetResult(state = input.copy(successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 1)),
        result,
    )
}

@Test
fun `maybeResetBackoffTransform — resets backoff at threshold`() {
    val task = buildTask()
    val input = WatchTaskState(
        currentBackoff = Duration.ofSeconds(10),
        successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 1,
    )
    val result = task.maybeResetBackoffTransform(input)
    assertEquals(
        BackoffResetResult(
            state = input.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0),
            didReset = true,
            nextSuccessesAtReset = SUCCESSES_TO_RESET_BACKOFF,
        ),
        result,
    )
}
```

Импорт в test-файл:
```kotlin
import ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.BackoffResetResult
```

Цель: эта тройка тестов фиксирует чёткий decision-table helper'а, что не покрывается
интеграционными `onPollCompleted` тестами (они проверяют composition, но не пограничные
cases helper'а напрямую).

### Step 4.6: Переписать `computeHealth(now)` — single snapshot read

Заменить функцию `computeHealth` (lines 275-389) на:

```kotlin
    fun computeHealth(now: Instant): Health {
        val s = state.get()
        val builder = baseBuilder(s)

        // BRANCH 1: supervisor not active
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }

        val regAt = s.lastSuccessfulRegistrationAt
        val started = s.startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 2: startup failure — never registered, threshold reached OR grace expired
        if (regAt == null &&
            (s.consecutiveRegistrationFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: no successful registration after " +
                        "${s.consecutiveRegistrationFailures} attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 3: never registered yet, but grace not expired — starting up
        if (regAt == null) {
            return builder
                .outOfService()
                .withDetail("reason", "registering... attempts=${s.consecutiveRegistrationFailures}")
                .build()
        }

        // BRANCH 3.5: registry collapsed after a successful start
        if (registeredDirs.isEmpty()) {
            return builder
                .down()
                .withDetail("reason", "registry empty after successful start (root folder gone?)")
                .build()
        }

        val lastEvent = s.lastEventProcessedAt

        // BRANCH 4: event-failures + stale → DOWN.
        val staleReference = lastEvent ?: regAt
        if (s.consecutiveEventFailures > 0 &&
            Duration.between(staleReference, now) > HEALTH_STALENESS
        ) {
            val anchor = if (lastEvent != null) "last processed at $lastEvent" else "no event ever processed (registered at $regAt)"
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: events failing for ${Duration.between(staleReference, now)} ($anchor)",
                ).build()
        }

        // BRANCH 5: event-failures, not stale → transient OUT_OF_SERVICE
        if (s.consecutiveEventFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "transient event-processing failures (${s.consecutiveEventFailures} consecutive)",
                ).build()
        }

        // BRANCH 6: general backoff (e.g., ClosedWatchServiceException recovery cycle)
        if (s.consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff after ${s.consecutiveFailures} consecutive iteration failures",
                ).build()
        }

        // BRANCH 7: idle (camera off / no events) — UP with note
        if (lastEvent == null && sinceStartup > HEALTH_STALENESS.multipliedBy(2)) {
            return builder
                .up()
                .withDetail("reason", "idle: registered but no events yet (camera offline?)")
                .build()
        }

        // BRANCH 8: all good
        return builder.up().withDetail("reason", "healthy").build()
    }
```

### Step 4.7: Извлечь `baseBuilder(s)` — принимает snapshot

После `computeHealth` (или перед, по стилю файла) добавить private-функцию:

```kotlin
    private fun baseBuilder(s: WatchTaskState): Health.Builder {
        val builder = Health
            .Builder()
            .withDetail("lastSuccessfulPollAt", s.lastSuccessfulPollAt?.toString() ?: "never")
            .withDetail("lastEventProcessedAt", s.lastEventProcessedAt?.toString() ?: "never")
            .withDetail("lastSuccessfulRegistrationAt", s.lastSuccessfulRegistrationAt?.toString() ?: "never")
            .withDetail("consecutiveEventFailures", s.consecutiveEventFailures)
            .withDetail("consecutiveRegistrationFailures", s.consecutiveRegistrationFailures)
            .withDetail("consecutiveFailures", s.consecutiveFailures)
            .withDetail("currentBackoffMs", s.currentBackoff.toMillis())
            .withDetail("registeredDirs", registeredDirs.size)
        s.lastFailure?.let {
            builder.withDetail(
                "lastFailure",
                "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
            )
        }
        s.lastFailureAt?.let { builder.withDetail("lastFailureAt", it.toString()) }
        return builder
    }
```

Главное — извлечь base-builder из тела `computeHealth` (где он был inline в текущей реализации); снять дублирование, передать `s` параметром.

### Step 4.8: Обновить `WatchRecordsTaskTest.kt` — helper свернуть к `state: WatchTaskState`

Найти `buildTask(...)` helper (точные строки — ~200-230, могут смещаться). Заменить сигнатуру:

```kotlin
// Was:
private fun buildTask(
    startupAt: Instant? = Instant.parse("2026-05-23T12:00:00Z"),
    lastSuccessfulPollAt: Instant? = null,
    lastEventProcessedAt: Instant? = null,
    lastSuccessfulRegistrationAt: Instant? = null,
    consecutiveEventFailures: Long = 0,
    consecutiveRegistrationFailures: Long = 0,
    consecutiveFailures: Long = 0,
    lastFailure: Throwable? = null,
    registerDummyDir: Boolean = true,
    // ... (другие параметры если есть)
): WatchRecordsTask { ... }

// Becomes:
private fun buildTask(
    state: WatchTaskState = WatchTaskState(startupAt = Instant.parse("2026-05-23T12:00:00Z")),
    registerDummyDir: Boolean = true,
): WatchRecordsTask {
    val task = WatchRecordsTask(...)  // unchanged constructor args
    task.supervisorJob = Job()
    task.stateForTesting = state
    if (registerDummyDir && state.lastSuccessfulRegistrationAt != null) {
        task.registeredDirs[Path.of("/dummy/dir")] = mockk(relaxed = true)
    }
    return task
}
```

Call-сайты `buildTask(...)` в test'ах поменять с keyword-параметров на конструирование `WatchTaskState`:

```kotlin
// Was:
buildTask(
    startupAt = Instant.parse("2026-05-23T12:00:00Z"),
    lastSuccessfulRegistrationAt = null,
    consecutiveRegistrationFailures = 5,
    consecutiveFailures = 5,
)

// Becomes:
buildTask(
    state = WatchTaskState(
        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
        lastSuccessfulRegistrationAt = null,
        consecutiveRegistrationFailures = 5,
        consecutiveFailures = 5,
    ),
)
```

В тестах, где есть direct reads `task.consecutiveFailures`, `task.lastFailure` и т.д. — заменить на:

```kotlin
val s = task.stateForTesting
assertEquals(0, s.consecutiveFailures, "Counter should reset on success")
assertTrue(s.lastFailure is RuntimeException)
```

В тестах, где есть direct writes на runtime-поля (вне `buildTask`) — например `task.consecutiveFailures = ...` — заменить на `task.stateForTesting = task.stateForTesting.copy(...)`.

Импорт в test-файл:
```kotlin
import ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.WatchTaskState
```

**Дополнительно — deterministic transition tests (per SUGGESTION-2).** Добавить новые
testcases в `WatchRecordsTaskTest`, проверяющие full snapshot equality после каждого
transition:

```kotlin
@Test
fun `onPollCompleted with events and no failures resets event-failures and bumps lastEventProcessedAt`() {
    val now = Instant.parse("2026-05-27T12:00:00Z")
    val task = buildTask(
        state = WatchTaskState(
            startupAt = now.minus(Duration.ofHours(1)),
            lastSuccessfulRegistrationAt = now.minus(Duration.ofMinutes(10)),
            consecutiveEventFailures = 3,
            consecutiveFailures = 3,
            successesSinceLastFailure = 1,
            currentBackoff = Duration.ofSeconds(10),
        ),
    )
    fakeClock.setInstant(now)

    task.onPollCompleted(eventsProcessed = 5, eventFailures = 0)

    assertEquals(
        WatchTaskState(
            startupAt = now.minus(Duration.ofHours(1)),
            lastSuccessfulRegistrationAt = now.minus(Duration.ofMinutes(10)),
            lastSuccessfulPollAt = now,
            lastEventProcessedAt = now,
            consecutiveEventFailures = 0,
            consecutiveFailures = 0,
            successesSinceLastFailure = 2,           // incremented by maybeResetBackoffTransform
            currentBackoff = Duration.ofSeconds(10), // threshold not reached → unchanged
        ),
        task.stateForTesting,
    )
}
```

Cases для покрытия:
1. `onPollCompleted(events=5, failures=0)` — success path с continuing backoff (см. пример выше).
2. `onPollCompleted(events=5, failures=0)` — success path при `successesSinceLastFailure + 1 >= SUCCESSES_TO_RESET_BACKOFF` (backoff reset).
3. `onPollCompleted(events=0, failures=0)` — empty poll (только `lastSuccessfulPollAt`, `consecutiveFailures = 0`).
4. `onPollCompleted(events=5, failures=2)` — mixed (counters: `consecutiveEventFailures += 2`, `consecutiveFailures + 1`, `successesSinceLastFailure = 0` после wasted-increment).
5. `onPollCompleted(events=0, failures=2)` — pure failure path (`consecutiveEventFailures += 2`, `consecutiveFailures + 1`).
6. `onRegistrationSuccess()` — `lastSuccessfulRegistrationAt = now`, `consecutiveRegistrationFailures = 0`.
7. `onRegistrationFailure(throwable)` — `consecutiveRegistrationFailures + 1`, `consecutiveFailures + 1`, `successesSinceLastFailure = 0`, `lastFailure = throwable`, `lastFailureAt = now`.
8. `onLoopFailure(throwable)` — `consecutiveFailures + 1`, `successesSinceLastFailure = 0`, `lastFailure = throwable`, `lastFailureAt = now`.

Каждый case — single `assertEquals(expected, task.stateForTesting)`, гарантирующий что
transition атомарно ставит **точно** заданный набор полей (никаких случайных side-effect'ов
в snapshot).

### Step 4.9: Обновить `WatchRecordsTaskHealthIndicatorTest.kt` если применимо

- [ ] Прочитать файл.
- [ ] Если есть direct field writes на task — применить тот же паттерн.
- [ ] Если есть direct reads — переписать на `stateForTesting`.

### Step 4.10: Запустить ktlintFormat и build core-модуля

- [ ] Через build-runner agent выполнить: `./gradlew :modules:core:ktlintFormat`.
- [ ] Через build-runner agent выполнить: `./gradlew :modules:core:test`. Ожидаемо — все тесты `:modules:core` проходят.

### Step 4.11: Commit

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt
git commit -m "refactor(core): atomic WatchTaskState for WatchRecordsTask

Replace 11 @Volatile var fields (startupAt, lastSuccessfulPollAt,
lastEventProcessedAt, lastSuccessfulRegistrationAt, consecutiveEventFailures,
consecutiveRegistrationFailures, consecutiveFailures, successesSinceLastFailure,
currentBackoff, lastFailure, lastFailureAt) with AtomicReference<WatchTaskState>.
All transitions (onPollCompleted, onRegistrationSuccess/Failure, onLoopFailure)
become single updateAndGet calls — three different failure counters now move
together; reader sees them as one snapshot. maybeResetBackoffTransform is now
a pure function applied inside the transform.

computeHealth reads a single state.get() at the top, all 8 branches operate
on the local snapshot. registeredDirs (CHM) stays outside State.

Tests use stateForTesting; the buildTask helper collapses from 9 keyword
parameters into one WatchTaskState argument.

Per the atomic snapshot refactor design (see branch history)."
```

---

## Task 5: Full build, external review prep

**Files:** none (verification + commit prep).

### Step 5.1: Запустить full project build

- [ ] Через build-runner agent выполнить: `./gradlew build`. Ожидаемо — проходит чисто.
- [ ] Если падает по ktlint — выполнить `./gradlew ktlintFormat`, посмотреть diff (`git diff`), при необходимости — выполнить новый коммит `chore: ktlint format` в той же ветке, повторить build.

### Step 5.2: Запустить полный test suite

- [ ] Через build-runner agent выполнить: `./gradlew test`. Все тесты должны проходить.
- [ ] Если падает — изучить failure, исправить inline в production code или тесте, который тригернул. Не закрывать как «test flake» без анализа.

### Step 5.3: Проверить чтение `/actuator/health` (опционально, manual)

- [ ] Если есть Telegram-токен и работающий Frigate — поднять локально: `./gradlew bootRun`.
- [ ] `curl http://localhost:8080/frigate-analyzer/actuator/health | jq .`. Проверить `components.watchRecordsTask.details` и `components.telegramBotSupervisor.details` (если enabled). **Не** ожидать что `consecutiveFailures == 0 → lastFailureAt == null` — semantics для `lastFailure`/`lastFailureAt` — **sticky** (см. spec §7 «sticky failure semantics»): они отражают LAST observed failure, не current state. После stable-success: `consecutiveFailures` сбрасывается, но `lastFailure*` остаются. Проверять следует пары `consecutiveFailures > 0` + `lastFailureAt != null` (если когда-либо был failure) — иначе `lastFailureAt` может быть `null` (ни разу не падали).
- [ ] Если нет Telegram-токена — пропустить, unit тесты + integration coverage уже это покрывают.

### Step 5.4: Внешний code review через `/code-review` или `superpowers:requesting-code-review`

- [ ] Запустить external review (например, `superpowers:requesting-code-review` или slash-command `/code-review high`). Это standalone step — управляется пользователем или driver-сессией.
- [ ] Применить полученные комментарии итеративно. Каждая итерация — новый commit `review: ...` в той же ветке, как в `fix/telegram-bot-supervisor` history.
- [ ] Перед PR — выполнить `git rm` файлов из `docs/superpowers/specs/` и `docs/superpowers/plans/` (per global CLAUDE.md правила), создать commit `chore: drop plan + spec docs before opening PR`. `docs/health-volatile-snapshot-issue.md` оставить, если он не помечен как scratch (он остаётся для future reference в основной ветке).

### Step 5.5: Open PR

- [ ] Push ветку: `git push -u origin refactor/atomic-state-snapshot`.
- [ ] Создать PR через `gh pr create`:

```bash
gh pr create --title "refactor: atomic snapshot for @Volatile runtime state in supervisor classes" --body "$(cat <<'EOF'
## Summary
- Replace ad-hoc `@Volatile var` field clusters with `AtomicReference<XState>` snapshot pattern across 4 classes: `TelegramBotSupervisor`, `WatchRecordsTask`, `ActiveExportRegistry.Entry`, `ServerState`.
- Closes inconsistent-read defect class flagged by 4 of 6 external reviewers on `fix/telegram-bot-supervisor` (PR #36).
- No behavioral change visible from `/actuator/health` JSON — same details, same state-machine branches; just always self-consistent across multi-field reads.

## Test plan
- [ ] `./gradlew build` passes (incl. ktlint).
- [ ] `./gradlew test` passes (all modules).
- [ ] Manual: hit `/actuator/health` locally (if Telegram-enabled), verify `consecutiveFailures` and `lastFailureAt` are coherent.
- [ ] External code review iteration complete.
EOF
)"
```

---

## Verification checklist (post-implementation)

After all 5 tasks complete:

- [ ] `./gradlew build` — clean.
- [ ] `./gradlew test` — all pass.
- [ ] `git log refactor/atomic-state-snapshot --oneline` — содержит 4 commits (ServerState, ActiveExportRegistry, TelegramBotSupervisor, WatchRecordsTask) + дальнейшие review fixes, если были.
- [ ] No remaining `@Volatile var` для runtime-метрик в 4 target-классах (grep finder).
- [ ] No `state.set(...)` в production (вне `stateForTesting`-setter). grep: `git grep -n "state\.set" modules/`.
- [ ] No leftover JMM-комментарий в `attachCancellable` (он удалён в Task 2).
