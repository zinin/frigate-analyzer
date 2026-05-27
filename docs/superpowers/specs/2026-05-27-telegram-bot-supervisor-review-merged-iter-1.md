# Merged Design Review — Iteration 1

**Date:** 2026-05-27
**Topic:** telegram-bot-supervisor
**Design:** `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`
**Plan:** `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md`

**Agents:** codex-executor (gpt-5.5/xhigh), ccs-executor (glm-5.1), ollama-kimi (Kimi K2.6), ollama-minimax (MiniMax M2.7), ollama-deepseek (DeepSeek-V4 Pro).

---

## codex-executor (gpt-5.5)

### Critical Issues

- **Health-state сейчас логически сломан: `UP` почти недостижим.**
  В design сказано, что `lastStableAt` обновляется только при завершении attempt, а живой стабильный polling должен определяться через `lastPollingStartAt` (design:193, design:222). Но в таблице health branch 2/3 (`lastStableAt == null`) стоят раньше branch 6 (`lastPollingStartAt >= STABLE_THRESHOLD`) (design:208). В plan это повторено в Step 6.4: branch 2/3 выполняются до branch 6 (plan:948, plan:988). Итог: первый здоровый long-polling после 2 минут станет `DOWN` как "startup failed", потому что `lastStableAt` всё ещё `null`.

- **После восстановления связи health тоже не станет `UP`.**
  После любой ошибки `consecutiveFailures > 0`; при новом успешно запущенном polling branch 4/5 стоят раньше branch 6 (design:211). Значит восстановившийся polling через 70 секунд всё равно останется `OUT_OF_SERVICE`, что противоречит финальному smoke-сценарию (plan:1428). Нужно либо обновлять `lastStableAt` во время живого attempt, либо ставить "live stable polling" выше startup/backoff веток и проверять, что `lastPollingStartAt` новее `lastFailureAt`.

- **`Job.join()` не бросает причину падения polling job.**
  План рассчитывает, что `bot.buildBehaviourWithLongPolling { ... }.join()` попадёт в `catch` при падении long-polling (plan:504). Но `join()` у `Job` ждёт завершения, а не пробрасывает exception завершённой job. Тест в Step 4, где `job.completeExceptionally(...)` должен дать failure bookkeeping, как написан, должен пройти по `success=true`, а не по failure (plan:608). Нужно захватывать completion cause через `invokeOnCompletion`/`CompletableDeferred` или оборачивать polling в дочернюю coroutine с явным пробросом причины.

- **Supervisor, вероятно, не владеет реальной long-polling job.**
  План вызывает `buildBehaviourWithLongPolling` без передачи scope (plan:503). В ktgbotapi 33.1.0 у этой функции есть отдельный `CoroutineScope` parameter с default provider, то есть возвращаемая polling job не обязана быть дочерней job supervisor-а. Тогда `shutdown()` отменяет `supervisorJob`, но не обязательно отменяет сам polling (plan:1084). Это риск утечки poller-а, конфликтов `getUpdates`, зависаний при shutdown и дубликатов обработчиков после reconnect.

- **Инструкция по `mockkStatic` неверна для текущего ktgbotapi.**
  План указывает `dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt` (plan:434), но в локальном jar 33.1.0 top-level class называется `BehaviourBuildersKt`. Кроме того, stub с шестью `any()` (plan:604) не соответствует JVM-сигнатуре функции с default args. Это заблокирует тесты Step 4.

- **Cancellation может проглатываться регистрацией команд.**
  Текущие `registerDefaultCommands` и `registerOwnerCommands` ловят `Exception` без отдельного `CancellationException` (FrigateAnalyzerBot.kt:311, FrigateAnalyzerBot.kt:332). Новый `registerOwnerCommandsIfPossible` планируется так же (plan:120). Это противоречит заявленному clean cancellation contract supervisor-а (plan:514).

### Concerns

- **Clean return из `join()` считается success без проверки длительности.**
  `onAttemptEnded(success=true)` всегда сбрасывает failures и пишет `lastStableAt` (design:153). Если ktgbotapi молча завершит polling через секунду, health получит ложный "stable". Риск признан в §10 (design:346), но mitigation "WARN log" слабоват для health-индикатора.

- **`lastPollingStartAt` не очищается на новом attempt / failure.**
  Сейчас это не даёт false `UP` только потому, что branch ordering уже блокирует `UP`. После исправления порядка веток старый `lastPollingStartAt` может стать опасным. Нужен invariant: polling start timestamp относится к текущему attempt и новее последней ошибки.

- **Набор `@Volatile` полей не даёт консистентный snapshot.**
  Health builder читает много независимых mutable fields (design:86). Могут быть кратковременные несогласованные причины/status. Для actuator это может быть приемлемо, но state machine здесь достаточно тонкая, лучше рассмотреть immutable state в `AtomicReference`.

- **Task 1 заявлен как "no behavior change", но меняет owner activation.**
  Сейчас event несёт точный `chatId` и вызывает `registerOwnerCommands(event.chatId)` (FrigateAnalyzerBot.kt:304). План заменяет это на повторный DB lookup и игнорирует `event.chatId` (plan:181). При временной DB ошибке owner menu не обновится до следующего `/start` или reconnect.

- **План тестов не покрывает заявленное "registration failures do not trigger backoff".**
  В design этот тест перечислен (design:304), но в plan отдельного шага нет. Через mock `FrigateAnalyzerBot.registerDefaultCommands()` это корректно не проверить: реальное свойство в том, что метод сам ловит `setMyCommands` ошибки.

### Suggestions

- Исправить health как явную state machine: `CONNECTING`, `POLLING_WARMUP`, `UP`, `BACKOFF_RECENT`, `DOWN_STALE`, `STOPPED`. Тогда branch priority станет проверяемой, а не набором пересекающихся условий.
- Для polling completion не полагаться на `join()`: capture cause через `invokeOnCompletion`, отменять polling job в `finally`, и различать `CancellationException` от supervisor shutdown.
- Передавать в `buildBehaviourWithLongPolling` attempt-owned scope или хотя бы хранить `pollingJob` и отменять его при `stopAndJoin()`/`shutdown()`.
- Вынести ktgbotapi extension за маленький adapter (`TelegramLongPollingRunner`), чтобы тестировать supervisor без fragile static mocking top-level Kotlin functions.
- Во всех suspend helper-ах в `FrigateAnalyzerBot` добавить: `catch (e: CancellationException) { throw e }` перед `catch (e: Exception)`.

### Questions

- Должен ли `clean return` до `STABLE_THRESHOLD` считаться failure, а не success?
- Нужно ли health становиться `UP` через 60 секунд после успешного reconnect даже если до этого были failures? Финальный smoke говорит "да", текущая таблица говорит "нет".
- Supervisor должен владеть ktgbotapi polling job полностью, включая shutdown, или допускается default scope библиотеки?
- Намеренно ли `OwnerActivatedEvent.chatId` перестаёт использоваться, несмотря на то что это самый точный источник chat id для регистрации owner-команд?

---

## ollama-kimi (Kimi K2.6)

### Critical Issues

**[C1] `mockkStatic` teardown без `try/finally` — ломает тест-сюит**

План, Task 4.1, `TelegramBotSupervisorTest.kt:604-627`: `mockkStatic(...)` вызывается в теле теста, а `unmockkStatic(...)` — только в конце теста. Если assertion упадёт (или произойдёт исключение до `unmockkStatic`), статический мок останется "висеть" в JVM для всех последующих тестов класса. *Как исправить:* обернуть в `try { ... } finally { unmockkStatic(...) }` или `@AfterEach`.

**[C2] `currentBackoff` удваивается после КАЖДОЙ итерации — даже после `success`**

Дизайн §4 / план Task 3.3, `runSupervised`:
```kotlin
onAttemptEnded(success = true, ...)
delay(currentBackoff.toMillis())          // 5 s (сброшено в onAttemptEnded)
currentBackoff = nextBackoff(currentBackoff) // → 10 s (!)
```

В `onAttemptEnded(success=true)` backoff сбрасывается до `INITIAL_BACKOFF`, но затем `nextBackoff()` увеличивает его до 10s **перед** следующей итерацией. *Как исправить:* `currentBackoff = nextBackoff(...)` должен выполняться только при `success = false`.

**[C3] Health state machine читает 8 `@Volatile` полей без атомарности — возможен inconsistent snapshot**

Health thread читает поля в произвольном порядке. Между чтениями supervisor может мутировать. Результат: `branch 6` (UP) вместо `branch 5` (OUT_OF_SERVICE). *Как исправить:* документировать как best-effort, либо `AtomicReference<SupervisorState>` (immutable data class).

**[C4] Clean return от `buildBehaviourWithLongPolling` при revoked token → бесконечный reconnect без `DOWN`**

Если Telegram отзовёт токен, `getMe()` может пройти, а long polling выйдет чисто (без exception) благодаря `runCatching` внутри ktgbotapi. Supervisor будет reconnect'иться с `INITIAL_BACKOFF = 5s`, health будет flap'ать `UP → OUT_OF_SERVICE → UP`, и `/actuator/health` никогда не уйдёт в sustained `DOWN`. *Как исправить:* добавить счётчик чистых возвратов или ограничение на количество reconnect'ов без stable polling.

### Concerns

**[W1] Почему `@PostConstruct`, а не `ApplicationReadyEvent` как у `WatchRecordsTask`?** — `@PostConstruct` запускается раньше, чем `ApplicationReadyEvent` listener'ы.

**[W2] `registerOwnerCommandsIfPossible` меняет семантику `onOwnerActivated`** — если `event.chatId` не совпадает с `properties.owner`, поведение изменится.

**[W3] `lastPollingStartAt` не сбрасывается при failure** — после краша остаётся равным времени начала предыдущего polling'а; health может попасть в branch 6 на основе старого значения.

**[W4] Отсутствие `isTestProfile()` guard в `TelegramBotSupervisor.start()`** — supervisor полагается только на `@ConditionalOnProperty(telegram.enabled=true)`, без явного guard'а как в `WatchRecordsTask`.

**[W5] Подход к сбросу backoff: `STABLE_THRESHOLD` (time) vs `SUCCESSES_TO_RESET_BACKOFF` (count)** — `WatchRecordsTask` сбрасывает после 5 успешных итераций, supervisor — после ≥60s. Разные философии.

### Suggestions

**[S1]** Упаковать `mockkStatic` в `try/finally` или `@AfterEach`.
**[S2]** Перенести `currentBackoff = nextBackoff(currentBackoff)` только в failure-ветку.
**[S3]** Добавить `isTestProfile()` guard в `start()`.
**[S4]** Задокументировать "best-effort" nature health snapshot.
**[S5]** Уточнить сигнатуру `buildBehaviourWithLongPolling` перед мокированием.

### Questions

**[Q1]** `Dispatcher`: почему `Dispatchers.Default`, а не `Dispatchers.IO.limitedParallelism(1)`?
**[Q2]** Почему `delay(currentBackoff)` происходит даже после clean return?
**[Q3]** `tickingClock` в тесте: безопасен ли `origin.plusMillis(testScheduler.currentTime)`?
**[Q4]** `Task 9.4` предлагает удалить `kotlinx.coroutines.launch` как unused — это ошибка? `eventScope.launch` всё ещё его использует.
**[Q5]** Как `registerOwnerCommandsIfPossible` обрабатывает ситуацию, когда `properties.owner` пуст?

---

## ollama-minimax (MiniMax M2.7)

### Critical Issues (must-fix, prevent compilation)

**1. Task 4 Step 1 — несовпадение mock-сигнатуры.** Тест использует 6 `any()` для `buildBehaviourWithLongPolling`, но в ktgbotapi 33.1.0 функция имеет **три** параметра (`scope`, `onFirstMetaInformation`, `block`). Тест не скомпилируется.

**2. Task 4 Step 1 — `Job.completeExceptionally` не существует.** Этот метод принадлежит `CompletableJob`, не `Job`. Нужно привести к `CompletableJob` или использовать `CompletableDeferred`.

### Concerns

**3. Task 5 — нет теста "registration failures do not trigger backoff".** Spec §8.1 описывает этот тест, но план содержит только cancellation test.

**4. Spec §5 branch 6 + §4.3 — "gap" между T+STABLE_THRESHOLD и attempt end.** Branch 6 проверяет `now - lastPollingStartAt >= STABLE_THRESHOLD`, но `lastStableAt` устанавливается только в конце attempt → ~30s health показывает OUT_OF_SERVICE после пересечения threshold.

**5. Task 7 Step 3 — `shutdown()` не различает cancel и timeout.** Если `CancellationException` вылетает из `join()`, `withTimeoutOrNull` возвращает `null` → ложный лог "did not exit within 30s".

**6. Task 9 Step 1.3 — `botScope` removal.** Подтверждение: после cutover у `FrigateAnalyzerBot` остаётся только `eventScope`. Это корректно.

**7. Spec §10 — clean polling return, never DOWN.** Если lib проглатывает ошибку, supervisor считает success → health = UP. Mitigation "WARN log" недостаточна. Предложение: счётчик последовательных clean-возвратов, после N логировать ERROR.

### Questions

**Q1.** Верифицировать реальную сигнатуру `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0 перед Task 3 — это блокирует Tasks 3-5 если signature неверная.

**Q2.** `onOwnerActivated` visibility — package-private currently, no change needed.

---

## ollama-deepseek (DeepSeek-V4 Pro)

### Critical Issues

**1. `nextBackoff` затирает сброс `currentBackoff` из `onAttemptEnded`** (дизайн §4, план Task 3 Step 3.3).

В `runSupervised()` вызов `nextBackoff` находится после `delay`, в общей части цикла — он выполняется и для success, и для failure. Последствия:
- После success (clean return): `delay(5s)`, `nextBackoff` → 10s. Следующая итерация стартует с `currentBackoff=10s`.
- После stable-crash: `delay(5s)`, `nextBackoff` → 10s. Первый retry после stable crash ждёт **10s вместо 5s**.

*Исправление:* перенести `nextBackoff` внутрь catch-блока (только для failure path), либо добавить `currentBackoff = INITIAL_BACKOFF` после `nextBackoff` в success-ветке.

**2. Тест "registration failures do not trigger backoff" заявлен в дизайне (§8.1), но не реализован в плане.**

`registerDefaultCommands()` и `registerOwnerCommandsIfPossible()` не могут выбросить исключение наружу — они ловят всё внутри себя. Тест физически невозможно написать без изменения сигнатур. *Решение:* удалить тест из дизайна с пояснением или добавить тест, проверяющий, что supervisor продолжает работу.

**3. Отсутствует `@AfterEach` очистка `mockkStatic`** (план Task 4 Step 4.1).

Тест вызывает `mockkStatic(...)` и `unmockkStatic(...)` внутри тестового метода. Если ассерт упадёт до `unmockkStatic`, статический мок утекает в другие тесты. *Решение:* `@AfterEach` или `try/finally`.

### Concerns

**4. Silent failure: clean return от `buildBehaviourWithLongPolling().join()` не детектируется** (дизайн §10).

Бесконечный цикл с интервалом ~5s, health oscillates UP/OUT_OF_SERVICE. Mitigation "WARN log" недостаточна. *Предложение:* счётчик быстрых clean-возвратов, после N → форсировать DOWN.

**5. `computeHealth` читает `@Volatile` поля неатомарно** (дизайн §5, план Task 6 Step 6.4). Для health indicator'а приемлемо как best-effort, но стоит документировать.

**6. `onOwnerActivated` меняет поведение — теперь делает DB-запрос** (план Task 1 Step 1.6). Если owner был ACTIVE на момент события, но стал не-ACTIVE к моменту обработки — команды не зарегистрируются. План документирует это, но не обсуждает improvement vs regression.

**7. `lastStableAt` на success-пути выставляется безусловно, даже для 1ms попытки** (дизайн §4). Для failure-пути есть проверка `duration >= STABLE_THRESHOLD`, для success — нет. Если polling возвращается мгновенно, `lastStableAt` обновится → branch 4 (stale→DOWN) никогда не сработает.

### Suggestions

**8.** Унифицировать `clock` usage: `Instant.now(clock)` вместо `clock.instant()`.
**9.** Добавить тест на чередование «успех↔провал».
**10.** Рассмотреть `Dispatchers.IO.limitedParallelism(1)` вместо `Dispatchers.Default` для supervisor scope.
**11.** Добавить `startupAt` в `baseBuilder()` — уже сделано в плане.
**12.** В плане Task 2 Step 2.1: верифицировать импорты `org.springframework.boot.health.contributor.Status` и `Health` в Spring Boot 4.0.6.

### Questions

1. **`@PostConstruct` vs `ApplicationReadyEvent`?** Если будущий рефакторинг заменит — нужен `isTestProfile()` guard. Это осознанное решение?
2. **Почему `HEALTH_STALENESS = 5 min`, а не 2 min?** Покрывает ~4.5 backoff-циклов. Не слишком ли консервативно? Может, 3 минуты достаточно?
3. **Должен ли supervisor логировать каждый успешный reconnect?** Сейчас логируется только WARN/ERROR.
4. **Почему в `onOwnerActivated` не использован собственный `CoroutineScope` из supervisor'а?**

---

---

## ccs-executor (glm-5.1)

### Critical Issues

**C-1. `nextBackoff` после успеха удваивает `INITIAL_BACKOFF` — первый сбой после стабильного запуска начинается с 10с, не 5с** (дизайн §4, план Task 3.3). После `onAttemptEnded(success=true)` сбрасывает `currentBackoff = 5s`, затем `delay(5s)` ждёт корректно, но `nextBackoff` удваивает до 10s перед следующей итерацией. *Исправление:* перенести `nextBackoff` внутрь ветки failure.

**C-2. `CancellationException` проглатывается внутри `registerDefaultCommands` и `registerOwnerCommandsIfPossible`.** Оба метода используют `catch (e: Exception)`, который в Kotlin coroutines ловит и `CancellationException`. Если supervisor отменён во время `setMyCommands()`, отмена проглатывается. *Исправление:*
```kotlin
catch (e: CancellationException) { throw e }
catch (e: Exception) { logger.warn(e) { ... } }
```

### Concerns

**W-1. Окно двойного polling между Task 7 и Task 9.** После Task 7 `TelegramBotSupervisor.start()` (`@PostConstruct`) реально запускает polling. До Task 9 `FrigateAnalyzerBot.start()` тоже запускает polling. При запуске между этими тасками два параллельных long-polling вызовут 409 Conflict от Telegram API. *Рекомендация:* объединить Task 7 и Task 9 в атомарный шаг, либо в Task 7 не запускать polling до удаления старого.

**W-2. `@Volatile` поля обновляются неатомарно** — `onAttemptEnded` пишет три поля подряд без синхронизации, `computeHealth` (другой поток) может видеть частичное состояние. Branch priority спасает от катастрофы, но стоит документировать.

**W-3. `mockkStatic` для `buildBehaviourWithLongPolling` — хрупко и нет cleanup-гарантии.** Точность сигнатуры + утечка мока при failure теста до `unmockkStatic`. *Рекомендация:* `@AfterEach { unmockkStatic(...) }` + верифицировать сигнатуру.

**W-4. Семантическое изменение `onOwnerActivated` — gratuitous DB round-trip.** `OwnerActivatedEvent` уже содержит `chatId`, и событие publish'ится AFTER транзакции. Замена `registerOwnerCommands(event.chatId)` на `registerOwnerCommandsIfPossible()` добавляет лишний DB-запрос. *Рекомендация:* оставить `registerOwnerCommands(event.chatId)` в `onOwnerActivated`. `registerOwnerCommandsIfPossible` нужен только supervisor'у (у него нет `chatId` при reconnect).

**W-5. `@Profile("!test")` на `TelegramBotHealthIndicator` избыточно при наличии `@ConditionalOnProperty`.** В test-профиле `telegram.enabled=false` → `@ConditionalOnProperty` уже предотвращает создание bean. `@Profile("!test")` — belt-and-suspenders, не вредно, но затуманивает причину.

**W-6. `tickingClock` не используется в Task 3 — латентная проблема для будущих изменений.** Если кто-то добавит тест с долгой попыткой в Task 3, фиксированные часы дадут 0ms duration → некорректный тест.

**W-7. Неявное разрешение `bot` внутри `with(context) { ... }` после рефакторинга `registerRoutes`.** После обёртки в `with(context)`, неявный receiver — `BehaviourContext`. `bot` разрешается в `this.bot` (= `context.bot`), а не `this@FrigateAnalyzerBot.bot`. Они совпадают, но неочевидно при чтении. *Рекомендация:* однострочный комментарий.

### Suggestions

**S-1.** Объединить Task 7 и Task 9 для атомарности cutover.
**S-2.** `stopAndJoin()` мог бы занулять `supervisorJob` — explicit intent. `WatchRecordsTask` не зануляет, consistent OK.
**S-3.** Добавить тест на «success → failure → success → failure» — выявил бы C-1.
**S-4.** Логировать `buildBehaviourWithLongPolling` clean return с длительностью attempt:
```kotlin
logger.warn { "buildBehaviourWithLongPolling returned cleanly after ${Duration.between(attemptStart, clock.instant())}; reconnecting" }
```

### Questions

**Q-1.** Точная сигнатура `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0 — проверить количество параметров и имя класса (`BuildBehaviourWithLongPollingKt`?).

**Q-2.** Как ведёт себя `buildBehaviourWithLongPolling().join()` при exception внутри polling? Возможны 3 сценария (runCatching swallow / Job fail clean / `CancellationException` rethrow). Необходимо верифицировать.

**Q-3.** `FrigateAnalyzerApplicationTests.actuatorHealth()` — как именно отключается Telegram (test profile / property absence)? От этого зависит, redundant ли `@Profile("!test")` или необходим.

---

## Cross-reviewer convergence summary

Замечания, которые подняли несколько ревьюеров — они приоритетны:

| Issue | codex | ccs/glm | kimi | minimax | deepseek |
|---|---|---|---|---|---|
| `nextBackoff` clobbers reset after success/stable-fail | — | C-1 | C2 | — | C1 |
| `mockkStatic` без teardown (try/finally или @AfterEach) | — | W-3 | C1 | — | C3 |
| Clean return → silent failure / never DOWN | C-Concern | (implicit) | C4 | C7 | C4 |
| `@Volatile` неатомарный snapshot | C-Concern | W-2 | C3 | — | C5 |
| Missing test "registration failures don't trigger backoff" | C-Concern | — | — | C3 | C2 |
| `onOwnerActivated` behavior change (DB re-lookup) | C-Concern | W-4 | W2 | — | C6 |
| `lastPollingStartAt` не reset на failure | C-Concern | — | W3 | — | (implicit) |
| `@PostConstruct` vs `ApplicationReadyEvent` | — | — | W1 | — | Q1 |
| `isTestProfile()` guard missing | — | — | W4/S3 | — | (implicit) |
| `Dispatchers.Default` vs `IO.limitedParallelism(1)` | — | — | Q1 | — | S10 |
| `Job.join()` не пробрасывает cause | C-Critical | Q-2 | — | C2 | — |
| Mock signature mismatch (6 vs 3 params) | C-Critical | W-3/Q-1 | S5 | C1 | — |
| `mockkStatic` class name wrong (`BuildBehaviourWithLongPollingKt` vs `BehaviourBuildersKt`) | C-Critical | Q-1 | — | — | — |
| Supervisor не владеет polling job (default scope в ktgbotapi) | C-Critical | — | — | — | — |
| Health branch ordering broken (UP unreachable) | C-Critical | — | — | — | — |
| `lastStableAt` set unconditionally on success (even 1ms) | (implicit) | — | — | — | C7 |
| `shutdown()` ложный лог при cancellation от join() | — | — | — | C5 | — |
| Cancellation проглатывается registerDefaultCommands/IfPossible | C-Critical | C-2 | — | — | — |
| **Окно двойного polling между Task 7 и Task 9** | — | W-1/S-1 | — | — | — |
| `@Profile("!test")` redundant к `@ConditionalOnProperty` | — | W-5 | — | — | — |
| `tickingClock` не используется в Task 3 | — | W-6 | — | — | — |
| Неявное разрешение `bot` после `with(context)` рефакторинга | — | W-7 | — | — | — |

**Codex поднял несколько критических unique issues** (broken branch ordering, Job.join() cause, supervisor doesn't own polling job, mockkStatic class name). **CCS/GLM поднял уникальное W-1 — окно двойного polling между Task 7 и Task 9** — это критический pitfall в плане. Эти находки самые серьёзные.
