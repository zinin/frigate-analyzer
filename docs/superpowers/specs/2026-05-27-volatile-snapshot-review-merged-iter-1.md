# Merged Design Review — Iteration 1

**Date:** 2026-05-28
**Topic:** volatile-snapshot
**Documents reviewed:**
- `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`
- `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`

**Review agents:** codex-executor (gpt-5.5, reasoning=xhigh), ccs-executor (glm), ollama-executor (kimi/minimax/deepseek)

---

## codex-executor (gpt-5.5)

Source: `/home/zinin/.claude/codex-interaction/2026-05-27-23-59-15-244023-design-review-volatile-snapshot-iter-1-codex/output.txt`

### Critical Issues

- `lastFailure` / `lastFailureAt` semantics are inconsistent between plan and current behavior. The verification step expects `consecutiveFailures == 0` to imply `lastFailureAt == null` after success ([plan](</opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md:1163>)), but proposed transitions do not clear failure fields. Current code also keeps them sticky in both `TelegramBotSupervisor` and `WatchRecordsTask`, and tests explicitly assert that behavior ([WatchRecordsTaskTest](</opt/github/zinin/frigate-analyzer/modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt:97>)). Decide first: either `lastFailure` means “last ever failure” and docs/checklist must stop treating it as inconsistent, or it means “current failure” and success/reset transitions must clear it.

- `maybeResetBackoffTransform` is not pure as written. The plan says `updateAndGet` transforms must be pure ([spec](</opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md:379>)), but the proposed transform logs inside the CAS function ([plan](</opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md:899>)). `AtomicReference.updateAndGet` may invoke the lambda more than once under contention, so logging there can duplicate or log a failed CAS attempt. Move logging after a successful update, remove it, or implement an explicit CAS loop that logs only after `compareAndSet` succeeds.

- `ServerState` is not actually single-writer, and the planned logging transition remains non-atomic. `ServerHealthMonitor` writes from async WebClient `subscribe` callbacks ([ServerHealthMonitor.kt](</opt/github/zinin/frigate-analyzer/modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerHealthMonitor.kt:36>)), and `markServerDead` is another writer path ([ServerHealthMonitor.kt](</opt/github/zinin/frigate-analyzer/modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerHealthMonitor.kt:58>)). The plan’s `val wasAlive = server.alive; server.updateHealth { ... }` still has a check-then-update race for transition logs. Add `getAndUpdateHealth` and log from the returned previous snapshot.

### Concerns

- The stated guarantee is broader than the implementation can provide. The plan says readers always see a self-consistent set of runtime metrics ([plan](</opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md:5>)), but `supervisorJob` and `registeredDirs` stay outside the snapshot ([spec](</opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md:82>)). That is probably acceptable, but document the residual races explicitly: `computeHealth` can combine an old `XState` with a newer `Job`, and `WatchRecordsTask` can combine a state snapshot with a concurrently changed `registeredDirs.isEmpty()` branch.

- The Throwable JMM rationale is inaccurate. `Throwable.message`, `cause`, and `stackTrace` are not a clean immutable/final value object as described ([spec](</opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md:382>)). AtomicReference safe-publishes prior writes to the Throwable object; it does not make later Throwable mutation impossible. Either document “effectively immutable after catch” or store a `FailureSnapshot(className, sanitizedMessage, at)` instead of retaining `Throwable`.

- `ServerState` API changes more than the plan highlights. Today `alive` and `lastCheckTimestamp` are primary-constructor properties and therefore part of `copy`, `equals`, and `hashCode` ([ServerState.kt](</opt/github/zinin/frigate-analyzer/modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/loadbalancer/ServerState.kt:8>)). The planned move into a private `AtomicReference` removes them from those generated methods. I found no in-repo `ServerState.copy(...)` usage, but this should be called out as an intentional API/semantic change.

- `ActiveExportRegistry.Entry` keeps separate `state` and `cancellable` getters. Current call sites are okay, but any future code needing both fields can accidentally reintroduce split-snapshot reads. Add an `internal fun snapshot(): EntryState = stateRef.get()` and document that multi-field reads must use it.

- The plan undercounts some production `Entry.state` readers: `ExportExecutor` and `QuickExportHandler` also read cancellation state, not only cancel tests / `cancellable` call-sites. The convenience getter policy still covers them, but the review inventory should list them.

### Suggestions

- Keep `AtomicReference<XState>`; it is a sound direction for this refactor. For the genuinely single-writer classes, a simpler `@Volatile private var state: XState` would also solve atomic snapshot reads, but `AtomicReference` is reasonable if you want CAS semantics and future multi-writer safety.

- Prefer domain-specific update methods over exposing generic `updateState` widely, especially on `Entry`. Generic transforms make it easier to add side effects or invalid transitions later.

- Add deterministic transition tests, not concurrency smoke tests: assert each success/failure transition produces the intended full state snapshot, especially the chosen `lastFailure` reset/sticky behavior and `maybeResetBackoff` threshold behavior.

- Fix the design typo: `maybeResetBackoffTransform` increments `successesSinceLastFailure`, it does not decrement it ([spec](</opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md:188>)).

### Questions

- What is the intended contract of `lastFailure` / `lastFailureAt`: last observed failure ever, or active/current failure only?

- Should the “atomic snapshot” guarantee cover only fields inside `XState`, or also health decisions that combine `XState` with `supervisorJob` / `registeredDirs`?

- Is changing `ServerState.copy/equals/hashCode` semantics acceptable for this PR, or should the plan avoid relying on `data class` behavior there?
---

## ollama-executor (kimi-k2.6:cloud)

Source: `/home/zinin/.claude/ollama-interaction/2026-05-27-23-59-47-245196-design-review-volatile-snapshot-iter-1-ollama-kimi/output.txt`

Прочитал все три документа, 4 production-класса и тесты. Вот критика.

---

## Критические замечания

### 1. `maybeResetBackoffTransform` — нечистая (impure) функция внутри `updateAndGet`

План декларирует в §7: «Transform-функции — pure (читают closure, конструируют `it.copy(...)`)». Однако в `WatchRecordsTask` (§4.5, Step 4.5) `maybeResetBackoffTransform` вызывает `logger.info { ... }` прямо внутри лямбды, передаваемой в `updateAndGet`:

```kotlin
private fun maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState {
    ...
    return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
        logger.info { "Backoff reset after $nextSuccesses consecutive successes" }
        s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0)
    } else { ... }
}
```

Эта функция используется внутри `state.updateAndGet { ... maybeResetBackoffTransform(n) ... }`. `AtomicReference.updateAndGet` работает через CAS-цикл: если между `get()` и `compareAndSet` произойдёт concurrent `set()` (например, из `stateForTesting` в тесте или из другого writer-пути), трансформ выполнится повторно. Лог-дублирование — реальный побочный эффект. Это прямое нарушение заявленного инварианта и создаёт ложную уверенность в «чистоте» всех transform'ов.

**Actionable:** вынести логирование за пределы `updateAndGet`. Например, возвращать `Pair<WatchTaskState, Boolean>` (флаг `didReset`), а логировать по факту изменения состояния уже снаружи.

### 2. `clock.instant()` вызывается внутри transform-лямбды в `ServerHealthMonitor`

План (Task 1, Step 1.3) предлагает:

```kotlin
server.updateHealth { it.copy(alive = true, lastCheckTimestamp = clock.instant()) }
```

При CAS-retry `clock.instant()` вернёт новое значение, и в итоговом состоянии `lastCheckTimestamp` может отличаться от того, что было задумано. Хотя contention на `ServerState` маловероятен (один writer — scheduler thread), это всё равно нарушение принципа «capture values before transform».

**Actionable:** захватывать `val now = clock.instant()` до `updateHealth`.

### 3. Single-field read в `runSupervised` нарушает собственную дисциплину

В `TelegramBotSupervisor.runSupervised()` (Plan §4.1, Step 3.4, строки 455–456) в catch-блоке:

```kotlin
logger.error(e) {
    "Telegram bot bootstrap/polling failed; next backoff=${state.get().currentBackoff.toMillis()}ms"
}
```

План учит: «Reader code MUST do exactly one `state.get()` at the top of any method that touches more than one field». Здесь в середине метода делается ещё один `state.get()` — только ради логирования. Для health-корректности это безвредно, но подрывает дисциплину: следующий maintainer скопирует паттерн и может прочитать два поля в разных `state.get()` в production-логике.

**Actionable:** читать backoff в локальную переменную перед `try` (она не меняется внутри успешного пути) или использовать `getAndUpdate` для RMW backoff'а с возвратом старого значения.

### 4. `data class ServerState` с `AtomicInteger` в constructor-параметрах

План добавляет `healthRef` как class-body property (не в конструктор), и верно отмечает, что `equals/hashCode` его не включают. Но 4 счётчика-`AtomicInteger` **уже** являются constructor-параметрами `data class`. `equals/hashCode` сравнивают `AtomicInteger` по **ссылке**, не по `.get()`. Это pre-existing footgun: если где-то в коде `ServerState` используется как ключ `Map` или проверяется на `==`, поведение зависит от identity `AtomicInteger`, а не от значений счётчиков. План трогает `ServerState.kt` и должен либо задокументировать это явно, либо предложить `override equals/hashCode` (или отказаться от `data class`).

---

## Опасения

### 1. Гибридная политика convenience getter'ов

Для `TelegramBotSupervisor` и `WatchRecordsTask` план удаляет convenience getter'ы «для дисциплины», а для `ServerState` и `Entry` оставляет «потому что public API». Это логично для backward compatibility, но создаёт непоследовательность: ranker читает `server.alive` (single-field), а `computeHealth` — через `snapshot()`. В документе стоит явно сформулировать **единое правило**: convenience getter допустим только для single-field reads; любая логика, зависящая от согласованности ≥2 полей, обязана использовать snapshot.

### 2. `stateForTesting` — backdoor без runtime-защиты

Сеттер `stateForTesting` делает `state.set(...)` в обход `updateAndGet`. KDoc говорит «только для тестов», но нет `@TestOnly` (Kotlin его не имеет) или `internal` — достаточно. Если через полгода кто-то скопирует паттерн в production-код, гонка вернётся. Это review-level invariant, но runtime-защиты ноль.

### 3. Неучтённый uncleared `lastFailure`/`lastFailureAt` в stable-success пути

В `TelegramBotSupervisor.onAttemptEnded` при `success && duration >= STABLE_THRESHOLD` план (как и текущий код) не трогает `lastFailure`/`lastFailureAt`. После stable-восстановления `baseBuilder` всё ещё покажет старый `lastFailure` в деталях health, хотя статус — `UP`. Это pre-existing behavior, но дизайн-документ должен либо объяснить, почему failure «прилипает» навсегда (до следующего failure), либо зафиксировать это как отдельный tech-debt.

### 4. `markCancelling` — KDoc врёт о типе возвращаемого значения

План (§4.3) пишет: «@return the entry snapshot after the transition on success». Но функция возвращает `Entry` (контейнер), а не `EntryState` (snapshot). Текущий код тоже возвращает `Entry`, так что речь об исправлении комментария, а не о поведении.

### 5. Отсутствие acknowledgment для read-modify-write backoff'а

В `runSupervised` обоих supervisor'ов план делает `state.get().currentBackoff`, затем `delay(...)`, затем `state.updateAndGet { nextBackoff(...) }`. Это **не atomic RMW**: между get и update backoff может теоретически измениться. В single-writer сценарии это безопасно, но дизайн-документ должен явно признать, что здесь atomicity достигается single-writer, а не `AtomicReference`.

---

## Предложения

1. **Сделать `maybeResetBackoffTransform` чистой.** Вернуть `Pair<WatchTaskState, Boolean>` или data class `BackoffTransformResult(state, didReset)`. Логировать снаружи `updateAndGet`, сравнивая старое и новое состояние.
2. **Захватывать timestamp до `updateHealth` в `ServerHealthMonitor` и `markServerDead`.**
3. **Добавить комментарий в `ServerState.kt`** о том, что `equals/hashCode` зависят от reference identity `AtomicInteger`, а не от счётчиков.
4. **Унифицировать правило convenience getter'ов:** задокументировать единый критерий — допустимы только для single-field reads, snapshot обязателен для multi-field consistency.
5. **Убрать `state.get()` из логирования в `runSupervised`.** Запомнить `currentBackoff` в локальную переменную до `try`-блока (для TelegramBotSupervisor) или вернуть его из `updateAndGet` в `onAttemptEnded` (сложнее, но чище архитектурно).
6. **Обновить комментарий в `ActiveExportRegistry.Entry`** — текущий комментарий (lines 33–36) говорит «Entry holds `var`s whose values mutate concurrently». После рефакторинга `Entry` больше не содержит `var`-полей; comment должен отражать `AtomicReference<EntryState>`.

---

## Вопросы

1. Есть ли production call-sites, которые полагаются на `ServerState.equals`/`hashCode` или используют `ServerState` в `Set`/`Map`? Добавление `healthRef` как class-body property не сломает `equals`, но наличие mutable `AtomicInteger` в constructor-параметрах `data class` остаётся latent footgun.
2. Почему stable-success путь в `onAttemptEnded` не очищает `lastFailure`/`lastFailureAt`? Это создаёт ситуацию, когда `/actuator/health` возвращает `UP`, но детали содержат exception от прошлого failure. Осознанный выбор или oversight?
3. Рассматривался ли `AtomicReferenceFieldUpdater` вместо `AtomicReference<XState>`? Для JVM-only проекта это позволило бы избежать аллокации ~100 байт на `updateAndGet`, хотя в данном случае overhead и вправду ничтожен. План отверг `kotlinx.atomicfu` как overkill, но про `AtomicReferenceFieldUpdater` не упоминает.
4. **Почему именно `AtomicReference`, а не `ReadWriteLock` для `ServerState`?** Для `ServerState` reader'ов (ranker + `canAcceptRequest`) много, а writer — один scheduler thread. `ReadWriteLock` позволил бы читателям не аллоцировать snapshot-объект вообще. План отверг Mutex из-за блокировки Actuator, но `ReadWriteLock` (или StampedeLock) не блокирует читателей. Был ли этот вариант рассмотрен?
---

## ollama-executor (minimax-m2.7:cloud)

Source: `/home/zinin/.claude/ollama-interaction/2026-05-27-23-59-55-245675-design-review-volatile-snapshot-iter-1-ollama-minimax/output.txt`



Теперь проведу анализ. Прочитал все три документа, все 4 production-файла и 2 ключевых тестовых файла.

---

## Критические Issues

### 1. `maybeResetBackoffTransform` не является чистой функцией

**Spec §4.2:**
> `maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState` — pure-функция

**Spec §4.2, план Step 4.5, итоговый код:**
```kotlin
private fun maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState {
    if (s.currentBackoff <= INITIAL_BACKOFF) return s
    val nextSuccesses = s.successesSinceLastFailure + 1
    return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
        logger.info { "Backoff reset after $nextSuccesses consecutive successes" }  // <--- ПОБОЧНЫЙ ЭФФЕКТ
        s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0)
    } else {
        s.copy(successesSinceLastFailure = nextSuccesses)
    }
}
```

Это вызывается из `onPollCompleted` внутри `state.updateAndGet { s -> ... }`:

```kotlin
state.updateAndGet { s ->
    var n = s.copy(lastSuccessfulPollAt = now)
    if (eventsProcessed > 0) {
        n = n.copy(...)
        n = maybeResetBackoffTransform(n)  // <--- side effect внутри CAS-транзакции
    }
    ...
}
```

Побочный эффект (логирование) внутри `AtomicReference.updateAndGet` — это не pure function. Даже в single-writer сценарии это нарушает инвариант, зафиксированный в KDoc на `state` (§3.1 spec):
> "Transform-функции — pure (читают closure, конструируют `it.copy(...)`)"

Причина, почему это critical: если `logger.info` внутри transform бросает исключение (редко, но возможно — appender может упасть), `updateAndGet` откатит CAS и повторит — логирование может сработать повторно или не сработать вообще. Чистая функция такой проблемы не имеет.

**Действие:** Либо убрать логирование из transform, либо переименовать в `maybeResetBackoffTransformWithLogging` и обновить KDoc на `state`, признав что transform не является чистым (но это противоречит spec §7).

---

### 2. Тестовые файлы перечислены неполностью

**Plan Task 3** указывает `TelegramBotSupervisorHealthIndicatorTest` в Step 3.9:
> Прочитать файл. Если есть direct field writes — применить паттерн. Если нет — никаких изменений.

Но сам план **нигде не фиксирует результат** этой проверки. Если файл действительно не имеет direct writes, план этого не подтверждает. Если имеет — изменения не описаны.

Аналогично для `WatchRecordsTaskHealthIndicatorTest` (Task 4, Step 4.9).

**Действие:** Добавить в план конкретные шаги grep-проверки до начала refactoring каждого класса:
```
grep -n "task\.lastFailure\s*=" modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskHealthIndicatorTest.kt
```

---

### 3. `ActiveExportRegistryTest` не проверен grep'ом

Spec §5.2 говорит:
> Если найдутся `entry.cancellable = ...` writes — переписать на `entry.updateState { it.copy(cancellable = ...) }`

Plan (Task 2, Step 2.1) говорит:
> (после grep — таких нет; reads `entry.state == CANCELLING` через convenience getter остаются)

Но ни spec, ни plan не показывают результат этого grep. Тесты `CancelExportHandlerTest` используют `registry.get(...)!!.state` (чтение), что покрывается convenience getter. Но это не подтверждено.

**Действие:** Добавить явный grep-шаг перед началом Task 2:
```
grep -rn "\.cancellable\s*=\|\.state\s*=" modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/
```

---

## Concerns

### 4. Single-writer утверждение не верифицировано в 3 классах из 4

Spec §2 утверждает, что `supervisorJob: Job?` — единственный writer для всех 4 классов. Это верно для:
- `TelegramBotSupervisor`: `supervisorJob` пишется только в `start()`, читается в `computeHealth`. ✓
- `WatchRecordsTask`: `supervisorJob` пишется только в `start()`. ✓

Но для `ActiveExportRegistry.Entry` — `cancellable` пишется в `attachCancellable`, а `state` пишется в `markCancelling` и `release`. Spec §2 не анализирует multi-writer сценарий для Entry.

**Для Entry:** `attachCancellable` вызывается из export-handler (одна coroutine), `markCancelling` вызывается из cancel-handler (другая coroutine). Это **real multi-writer** — не single-writer. Spec это признаёт косвенно (JMM comment в lines 114-128 существует потому что есть race между attach и markCancelling).

Plan §4.3 переписывает `attachCancellable` с одним `updateState`, и `markCancelling` использует `getAndUpdateState` + `synchronized(entry)`. Это правильно. Но **semantic claim "single writer" для Entry неверна**.

**Действие:** Убрать из spec §2 или переформулировать "single writer pattern is the reality for all 4 sites" — для Entry это не single-writer.

---

### 5. Convenience getter политика несогласованна внутри себя

Spec §5.1:

| Класс | Getters |
|-------|---------|
| TelegramBotSupervisor | нет |
| WatchRecordsTask | нет |
| ActiveExportRegistry.Entry | да |
| ServerState | да |

Обоснование для Entry/ServerState: "existing public API с production call-sites". Но это несогласованно с решением для Supervisor/Task.

**Конкретный вопрос:** `ServerState.alive` и `ServerState.lastCheckTimestamp` — действительно ли это **production** call-sites, или только test-sites? В production код читает `canAcceptRequest`, который использует `alive`. Но `canAcceptRequest` делает `alive && getCurrentCount(...)` — это single-field read, atomic сам по себе. Если читателю нужна **согласованность пары** `alive + lastCheckTimestamp`, то convenience getter `alive` не помогает — нужен `snapshot()`.

Spec §4.4 говорит:
> Where the pair (alive + lastCheckTimestamp) needs consistency: `val h = server.snapshot(); h.alive && h.lastCheckTimestamp.isAfter(...)`

Это означает, что convenience getters для парной консистентности **недостаточны**. Для Entry аналогично: если читателю нужна согласованность `cancellable + state`, один `entry.cancellable` getter не даёт pair-consistency.

**Действие:** Явно задокументировать, для каких production call-сайтов какой accessor нужен (single-field vs pair snapshot).

---

### 6. Plan Step 3.4 — использование `state.get()` внутри loop

```kotlin
val backoff = state.get().currentBackoff
delay(backoff.toMillis())
state.updateAndGet { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
```

Это читает `currentBackoff` **до** `delay`, потом пишет `nextBackoff(it.currentBackoff)` **после**. Между read и write другое выражение (`delay`) не меняет state, но паттерн "read → delay → write nextBackoff(read value)" выглядит как потенциальная логическая ошибка: `nextBackoff` читает `it.currentBackoff` напрямую из state (а не из захваченного `backoff`), так что тут нет проблемы — значение `backoff` не используется для вычисления следующего.

Но **намерение** неясно из кода. Почему не:
```kotlin
delay(state.get().currentBackoff.toMillis())
state.updateAndGet { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
```
или:
```kotlin
val backoff = state.get().currentBackoff
delay(backoff.toMillis())
state.updateAndGet { it.copy(currentBackoff = nextBackoff(backoff)) }
```
Второй вариант делает intent явным (nextBackoff применяется к значению, которое мы реально ждали).

---

## Suggestions

### 7. Добавить диаграмму потока данных для `onPollCompleted`

Spec §4.2 показывает код `onPollCompleted` с составным transform внутри `updateAndGet`. Этот transform — самый сложный из 4 классов (6 возможных веток внутри одного updateAndGet). Читателю сложно проверить корректность without трассировки всех возможных комбинаций `eventsProcessed` и `eventFailures`.

Диаграмма или таблица решений (decision table) для 4 возможных входных комбинаций помогла бы верификации:

| eventsProcessed | eventFailures | lastSuccessfulPollAt | consecutiveEventFailures | consecutiveFailures | successesSinceLastFailure | currentBackoff |
|----------------|---------------|----------------------|--------------------------|---------------------|---------------------------|----------------|
| >0             | >0            | now                  | 0                       | 0                    | +1 / reset                | reset/inc      |
| >0             | =0            | now                  | 0                       | 0                    | +1 / reset                | reset/inc      |
| =0             | >0            | now                  | +eventFailures           | +1                   | 0                         | inc            |
| =0             | =0            | now                  | unchanged                | 0                    | unchanged                 | unchanged      |

### 8. Рассмотреть вынос `stateForTesting` в отдельный internal interface

Plan вводит `stateForTesting` как property с getter/setter на каждом классе. Это работает, но дублирует паттерн.

Альтернатива: создать `TestingStateAccessor<XState>` интерфейс с единственным методом `var snapshot: XState`, и реализовать его в каждом классе. Это позволит иметь один общий test utility для всех 4 классов.

Но это, возможно, premature generalization — 4 класса, вероятно, не будут расти.

### 9. Указать явную стратегию миграции для `runSupervised` tail delay

В Plan Step 3.4 (`runSupervised`):
```kotlin
val backoff = state.get().currentBackoff
delay(backoff.toMillis())
state.updateAndGet { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
```

Проблема: после `delay` значение `state.get().currentBackoff` может уже измениться (если кто-то другой написал). Но в single-writer это невозможно. Однако CAS-retry semantics `updateAndGet` guarantee что `nextBackoff` применяется к текущему значению.

Добавить комментарий:
```kotlin
// In single-writer context, no concurrent update can intervene between the
// delay and the updateAndGet — it.currentBackoff is always the value we waited on.
```

---

## Questions

### Q1. Почему не выбрана опция D (document as acceptable) несмотря на production impact ≈ 0?

Background §5:
> "Consistency vs simplicity trade-off — single-deployment проект, monitoring у владельца домашнее. Real-world cost транзитного inconsistent JSON ≈ ноль. Стоит ли усложнять?"

Ответgiven в spec §1: 4 из 6 внешних ревьюеров независимо указали на это. Но это аргумент "потому что ревьюеры флагнули", а не технический. 

Если production impact ≈ 0, почему это не documented-as-acceptable, с комментарием в коде, вместо ~400 LoC изменений?

Конкретно: для **ServerState** — 2 поля, writer в одном месте. Замена `@Volatile var` на `AtomicReference` добавляет indirection без реальной пользы, если writer действительно single-threaded (health monitor работает на том же dispatcher's thread, что и reader).

### Q2. Что происходит с `consecutiveEventFailures` и `consecutiveRegistrationFailures` при `onLoopFailure`?

В текущем коде `onLoopFailure`:
```kotlin
private fun onLoopFailure(t: Throwable) {
    consecutiveFailures++
    successesSinceLastFailure = 0
    lastFailure = t
    lastFailureAt = Instant.now(clock)
}
```

`consecutiveEventFailures` и `consecutiveRegistrationFailures` **не трогаются**. Spec §4.2 план для `onLoopFailure` — тоже не трогает их. Это consistent.

Но в spec §2 (что НЕ попадает в State) для `WatchRecordsTask`:
> "`currentBackoff` обновляется в нескольких местах: `runSupervised` (tail bump after `delay`), `onPollCompleted/maybeResetBackoff` (success path), `onLoopFailure` (если применимо)."

Фраза "если применимо" неясна. `onLoopFailure` не обновляет `currentBackoff` в текущем коде (его caller в `runSupervised` обновляет после `delay`). Spec §4.2 план для `onLoopFailure` — только `consecutiveFailures++` etc., что правильно. Фраза в §2 про `onLoopFailure` неточна.

### Q3. Как тестируется health consistency guarantee?

Spec §9 говорит:
> "Existing coverage already covers state transitions; updated tests will guarantee that transition methods atomically set all related fields (through `s = stateForTesting` they become visible as one snapshot)."

Но ни spec, ни plan не показывают, как после рефакторинга **верифицировать** что reader действительно видит consistent snapshot. Тесты сейчас проверяют individual field values, но не проверяют что при **конкурентном** reader/writer (даже simulated) snapshot действительно атомарный.

Есть ли план для теста, который проверяет: "если writer обновляет 3 поля в одном updateAndGet, reader в тот же момент видит либо все 3 старых значения, либо все 3 новых — не смесь"?

---

## Резюме

| Категория | Кол-во | Приоритет |
|-----------|--------|-----------|
| Critical | 3 | Исправить до начала реализации |
| Concerns | 3 | Уточнить перед реализацией |
| Suggestions | 3 | Опциональные улучшения |
| Questions | 3 | Требуют ответа от автора |

Наиболее срочное: **Issue 1** (`maybeResetBackoffTransform` не pure) и **Issue 2** (неполные тестовые файлы). Без их решения имплементация будет содержать скрытый дефект.

---

## ollama-executor (deepseek-v4-pro:cloud)

Source: `/home/zinin/.claude/ollama-interaction/2026-05-28-00-00-09-246163-design-review-volatile-snapshot-iter-1-ollama-deepseek/output.txt`

Теперь у меня есть полная картина. Проверил все production-файлы и тесты. Перехожу к ревью.

---

# Ревью: Atomic snapshot для `@Volatile` runtime state

## Контекст проверки

Прочитаны все 3 документа (spec, plan, issue-report), 5 production-файлов, 7 тестовых файлов. Проверены call-сайты `server.alive` (7 тестовых, 3 production), `entry.state`/`entry.cancellable` (2 тестовых, 1 production в `CancelExportHandler.kt:116`), `stateForTesting`-паттерн в тестах на непротиворечивость.

## Critical Issues

### C1. `maybeResetBackoffTransform` нарушает контракт pure-функции

**Plan §4.5 (lines 899-908)** объявляет `maybeResetBackoffTransform` как "Pure transform" в KDoc, но тело содержит `logger.info { ... }` — side effect, выполняющий I/O (запись в лог).

```kotlin
private fun maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState {
    if (s.currentBackoff <= INITIAL_BACKOFF) return s
    val nextSuccesses = s.successesSinceLastFailure + 1
    return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
        logger.info { "Backoff reset after $nextSuccesses consecutive successes" }  // ← side effect
        s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0)
    } else {
        s.copy(successesSinceLastFailure = nextSuccesses)
    }
}
```

**Почему это проблема:** `AtomicReference.updateAndGet` реализован через CAS-loop. При contention (даже теоретическом) transform выполняется повторно при каждом retry. `logger.info` вызовется N раз вместо одного.

**Практический impact в single-writer:** нулевой — CAS никогда не retry'ится. Но:

1. Это **противоречит собственному spec'у** (Spec §7: "Transform-функции — pure (читают closure, конструируют `it.copy(...)`)"). Spec явно называет side-effect внутри transform "багом review-уровня".
2. KDoc на `state` поле требует pure transform — и тут же нарушается в первом же вызове.
3. Создаёт прецедент: будущий разработчик видит `logger.info` в transform и считает это допустимым.

**Исправление:** вынести `logger.info` ЗА пределы `updateAndGet` — прочитать результат transform и залогировать снаружи:

```kotlin
state.updateAndGet { s ->
    // ...pure transform...
    n = maybeResetBackoffTransform(n)  // pure — убрать logger.info
    // ...
}
// Снаружи CAS:
if (state.get().currentBackoff <= INITIAL_BACKOFF && s.currentBackoff > INITIAL_BACKOFF) {
    logger.info { "Backoff reset after ..." }
}
```

Либо честно признать impurity в KDoc с оговоркой "safe under single-writer" — но это слабее.

### C2. `runSupervised` читает `state.get()` между `updateAndGet` — нарушение single-snapshot дисциплины

**Plan §3.4 (lines 458-461)** для TelegramBotSupervisor и **Plan §4.4 (lines 800-803, 812-820)** для WatchRecordsTask:

```kotlin
val backoff = state.get().currentBackoff   // (A) read outside transform
delay(backoff.toMillis())
state.updateAndGet { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }  // (B) read inside transform
```

Точка (A) читает `currentBackoff` из одного снапшота, точка (B) — из другого. Если бы между ними был другой writer, `nextBackoff` получила бы значение, не равное тому, от которого мы сделали `delay`.

**В single-writer реальности:** другой writer отсутствует, поэтому `it.currentBackoff` внутри transform ВСЕГДА равен `backoff` из (A). Практически безопасно.

**Но:** это концептуальная дыра в invariant'е "exactly one `state.get()` per method". Spec §3.1: "Reader code MUST do exactly one `state.get()` at the top of any method that touches more than one field." Здесь `runSupervised` — writer, но тот же принцип: он читает state вне `updateAndGet`.

**Исправление:** использовать `getAndUpdate` чтобы атомарно прочитать старый backoff и записать новый:

```kotlin
val effectiveBackoff = state.getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }.currentBackoff
delay(effectiveBackoff.toMillis())
```

`getAndUpdate` возвращает состояние ДО transform — `currentBackoff` в нём это ровно то значение, от которого считался `nextBackoff`, и ровно то, на сколько нужно делать `delay`. Одна атомарная операция вместо двух.

## Concerns

### CN1. `ServerState.canAcceptRequest` — две независимые atomic reads

После рефакторинга `alive` становится `healthRef.get().alive`, а `processingFrameRequestsCount.get()` остаётся отдельным `AtomicInteger.get()`. Это два атомарных чтения, между которыми может измениться и то и другое. Рефакторинг не делает ситуацию хуже (сейчас те же два чтения `@Volatile` + `AtomicInteger`), но и не улучшает. Plan не упоминает этот нюанс как acceptable limitation, хотя он прямо релевантен цели "reader всегда видит consistent комбинацию полей". Для `canAcceptRequest` это не критично (load-balancer retry), но упоминания заслуживает.

### CN2. `onPollCompleted` transform — `maybeResetBackoffTransform` и `eventFailures > 0` гоняют `successesSinceLastFailure` вхолостую

Plan §4.5:
```kotlin
n = maybeResetBackoffTransform(n)  // инкрементит successesSinceLastFailure
// ...
if (eventFailures > 0) {
    n = n.copy(successesSinceLastFailure = 0)  // зануляет
}
```

Когда `eventsProcessed > 0` И `eventFailures > 0` одновременно, `maybeResetBackoffTransform` инкрементит счётчик, а через 2 строки он зануляется. Это **pre-existing behaviour** (текущий код делает то же самое), но план не помечает это как known quirk. Стоит либо задокументировать, либо вынести `maybeResetBackoffTransform` после проверки `eventFailures` (но тогда меняется семантика — надо проверять).

## Suggestions

### S1. Вернуть `successesSinceLastFailure` как `internal` (сейчас `private`)

Текущий код: `@Volatile private var successesSinceLastFailure` (WatchRecordsTask.kt:78). После рефакторинга становится полем `WatchTaskState` → автоматически `internal` (data class в Kotlin генерирует public component-функции). Plan не обсуждает это изменение видимости. Оно **безопасно** (тесты в том же модуле, production-читателей нет), но должно быть явно отмечено.

### S2. В `watchServiceFactory` нет изменений — но он упоминается в тестах

Plan §4.2 правильно исключает `watchService`, `registeredDirs`, `watchServiceFactory` из State. Но тест `WatchRecordsTaskTest.kt:109-113` передаёт `watchServiceFactory` как lambda в конструктор — это не затронуто. Plan мог бы явно отметить это в списке "не меняется" для полноты.

### S3. Добавить тест на `maybeResetBackoffTransform` как unit

После извлечения pure-функции имеет смысл отдельный тест на неё. Сейчас логика покрыта только интегрально через `runSupervised` тесты. Plan §5.3 говорит "Новые тесты — нет", но чистую функцию легко тестировать изолированно без моков.

### S4. `computeHealth` branch numbering inconsistency

Plan §4.6 для WatchRecordsTask использует нумерацию BRANCH 1-8, но в текущем коде (`WatchRecordsTask.kt:275-389`) это BRANCH 1, BRANCH 2, BRANCH 3, BRANCH 3.5, BRANCH 4, BRANCH 5, BRANCH 6, BRANCH 7, BRANCH 8. Plan's refactored код использует ту же нумерацию — вопрос стиля, но стоит унифицировать (либо 1-8 плоский список, либо сохранить 3.5).

## Questions

### Q1. Нужно ли обновлять `DetectServerLoadBalancer.kt:86,121`?

`DetectServerLoadBalancer.kt:86` читает `server.alive`:
```kotlin
status = if (server.alive) ServerStatus.ALIVE else ServerStatus.DEAD
```
Строка 121:
```kotlin
"${server.id}(alive=${server.alive}, ..."
```

Convenience getter `val alive: Boolean get() = healthRef.get().alive` покрывает оба call-сайта без изменений. Но plan не проверяет это явно — для completeness стоит добавить grep в Step 1.1 на все call-сайты `.alive`.

### Q2. `CancelExportHandler.kt:116` — `marked.cancellable` после `markCancelling`

```kotlin
marked.cancellable?.let { cancellable ->
```

`marked` — результат `markCancelling(exportId)`, который возвращает `Entry?`. После рефакторинга `Entry.cancellable` — convenience getter `stateRef.get().cancellable`. Plan §4.3 правильно отмечает, что этот call-сайт работает без изменений. Но нужно верифицировать, что **happens-before гарантия между `markCancelling` (пишет state=CANCELLING) и `attachCancellable` (пишет cancellable) сохраняется**. Сейчас она держится на `synchronized(entry)` + `computeIfPresent`. После рефакторинга `attachCancellable` пишет через `updateState`, а `markCancelling` читает через `getAndUpdateState`. Оба под `synchronized(entry)` — happens-before обеспечен монитором. Порядок не сломан.

### Q3. `nextBackoff` в `runSupervised` — а нужен ли вообще отдельный `get()`?

В текущем коде `WatchRecordsTask.runSupervised()` есть логика (line 156):
```kotlin
if (result.eventFailures > 0 && result.eventsProcessed == 0) {
    delay(currentBackoff.toMillis())
    currentBackoff = nextBackoff(currentBackoff)
}
```

Этот backoff bump — в ответвлении conditional внутри try-блока, а не в хвосте цикла (как в TelegramBotSupervisor). Plan §4.4 корректно это воспроизводит. Но важно отметить: `state.get().currentBackoff` (line 801 плана) может быть **не равен** значению, которое `onPollCompleted` только что записала в `updateAndGet`. 

Пример: `onPollCompleted` только что изменила `currentBackoff` (через `maybeResetBackoffTransform`), а следующая строка читает `state.get().currentBackoff` — получает свежее значение. Всё корректно, потому что это **последовательные операции в одном потоке**, и happens-before гарантирован program order. Но семантически это опять "два чтения одного поля" — безвредно, но некрасиво.

### Q4. Почему в плане `runSupervised()` TelegramBotSupervisor делает `state.updateAndGet` ДО `supervisorJob = scope.launch`?

Plan §3.3:
```kotlin
fun start() {
    // ...
    state.updateAndGet { it.copy(startupAt = Instant.now(clock)) }
    supervisorJob = scope.launch { runSupervised() }
}
```

Текущий код:
```kotlin
startupAt = Instant.now(clock)
supervisorJob = scope.launch { runSupervised() }
```

Порядок сохранён — `startupAt` пишется первым. Но атомарность `updateAndGet` здесь избыточна (одно поле). Это не баг, а consistency: все writes через `updateAndGet`. Принимается.

---

## Итого

**Дизайн в целом sound.** Подход `AtomicReference<XState>` + immutable data class корректен для single-writer сценариев. План детальный, покрывает все 4 класса и их тесты.

**Два critical issues требуют исправления до реализации:**

1. **C1** — убрать `logger.info` из `maybeResetBackoffTransform` (или признать impurity в KDoc).
2. **C2** — заменить пару `state.get()` + `state.updateAndGet` на один `state.getAndUpdate` в хвостовых backoff-bump.

**Остальное — concerns/suggestions**, которые стоит адресовать, но они не блокируют реализацию.
---

## ccs-executor (glm) — NOTE: did not produce structured review

The CCS GLM agent ran for 11+ minutes and produced an inventory of test/production call-sites rather than a structured Critical/Concerns/Suggestions/Questions review.
Source: `/home/zinin/.claude/ccs-interaction/2026-05-27-23-59-40-244834-design-review-volatile-snapshot-iter-1-ccs-glm/log.jsonl` (585KB log)

**Useful side-effect — inventory of writes/reads in tests + production:**

TelegramBotSupervisorTest: ~30 direct writes to fields (startupAt, lastStableAt, lastPollingStartAt, consecutiveFailures, lastFailure, lastFailureAt, supervisorJob, currentBackoff).

WatchRecordsTaskTest: helper-driven access via `buildTask(...)` setting 9 fields; reads via `task.consecutiveFailures`, `task.lastFailure`, `task.lastSuccessfulPollAt`.

ActiveExportRegistryTest: production tests via API only (no direct field writes); reads `registry.get(exportId)!!.cancellable`, `entry.state == CANCELLING`.

CancelExportHandlerTest: API-driven; only reads of `entry.state == CANCELLING` at lines 186, 266, 407.

DetectServiceTest: 11 `server.alive = true` writes (lines 71, 255, 272, 289, 305, 319, 333, 350, 367, 388, 407); 1 `primaryServer.processingFrameRequestsCount.set(1)` write.

DetectServiceCancelJobTest: 1 `registry.getServer("test")!!.alive = true` write at line 75.

VideoVisualizationServiceTest: 1 `registry.getServer("test")!!.alive = true` write at line 76.

**Additional Entry.state readers (production code, NOT in plan inventory):**
- ExportExecutor: state reads on lines 115, 268
- QuickExportHandler: state reads on lines 186, 300

(These additional readers are covered by the existing `entry.state` convenience getter — no fix required, but inventory should mention them per Codex Concern.)

---

## ccs-executor (glm) — UPDATED with structured review

Source: `/home/zinin/.claude/ccs-interaction/2026-05-27-23-59-40-244834-design-review-volatile-snapshot-iter-1-ccs-glm/output.txt`

**NOTE:** GLM ultimately produced structured review (the earlier inventory-only block above remains as supplementary data). Both sections from GLM are included.

Теперь у меня достаточно информации для полного ревью. Вот результаты.

---

## Ревью дизайна и плана рефакторинга «Atomic snapshot for @Volatile runtime state»

---

### Critical Issues

**1. `maybeResetBackoffTransform` нарушает заявленную чистоту transform-функций**

Design §7 прямо утверждает: *«Transform-функции — pure (читают closure, конструируют `it.copy(...)`). Если когда-нибудь добавится side-effect — это баг review-уровня»*. Plan Step 4.5 включает `logger.info` внутри `maybeResetBackoffTransform` (строка 903 плана):

```kotlin
private fun maybeResetBackoffTransform(s: WatchTaskState): WatchTaskState {
    // ...
    return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
        logger.info { "Backoff reset after $nextSuccesses consecutive successes" }  // side-effect!
        s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0)
    } else { ... }
}
```

`AtomicReference.updateAndGet` может вызвать transform многократно при CAS-ретрае (контракт JDK, см. `java.util.concurrent.atomic.AtomicReference.updateAndGet`). При single-writer+test-only contention ретрай маловероятен, но **документ заявляет чистоту как invariant, а план её нарушает в первом же нетривиальном transform**. Это создаёт прецедент: следующий разработчик увидит `logger.info` в `maybeResetBackoffTransform` и решит, что side-effects в transform допустимы.

**Решение:** вынести логирование за пределы `updateAndGet`, сравнив старый и новый state:

```kotlin
val old = state.get()
val new = state.updateAndGet { s -> onPollCompletedTransform(s, eventsProcessed, eventFailures, now) }
if (new.currentBackoff == INITIAL_BACKOFF && old.currentBackoff > INITIAL_BACKOFF) {
    logger.info { "Backoff reset after ${old.successesSinceLastFailure + 1} consecutive successes" }
}
```

Или сделать `maybeResetBackoffTransform` по-настоящему чистой (убрать `logger.info`), а логирование перенести в вызывающий код после `updateAndGet`.

---

**2. `ServerState` data class: незадокументированное изменение семантики `equals`/`hashCode`/`copy`**

Текущий `ServerState` — `data class` с `alive` и `lastCheckTimestamp` в конструкторе. После рефакторинга эти поля уходят из конструктора в `private val healthRef`. Следствия:

- **`equals`/`hashCode`** перестают учитывать `alive` и `lastCheckTimestamp`.
- **`copy()`** теряет параметры `alive`/`lastCheckTimestamp`.
- Любой код, полагающийся на структурное равенство двух `ServerState` с разными `alive`-значениями, сломается.

Проверка показывает, что сейчас это безопасно: `DetectServerRegistry` хранит `ConcurrentHashMap<String, ServerState>` (ключ — `String`), `.copy()` нигде не вызывается, `Set`/`List.contains` не используются. Но design-документ вообще не упоминает это изменение — оно есть только в коде плана. Минимум: явно задокументировать в design §4.4, что `equals`/`hashCode` более не включает health-поля, и что это assessed как безопасный change.

---

### Concerns

**3. TOCTOU между `wasAlive` и `updateHealth` в `ServerHealthMonitor`**

Plan Step 1.3:
```kotlin
val wasAlive = server.alive          // read #1 (через convenience getter)
server.updateHealth { it.copy(alive = true, lastCheckTimestamp = clock.instant()) }  // CAS
if (!wasAlive) { logger.info { ... } }
```

Между `read #1` и CAS состояние может измениться другим subscriber'ом WebFlux (webClient subscribe callbacks могут конкурировать). Если два success-callback'а придут почти одновременно, оба прочитают `wasAlive = false`, оба залогируют «now ALIVE». Это та же гонка, что и в текущем коде (`val wasAlive = server.alive; server.alive = true`), так что план **не ухудшает** ситуацию, но и не исправляет её. Стоит явно отметить в design, что дублированное ALIVE-сообщение — acceptable.

**4. `successesSinceLastFailure` расширяет тестовый API-контракт**

Сейчас `successesSinceLastFailure` — `@Volatile private var` в `WatchRecordsTask` (строка 78). Тесты **не могут** устанавливать его напрямую (Kotlin `private` не доступен из других файлов). После рефакторинга это поле в `WatchTaskState`, доступное через `stateForTesting`. Тесты получат возможность устанавливать его в произвольное значение, что может маскировать баги (например, значение, невозможное в production).

Это осознанное решение (uniformity, из brainstorming), но оно не упомянуто в design как trade-off. Стоит добавить ремарку в §5.2.

**5. `computeHealth` читает `supervisorJob` отдельно от state-snapshot**

Оба supervisor-класса в `computeHealth` делают:
```kotlin
val s = state.get()           // snapshot
val job = supervisorJob        // отдельный @Volatile read
```

Между этими двумя чтениями `supervisorJob` может стать `null` (shutdown), при этом snapshot отражает «active» состояние. Или наоборот — snapshot содержит `consecutiveFailures > 0`, а `job.isActive == true` уже в новом успешном polling. Design §3.2 это обосновывает (`supervisorJob` — lifecycle handle), но inconsistency между snapshot и job **остаётся**. Для health indicator это ок (самокоррекция на следующем тике), но стоит явно указать, что atomic snapshot покрывает только value-fields, не lifecycle state.

**6. `onAttemptEnded` — 4 отдельных `updateAndGet` в TelegramBotSupervisor (Plan Step 3.5)**

`onAttemptEnded` имеет 4 branch с отдельными `updateAndGet` каждый. Между ними state консистентен внутри каждой ветки. Но текущий код обновляет поля раздельными volatile writes — каждый branch — это ~3 раздельных записи. План сокращает это до одной atomic записи на branch. Это улучшение, но стоит проверить: не потеряна ли какая-то intermediate state комбинация, на которую полагался тест.

Проверка `TelegramBotSupervisorTest` показывает, что тесты устанавливают state через `sup.field = ...` и сразу вызывают `computeHealth()` — раздельные writes никогда не наблюдались в промежуточном состоянии в тестах (StandardTestDispatcher). Проблемы нет, но явная ремарка в плане не помешала бы.

**7. В Plan Step 2.2 `Entry` по-прежнему `class` (не `data class`) — правильное решение, но комментарий избыточен**

План добавляет комментарий к `Entry` (строки 219-222 плана): *«Plain class (not data class): Entry holds an AtomicReference whose state mutates concurrently. data class equals/hashCode over an AtomicReference is a latent footgun»*. Это правильное решение. Но в текущем коде уже есть почти идентичный комментарий (строки 33-36 `ActiveExportRegistry.kt`). Стоит не дублировать, а обновить существующий.

---

### Suggestions

**8. Вывести логирование из `maybeResetBackoffTransform` (дополнение к Critical #1)**

Альтернатива — сделать `maybeResetBackoffTransform` pure и возвращать `Pair<WatchTaskState, Boolean>` где Boolean = «was reset». Вызывающий код проверяет Boolean и логирует:

```kotlin
private data class BackoffResult(val state: WatchTaskState, val wasReset: Boolean)

private fun maybeResetBackoffTransform(s: WatchTaskState): BackoffResult {
    if (s.currentBackoff <= INITIAL_BACKOFF) return BackoffResult(s, false)
    val nextSuccesses = s.successesSinceLastFailure + 1
    return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
        BackoffResult(s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0), true)
    } else {
        BackoffResult(s.copy(successesSinceLastFailure = nextSuccesses), false)
    }
}
```

Это сохраняет чистоту transform и позволяет логировать только при реальном reset.

**9. Добавить `@VisibleForTesting`-аннотацию на `stateForTesting`**

Все 4 класса предоставляют `stateForTesting` как `internal var`. Рассмотрите явно аннотировать это через `@VisibleForTesting` (из AndroidX или custom annotation), чтобы IDE/static analysis подсвечивали использование в production-коде. Это особенно важно, учитывая, что `state.set(...)` разрешён только в тестах (design §3.1).

**10. `baseBuilder(s)` — рассмотреть extension function на State**

Вместо `private fun baseBuilder(s: SupervisorState)` и `private fun baseBuilder(s: WatchTaskState)`, можно сделать их extension function:

```kotlin
private fun SupervisorState.toHealthBuilder(): Health.Builder = ...
private fun WatchTaskState.toHealthBuilder(registeredDirs: ConcurrentHashMap<*, *>): Health.Builder = ...
```

Это чище separation of concerns и лучше тестируется.

**11. Commit-сообщения: убрать ссылку на spec-файлы**

Plan предполагает `git rm docs/superpowers/` перед PR (Step 5.4), но commit-сообщения (Steps 1.6, 2.6, 3.11, 4.11) ссылаются на spec §4.X. Если spec-файлы удалены из branch, ссылки теряют смысл. Либо убрать ссылки из commit-сообщений, либо не удалять spec до PR (только перед merge).

**12. `ServerState.lastCheckTimestamp` — не используется ни одним production-reader**

Grep показал, что `lastCheckTimestamp` пишется в 3 местах `ServerHealthMonitor`, но **нигде не читается** в production-коде (ни в ranker, ни в load balancer, ни в status service). `ServerSelectionStrategy` фильтрует только по `server.alive`. Поле существует чисто для observability, но не потребляется. Если это так, его можно было бы исключить из `HealthSnapshot` (или документировать как placeholder для будущего использования). Это не баг плана, но повод упростить scope.

---

### Questions

**Q1.** Design §3.1 говорит: *«`state.set(...)` — только в setter `stateForTesting`»*. Но что мешает production-коду случайно вызвать `stateForTesting.set(...)` (он `internal var`)? Рассматривался ли вариант сделать setter `@VisibleForTesting` или `@Suppress("NOTHING_TO_INLINE")` с assertion-чеком?

**Q2.** В `onPollCompleted` (Plan Step 4.5) transform содержит branching (`if eventsProcessed > 0`, `if eventFailures > 0`, `else if eventsProcessed == 0`). Это ~20 строк в одном lambda. Рассматривался ли вариант вынести это в именованную private-функцию с explicit `WatchTaskState`-параметром, как сделано для `maybeResetBackoffTransform`?

**Q3.** Plan Step 3.4 (`runSupervised`): после `onAttemptEnded` код делает `val backoff = state.get().currentBackoff`, потом `delay(backoff)`, потом `state.updateAndGet { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }`. Между `state.get()` и `delay` состояние может измениться (если health indicator не пишет, то ок; но при future changes). Это состояние «read-then-act» — намеренное? В текущем коде это `delay(currentBackoff.toMillis())` с прямым volatile read, та же семантика.

**Q4.** В `ActiveExportRegistry.markCancelling` (Plan Step 2.4) `getAndUpdateState` вызывается внутри `synchronized(entry)`. Если CAS внутри `getAndUpdate` ретраится (конкуренция с `attachCancellable`), будет ли `synchronized(entry)` удерживаться пока CAS не завершится? Ответ: да, но на практике `attachCancellable` не берёт `synchronized(entry)`, так что contention = 0. Подтверждаете, что это acceptable?

**Q5.** В design §5.1 указано: TelegramBotSupervisor и WatchRecordsTask **не** получают convenience getter'ы. Но `stateForTesting` — это `internal var`, доступный из тестов. Рассматривался ли отдельный `@VisibleForTesting` accessor, который возвращает `val state: SupervisorState get() = state.get()` (read-only) для production, с отдельным write-only setter только для тестов?

---

### Резюме

Дизайн и план в целом **состоятельны** — `AtomicReference<State>` + immutable data class — правильный паттерн для описанной проблемы. Гибридная политика convenience getter'ов обоснована. Покрытие тестовых файлов полное (все call-сайты идентифицированы). Порядок от простого к сложному — логичный.

**Единственный must-fix**: убрать side-effect (`logger.info`) из `maybeResetBackoffTransform` (или любой другой transform-функции), чтобы не нарушать заявленный в design pure-transform invariant. Остальные замечания — уточнения и improvement-предложения, не блокирующие имплементацию.
