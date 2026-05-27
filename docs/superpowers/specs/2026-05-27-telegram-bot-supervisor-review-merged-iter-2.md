# Merged Design Review — Iteration 2 (2026-05-27)

Reviewers:
- codex-executor (gpt-5.5, xhigh) — ✓
- ccs-executor (glm-5.1) — ✓ (доставлен позже остальных)
- ollama-executor (ollama-kimi, Kimi K2.6) — ✓
- ollama-executor (ollama-minimax, MiniMax M2.7) — ✓
- ollama-executor (ollama-deepseek, DeepSeek-V4 Pro) — ✓

---

## codex-executor (gpt-5.5, xhigh)

### Critical Issues

- **[Task 9] Atomic-cutover [D4] частично нарушен.** Шаг 9.0a меняет `TelegramBotSupervisor.kt`, но в секции `Files` и `git add` указан только `FrigateAnalyzerBot.kt` (`plan:1474`). Если выполнить буквально — закоммитится удаление старого `start()` при оставшемся stub-`start()` у супервайзера → коммит **no-poller**.

- **План после [D2] не полностью согласован с `TelegramLongPollingRunner`.** В scaffold-конструкторе supervisor-а нет `runner`, но Task 3 уже вызывает `runner.run` (`plan:561`); helpers тоже не принимают runner. В Task 4 создаётся fake runner, но не передаётся в supervisor; ниже остался реальный блок с `bot.buildBehaviourWithLongPolling(...)`, `Job.completeExceptionally`, `unmockkStatic` (`plan:673-711`). Не просто мусор — тест не скомпилируется и не проверит adapter.

- **[Task 6] всё ещё реализует старый порядок `computeHealth`.** Note `plan:906-915` говорит, что branch 2 должен быть «live stable polling → UP» с invariant `lastPollingStartAt > lastFailureAt`, но код ниже сначала проверяет startup failure и только потом UP без invariant (`plan:1053-1097`). Это заново ломает [D1]: cold start / recovery может остаться OUT_OF_SERVICE / DOWN при реально живом polling.

- **Новая дырка в state machine после [D3]: stable clean return** оставляет `lastPollingStartAt` установленным и **не пишет** `lastFailureAt` (`design:214-218`, `design:293`). Пока loop спит перед reconnect, branch 2 может вернуть UP, хотя polling уже завершён. Нужен явный `pollingActive` flag или очистка / разделение `lastPollingStartAt` при выходе из `runner.run`.

- **Backoff order даёт неверную первую задержку.** Сейчас `currentBackoff = nextBackoff(currentBackoff)` выполняется до `delay` (`plan:587-593`, `design:204-208`), поэтому первая ошибка ждёт 10s, а не 5s. Противоречит тестам / константам `5s → 10s → 20s` и ломает stable-reset expectation (`plan:707`).

- **`SilentPollingFailure` используется в плане (`plan:766`), но Task 4 не добавляет сам класс.** Также нет теста на fast clean return `< STABLE_THRESHOLD` — именно он должен доказать `SilentPollingFailure`, рост backoff и eventual DOWN.

### Concerns

- **Design §10 всё ещё описывает старый риск:** clean return считается success, reconnect с `INITIAL_BACKOFF`, revoked token «never flip to DOWN» (`design:458-464`). Противоречит [D3] и `SilentPollingFailure`.

- **Task 3 использует `newSupervisorWithTickingClock(testScheduler)` до того, как helper вводится в Task 4** (`plan:498`, `plan:804`). Task order не self-contained.

- **Имя actuator component.** `TelegramBotHealthIndicator` Spring обычно превратит в `telegramBot`, а не `telegramBotSupervisor`. План и smoke ожидают `telegramBotSupervisor` (`plan:1530`, `plan:1572`). Если имя важно — переименовать класс в `TelegramBotSupervisorHealthIndicator`.

- **ktgbotapi 33.1.0:** фактический JVM class для top-level функции — `BehaviourBuildersKt`, не `BuildBehaviourWithLongPollingKt`; source signature возвращает `Job` с дополнительными параметрами с defaults. Adapter-call `bot.buildBehaviourWithLongPolling(this) { ... }` выглядит компилируемым, но любые старые static-mock references хрупкие. Источник: `BehaviourBuilders.kt` v33.1.0 в ktgbotapi.

### Suggestions

- Переписать Task 2.5 / 3 / 4 вокруг runner как единой сквозной правки: constructor `runner: TelegramLongPollingRunner`, helpers принимают fake runner, никаких `buildBehaviourWithLongPolling` imports в supervisor-тестах.

- В `runSupervised` зафиксировать порядок backoff так: выбрать `delayFor` (current value) → обновить health state → `delay(delayFor)` → затем bump для следующего failure. Совпадёт с `WatchRecordsTask`.

- Добавить tests: live stable polling с `lastStableAt == null`; recovery со stale `consecutiveFailures`; stale `lastPollingStartAt` после более нового `lastFailureAt`; fast clean return → `SilentPollingFailure`; stable clean return НЕ репортит UP пока poller не активен.

### Questions

- Нужно ли actuator component именно `telegramBotSupervisor`, или допустим `telegramBot`?
- Принимается ли ограничение, что health без heartbeat не увидит часть post-start ошибок, которые ktgbotapi может логировать / переваривать внутри long-polling loop, пока job остаётся active?

---

## ollama-executor (Kimi K2.6)

### Critical Issues

**1. Plan Task 6 — `computeHealth` полностью отменяет исправление [D1] по порядку веток.**
Spec §5 (таблица и prose [D1]) требует: ветка 2 (live stable polling → UP) проверяется **сразу после** ветки 1 (supervisor not active) и **до** всех startup/backoff веток. Plan Task 6 Step 6.4 ставит polling-stable ветку **последней** (BRANCH 6), после веток stale/backoff. Это воспроизводит исходный баг.

**2. Plan Task 6 — пропущен инвариант `lastPollingStartAt > lastFailureAt` в polling-stable ветке.**
Spec ветка 2 включает guard `(lastFailureAt == null || lastPollingStartAt > lastFailureAt)`. Plan Task 6 Step 6.4 (`BRANCH 6`) его не содержит.

**3. Plan Task 2 — в конструкторе `TelegramBotSupervisor` отсутствует `runner: TelegramLongPollingRunner`.**
Spec §3.2 говорит, что `runner` — первый параметр конструктора. Plan Task 2 Step 2.3 создаёт класс только с `bot`, `frigateAnalyzerBot`, `clock`, `dispatcher`. Task 3 Step 3.3 (`runner.run { … }`) вызовет unresolved reference. Хелперы `newSupervisor` / `newSupervisorWithTickingClock` тоже не обновлены.

**4. Plan Task 4 — тест stable-attempt использует недостаточный `advanceTimeBy`.**
В тесте Step 4.1: `advanceTimeBy(5_000 + 10_000 + 61_000 + 1)` = 76 s. Но фактические задержки между attempts (учитывая bump-before-delay) = 10 + 20 + 40, и runner.run в attempt 3 завершится только в t=91s. Тест упадёт.

### Concerns

**5. Spec §4 — design code block `runSupervised` устарел относительно [D3].** Блок (строки 172–210) не содержит `if (attemptDuration < STABLE_THRESHOLD) { currentBackoff = nextBackoff(…) }` в success-пути.

**6. Task 3 Step 3.1 Background note ссылается на `mockkStatic`.** Полностью устарело после [D2].

**7. Task 4 Step 4.1 — в тесте оставлен мёртвый `mockkStatic`/`coEvery` блок** (помечен как "historical context; remove during implementation", но не удалён явным step-ом).

**8. Путаница с нумерацией веток в Task 6.** Spec после [D1] переименовал polling-stable в ветку 2; план использует старую нумерацию.

**9. `KtgBotApiLongPollingRunner` использует `runCatching`, который ловит `CancellationException`.** Нарушает конвенции structured concurrency.

**10. Несогласованность `Instant.now(clock)` vs `clock.instant()`.** Plan Task 4 Step 4.3 `onAttemptEnded` использует обе формы.

### Suggestions

**11. Переписать Task 6 Step 6.4.** Переставить polling-stable ветку на место branch 2, добавить guard `(lastFailureAt == null || lastPollingStartAt > lastFailureAt)`.

**12. Добавить `runner` в Task 2.** Включить в конструктор + обновить хелперы.

**13. Исправить timeline в Task 4 stable-attempt test.** `advanceTimeBy` должен покрывать 10+20+61 минимум, плюс runner.run завершается в t=91s. Исправить комментарий "5 + 10 + 20 = 35 s" в Task 3 Step 3.1.

**14. Удалить устаревшие `mockkStatic` reference'ы** как явный step.

**15. Заменить `runCatching` в `KtgBotApiLongPollingRunner`** на try/catch с явным rethrow CancellationException.

**16. Отказаться от числовых branch-номеров в именах тестов.** Использовать описательные.

### Questions

**17.** Верифицирована ли фактическая сигнатура `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0?
**18.** Какой точно пакет Spring Boot health? (`org.springframework.boot.health.contributor.Health` vs `org.springframework.boot.actuate.health`)
**19.** Нужен ли `registerOwnerCommandsIfPossible` отдельный `catch (CancellationException)` если `registerOwnerCommands` уже делает то же самое?

---

## ollama-executor (MiniMax M2.7)

### Critical Issues

**[C1] Branch ordering mismatch between spec §5 and plan §6 `computeHealth` [D1].**
Spec §5 требует ветку 2 «live stable polling → UP» первой после liveness. Plan §6 ставит её ПОСЛЕ backoff-веток (4, 5). Когда `consecutiveFailures > 0` и `lastStableAt` свежий, branch 4 не сработает, branch 5 вернёт `OUT_OF_SERVICE`, branch 6 (UP) недостижим — даже после 90с стабильного polling.

**[C2] Task 4 тест всё ещё использует `mockkStatic` несмотря на намерение [D2] устранить его.**
Plan Task 4 Step 4.1 (`plan:688–712`) оставляет mockkStatic блок "as historical context; remove during implementation". Это dead code, ссылающийся на хрупкое имя класса.

**[C3] Branch 2 в spec использует invariant `lastFailureAt`, plan-реализация его опускает.**
Spec §5 branch 2 требует `lastPollingStartAt != null && (lastFailureAt == null || lastPollingStartAt > lastFailureAt)`. Plan branch 6 опускает `lastFailureAt`-инвариант.

### Concerns

**[CO1] Spec §4 [D3] internal inconsistency about `nextBackoff` placement.** Design.md:250 говорит, что success path вызывает `nextBackoff`; design.md:195-197 [A1] note говорит НЕ вызывать.

**[CO2] `stopAndJoin()` оставляет `supervisorJob` non-null после cancel.** Расхождение с `shutdown()`.

**[CO3] Plan Task 6 test names используют plan-numbering, не spec-numbering.**

### Suggestions

**[S1]** Полностью удалить mockkStatic блок из Task 4 plan теста.
**[S2]** Добавить тест branch 2 invariant `lastPollingStartAt > lastFailureAt` — [D1] note упоминает его, но в Task 6 он не появляется.
**[S3]** Документировать, что `stopAndJoin()` намеренно не nullит `supervisorJob`.

### Questions

**[Q1]** Должен ли `consecutiveFailures` сбрасываться в 0 при live UP polling? Сейчас он сбрасывается только в `onAttemptEnded(success && duration >= STABLE_THRESHOLD)`.
**[Q2]** `shutdown()` ordering matches WatchRecordsTask.shutdown() pattern. Confirmed consistent.
**[Q3]** Task 9 final state: `eventScope` uses `Dispatchers.Default` while supervisor scope uses `IO.limitedParallelism(1)`. Intentional?

---

## ollama-executor (DeepSeek V4 Pro)

### Critical Issues

**1. [D1] не применено в коде `computeHealth` плана — ветка 2 (live polling → UP) на неверной позиции.**
Дизайн (§5, [D1]) требует ветку 2 «live stable polling → UP» сразу после liveness. План Task 6 Step 6.4 располагает её в **старом** порядке — последней (BRANCH 6).

**2. Конструктор `TelegramBotSupervisor` не принимает `runner` во всех code-блоках плана после Task 2.5.**
Task 2.5 добавляет адаптер, но Task 2/3/4/6/7 конструируют supervisor без параметра `runner`. `runSupervised()` в Task 3 использует `runner.run { ... }`. Конструктор должен быть обновлён явным шагом.

**3. Код тестов в Task 4 Step 4.1 содержит неудалённый `mockkStatic`/`unmockkStatic`.**
Hand-rolled fake `TelegramLongPollingRunner` соседствует с активным блоком `mockkStatic` и `bot.buildBehaviourWithLongPolling(any(), ...)`. "Historical context" не отменяет того, что это активный код в плане.

**4. `computeHealth` тесты (Task 6) не покрывают [D1]-инвариант `lastPollingStartAt > lastFailureAt`.**
Тест задаёт `consecutiveFailures = 0L` и не задаёт `lastFailureAt` (оставляя `null`). Обещанный в Task 6 тест "branch 2 stale lastPollingStartAt from a previous failure does not yield UP" **отсутствует** в code-блоках.

### Concerns

**5. `onAttemptEnded` — непоследовательное использование clock.**
Plan Step 4.3 mix-ит `Instant.now(clock)` и `clock.instant()`. [A9] требует `Instant.now(clock)` везде.

**6. `start()` идемпотентность: guard `supervisorJob != null`.** Тестам может понадобиться сброс. Стоит добавить `supervisorJob = null` в `stopAndJoin()`.

**7. Task 7 Step 7.1: `scope` visibility change не отражён в дизайне.**
План требует сменить `private val scope` на `internal val scope`. Дизайн (§3.2) показывает `private val scope`.

**8. `FrigateAnalyzerBot.registerDefaultCommands` не rethrows `CancellationException` явным step-ом с галочкой.**
Step 1.4a — отдельный bullet без маркера `[ ]`. Если пропустить — `CancellationException` от shutdown supervisor-а будет проглочен.

### Suggestions

**9. `supervisorWithLiveJob()` — добавить `dummyScope.cancel()` для гигиены тестов.**

**10. Проверить пакет `Health.Builder()` в Spring Boot 4.0.6.**

**11. Task 10: документация `telegram.md` не упоминает `TelegramLongPollingRunner`.** Стоит добавить строку в Components-таблицу.

### Questions

**Q1:** `SilentPollingFailure` — private класс; имя в health-details (`SilentPollingFailure: ...`) неинформативно. Заменить на `RuntimeException` с тем же сообщением?

**Q2:** `KtgBotApiLongPollingRunner.run()` использует `coroutineScope { ... }` + `runCatching`. Если ktgbotapi внутренне перезапускает polling, получим бесконечное ожидание на `pollingJob.join()`. Есть ли в ktgbotapi 33.1.0 такая логика?

**Q3:** Task 9 Step 9.5 — supervisor тесты с virtual time. Стоит явно проверить, что `./gradlew :frigate-analyzer-telegram:test` не зависает на минуты из-за забытого реального `delay`.

---

## ccs-executor (glm-5.1)

### Critical Issues

**C1.** План Task 6 Step 6.4 — `computeHealth` НЕ следует решению [D1]. UP-ветка на позиции 6, после startup-grace/backoff. Cold start с `lastStableAt == null` + polling 60+ секунд вернёт `OUT_OF_SERVICE`, а не `UP`. Recovery с `consecutiveFailures > 0` — `OUT_OF_SERVICE` через branch 5.

**C2.** План Task 4 Step 4.1 — тест содержит **ОБА** подхода (adapter fake + mockkStatic с 6 `any()` + `Job.completeExceptionally` + `unmockkStatic`). Не скомпилируется. Метод `completeExceptionally` существует только на `CompletableJob`, не на `Job`.

**C3.** Конструктор `TelegramBotSupervisor` (Task 2 Step 2.3) не содержит `runner: TelegramLongPollingRunner`. Design §3.2 показывает первым параметром. Task 2.5 не содержит шага по обновлению. После Task 2.5 все тесты сломаются компиляцией.

**C4.** **Design §3.3 — `bot.buildBehaviourWithLongPolling(this)` — несоответствие позиционного параметра.** По данным Context7, сигнатура в ktgbotapi 33.1.0:
```kotlin
fun TelegramBot.buildBehaviourWithLongPolling(
    timeoutSeconds: Int = 30,
    autoDisableWebhooks: Boolean = true,
    mediaGroupsDebounceTimeMillis: Long = 1000L,
    scope: CoroutineScope = ...,
    ...
    block: suspend BehaviourContext.() -> Unit
): Job
```
Позиционный `this` (тип `CoroutineScope`) попадёт на `timeoutSeconds: Int` — type mismatch. **Исправление:**
```kotlin
val pollingJob = bot.buildBehaviourWithLongPolling(scope = this) { onUpdate() }
```

**C5.** Design §3.3 — `runCatching` в адаптере проглатывает `CancellationException`. Хотя `if (cause != null) throw cause` потом re-throws, нарушает structured concurrency. Заменить на explicit `try/catch` с rethrow CancellationException.

### Concerns

**W1.** Task 3 Step 3.1 forward-references `newSupervisorWithTickingClock` (определяется в Task 4).
**W2.** Task 3 Step 3.3 background note всё ещё описывает mockkStatic-подход.
**W3.** Import `buildBehaviourWithLongPolling` в supervisor — dead code после [D2].
**W4.** `clock.instant()` vs `Instant.now(clock)` непоследовательно в failure/success branches `onAttemptEnded`.
**W5.** Отсутствует тест "stale `lastPollingStartAt` от предыдущей failure не даёт UP".
**W6.** Нет теста "live stable polling с `consecutiveFailures > 0` даёт UP" — core motivation [D1].

### Suggestions

**S1.** Переставить `computeHealth` ветки в реализации (live polling → UP на позицию 2), добавить тесты W5 и W6.
**S2.** Полностью переписать Task 4 Step 4.1 без mockkStatic.
**S3.** Добавить явный шаг в Task 2.5 для обновления конструктора и хелперов.
**S4.** Использовать именованный параметр `scope = this` в adapter.
**S5.** Заменить `runCatching` на explicit `try/catch` в adapter.

### Questions

**Q1.** Верифицирована ли сигнатура `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0? Context7 подтверждает 4-й параметр `scope: CoroutineScope` — рекомендуется scratch-compile до начала реализации.

**Q2.** Должен ли `runSupervised` сбрасывать `lastFailure` / `lastFailureAt` в начале каждой итерации? Сейчас [A5] сбрасывает только `lastPollingStartAt`. Если предыдущая попытка упала, поля сохраняются — может «прилипнуть».
