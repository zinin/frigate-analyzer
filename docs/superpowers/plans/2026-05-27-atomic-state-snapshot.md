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

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerHealthMonitor.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

### Step 1.1: Прочитать целевые файлы и зафиксировать существующее API

- [ ] Прочитать `ServerState.kt`. Зафиксировать публичные/internal члены: `id`, `properties`, `alive` (`var`), `lastCheckTimestamp` (`var`), 4 counter-`AtomicInteger`, методы `canAcceptRequest`, `getCurrentCount`, `getMaxCount`, extension-функции `getCounter`, `getRequestConfig`.
- [ ] Прочитать `ServerHealthMonitor.kt`. Зафиксировать 3 точки writes: lines 39-40, 48-49, 60-61.
- [ ] Прочитать тесты, отметить call-сайты `server.alive = true` (12 в DetectServiceTest, 1 в DetectServiceCancelJobTest, 1 в VideoVisualizationServiceTest) — все они **writes**, надо менять.

**Grep-инвентаризация (запустить и зафиксировать вывод в коммит-сообщении или ноутбук агента):**

```bash
# Все reader/writer-сайты для server.alive (включая через registry)
git grep -nP '\.alive\b' -- 'modules/**/*.kt'

# Все reader-сайты для lastCheckTimestamp
git grep -nP '\.lastCheckTimestamp\b' -- 'modules/**/*.kt'

# Все call-сайты canAcceptRequest (узнаем, кто читает alive opaquely)
git grep -nP '\.canAcceptRequest\b' -- 'modules/**/*.kt'

# Writers конкретно (assignment vs comparison)
git grep -nP '\.alive\s*=' -- 'modules/**/*.kt'
```

Ожидаемые reader-сайты `.alive`:
- `modules/core/src/main/kotlin/.../core/loadbalancer/DetectServerLoadBalancer.kt:86,121` — status enum + log line.
- `modules/core/src/main/kotlin/.../core/loadbalancer/ServerSelectionStrategy.kt:15` — `.filter { it.alive }`.
- Внутри `ServerState.canAcceptRequest` — реализационный read (после refactor пойдёт через convenience getter).

Writer-сайты `.alive`:
- `ServerHealthMonitor.kt` — 3 точки (см. выше).
- Тесты — 14 точек (12+1+1).

Все reader-сайты остаются неизменными (convenience getter `val alive: Boolean` сохраняется). Все writer-сайты переписываются на `updateHealth { it.copy(alive = ..., lastCheckTimestamp = ...) }`.

### Step 1.2: Изменить `ServerState.kt` — добавить `HealthSnapshot` и `AtomicReference`

Заменить полностью `data class ServerState` (lines 8-35) на (CONCERN-3 fix: regular `class`
+ explicit `equals/hashCode` by `id` — устраняет AtomicInteger reference-identity footgun
в data-class-generated equals; верифицировано grep'ом что `.copy()` / componentN никогда
не использовались):

```kotlin
class ServerState(
    val id: String,
    val properties: DetectServerProperties,
    val processingFrameRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingFrameExtractionRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
    val processingVideoVisualizeRequestsCount: AtomicInteger = AtomicInteger(0),
) {
    /**
     * Health snapshot read/written atomically. All writes MUST go through
     * [updateHealth]. Reads happen via [snapshot] or convenience accessors
     * [alive] / [lastCheckTimestamp] — each single-field read is safe; for
     * combined read of multiple fields use [snapshot] and work with the
     * returned [HealthSnapshot] locally.
     */
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

    /**
     * Atomic RMW returning the **previous** snapshot. Use this (not [updateHealth]) when the
     * caller needs to detect a transition — e.g., "was alive, now dead" — without a check-then-act
     * race against concurrent writers. ServerState has up to three concurrent writers (health-check
     * success callback, error callback, `markServerDead`), so any transition-detection logic MUST
     * use the pre-update snapshot returned here rather than a separate `server.alive` read.
     */
    fun getAndUpdateHealth(transform: (HealthSnapshot) -> HealthSnapshot): HealthSnapshot =
        healthRef.getAndUpdate(transform)

    fun canAcceptRequest(requestType: RequestType): Boolean =
        alive && getCurrentCount(requestType) < getMaxCount(requestType)

    private fun getCurrentCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> processingFrameRequestsCount.get()
            RequestType.FRAME_EXTRACTION -> processingFrameExtractionRequestsCount.get()
            RequestType.VISUALIZE -> processingVisualizeRequestsCount.get()
            RequestType.VIDEO_VISUALIZE -> processingVideoVisualizeRequestsCount.get()
        }

    private fun getMaxCount(type: RequestType): Int =
        when (type) {
            RequestType.FRAME -> properties.frameRequests.simultaneousCount
            RequestType.FRAME_EXTRACTION -> properties.framesExtractRequests.simultaneousCount
            RequestType.VISUALIZE -> properties.visualizeRequests.simultaneousCount
            RequestType.VIDEO_VISUALIZE -> properties.videoVisualizeRequests.simultaneousCount
        }

    override fun equals(other: Any?): Boolean = other is ServerState && other.id == this.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String =
        "ServerState(id=$id, alive=$alive, lastCheckTimestamp=$lastCheckTimestamp)"
}
```

Импорты в начале файла должны включать (помимо текущих):
```kotlin
import java.util.concurrent.atomic.AtomicReference
```

Extension-функции `getCounter`, `getRequestConfig` ниже class-объявления **не меняются**.

### Step 1.3: Изменить `ServerHealthMonitor.kt` — 3 пары writes на `updateHealth`

В функции `checkServerHealth` (lines 27-55), заменить две `subscribe`-ветки:

**CRITICAL-3 fix:** все три точки используют `getAndUpdateHealth` (returns previous snapshot)
для transition-detection — устраняет check-then-act race против concurrent writers
(ServerState has up to 3 writers: success/error callbacks + markServerDead).

Заменить строки 37-45 на:
```kotlin
                {
                    val prev = server.getAndUpdateHealth {
                        it.copy(alive = true, lastCheckTimestamp = clock.instant())
                    }
                    if (!prev.alive) {
                        logger.info { "Server ${server.id} is now ALIVE" }
                    }
                },
```

Заменить строки 46-54 на:
```kotlin
                { error ->
                    val prev = server.getAndUpdateHealth {
                        it.copy(alive = false, lastCheckTimestamp = clock.instant())
                    }
                    if (prev.alive) {
                        logger.warn(error) { "Server ${server.id} is now DEAD" }
                    }
                },
```

В функции `markServerDead` (lines 58-64), заменить тело lambda на:
```kotlin
    fun markServerDead(id: String) {
        registry.getServer(id)?.let { server ->
            // Transition-detection: log "marked as dead" only on the actual alive→dead edge.
            // If multiple callers race to mark the same server dead, only the first one logs.
            val prev = server.getAndUpdateHealth {
                it.copy(alive = false, lastCheckTimestamp = clock.instant())
            }
            if (prev.alive) {
                logger.warn { "Server $id marked as dead" }
            }
        }
    }
```

### Step 1.4: Обновить тесты — direct `server.alive = true` writes на `updateHealth`

В `DetectServiceTest.kt`: для каждого call-сайта `server.alive = true` (lines ~71, 255, 272, 289, 305, 319, 333, 350, 367, 388, 407, 427 — точные строки могут смещаться, ищи по grep) заменить на:

```kotlin
server.updateHealth { it.copy(alive = true) }
```

Также строка 71: `registry.getServer("test")!!.alive = true` → `registry.getServer("test")!!.updateHealth { it.copy(alive = true) }`.
Также строка 305 (primaryServer): `primaryServer.alive = true` → `primaryServer.updateHealth { it.copy(alive = true) }`.
Также строка 319 (secondary): `registry.getServer("secondary")!!.alive = true` → `registry.getServer("secondary")!!.updateHealth { it.copy(alive = true) }`.
Аналогично для оставшихся 8 строк.

В `DetectServiceCancelJobTest.kt:75`: `registry.getServer("test")!!.alive = true` → `registry.getServer("test")!!.updateHealth { it.copy(alive = true) }`.

В `VideoVisualizationServiceTest.kt:76`: `registry.getServer("test")!!.alive = true` → `registry.getServer("test")!!.updateHealth { it.copy(alive = true) }`.

**Read-сайты тестов** (`assertEquals(true, server.alive)`, `if (server.alive)`, etc.) **не меняются** — convenience getter `val alive: Boolean get() = healthRef.get().alive` остаётся.

### Step 1.5: Запустить ktlintFormat и build core-модуля

- [ ] Через build-runner agent выполнить: `./gradlew :modules:core:ktlintFormat`. Ожидаемо — форматирование применяется или no-op.
- [ ] Через build-runner agent выполнить: `./gradlew :modules:core:test`. Ожидаемо — все тесты `:modules:core` проходят.

### Step 1.6: Commit

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerHealthMonitor.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt
git commit -m "refactor(loadbalancer): atomic HealthSnapshot for ServerState

Replace @Volatile var alive/lastCheckTimestamp with AtomicReference<HealthSnapshot>.
Writers (ServerHealthMonitor and test fixtures) go through updateHealth { it.copy(...) }.
Convenience getters server.alive / server.lastCheckTimestamp remain for production
read-sites (canAcceptRequest, ranker, log lines). Counters (AtomicInteger) untouched.

Closes the smallest of four non-atomic snapshot sites identified in the atomic
snapshot refactor design (see branch history for design + plan docs)."
```

---

## Task 2: Refactor `ActiveExportRegistry.Entry` to AtomicReference EntryState

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt`

### Step 2.1: Прочитать целевой файл и зафиксировать API

- [ ] Прочитать `ActiveExportRegistry.kt` полностью (он маленький, ~205 строк).
- [ ] Отметить: `Entry` — `class` (не data class!); поля `cancellable` (`@Volatile var`, line 43), `state` (`@Volatile var`, line 44). Reader-сайты в файле: `attachCancellable` (lines 113, 130), `markCancelling` (lines 161-163). Внешние reader-сайты — `entry.state == CANCELLING` в `CancelExportHandlerTest.kt:186, 407`; `registry.get(...)!!.cancellable` (искать через grep если ещё не сделано).

**Grep-инвентаризация:**

```bash
# Reader-сайты entry.state
git grep -nP '\bentry\.state\b|\.state\b\s*==\s*State\.|\.state\b\s*!=\s*State\.' -- 'modules/**/*.kt'

# Reader-сайты entry.cancellable
git grep -nP '\bentry\.cancellable\b|\.cancellable\?\.|\.cancellable\b\s*==' -- 'modules/**/*.kt'

# Writers — нужно отличить assignments от reads
git grep -nP '\.cancellable\s*=' -- 'modules/**/*.kt'
git grep -nP '\.state\s*=\s*State\.' -- 'modules/**/*.kt'
```

Ожидаемые reader-сайты для `entry.state`:
- `ActiveExportRegistry.kt` сам (внутренние reads в `attachCancellable`, `markCancelling`).
- `modules/telegram/src/main/kotlin/.../telegram/bot/handler/export/ExportExecutor.kt` — lines 115, 268.
- `modules/telegram/src/main/kotlin/.../telegram/bot/handler/quickexport/QuickExportHandler.kt` — lines 186, 300.
- `modules/telegram/src/test/.../CancelExportHandlerTest.kt:186, 407` — `entry.state == CANCELLING` для assertions.
- `modules/telegram/src/test/.../ActiveExportRegistryTest.kt` — несколько assertions на `entry.state`.

Все эти reader-сайты — single-field reads, остаются неизменными (convenience getter сохраняется).

Writer-сайты для `cancellable` / `state`:
- `attachCancellable` (production-writer для `cancellable`) — переписывается на `updateState`.
- `markCancelling` (production-writer для `state`) — переписывается на `getAndUpdateState`.
- Тесты не пишут internal state напрямую (если grep найдёт — переписать через `updateState`).

### Step 2.2: Изменить `Entry`-класс — добавить `EntryState` и `AtomicReference`

В `ActiveExportRegistry.kt`, заменить блок `Entry`-объявления (lines 33-45) на следующий блок.
**Важно:** первые 4 строки блока ниже — это обновление существующего комментария (lines 33-36
в текущем файле, который сейчас говорит «Entry holds vars whose values mutate concurrently
…»). Мы **не** добавляем новый комментарий — мы **переписываем** существующий с актуальной
мотивацией («AtomicReference вместо vars; plain class сохраняется потому что data class
equals/hashCode над AtomicReference — footgun»):

```kotlin
    // Plain `class` (not `data class`): Entry holds an AtomicReference whose state mutates
    // concurrently. data class equals/hashCode over an AtomicReference is a latent footgun —
    // Entry is identified by `exportId` alone, and this registry already keys maps on the UUID
    // rather than on Entry instances.
    class Entry(
        val exportId: UUID,
        val chatId: Long,
        val mode: ExportMode,
        val recordingId: UUID?,
        val job: Job,
    ) {
        /**
         * Mutable snapshot of the entry's transient state. All writes MUST go through
         * [updateState] / [getAndUpdateState]. Convenience getters [cancellable] / [state]
         * each do a single atomic read.
         */
        private val stateRef = AtomicReference(EntryState())

        internal data class EntryState(
            val cancellable: CancellableJob? = null,
            val state: State = State.ACTIVE,
        )

        val cancellable: CancellableJob? get() = stateRef.get().cancellable
        val state: State get() = stateRef.get().state

        internal fun updateState(transform: (EntryState) -> EntryState): EntryState =
            stateRef.updateAndGet(transform)

        internal fun getAndUpdateState(transform: (EntryState) -> EntryState): EntryState =
            stateRef.getAndUpdate(transform)
    }
```

Импорт в начале файла добавить:
```kotlin
import java.util.concurrent.atomic.AtomicReference
```

### Step 2.3: Переписать `attachCancellable` — удалить JMM-комментарий, использовать `updateState`

Заменить функцию `attachCancellable` (lines 109-147) на:

```kotlin
    /**
     * Publishes the cancel handle. If the entry is already `CANCELLING` at the time of publication
     * (because a cancel click arrived between `submitWithRetry` and this call), fire-and-forget the
     * cancel so the vision server is still told to stop.
     *
     * Atomicity: a single [updateState] writes the cancellable; the returned snapshot is read
     * back for the CANCELLING check. No JMM gymnastics — happens-before is established by
     * AtomicReference.
     */
    fun attachCancellable(
        exportId: UUID,
        cancellable: CancellableJob,
    ) {
        val entry = byExportId[exportId] ?: return
        val updated = entry.updateState { it.copy(cancellable = cancellable) }
        if (updated.state == State.CANCELLING) {
            exportScope.launch {
                try {
                    cancellable.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Vision server cancel failed on attach-retry path, exportId=$exportId"
                    }
                }
            }
        }
    }
```

Главные удаления: весь блок `// Local invariant: write cancellable BEFORE reading state ...` + `// JMM note: @Volatile writes/reads on DIFFERENT fields ...` (lines 114-128).

### Step 2.4: Переписать `markCancelling` — использовать `getAndUpdateState` для детекции transition

Заменить функцию `markCancelling` (lines 156-169) на:

```kotlin
    /**
     * Atomic transition `ACTIVE → CANCELLING` under `computeIfPresent` + `synchronized(entry)`.
     * Closes the TOCTOU race vs. `release()` — if a concurrent release removes the entry from the
     * map, `computeIfPresent` sees null and we return null cleanly. `synchronized(entry)` is
     * retained for lock-ordering invariant with `release` (see telegram-export.md §lock-ordering),
     * NOT for state mutation — the state mutation itself is atomic via [getAndUpdateState].
     *
     * @return the [Entry] that just transitioned to `CANCELLING`, or `null` if the entry does
     *   not exist (released) or was already in `CANCELLING`. The returned reference is the
     *   live registry object — callers reading `entry.state` / `entry.cancellable` on it observe
     *   the post-transition snapshot via the convenience getters.
     */
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

### Step 2.5: Запустить ktlintFormat и build telegram-модуля

- [ ] Через build-runner agent выполнить: `./gradlew :modules:telegram:ktlintFormat`.
- [ ] Через build-runner agent выполнить: `./gradlew :modules:telegram:test`. Ожидаемо — `ActiveExportRegistryTest` + `CancelExportHandlerTest` + `QuickExportHandlerTest` + `ExportExecutorTest` проходят без изменений (convenience getter'ы покрывают reads).

### Step 2.6: Commit

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt
git commit -m "refactor(telegram): atomic EntryState for ActiveExportRegistry.Entry

Replace @Volatile var cancellable/state on Entry with AtomicReference<EntryState>.
attachCancellable becomes one updateState; the lengthy JMM comment about cross-field
happens-before is removed (no longer needed — AtomicReference covers it).
markCancelling uses getAndUpdateState to detect the ACTIVE→CANCELLING transition
without holding any state-mutation lock; synchronized(entry) is kept solely for the
lock-ordering invariant with release (telegram-export.md §lock-ordering).

Convenience getters entry.cancellable / entry.state preserved for production
call-sites and existing tests.

Per the atomic snapshot refactor design (see branch history)."
```

---

## Task 3: Refactor `TelegramBotSupervisor` to AtomicReference SupervisorState

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorHealthIndicatorTest.kt`

### Step 3.1: Прочитать файлы и зафиксировать поля/writes/reads

- [ ] Прочитать `TelegramBotSupervisor.kt`. 8 полей-кандидатов на удаление (lines 67-83 кроме `supervisorJob`): `startupAt`, `lastAttemptAt`, `lastPollingStartAt`, `lastStableAt`, `lastFailure`, `lastFailureAt`, `consecutiveFailures`, `currentBackoff`.
- [ ] Зафиксировать writes: `start` (line 101 — `startupAt`), `runSupervised` (lines 146-186 — `lastAttemptAt`, `lastPollingStartAt`×3, `currentBackoff`×2), `onAttemptEnded` (lines 190-223 — 3 ветки).
- [ ] Зафиксировать reads: `computeHealth` (lines 227-299, 7 branches), `baseBuilder` (lines 301-318, 6 details).
- [ ] Прочитать `TelegramBotSupervisorTest.kt`. ~30 direct field writes (`sup.startupAt = ...`, `sup.consecutiveFailures = ...`, и т.д.); 6+ direct field reads (`supervisor.currentBackoff`, `supervisor.consecutiveFailures`, и т.д.).
- [ ] Прочитать `TelegramBotSupervisorHealthIndicatorTest.kt` если он содержит direct field writes — обработать симметрично.

**Grep-инвентаризация (запустить чтобы убедиться что не упустили reader/writer-сайт):**

```bash
# Все field-accesses на 8 кандидатов (writes + reads)
for field in startupAt lastAttemptAt lastPollingStartAt lastStableAt lastFailure lastFailureAt consecutiveFailures currentBackoff; do
  echo "=== $field ==="
  git grep -nP "\.${field}\b" -- 'modules/**/*.kt'
done

# Конкретно writes (assignments)
for field in startupAt lastAttemptAt lastPollingStartAt lastStableAt lastFailure lastFailureAt consecutiveFailures currentBackoff; do
  echo "=== $field assignments ==="
  git grep -nP "\.${field}\s*=" -- 'modules/**/*.kt'
done
```

Ожидаемые reader-сайты вне `TelegramBotSupervisor.kt` — только тесты:
- `TelegramBotSupervisorTest.kt` — assertions `assertEquals(..., supervisor.currentBackoff)` и аналогично для других полей.
- `TelegramBotSupervisorHealthIndicatorTest.kt` (если есть) — аналогично.

Ожидаемые writer-сайты вне `TelegramBotSupervisor.kt` — только тесты:
- `TelegramBotSupervisorTest.kt` — ~30 строк direct field writes.

Если grep найдёт write в production-коде вне `TelegramBotSupervisor.kt` — это баг spec'а (вторая writer-path помимо `start`/`runSupervised`/`onAttemptEnded`). СТОП — обсудить с пользователем.

### Step 3.2: Изменить `TelegramBotSupervisor.kt` — добавить `SupervisorState`, удалить 8 полей

Импорт добавить:
```kotlin
import java.util.concurrent.atomic.AtomicReference
```

Заменить блок `@Volatile internal var supervisorJob: Job? = null` + 8 последующих `@Volatile`-полей (lines 67-83) на:

```kotlin
    @Volatile internal var supervisorJob: Job? = null

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

    /**
     * Single source of truth for runtime metrics. ALL writes MUST go through
     * [updateAndGet] / [getAndUpdate]; direct `state.set(...)` is reserved for test fixtures
     * via [stateForTesting]. Reader code MUST do exactly one `state.get()` at the top of any
     * method that touches more than one field.
     */
    private val state = AtomicReference(SupervisorState())

    internal var stateForTesting: SupervisorState
        get() = state.get()
        set(value) { state.set(value) }
```

### Step 3.3: Переписать `start()` — `startupAt` через `updateAndGet`

Заменить функцию `start()` (lines 94-103) на:

```kotlin
    @PostConstruct
    fun start() {
        if (supervisorJob != null) {
            logger.warn { "TelegramBotSupervisor.start() invoked twice; ignoring duplicate." }
            return
        }
        logger.info { "Starting Telegram bot supervisor..." }
        state.updateAndGet { it.copy(startupAt = Instant.now(clock)) }
        supervisorJob = scope.launch { runSupervised() }
    }
```

### Step 3.4: Переписать `runSupervised()` — все writes через `updateAndGet`

Заменить функцию `runSupervised()` (lines 143-188) на:

```kotlin
    internal suspend fun runSupervised() {
        state.updateAndGet { it.copy(currentBackoff = INITIAL_BACKOFF) }
        while (currentCoroutineContext().isActive) {
            val attemptStart = Instant.now(clock)
            // Stamp lastAttemptAt and clear stale lastPollingStartAt atomically.
            state.updateAndGet { it.copy(lastAttemptAt = attemptStart, lastPollingStartAt = null) }
            try {
                bot.getMe()
                frigateAnalyzerBot.registerDefaultCommands()
                frigateAnalyzerBot.registerOwnerCommandsIfPossible()
                val pollStart = Instant.now(clock)
                state.updateAndGet { it.copy(lastPollingStartAt = pollStart) }
                logger.info { "Telegram bot polling started" }
                val cause = runner.run { frigateAnalyzerBot.registerRoutes(this) }
                // Clear lastPollingStartAt the instant runner.run exits — see comment in original
                // implementation (tail delay window must not leave a stale live-polling stamp).
                state.updateAndGet { it.copy(lastPollingStartAt = null) }
                if (cause != null) {
                    throw cause
                }
                val attemptDuration = Duration.between(attemptStart, Instant.now(clock))
                logger.warn {
                    "long-polling runner returned cleanly after $attemptDuration; reconnecting"
                }
                onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // CRITICAL-2: capture pre-onAttemptEnded backoff for the log message — this
                // preserves the pre-refactor log content (the message says what the backoff
                // WAS at the moment of the failure, not what the tail will bump it to).
                val preFailureBackoff = state.get().currentBackoff
                logger.error(e) {
                    "Telegram bot bootstrap/polling failed; " +
                        "next backoff=${preFailureBackoff.toMillis()}ms"
                }
                onAttemptEnded(success = false, attemptStart = attemptStart, failure = e)
            }
            // CRITICAL-2: single atomic RMW for the tail bump. getAndUpdate returns the
            // PRE-bump snapshot whose currentBackoff drives the upcoming delay; the bumped
            // value is what the NEXT iteration will see. Replaces the prior 3-op pattern
            // (state.get() for delay + state.updateAndGet for bump + logger's own state.get()).
            val effectiveBackoff = state
                .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                .currentBackoff
            delay(effectiveBackoff.toMillis())
        }
    }
```

**CRITICAL-2 rationale (per review iter-1 decision option A) — TelegramBotSupervisor.** Same
fix as in WatchRecordsTask: collapse the tail `state.get() + delay + updateAndGet` triple
into one `getAndUpdate` whose return supplies the pre-bump value for `delay`. The catch
block keeps a single `state.get()` to read the pre-`onAttemptEnded` backoff for the log
message — that preserves the pre-refactor log content (note: `onAttemptEnded` may reset
`currentBackoff` to INITIAL on the "failure && duration >= STABLE_THRESHOLD" branch, so the
log message and the actual delay may legitimately differ; that's a pre-existing observable
property out of scope for this refactor). Total: 2 atomic state operations per failure-tail
(down from 3); 1 atomic op per success-tail.

### Step 3.5: Переписать `onAttemptEnded(...)` — 3 ветки через `updateAndGet`

Заменить функцию `onAttemptEnded` (lines 190-223) на:

```kotlin
    private fun onAttemptEnded(
        success: Boolean,
        attemptStart: Instant,
        failure: Throwable?,
    ) {
        val now = Instant.now(clock)
        val duration = Duration.between(attemptStart, now)
        if (success && duration >= STABLE_THRESHOLD) {
            state.updateAndGet {
                it.copy(
                    consecutiveFailures = 0,
                    currentBackoff = INITIAL_BACKOFF,
                    lastStableAt = now,
                )
            }
        } else if (success) {
            // Clean return faster than STABLE_THRESHOLD — library likely swallowed an error.
            state.updateAndGet {
                it.copy(
                    lastFailure = SilentPollingFailure("clean return after $duration"),
                    lastFailureAt = now,
                    consecutiveFailures = it.consecutiveFailures + 1,
                )
            }
        } else {
            if (duration >= STABLE_THRESHOLD) {
                // Crash after a stable run — reset counters; lastStableAt = now (moment of crash)
                // per original semantics.
                state.updateAndGet {
                    it.copy(
                        lastFailure = failure,
                        lastFailureAt = now,
                        consecutiveFailures = 1,
                        currentBackoff = INITIAL_BACKOFF,
                        lastStableAt = now,
                    )
                }
            } else {
                state.updateAndGet {
                    it.copy(
                        lastFailure = failure,
                        lastFailureAt = now,
                        consecutiveFailures = it.consecutiveFailures + 1,
                    )
                }
            }
        }
    }
```

### Step 3.6: Переписать `computeHealth(now)` — single snapshot read

Заменить функцию `computeHealth` (lines 227-299) на:

```kotlin
    fun computeHealth(now: Instant): Health {
        val s = state.get()
        val builder = baseBuilder(s)

        // BRANCH 1: supervisor not active → DOWN
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }

        // BRANCH 2: live stable polling → UP.
        val pollStart = s.lastPollingStartAt
        val failedAt = s.lastFailureAt
        if (pollStart != null &&
            (failedAt == null || pollStart.isAfter(failedAt)) &&
            Duration.between(pollStart, now) >= STABLE_THRESHOLD
        ) {
            return builder.up().withDetail("reason", "healthy").build()
        }

        val stable = s.lastStableAt
        val started = s.startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 3: never stable, threshold OR grace expired → DOWN
        if (stable == null &&
            (s.consecutiveFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: ${s.consecutiveFailures} attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 4: never stable, still in grace → OUT_OF_SERVICE
        if (stable == null) {
            return builder
                .outOfService()
                .withDetail("reason", "connecting... attempts=${s.consecutiveFailures}")
                .build()
        }

        // BRANCH 5: in backoff with stale stable point → DOWN
        if (s.consecutiveFailures > 0 && Duration.between(stable, now) > HEALTH_STALENESS) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: failing for ${Duration.between(stable, now)} since lastStable=$stable",
                ).build()
        }

        // BRANCH 6: transient backoff (recent stable) → OUT_OF_SERVICE
        if (s.consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff (failures=${s.consecutiveFailures}, backoff=${s.currentBackoff.toMillis()}ms)",
                ).build()
        }

        // BRANCH 7: just (re)connected, polling < STABLE_THRESHOLD → OUT_OF_SERVICE
        return builder
            .outOfService()
            .withDetail("reason", "connecting... (just (re)started, <STABLE_THRESHOLD)")
            .build()
    }
```

### Step 3.7: Переписать `baseBuilder()` — принимать snapshot

Заменить функцию `baseBuilder()` (lines 301-318) на:

```kotlin
    private fun baseBuilder(s: SupervisorState): Health.Builder =
        Health
            .Builder()
            .withDetail("startupAt", s.startupAt?.toString() ?: "never")
            .withDetail("lastAttemptAt", s.lastAttemptAt?.toString() ?: "never")
            .withDetail("lastPollingStartAt", s.lastPollingStartAt?.toString() ?: "never")
            .withDetail("lastStableAt", s.lastStableAt?.toString() ?: "never")
            .withDetail("lastFailureAt", s.lastFailureAt?.toString() ?: "never")
            .withDetail("consecutiveFailures", s.consecutiveFailures)
            .withDetail("currentBackoffMs", s.currentBackoff.toMillis())
            .also { b ->
                s.lastFailure?.let {
                    b.withDetail(
                        "lastFailure",
                        "${it.javaClass.simpleName}: ${sanitizeFailureMessage(it.message)}",
                    )
                }
            }
```

### Step 3.8: Обновить `TelegramBotSupervisorTest.kt` — writes на `stateForTesting`, reads на snapshot

В тестовом файле — рефакторинг применяется ко всем тестам, которые сейчас делают `sup.consecutiveFailures = ...`, `sup.startupAt = ...`, и т.д. Шаблон:

**Was (несколько строк присваиваний):**
```kotlin
sup.startupAt = now.minus(Duration.ofHours(1))
sup.lastStableAt = now.minusSeconds(120)
sup.lastPollingStartAt = now.minusSeconds(90)
sup.consecutiveFailures = 0L
```

**Becomes (один присваивание):**
```kotlin
sup.stateForTesting = SupervisorState(
    startupAt = now.minus(Duration.ofHours(1)),
    lastStableAt = now.minusSeconds(120),
    lastPollingStartAt = now.minusSeconds(90),
    consecutiveFailures = 0L,
)
```

`sup.supervisorJob = ...` оставлять как есть (Job вне State).

**Reads in tests:**
```kotlin
// Was:
assertEquals(40_000L, supervisor.currentBackoff.toMillis())
assertEquals(3L, supervisor.consecutiveFailures)
assertNotNull(supervisor.lastFailure)
```

**Becomes:**
```kotlin
val s = supervisor.stateForTesting
assertEquals(40_000L, s.currentBackoff.toMillis())
assertEquals(3L, s.consecutiveFailures)
assertNotNull(s.lastFailure)
```

Для тестов, где предпочтительна более точная attribution (одна assertion на одно поле) — допустимо писать `supervisor.stateForTesting.currentBackoff` напрямую (single `state.get()` под капотом).

Применить ко всем call-сайтам (~30 writes, ~6+ reads). Точные numbers/строки могут смещаться при ktlintFormat — действовать по факту.

Импорт в test-файл:
```kotlin
import ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisor.SupervisorState
```

(или class-qualified `TelegramBotSupervisor.SupervisorState(...)` без отдельного импорта — на вкус).

**Дополнительно — deterministic transition tests (per SUGGESTION-2).** В дополнение к
обновлению существующих тестов, добавить новые testcases в `TelegramBotSupervisorTest`,
которые проверяют, что transition-методы атомарно ставят **полный** SupervisorState
(snapshot-equality assertion):

```kotlin
@Test
fun `onAttemptEnded success stable resets counters and bumps lastStableAt`() {
    val now = Instant.parse("2026-05-27T12:00:00Z")
    sup.stateForTesting = SupervisorState(
        consecutiveFailures = 3,
        currentBackoff = Duration.ofSeconds(40),
        startupAt = now.minus(Duration.ofMinutes(5)),
    )
    val attemptStart = now.minus(STABLE_THRESHOLD).minusSeconds(5)
    fakeClock.setInstant(now)

    sup.onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)

    assertEquals(
        SupervisorState(
            consecutiveFailures = 0,
            currentBackoff = INITIAL_BACKOFF,
            lastStableAt = now,
            startupAt = now.minus(Duration.ofMinutes(5)),
        ),
        sup.stateForTesting,
    )
}
```

Cases для покрытия (3 ветки `onAttemptEnded`):
1. `success = true`, `duration >= STABLE_THRESHOLD` — counters reset, `lastStableAt = now`, `currentBackoff = INITIAL`.
2. `success = true`, `duration < STABLE_THRESHOLD` — `SilentPollingFailure` записан, `consecutiveFailures++`.
3. `success = false`, `duration >= STABLE_THRESHOLD` — `lastFailure = failure`, counters reset to 1, `currentBackoff = INITIAL`, `lastStableAt = now`.
4. `success = false`, `duration < STABLE_THRESHOLD` — `lastFailure = failure`, `consecutiveFailures++`, `currentBackoff` НЕ trogается (изменится в `runSupervised` tail).

Ровно тот же подход применяется для `start()` (single field — `startupAt`) и для `runSupervised` tail bump (`currentBackoff = nextBackoff(...)`), если хочется покрыть. Эти тесты гарантируют, что
transition не оставляет случайных «лишних» полей в SupervisorState — каждый branch явно
конструирует ожидаемый snapshot.

### Step 3.9: Обновить `TelegramBotSupervisorHealthIndicatorTest.kt` если применимо

- [ ] Прочитать файл.
- [ ] Если есть direct field writes на supervisor — применить тот же паттерн (`stateForTesting = SupervisorState(...)`).
- [ ] Если нет — никаких изменений.

### Step 3.10: Запустить ktlintFormat и build telegram-модуля

- [ ] Через build-runner agent выполнить: `./gradlew :modules:telegram:ktlintFormat`.
- [ ] Через build-runner agent выполнить: `./gradlew :modules:telegram:test`. Ожидаемо — все тесты проходят, включая `TelegramBotSupervisorTest`, `TelegramBotSupervisorHealthIndicatorTest`, `KtgBotApiLongPollingRunnerTest`.

### Step 3.11: Commit

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt
git add modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git add modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorHealthIndicatorTest.kt
git commit -m "refactor(telegram): atomic SupervisorState for TelegramBotSupervisor

Replace 8 @Volatile var fields (startupAt, lastAttemptAt, lastPollingStartAt,
lastStableAt, lastFailure, lastFailureAt, consecutiveFailures, currentBackoff)
with AtomicReference<SupervisorState>. All writers (start, runSupervised,
onAttemptEnded) go through updateAndGet { it.copy(...) } — multi-field
transitions are atomic. computeHealth reads a single snapshot at the top,
guaranteeing reader sees a self-consistent set of metrics.

supervisorJob remains a standalone @Volatile var (lifecycle handle, not a value).

Tests use stateForTesting setter/getter for fixture setup and snapshot reads
for assertions. ~30 direct field writes collapse to ~10 SupervisorState
constructions.

Per the atomic snapshot refactor design (see branch history)."
```

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
