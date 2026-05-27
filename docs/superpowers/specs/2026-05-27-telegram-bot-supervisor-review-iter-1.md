# Review Iteration 1 — 2026-05-27 14:15

## Источник

- Design: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md`
- Review agents: codex-executor (gpt-5.5/xhigh), ccs-executor (glm-5.1), ollama-kimi (Kimi K2.6),
  ollama-minimax (MiniMax M2.7), ollama-deepseek (DeepSeek-V4 Pro)
- Merged output: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-1.md`

## Резюме

Получено **25 уникальных замечаний** от 5 ревьюеров. Несколько находок — **критические архитектурные
проблемы**, требующие настоящего обсуждения, а не автоматического исправления. Учитывая объём и
взаимосвязанность правок (каждая обычно требует синхронных изменений в `design + plan`), фазы
auto-fix и disputed-discussion **отложены до следующей сессии** — этот файл фиксирует
классификацию, чтобы следующая итерация могла продолжить с того же места.

## Классификация

```
Всего: ~25 уникальных замечаний
  REPEAT (автоответ):    0   (первая итерация)
  AUTO (одно решение):  12   — отложены, см. ниже
  DISPUTED (trade-off):  8   — отложены, см. ниже
  DISMISSED:             5   — задокументированы
```

---

## AUTO — авто-исправления (отложены, требуют правок в design + plan)

### [A1] `nextBackoff` затирает сброс `currentBackoff` после success/stable-fail

**Источник:** ollama-kimi C2, ollama-deepseek C1, ccs-glm C-1 (3 ревьюера)

**Проблема:** в `runSupervised()` после `onAttemptEnded(success=true)` сбрасывает `currentBackoff = 5s`, затем
`delay(5s)` корректно, но `currentBackoff = nextBackoff(currentBackoff)` удваивает до 10s **перед
следующей итерацией**. Если следующая попытка упадёт, первый delay будет 10s вместо документированных 5s.

**Решение:** перенести `currentBackoff = nextBackoff(currentBackoff)` внутрь ветки `success = false` в
`onAttemptEnded`, либо в `catch` блок `runSupervised`.

**Файлы:** design §4 (code block), plan Task 3 Step 3.3, plan Task 4.

### [A2] `mockkStatic` без cleanup — утечка между тестами

**Источник:** ollama-kimi C1, ollama-deepseek C3, ccs-glm W-3 (3 ревьюера)

**Проблема:** тест в plan Task 4 Step 4.1 вызывает `mockkStatic(...)` и `unmockkStatic(...)` внутри
тела теста. При падении assertion до `unmockkStatic` статический мок утекает в последующие тесты JVM.

**Решение:** `@AfterEach { unmockkStatic("...BehaviourBuildersKt") }` либо `try { ... } finally {
unmockkStatic(...) }`.

**Файлы:** plan Task 4 Step 4.1.

### [A3] `CancellationException` проглатывается в command-registration

**Источник:** codex C-Critical, ccs-glm C-2 (2 ревьюера)

**Проблема:** `registerDefaultCommands`, `registerOwnerCommands` (FrigateAnalyzerBot.kt:311, :332) и
новый `registerOwnerCommandsIfPossible` (plan:120) используют `catch (e: Exception)`, который в
Kotlin coroutines ловит и `CancellationException`. Противоречит clean-cancellation contract'у
supervisor'а.

**Решение:** во всех трёх методах:
```kotlin
catch (e: CancellationException) { throw e }
catch (e: Exception) { logger.warn(e) { ... } }
```

**Файлы:** design §3.1, plan Task 1 Step 1.4 (новый метод), plan нужно явно отметить правку существующих методов.

### [A4] `mockkStatic` class name + signature mismatch

**Источник:** codex C-Critical, ollama-minimax C1+C2, ollama-kimi S5, ccs-glm Q-1 (4 ревьюера)

**Проблема:**
1. План указывает `dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt`,
   но реальный класс называется `BehaviourBuildersKt` (по codex), нужна верификация.
2. План использует 6 `any()` matcher'ов, но функция в ktgbotapi 33.1.0 имеет 3 параметра
   (`scope`, `onFirstMetaInformation`, `block`).
3. План использует `Job.completeExceptionally(...)`, но этот метод принадлежит `CompletableJob`,
   не `Job` — тест не скомпилируется.

**Решение:** перед реализацией Task 3-5 верифицировать сигнатуру `buildBehaviourWithLongPolling` в
ktgbotapi 33.1.0 (через декомпиляцию JAR или GitHub-источники), затем переписать mock-инструкции в
plan Task 3.3 / Task 4.1.

**Файлы:** plan Task 3 Step 3.3, plan Task 4 Step 4.1.

**Связь с DISPUTED:** если в [D2] будет выбран adapter pattern (Codex S-4), этот fix станет
ненужным (mock не понадобится).

### [A5] `lastPollingStartAt` не сбрасывается перед новой попыткой

**Источник:** codex C-Concern, ollama-kimi W3

**Проблема:** после краша `lastPollingStartAt` остаётся равным времени начала предыдущего polling'а.
Race-window между крашом и `consecutiveFailures++` может дать false `UP` в branch 6.

**Решение:** в начале каждой итерации `runSupervised` устанавливать `lastPollingStartAt = null` до
бутстрапа.

**Связь с DISPUTED:** актуально только после фикса [D1] (branch ordering).

**Файлы:** design §4, plan Task 3 Step 3.3.

### [A6] `Dispatchers.Default` → `Dispatchers.IO.limitedParallelism(1)`

**Источник:** ollama-kimi Q1, ollama-deepseek S10

**Проблема:** long-polling — I/O-bound. `WatchRecordsTask` использует `IO.limitedParallelism(1)`.
Supervisor использует `Default` без обоснования.

**Решение:** заменить на `Dispatchers.IO.limitedParallelism(1)` для parity и явного выражения
single-threaded инварианта.

**Файлы:** design §3.2, plan Task 2 Step 2.3.

### [A7] `shutdown()` ложный лог при cancellation от `join()`

**Источник:** ollama-minimax C5

**Проблема:** если `CancellationException` вылетает из `supervisorJob.join()`, `withTimeoutOrNull`
возвращает `null`, и логируется "did not exit within 30s", хотя shutdown был чистым.

**Решение:** мелкая правка — проверять `supervisorJob?.isCompleted` после `join()` или
оборачивать `join()` в `runCatching` внутри `withTimeoutOrNull`.

**Файлы:** design §7, plan Task 7 Step 7.3.

### [A8] `tickingClock` не используется в Task 3

**Источник:** ccs-glm W-6

**Проблема:** Task 3 использует фиксированный `Clock`. Будущие тесты с длинными попытками будут
получать `duration = 0ms`.

**Решение:** в Task 3 переключиться на `newSupervisorWithTickingClock(testScheduler)` сразу, не
ждать Task 4.

**Файлы:** plan Task 3 Step 3.1.

### [A9] `Instant.now(clock)` consistency

**Источник:** ollama-deepseek S8

**Проблема:** supervisor использует `clock.instant()`, `WatchRecordsTask` — `Instant.now(clock)`.

**Решение:** унифицировать стиль через `Instant.now(clock)` (идиоматичнее).

**Файлы:** design §4 (code block), plan Task 3 Step 3.3.

### [A10] Логировать clean return с длительностью attempt

**Источник:** ccs-glm S-4

**Проблема:** существующий WARN "buildBehaviourWithLongPolling returned without exception" не даёт
оператору понять, был ли это мгновенный возврат (баг библиотеки) или нормальный disconnect
после часов работы.

**Решение:** в WARN добавить `${Duration.between(attemptStart, clock.instant())}`.

**Файлы:** design §4, plan Task 3 Step 3.3.

### [A11] Логировать успешный reconnect

**Источник:** ollama-deepseek Q3

**Проблема:** при перезапуске после серии ERROR-ов нет явного "Bot reconnected successfully" в логах.

**Решение:** добавить `logger.info { "Telegram bot polling started" }` после `lastPollingStartAt = ...`.

**Файлы:** design §4, plan Task 3 Step 3.3.

### [A12] Комментарий на разрешение `bot` после `with(context)` в `registerRoutes`

**Источник:** ccs-glm W-7

**Проблема:** после рефакторинга `registerRoutes` в обёртку `with(context) { ... }`, `bot` внутри
разрешается в `BehaviourContext.bot`, а не `FrigateAnalyzerBot.bot`. Они совпадают, но неочевидно.

**Решение:** добавить однострочный комментарий в `registerRoutes`.

**Файлы:** plan Task 1 Step 1.1.

---

## DISPUTED — требует обсуждения (отложены)

### [D1] ⚠️ Сломанный health branch ordering — `UP` практически недостижим

**Источник:** codex C-Critical-1+2 (только codex)

**Суть:** в таблице priority веток (design §5):
- Branch 2/3 (`lastStableAt == null`) идут **перед** branch 6 (`lastPollingStartAt >= STABLE_THRESHOLD`)
- Branch 4/5 (`consecutiveFailures > 0`) тоже идут **перед** branch 6

**Последствия:**
1. **Первый запуск:** через 70s polling'а `lastStableAt` всё ещё `null` (обновляется только в конце
   attempt). Branch 3 фиксирует "connecting...". Через 2 min STARTUP_GRACE истекает → branch 2 → DOWN.
   `UP` никогда не достигается на холодном запуске.
2. **После сбоя + восстановления:** `consecutiveFailures > 0` (никогда не сбрасывается в success-ветке,
   которая срабатывает только при clean return). Branch 4/5 фиксируют OUT_OF_SERVICE/DOWN. Smoke-тест
   в plan:1428 утверждает "UP через ~70s", но таблица даёт OUT_OF_SERVICE.

**Варианты:**
- A: Поменять порядок веток — branch 6 раньше branches 2-5
- B: Периодический watcher coroutine внутри `runSupervised`, обновляет `lastStableAt` каждые
  STABLE_THRESHOLD секунд работающего polling'а
- C: Явная state machine (CONNECTING/POLLING_WARMUP/UP/BACKOFF_RECENT/DOWN_STALE/STOPPED) —
  более крупный рефакторинг

### [D2] ⚠️ `Job.join()` не пробрасывает cause + supervisor не владеет polling job

**Источник:** codex C-Critical-3+4, ollama-minimax C2, ccs-glm Q-2

**Суть:** две связанные проблемы:
1. `bot.buildBehaviourWithLongPolling { ... }.join()` ждёт завершения Job, но **не пробрасывает**
   exception завершённой Job. Supervisor увидит провал как success.
2. `buildBehaviourWithLongPolling` принимает отдельный `CoroutineScope` параметр с default provider.
   Возвращаемая polling job **не обязательно** child of supervisor. Утечка poller'а, конфликты
   `getUpdates`, дубли обработчиков.

**Варианты:**
- A: Передать `scope = this@TelegramBotSupervisor.scope` (или новый child scope для attempt) +
  обернуть в `try { join(); checkCancelledOrFailed(this) }` (deferred cause capture)
- B: Использовать `CompletableDeferred` + `invokeOnCompletion { cause -> deferred.complete... }`
  для capture причины
- C: Создать adapter `TelegramLongPollingRunner` поверх ktgbotapi (Codex S-4) — изолирует API,
  упрощает тесты (не нужен `mockkStatic`)

### [D3] ⚠️ Clean return silent failure + `lastStableAt` unconditional on success

**Источник:** все 5 ревьюеров (ollama-kimi C4, codex C-Concern, ollama-minimax C7, ollama-deepseek C4+C7, ccs-glm implicit)

**Суть:** spec §10 признаёт риск: ktgbotapi `runCatching` глотает ошибки → `join()` возвращается
чисто → supervisor считает success. При revoked token бот бесконечно reconnect'ится с
`INITIAL_BACKOFF`, health flap'ает UP↔OUT_OF_SERVICE, никогда не уходит в sustained DOWN.
DeepSeek дополнительно: `lastStableAt` выставляется безусловно даже для 1ms успеха.

Mitigation в spec ("WARN log") **недостаточна** — оператор должен tail-ить логи.

**Варианты:**
- A: Принять как есть, документировать (текущий план)
- B: Счётчик последовательных clean-возвратов с `duration < FAST_RETURN_THRESHOLD`, после N
  принудительный DOWN
- C: На success-ветке `lastStableAt = clock.instant()` ставить только если `duration >= STABLE_THRESHOLD`,
  иначе обрабатывать как failure (consecutiveFailures++)
- D: B + C вместе

### [D4] ⚠️ Окно двойного polling между Task 7 и Task 9

**Источник:** ccs-glm W-1/S-1 (только ccs-glm — критический gap плана)

**Суть:** после Task 7 supervisor's `@PostConstruct` уже запускает polling. До Task 9 bot's
`@PostConstruct` тоже запускает polling. Если приложение поднимут между этими тасками, два
параллельных long-polling вызовут 409 Conflict от Telegram API.

**Варианты:**
- A: Объединить Task 7 и Task 9 в один атомарный шаг (cutover в одной правке)
- B: В Task 7 заполнить `@PostConstruct` supervisor пустой stub'ой, реальный запуск polling — в Task 9
- C: Принять риск (тесты не проявят, ручной smoke между тасками маловероятен)

### [D5] `onOwnerActivated`: DB re-lookup vs `event.chatId`

**Источник:** codex C-Concern, ollama-kimi W2, ollama-deepseek C6, ccs-glm W-4

**Суть:** план меняет `registerOwnerCommands(event.chatId)` на `registerOwnerCommandsIfPossible()`,
который делает `findByUsernameIgnoreCase` + status-check. CCS аргументирует: gratuitous DB
round-trip, `event.chatId` точнее.

**Варианты:**
- A: Оставить `registerOwnerCommands(event.chatId)` в `onOwnerActivated`. `registerOwnerCommandsIfPossible`
  использовать только в supervisor (где `chatId` неизвестен)
- B: Текущий план — единый метод `registerOwnerCommandsIfPossible()` (improvement в consistency
  ценой DB round-trip)

### [D6] `@PostConstruct` vs `ApplicationReadyEvent` + `isTestProfile()` guard

**Источник:** ollama-kimi W1/W4/S3, ollama-deepseek Q1

**Суть:** spec §10 явно выбирает `@PostConstruct` "для minimum churn", но `WatchRecordsTask`
использует `@EventListener(ApplicationReadyEvent::class)` с `isTestProfile()` guard.

**Варианты:**
- A: Оставить `@PostConstruct` (текущий план)
- B: Перейти на `@EventListener(ApplicationReadyEvent::class)` + `isTestProfile()` guard для
  parity с `WatchRecordsTask`

### [D7] Missing test "registration failures don't trigger backoff"

**Источник:** codex C-Concern, ollama-minimax C3, ollama-deepseek C2

**Суть:** spec §8.1 описывает тест, но plan его не содержит. `registerDefaultCommands` и
`registerOwnerCommandsIfPossible` ловят исключения внутри себя — невозможно проверить, что
supervisor не реагирует на их провал, без изменения сигнатур.

**Варианты:**
- A: Удалить тест из spec §8.1 с пояснением: "internal try/catch гарантирует поведение на уровне
  компиляции"
- B: Добавить тест, проверяющий: mock `registerDefaultCommands()` бросает (что технически
  невозможно при текущей сигнатуре, но можно через mockk relaxed), supervisor продолжает,
  `consecutiveFailures == 0`, `buildBehaviourWithLongPolling` вызывается

### [D8] `@Volatile` неатомарный snapshot в `computeHealth`

**Источник:** codex C-Concern, ollama-kimi C3, ollama-deepseek C5, ccs-glm W-2

**Суть:** `computeHealth` читает 8 `@Volatile` полей в произвольном порядке. Между чтениями
supervisor может мутировать state.

**Варианты:**
- A: Документировать как best-effort (минимум усилий, consistency с `WatchRecordsTask`)
- B: `AtomicReference<SupervisorState>` с immutable data class — точный snapshot, ломает consistency с
  `WatchRecordsTask`

---

## DISMISSED — отклонены с обоснованием

### [DI1] `isTestProfile()` guard в `start()` отсутствует
**Источник:** ollama-kimi W4/S3
**Причина:** `@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"],
havingValue = "true")` уже отрубает создание bean'а в test profile (где `telegram.enabled=false` по
умолчанию). Дополнительный guard избыточен.

### [DI2] План Task 9.4 удаляет `kotlinx.coroutines.launch` как unused
**Источник:** ollama-kimi Q4
**Причина:** план уже содержит дисклеймер "(only if no other launches remain — `eventScope.launch`
still uses it; keep if needed)" и явное "Remove only the ones that are actually unused. Leave any
import that is still referenced." Ревьюер не дочитал инструкцию.

### [DI3] `properties.owner.isBlank()` guard в `registerOwnerCommandsIfPossible`
**Источник:** ollama-kimi Q5
**Причина:** `TelegramProperties.owner` валидируется на уровне Spring `@ConfigurationProperties`.
Дополнительная защита внутри метода излишня; при пустом значении `findByUsernameIgnoreCase("")`
вернёт `null`, и метод корректно отработает (no-op).

### [DI4] `HEALTH_STALENESS = 5 min` слишком консервативно (предлагают 3 min)
**Источник:** ollama-deepseek Q2
**Причина:** spec §5.2 явно обосновывает выбор: 5 min покрывает ~4.5 полных backoff-циклов
(5→10→20→40→60). Для event-driven бота — разумный баланс.

### [DI5] `@Profile("!test")` на `TelegramBotHealthIndicator` избыточно с `@ConditionalOnProperty`
**Источник:** ccs-glm W-5
**Причина:** defensive belt-and-suspenders, matches `WatchRecordsTaskHealthIndicator` pattern.
Если кто-то случайно установит `telegram.enabled=true` в test profile, `@Profile("!test")`
предотвратит DOWN-aggregation в `actuatorHealth()`. Низкая стоимость.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md | Не изменялся в этой итерации |
| docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md | Не изменялся в этой итерации |
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-1.md | Создан |
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-1.md | Этот файл |

## Статистика

- Всего замечаний: ~25 уникальных
- Автоисправлено (без обсуждения): **0 — отложены**
- Авто-применено после анализа: 0
- Обсуждено с пользователем: **0 — отложены**
- Отклонено: 5
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет (контекст-ориентированное решение)
- Агенты: codex-executor, ccs-executor (glm), ollama-kimi, ollama-minimax, ollama-deepseek

## Рекомендации для следующей итерации

1. **Приоритет 1 (критические архитектурные):** обсудить [D1], [D2], [D3], [D4] — это блокеры,
   которые требуют пересмотра design.
2. **Приоритет 2 (поведенческие изменения):** обсудить [D5], [D6], [D7].
3. **Приоритет 3 (auto-fixes):** применить [A1]–[A12] после resolution disputed (некоторые
   зависят: например [A4] от [D2], [A5] от [D1]).
4. **Verification first:** перед любой работой над тестами — verify сигнатуру
   `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0 (через `javap`, декомпиляцию JAR или
   GitHub-источники). Этот шаг блокирует фиксы [A4] и зависимых.
