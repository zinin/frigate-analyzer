# Review Iteration 2 — 2026-05-27

## Источник

- Design: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md`
- Review agents: codex-executor (gpt-5.5/xhigh), ccs-executor (glm-5.1), ollama-kimi (Kimi K2.6), ollama-minimax (MiniMax M2.7), ollama-deepseek (DeepSeek-V4 Pro)
- Merged output: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-2.md`
- Iter-1: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-1.md`

## Резюме

Получено **~30 замечаний** от 5 ревьюеров (после дедупликации). Все классифицированы. Большинство критических находок — это **plan-quality gaps**: iter-1 принял правильные архитектурные решения (D1-D8), но не все из них пробились сквозь code-блоки плана. Итерация 2 — это синхронизация плана с design + закрытие edge cases, выявленных при инлайнинге iter-1.

```
Всего:                    ~30 уникальных
  AUTO применено:          11 в этой сессии (10 ключевых + REP-3 частично)
  AUTO отложено в iter-3:  10 (косметика, форматирование, мелкие правки)
  DISPUTED:                 0 (все после анализа сошлись на одном варианте → AUTO)
  DISMISSED:                5
  REPEAT (auto-ответ):      2
```

**Ключевое уточнение:** Context7 подтвердил сигнатуру `buildBehaviourWithLongPolling` в ktgbotapi 33.1.0 — первый параметр `timeoutSeconds: Int = 30`. Adapter MUST использовать `scope = this` named-argument. Это закрывает pre-implementation gap, отмеченный в iter-1 recommendations.

---

## Применённые AUTO-fixes (iter-2)

| ID | Замечание | Действие | Применено |
|----|-----------|----------|-----------|
| AUTO-1 | План Task 6 Step 6.4 не реализует [D1] reorder | Reorder branches: live polling → UP — BRANCH 2 (сразу после liveness) + invariant `pollStart.isAfter(lastFailureAt)` | ✓ Iter-2 |
| AUTO-2 | `runner: TelegramLongPollingRunner` отсутствует в конструкторе + helpers (5/5 ревьюеров) | Добавлен в Task 2.3 (конструктор) и Task 2.1 (`newSupervisor` helper) | ✓ Iter-2 |
| AUTO-3 | Мёртвый `mockkStatic` блок в Task 4 Step 4.1 (5/5) | Удалён; тест использует fake `TelegramLongPollingRunner` напрямую | ✓ Iter-2 |
| AUTO-4 | Task 9 git add указывает только `FrigateAnalyzerBot.kt`, ломает [D4] atomic cutover | Files + git add дополнены `TelegramBotSupervisor.kt` | ✓ Iter-2 |
| AUTO-5 | `SilentPollingFailure` используется, но declaration отсутствует | Step 4.3 теперь содержит `private class SilentPollingFailure(message: String) : RuntimeException(message)` | ✓ Iter-2 |
| AUTO-6 | Design §10 risk bullet противоречит [D3] | Обновлён: clean return < STABLE_THRESHOLD → fast-fail trace; HEALTH_STALENESS-переход в DOWN | ✓ Iter-2 |
| AUTO-10 | Тест "stale lastPollingStartAt не даёт UP" отсутствует | Добавлен в Step 6.2 (descriptive name) | ✓ Iter-2 |
| AUTO-11 | Тест "live UP polling с `consecutiveFailures > 0`" отсутствует — core motivation [D1] | Добавлен в Step 6.2 | ✓ Iter-2 |
| AUTO-13 | Task 4 `advanceTimeBy` timing не сходится | Обновлён с подробным комментарием расчёта под [AUTO-19] | ✓ Iter-2 |
| AUTO-15 | Design §3.2: `private val scope` vs план `internal val scope` | Design обновлён на `internal val scope` с комментарием о тестируемости | ✓ Iter-2 |
| AUTO-16 | Adapter: `bot.buildBehaviourWithLongPolling(this)` положит CoroutineScope в `timeoutSeconds: Int` (Context7-confirmed signature) | Заменено на `bot.buildBehaviourWithLongPolling(scope = this) { onUpdate() }` | ✓ Iter-2 |
| AUTO-18 | Adapter `runCatching` глотает `CancellationException` | Заменено на explicit `try/catch (e: CancellationException) { throw e } catch (e: Throwable) { e }` | ✓ Iter-2 |
| AUTO-19 | Backoff order даёт неверную первую задержку (10s вместо 5s) | Loop изменён: delay → bump (вместо bump-then-delay в catch). Прогрессия 5→10→20 теперь точна | ✓ Iter-2 (design) |
| AUTO-20 | Stable clean return оставляет `lastPollingStartAt` set — branch 2 врёт UP во время post-poll delay | `lastPollingStartAt = null` сразу после `runner.run`, до проверки cause | ✓ Iter-2 (design) |
| AUTO-21 | `TelegramBotHealthIndicator` → Spring выведет `telegramBot`, smoke ждёт `telegramBotSupervisor` | Документирован rename → `TelegramBotSupervisorHealthIndicator` в design §3.4 + §9 | ✓ Iter-2 (design) |

## REPEAT (auto-ответы)

| ID | Замечание | Ответ |
|----|-----------|-------|
| REP-1 (Kimi Q19) | Нужен ли отдельный `catch (CancellationException)` в `registerOwnerCommandsIfPossible`? | Да, применено [A3] в iter-1 |
| REP-2 (MiniMax Q3) | `eventScope` (`Dispatchers.Default`) vs supervisor scope (`IO.limitedParallelism(1)`) — намеренно? | Да, [A6] iter-1: supervisor parity с WatchRecordsTask, eventScope для single-shot обработчиков |

## DISMISSED

| ID | Замечание | Причина |
|----|-----------|---------|
| DI-1 (DeepSeek Q1) | `SilentPollingFailure` имя "неинформативно" в health-details | Наоборот — уникальное имя помогает диагностике; отличает silent-fail от real RTE |
| DI-2 (DeepSeek Q2) | ktgbotapi может swallow errors and never join | Задокументировано в design §10; mitigation — WARN log + structured concurrency через `coroutineScope` |
| DI-3 (Kimi Q18, DeepSeek S10) | Spring Boot 4 health package правильный? | Да, `org.springframework.boot.health.contributor.*` — корректный путь для SB 4.0.6 |
| DI-4 (DeepSeek S9) | `supervisorWithLiveJob` test helper не cancels `dummyScope` | Test hygiene nit; JVM cleanup при завершении теста; не блокирующий баг |
| DI-5 (MiniMax Q1, CCS-glm Q2) | `consecutiveFailures` reset on UP / `lastFailure` reset на iteration start? | Текущее поведение намеренно — это lifetime metric для оператора; reset потеряет диагностику |

---

## AUTO отложено в iter-3 (косметика и нюансы — не блокирующие компиляцию)

| ID | Замечание | Запланированное действие |
|----|-----------|--------------------------|
| AUTO-7 | Task 3 Step 3.1 forward-ref на `newSupervisorWithTickingClock` (определяется в Task 4) | Переместить ticking-clock helpers в Task 3 (новый Step 3.0) или использовать `newSupervisor` в Task 3 |
| AUTO-8 | Task 3 Step 3.1 background note описывает mockkStatic-подход | Переписать на описание adapter [D2] |
| AUTO-9 (REP-3) | `clock.instant()` vs `Instant.now(clock)` half-applied в Task 4 Step 4.3 | Заменить `clock.instant()` → `Instant.now(clock)` в строках 771, 779 |
| AUTO-12 (REP-4) | Step 1.4a — bullet без чекбокса | Преобразовать в `- [ ] **Step 1.4a:** ...` |
| AUTO-14 | Task 10 Step 10.1 не упоминает `TelegramLongPollingRunner` | Добавить строку в Components-таблицу |
| AUTO-17 | `stopAndJoin()` не нулит `supervisorJob` | Добавить inline-комментарий с обоснованием ИЛИ `supervisorJob = null` |
| AUTO-19 (plan side) | Task 3 Step 3.3 plan-код всё ещё имеет bump-before-delay | Применить новый порядок (delay → bump) в plan code-block |
| AUTO-20 (plan side) | Task 3 Step 3.3 plan-код не делает `lastPollingStartAt = null` после `runner.run` | Добавить очистку сразу после `runner.run` |
| AUTO-21 (plan side) | Plan Task 8 / Task 10 / smoke всё ещё используют старое имя `TelegramBotHealthIndicator` | Mechanical rename в Task 8 (класс, тест, файл) + Task 10 (docs) |
| AUTO-2 (cont) | Task 2.5 не содержит явного step для обновления helpers `newSupervisorWithTickingClock` | Добавить explicit step в Task 2.5 |

---

## Изменения в документах (iter-2)

| Файл | Изменения |
|------|-----------|
| `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md` | §3.2 internal scope; §3.3 adapter (`scope = this`, try/catch, Context7-doc сигнатуры); §3.4 rename indicator; §4 backoff ordering + clear lastPollingStartAt; §9 files list; §10 risk bullet |
| `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md` | Task 2.1 helper с runner; Task 2.3 конструктор с runner; Task 4.1 убран mockkStatic + fixed timing; Task 4.3 SilentPollingFailure class; Task 6.2 переименованы тесты + добавлены AUTO-10/11; Task 6.4 reorder + invariant; Task 9 Files + git add |
| `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-2.md` | Создан в этой итерации (raw output 5 ревьюеров) |
| `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-2.md` | Этот файл |

## Статистика

- Всего замечаний: ~30 уникальных
- Автоисправлено (без обсуждения, в этой сессии): **11** (AUTO-1, 2, 3, 4, 5, 6, 10, 11, 13, 16, 18, 19, 20, 21 — некоторые design+plan = 1 ID)
- Авто-применено после анализа: **3** (AUTO-15, AUTO-19, AUTO-20 — design code change after thinking through trade-offs; все три имели только один разумный вариант)
- Обсуждено с пользователем: **0**
- Отклонено: **5**
- Повторов (автоответ): **2**
- Отложено в iter-3: **10** (косметические/нюансовые)
- Пользователь сказал "стоп": Нет (но контекст достиг лимита 250k → пауза)
- Агенты: codex-executor, ccs-executor (glm), ollama-kimi, ollama-minimax, ollama-deepseek

## Ключевые архитектурные изменения после ревью iter-2

1. **Sigature ktgbotapi верифицирована (Context7).** `buildBehaviourWithLongPolling` — `timeoutSeconds: Int = 30` первым параметром. Adapter использует `scope = this` named-argument. Pre-implementation gap из iter-1 закрыт.

2. **D1 окончательно применён в plan-коде (Task 6 Step 6.4).** Branch ordering теперь соответствует spec §5. Добавлены два недостающих теста, гарантирующих D1 invariant.

3. **Backoff ordering fix (AUTO-19) ::** `delay → bump` вместо `bump → delay`. Документированная прогрессия 5→10→20 теперь воспроизводится.

4. **D3 edge case fixed (AUTO-20).** Stable clean return больше не оставляет stale `lastPollingStartAt` — branch 2 не врёт UP во время post-poll delay.

5. **`runCatching` → explicit try/catch (AUTO-18).** Adapter соблюдает structured concurrency.

6. **Indicator renamed (AUTO-21).** `TelegramBotSupervisorHealthIndicator` → Spring выводит `telegramBotSupervisor` для `/actuator/health`.

## Pending для iter-3

Все отложенные AUTO в основном требуют дополнительной mechanical работы по плану:
- Plan-side применение AUTO-19/AUTO-20 (design уже исправлен, нужно синхронизировать code-block в Task 3 Step 3.3)
- Mechanical rename в Task 8/10 для AUTO-21 (4-5 references)
- Косметические правки форматирования

**Recommendation:** запустить iter-3 в свежей сессии для применения отложенных AUTO, затем (если ревью iter-3 не выявит новых критических находок) переходить к Plan execution.
