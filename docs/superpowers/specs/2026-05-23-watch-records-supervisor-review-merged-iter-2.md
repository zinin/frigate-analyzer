# Merged Design Review — Iteration 2

**Date:** 2026-05-24
**Documents under review:**
- Design: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md`

**Iter-1 decisions auto-loaded as PREVIOUS_DECISIONS** (D1-D9, 26 auto-fixes).

**Reviewers used:**
- codex-executor (gpt-5.5, xhigh reasoning) — completed
- ccs-executor (glm-5.1) — completed (extracted from thinking blocks; CCS process died before flushing final text block but full analysis present)
- ollama-executor (ollama-kimi) — completed
- ollama-executor (ollama-deepseek) — completed
- ollama-executor (ollama-minimax) — **FAILED** (timeout, same as iter-1; silent stall at 11:24:22 after reading 2 docs; raw.jsonl frozen at 22556 bytes; known issue with minimax-m2.7:cloud on large doc-review prompts)

---

## codex-executor (gpt-5.5, xhigh)

### Critical Issues

- **План откатился к старой health-модели** — Design описывает 9 полей и 8 priority-веток (`design:101-111`, `392-405`), но Task 6 реализует старые `lastSuccessfulIterationAt/consecutiveFailures` (`plan:1176-1180`, `1277-1315`), а Task 8 тестирует только 5 старых состояний (`plan:1635-1726`).
  **Почему важно:** D2 фактически не будет реализован: startup registration failure, event failure vs empty poll и stale-event ветки снова будут вести себя неправильно.
  **Фикс:** переписать Tasks 4-8 вокруг `lastSuccessfulPollAt`, `lastEventProcessedAt`, `lastSuccessfulRegistrationAt`, `consecutiveEventFailures`, `consecutiveRegistrationFailures`, `startupAt`; добавить тесты на все 8 веток таблицы.

- **Событие с transient DB/IO failure теряется, а не retry'ится** — `WatchService` events потребляются через `key.pollEvents()`, затем `key.reset()` в `finally` (`design:280`, `plan:710-748`). В плане нет backlog/rescan/retry конкретного failed path.
  **Почему важно:** при временном `R2dbcException` на `createRecording` файл уже не придёт повторно из `WatchService`; после восстановления БД supervisor будет жив, но запись так и не появится до ручного restart/first scan. Это превращает outage в data gap.
  **Фикс:** хранить retry queue failed file paths или запускать bounded rescan affected directory/watch-period после recovery; добавить тест "valid mp4 → DB fails once → DB succeeds without new FS event → recording created".

- **`eventFailures` не встроены в backoff loop** — Design §5.2.1 говорит, что `WatchRecordsLoop` ловит per-event exceptions и возвращает `eventFailures` (`design:417-443`), но supervisor-псевдокод и Task 6 после `runIteration` вызывают обычный success path (`design:163-168`, `plan:1212-1215`).
  **Почему важно:** если реализовать expanded `IterationResult`, event-processing failures могут не давать delay/backoff и не обновлять `lastFailure`; если реализовать план буквально, `eventFailures` вообще отсутствуют.
  **Фикс:** `runSupervised()` должен обрабатывать result: `onPollCompleted(eventsProcessed, eventFailures, lastEventFailure?)`, при `eventFailures > 0` делать backoff progression и сохранять failure detail.

- **Порядок `ApplicationReadyEvent` не гарантирует FirstTimeScan-before-watcher** — Design утверждает, что отдельный `@EventListener(ApplicationReadyEvent)` сохраняет порядок относительно `FirstTimeScanTask` (`design:115-141`), но в плане это два независимых listener'а без `@Order` (`plan:1383-1411`). Более того, `FirstTimeScanTask.run()` сам `@Async` и сразу запускает coroutine (`FirstTimeScanTask.kt:32-38`), то есть даже ordered listener не ждёт окончания scan.
  **Почему важно:** watcher и first scan могут работать параллельно; rationale "предотвратить duplicate processing" документально неверен.
  **Фикс:** либо явно принять concurrency и обновить rationale, либо сделать один orchestrator/explicit `@Order` + awaitable first scan, если порядок действительно обязателен.

- **Task 6/3 snippets не компилируются как написаны** — В `WatchRecordsTask` snippet используется `@EventListener`, `ApplicationReadyEvent`, `scope.cancel()` и `startupAt`, но нет соответствующих imports/field (`plan:1125-1144`, `1184-1192`, `1198-1200`). В `StartupTelegramNotifier` используется `@ConditionalOnBean`, но import отсутствует (`plan:384-405`).
  **Почему важно:** план обещает compile checkpoints, но executor, копирующий snippets, упрётся в compile blockers до смысловых тестов.
  **Фикс:** обновить snippets полностью и запускать compile после Task 3 и Task 6 до commit.

### Concerns

- **Lifecycle test может зависнуть** — `shutdown()` делает `runBlocking { supervisorJob?.join() }` (`plan:1198-1200`), а тест запускает job на `StandardTestDispatcher` и затем синхронно вызывает `shutdown()` (`plan:1735-1760`).
  **Почему важно:** cancellation completion требует продвижения test scheduler, но поток `runTest` будет заблокирован `runBlocking`.
  **Фикс:** использовать `UnconfinedTestDispatcher`, запускать shutdown в background job с `advanceUntilIdle()`, либо вынести suspend `stopAndJoin()` для тестов.

- **Manual sanity не проверяет главный failure mode** — Step 11.2 предлагает `garbage.txt` для parse exception (`plan:2021-2035`). Это проверяет non-retryable bad filename, но не transient PostgreSQL/IO failure, который был причиной incidents.
  **Почему важно:** smoke может пройти, а потеря валидных `.mp4` при временном DB outage останется незамеченной.
  **Фикс:** добавить manual или unit сценарий с валидным mp4 и временным отказом `createRecording`.

- **Документы всё ещё противоречат D9/D6 терминологии** — В design tree health class всё ещё назван `ReactiveHealthIndicator` (`design:45`), а lifecycle местами описан как `@PostConstruct` (`design:77`, `plan:1359`, `1953`) при фактическом решении `ApplicationReadyEvent`.
  **Почему важно:** executor может копировать не тот lifecycle/indicator тип, особенно при дальнейшем сопровождении.
  **Фикс:** привести все summary/table/pipeline-doc snippets к sync `HealthIndicator` и `@EventListener(ApplicationReadyEvent)`.

- **Owner notification coupling лучше обозначить явно** — Прямого inject `NotificationQueue` в `StartupTelegramNotifier` в iteration 2 уже нет; он идёт через `TelegramNotificationService` (`design:341-343`, `plan:405-407`). Но интерфейс `TelegramNotificationService` расширяется системным admin-сообщением (`plan:23-24`, `217-241`).
  **Почему важно:** доменный notification API начинает смешивать user/event notifications и operator/admin channel.
  **Фикс:** либо принять это как маленький проектный trade-off, либо выделить `OwnerNotificationService`/`AdminNotificationPort` в telegram-модуле.

### Suggestions

- Добавить обязательный state-machine тест: registration fails до threshold → `OUT_OF_SERVICE`, затем threshold/grace → `DOWN`; successful registration resets only registration failures.
- Разделить retryable и non-retryable event failures: `IllegalArgumentException` parse можно считать permanent bad file, а `R2dbcException`/`IOException` должны попадать в retry/rescan path.
- Заменить stale Step 8.8 note "pending architectural decision" (`plan:1772-1774`) на финальные D2-тесты; сейчас он выглядит как unresolved из iteration 1.

### Questions

- Должен ли supervisor гарантировать eventual ingestion failed file paths, или цель только "loop жив + health сигнал"? Сейчас документы обещают retry/backoff, но не retry конкретной записи.
- Нужно ли реально дождаться завершения `FirstTimeScanTask` перед watcher, или достаточно idempotent `createRecording` и параллельный запуск допустим?
- Что должен показывать health после одного non-retryable bad file и дальнейшего idle: оставаться `OUT_OF_SERVICE`, переходить в `UP` после rescan, или требовать operator acknowledgement?

---

## ccs-executor (glm-5.1)

**Note:** CCS process died before flushing the final assistant text block, but the full review was written into the final thinking block. Content extracted verbatim from `raw.jsonl`.

### Critical Issues

1. **Design/Plan divergence: expanded state (iter-1 §D2) not implemented in plan** — The plan claims to implement the 9-field expanded state and 8-branch health table from iter-1 §D2, but Step 6.1 only implements 5 simplified fields and a 5-branch computeHealth(). Missing: `lastSuccessfulPollAt`, `lastEventProcessedAt`, `consecutiveEventFailures`, `lastSuccessfulRegistrationAt`, `consecutiveRegistrationFailures`, `startupAt`, `STARTUP_GRACE`, `STARTUP_FAILURE_THRESHOLD`, health branches #2, #3, #4, #5, #6, #7.

2. **Empty polls mask persistent event failures** — Plan's `onIterationSuccess()` resets `consecutiveFailures = 0` on any non-throwing iteration, including empty polls. This means a pattern of [failure, empty poll, failure, empty poll, ...] keeps consecutiveFailures oscillating between 0 and 1, preventing backoff progression and DOWN transition. The design's expanded state with separate `consecutiveEventFailures` was specifically designed to prevent this (iter-1 §D2).

3. **Startup failure never transitions to DOWN** — Plan's computeHealth() branch `last == null && consecutiveFailures > 0` always returns OUT_OF_SERVICE. If registration persistently fails at startup (e.g., missing mount point), health stays OUT_OF_SERVICE forever, never transitioning to DOWN. The design's branches #2/#3 with STARTUP_FAILURE_THRESHOLD and STARTUP_GRACE address this, but the plan omits them.

4. **Missing imports in plan Step 6.1** — The WatchRecordsTask code uses `@EventListener(ApplicationReadyEvent::class)` but the imports don't include `org.springframework.boot.context.event.ApplicationReadyEvent` or `org.springframework.context.event.EventListener`. Also imports unused `jakarta.annotation.PostConstruct`. Would fail to compile.

5. **Integration test will break** — `FrigateAnalyzerApplicationTests.actuatorHealth()` expects overall status UP. After adding `WatchRecordsTaskHealthIndicator` (a `@Component` that always registers), it will report DOWN in test profile (`supervisorJob=null` → "supervisor not active"), propagating to overall DOWN status. The plan doesn't address this.

### Concerns

1. **Plan Step 8.7 shutdown test may deadlock** — `shutdown()` calls `runBlocking { supervisorJob?.join() }`. The supervisorJob is launched on StandardTestDispatcher. Inside `runTest`, `runBlocking` creates a separate blocking context that doesn't advance the test dispatcher. The job cancellation won't be processed until the test dispatcher runs, but `runBlocking` blocks the test thread. Potential deadlock.

2. **Plan Task 6 commit breaks compilation** — Between Task 6.4 commit (removes `WatchRecordsTask.run()`) and Task 7.3 commit (removes `watchRecordsTask.run()` call from ApplicationListener), the branch doesn't compile. The plan text says "порядок Task 6 → Task 7 гарантирует, что ветка компилируется после каждого commit'а" but this is contradicted by the actual sequence. This was supposedly fixed in iter-1 (CCS #8) but the fix only renumbered tasks — the commit sequence is still broken.

3. **Step 8.8 mandatory tests are unspecified** — Three mandatory tests (key.reset on exception, ensureWatchService partial failure, sustained registration failure → DOWN) are described in prose but no concrete test code is provided. These are supposed to be the "additional mandatory tests covering CRITICAL-4/5/8 fixes" from iter-1.

4. **`WatchRecordsLoop.runIteration` doesn't catch per-event exceptions** — Design §5.2.1 specifies that the loop should catch per-event exceptions internally and count them in `IterationResult(eventFailures)`. Plan's implementation lets exceptions propagate to supervisor, meaning a single bad file in a batch kills the entire iteration and skips all remaining events in that poll.

5. **IterationResult schema mismatch** — Design §5.2.1 says `IterationResult(eventsProcessed, eventFailures, lastCleanupAt)`. Plan creates `IterationResult(processedEvents, lastCleanupAt)` with only 2 fields. The third field `eventFailures` is missing, and the first field is named differently.

6. **`onIterationSuccess()` called on empty poll masks health issues** — The plan's simplified `onIterationSuccess()` doesn't distinguish between "processed events successfully" and "nothing happened (empty poll)". This conflates two very different operational states.

7. **No `startupAt` field in plan** — Design §4.1 lists `startupAt: Instant` for STARTUP_GRACE calculations. Plan §6.1 code sets `startupAt = Instant.now(clock)` in `start()` but doesn't declare the field. Also `STARTUP_GRACE` constant is missing. Compile error.

8. **Commit message mismatch** — Plan Step 6.4 commit message says "Replaces @Async + while(!stopped.get()) with @PostConstruct/@PreDestroy" but the actual code uses `@EventListener(ApplicationReadyEvent::class)`, not `@PostConstruct`. The commit message is misleading.

### Suggestions

1. **Align plan with design's expanded state** — Either implement the full 9-field expanded state from design §4.1/§5.2, or explicitly document the simplification and update the design to match. Currently the design describes architecture that the plan doesn't build.

2. **Add `@Profile("!test")` to `WatchRecordsTaskHealthIndicator`** — Or use `@ConditionalOnProperty` to prevent the health indicator from registering in test profile. This prevents the integration test from breaking.

3. **Merge Task 6 and Task 7 into a single atomic commit** — This ensures the branch always compiles between commits. Alternative: make `WatchRecordsTask` keep the `run()` method as a deprecated wrapper that calls `start()`, then remove it in Task 7.

4. **Provide concrete test code for Step 8.8** — The three "mandatory" tests should have concrete code, not prose descriptions. The implementation agent won't be able to infer the exact testing approach from prose alone.

### Questions

1. Was the decision to NOT implement the expanded state (iter-1 §D2) intentional? If so, the design document needs to be updated to reflect the simplified model. If not, the plan needs a major rewrite of Step 6.1 and Task 8.

2. The plan claims Task 8 should "fill in" the expanded state, but the concrete code in Step 8.2-8.6 only tests the simplified 5-field model. Is there a missing Step 8.X that was supposed to add the expanded state fields and transition methods?

3. How should `WatchRecordsTaskHealthIndicator` behave in test profile? The current plan would break `FrigateAnalyzerApplicationTests.actuatorHealth()`.

---

## ollama-executor (ollama-kimi / kimi-k2.6:cloud)

### Critical Issues

**CR-1. D2-расширенное состояние задокументировано, но не реализовано в коде**
- 9-полевое состояние и 8-ветвевая health-таблица из решения D2 присутствуют в design §5.2, но фактический `computeHealth()` остался 5-ветвевым на 2 полях. Поля `startupAt`, `STARTUP_GRACE`, `STARTUP_FAILURE_THRESHOLD` и методы `onPollCompleted/onRegistrationSuccess/Failure` отсутствуют.
- Исправление: либо переписать код под 8 ветвей, либо упростить design §5.2 до реально реализуемых 5 ветвей.

**CR-2. `IterationResult` остаётся 2-полевым вместо 3-полевого**
- Design §5.2.1 требует `IterationResult(eventsProcessed, eventFailures, lastCleanupAt)`, plan Task 4 создаёт только `(processedEvents, lastCleanupAt)`. Без `eventFailures` ветки #4/#5 health-таблицы не реализуемы.

### Concerns

- **C-1.** Терминологическая неконсистентность `@PostConstruct` vs `@EventListener(ApplicationReadyEvent)` в нескольких секциях (commit messages Step 6.4 / 7.3, File Structure таблица) — остатки до-D6 решения.
- **C-2.** Комментарий «supervisor пока пустой» в Task 6 противоречит Step 6.1, где уже полный `runSupervised()` с backoff и exception handling.
- **C-3.** Тесты `computeHealth` покрывают 5/8 веток. `taskWithActiveJob()` не способен установить `lastSuccessfulRegistrationAt`, `consecutiveEventFailures`, `lastEventProcessedAt` — отсутствуют тесты на ветки #2/#3/#4/#5/#7.
- **C-4.** `StartupTelegramNotifier` не валидирует null-поля `BuildProperties`/`GitProperties` — сообщение может содержать `Version: null`.

### Suggestions

- **S-1.** Явно отметить, что `WatchRecordsLoop.registerAllDirs` мутирует входной `registeredDirs` (не stateless строго).
- **S-2.** Если остаёмся на 5 ветвях — задокументировать в коде, что `lastSuccessfulIterationAt` обновляется при empty poll тоже.
- **S-3.** Pending-тест Step 8.8 #3 либо удалить, либо переписать после разрешения CR-1.

### Questions

- **Q-1.** Финальная health-модель — 5 ветвей или 8? (главная несогласованность документа)
- **Q-2.** `eventFailures` в `IterationResult` — забытое требование или сознательный defer?
- **Q-3.** Нужна ли валидация null-полей `BuildProperties`/`GitProperties` в `StartupTelegramNotifier`?

---

## ollama-executor (ollama-deepseek / DeepSeek-V4-Pro)

### Critical Issues

**C1. Design/Plan drift: 10-field D2 state vs 5-field Plan**
- Design §4.1 (lines 100-110) defines 10 `@Volatile` fields per D2 (incl. `lastSuccessfulPollAt`, `lastEventProcessedAt`, `consecutiveEventFailures`, `lastSuccessfulRegistrationAt`, `consecutiveRegistrationFailures`, `startupAt`).
- Plan Task 6 Step 6.1 (lines 1176-1180) defines only 5 fields (`lastSuccessfulIterationAt`, `consecutiveFailures`, `successesSinceLastFailure`, `currentBackoff`, `lastFailure`).
- D2-specific fields missing entirely. If implementer follows Plan, D2 will not be realized.
- **Fix:** Align Plan Task 6 with Design §4.1 — add all 10 fields.

**C2. computeHealth: 8-branch table in Design → 4 branches in Plan**
- Design §5.2 (lines 396-405) defines 8 health branches checking startup grace, registration state, event-failures vs poll heartbeat, idle camera.
- Plan Task 6 Step 6.1 `computeHealth` (lines 1277-1316) has ~4 branches using only `supervisorJob.isActive`, `lastSuccessfulIterationAt`, `consecutiveFailures`, `HEALTH_STALENESS`. **Zero D2 branches implemented.**
- The very bug D2 fixes ("persistent DB failure masked by empty poll") will remain.
- **Fix:** Rewrite `computeHealth` in Plan Step 6.1 to match the 8-branch table from Design §5.2.

**C3. IterationResult missing `eventFailures` field**
- Design §5.2.1 (line 443): `data class IterationResult(val eventsProcessed: Int, val eventFailures: Int, val lastCleanupAt: Instant)`.
- Plan Step 4.3 (lines 564-567): `data class IterationResult(val processedEvents: Int, val lastCleanupAt: Instant)` — `eventFailures` absent.
- Without this, `WatchRecordsLoop` cannot return per-event exception count → health branches 4/5 unworkable; supervisor cannot distinguish "single event failed" from "entire poll died".
- **Fix:** Add `eventFailures: Int` to Plan's `IterationResult` and propagate through call sites.

**C4. ensureWatchService does NOT call registration transition methods**
- Design §5.2.1 defines `onRegistrationSuccess()` and `onRegistrationFailure(t)`.
- Plan Step 6.1 `ensureWatchService` (lines 1234-1248) calls neither. Even if D2 fields existed (C1), they'd never be updated — health branches 2/3 stuck forever in "registering...".
- **Fix:** Invoke `onRegistrationSuccess()` after successful registration and `onRegistrationFailure(e)` in catch block.

**C5. @EventListener ordering: FirstTimeScanTask not guaranteed before coroutine**
- Plan Task 6 Step 6.1 (lines 1182-1192): `WatchRecordsTask.start()` uses `@EventListener(ApplicationReadyEvent::class)`.
- Plan Task 7 Step 7.1 (line 1391): `ApplicationListener.initializeApplication()` also `@EventListener(ApplicationReadyEvent::class)` and synchronously calls `firstTimeScanTask.run()`.
- Spring does NOT guarantee ordering between `@EventListener` methods on different beans without `@Order`. WatchRecordsTask may run BEFORE FirstTimeScanTask → same file processed by both scan and ENTRY_CREATE → duplicate DB rows.
- **Fix:** Either (A) make `ApplicationListener.initializeApplication()` explicitly call `watchRecordsTask.start()` after `firstTimeScanTask.run()` (preferred — explicit, removes Spring dispatch dependency), or (B) add `@Order(1)`/`@Order(2)`.

**C6. `startupAt` used in `start()` but not declared (compile blocker)**
- Plan Step 6.1 (line 1191): `startupAt = Instant.now(clock)` used, but field not declared in body (lines 1176-1180). Compilation fails.
- **Fix:** Add `@Volatile internal var startupAt: Instant? = null` to fields section.

**C7. Missing imports: @EventListener, ApplicationReadyEvent**
- Plan Step 6.1 (lines 1123-1152) imports `jakarta.annotation.PostConstruct`/`PreDestroy` but NOT `org.springframework.context.event.EventListener` or `org.springframework.boot.context.event.ApplicationReadyEvent`. `@PostConstruct` imported but unused.
- **Fix:** Drop `import jakarta.annotation.PostConstruct`; add Spring `@EventListener` + `ApplicationReadyEvent` imports.

**C8. Constants `STARTUP_GRACE` and `STARTUP_FAILURE_THRESHOLD` absent in Plan**
- Design §4.1 (lines 150-151) defines `STARTUP_GRACE = 2 minutes` and `STARTUP_FAILURE_THRESHOLD = 5L`, used in health branches 2/7.
- Plan Step 6.1 (lines 1156-1159) has only `INITIAL_BACKOFF`, `MAX_BACKOFF`, `SUCCESSES_TO_RESET_BACKOFF`, `HEALTH_STALENESS`.
- **Fix:** Add both constants to Plan Step 6.1.

### Concerns

**CN1. Transition methods: 3 in Design → 2 in Plan** — Design has `onPollCompleted`/`onRegistrationSuccess`/`onRegistrationFailure`; Plan has only `onIterationSuccess`/`onIterationFailure`. Without `onPollCompleted` cannot separate empty poll (heartbeat) from real work — core of D2.

**CN2. Design-internal contradiction** — Design §4.1 field list lacks `lastSuccessfulIterationAt`, but Design §5.2.1 `onIterationSuccess()` sets it. Plan inherits this stale pre-D2 name.

**CN3. Backoff incorrectly reset on empty poll** — Plan `onIterationSuccess()` resets `consecutiveFailures` on ANY success including empty poll. Design `onPollCompleted` resets only when `eventsProcessed > 0`. Result: **the exact bug D2 was meant to fix returns** — persistent DB failure masked by empty poll heartbeat.

**CN4. `lastSuccessfulIterationAt` updates every 500ms even on empty poll** — branch `Duration.between(last, now) > HEALTH_STALENESS` (Plan line 1304) NEVER fires while supervisor is alive — even if DB is down and every event fails. Direct reproduction of original outage scenario.

**CN5. No tests for D2's 8-branch table** — Plan Task 8.6 (lines 1637-1761) has 5 health tests matching pre-D2 model. Missing tests: startup-failure (grace expired → DOWN), startup-grace (in grace → OUT_OF_SERVICE), event-failures stale (live poll → DOWN), event-failures transient (→ OUT_OF_SERVICE), registration backoff, idle camera (UP).

**CN6. `@ConditionalOnBean` silent skip** — If `GitProperties` or `BuildProperties` bean missing, `StartupTelegramNotifier` not registered at all (no fallback). User learns only from Spring logs. Trade-off acknowledged in D7 but worth flagging.

**CN7. `scope.cancel()` ordering invariant undocumented** — Currently safe (`supervisorJob` joined first), but if other child coroutines added to the same scope later, they'd be cancelled. Document the invariant.

### Suggestions

**S1. Replace `@EventListener` on WatchRecordsTask with explicit `start()` call** from `ApplicationListener.initializeApplication()` after `firstTimeScanTask.run()`. Removes C5, makes control flow explicit, no `@Order` needed.

**S2. Promote `SupervisorState` to data class + AtomicReference** — With 10 fields and known D4 snapshot-consistency limitation, an explicit `data class SupervisorState(...)` + `AtomicReference<SupervisorState>` solves atomicity in one op. Design §4.3 (line 286) already lists this as postponed — formalize as "deferred until Prometheus arrives".

**S3. Add smoke test for "event-failure while poll alive"** in Task 11.2 — Stop PostgreSQL, verify `/actuator/health` shows DOWN (not UP); restart DB, verify recovery. Real-world D2 validation.

**S4. Plan Task 6 heading still says `@PostConstruct`** (line 1110) but code uses `@EventListener`. Rename heading to match.

### Questions

**Q1. Authoritative field count?** Iter-1 review says "9", Design lists 10, Plan implements 5. Which is correct?

**Q2. Design §5.2 branch 6 — is it ever reachable?** `consecutiveFailures > 0` with registration OK and event OK — only `ClosedWatchServiceException` qualifies, but `ensureWatchService` recreates it next iteration. Transient single-cycle state — branch may be dead code.

**Q3. Branch 7 has redundant predicate?** `lastSuccessfulPollAt != null` in branch 7 is always true after startup grace. Can be simplified.

---

## ollama-executor (ollama-minimax)

**FAILED — timeout.** Same pattern as iter-1: silent stall after reading both documents (raw.jsonl frozen at 22556 bytes from 11:24:22, no further events for 13+ minutes). Known issue with minimax-m2.7:cloud on large doc-review prompts. Output not produced. Continuing with the other 4 reviewers.
