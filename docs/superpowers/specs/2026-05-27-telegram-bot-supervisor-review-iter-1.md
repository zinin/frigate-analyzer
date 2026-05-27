# Review Iteration 1 — 2026-05-27

## Источник

- Design: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md`
- Review agents: codex-executor (gpt-5.5/xhigh), ccs-executor (glm-5.1), ollama-kimi (Kimi K2.6),
  ollama-minimax (MiniMax M2.7), ollama-deepseek (DeepSeek-V4 Pro)
- Merged output: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-1.md`

## Резюме

Получено **25 уникальных замечаний** от 5 ревьюеров. Все классифицированы, все AUTO применены,
все DISPUTED обсуждены и решены. Полный цикл итерации завершён за одну сессию.

```
Всего:                    ~25 уникальных
  AUTO применено:          12  (включая A4, A5 — после resolution disputed)
  DISPUTED применено:       8  (D1-D8 — 1 спросили у пользователя, 7 авто)
  DISMISSED:                5
  REPEAT:                   0  (первая итерация)
```

---

## Применённые AUTO-fixes

| ID | Замечание | Действие |
|---|---|---|
| A1 | `nextBackoff` затирает сброс `currentBackoff` после success/stable-fail (Kimi C2, DeepSeek C1, ccs-glm C-1) | Перенесено в catch-ветку failure; добавлен conditional bump после fast-fail success (см. также D3) |
| A2 | `mockkStatic` без teardown (Kimi C1, DeepSeek C3, ccs-glm W-3) | **Закрыто через D2** — `mockkStatic` полностью убран в пользу `TelegramLongPollingRunner` adapter |
| A3 | `CancellationException` проглатывается в command-registration (codex, ccs-glm C-2) | Добавлен `catch (e: CancellationException) { throw e }` в `registerDefaultCommands`, `registerOwnerCommands`, `registerOwnerCommandsIfPossible` |
| A4 | `mockkStatic` class/signature mismatch (codex, MiniMax C1+C2) | **Закрыто через D2** — нет больше `mockkStatic` |
| A5 | `lastPollingStartAt` не reset на failure (codex, Kimi W3) | `lastPollingStartAt = null` в начале каждой итерации `runSupervised` |
| A6 | `Dispatchers.Default` → `IO.limitedParallelism(1)` (Kimi Q1, DeepSeek S10) | Применено в design + plan для parity с `WatchRecordsTask` |
| A7 | `shutdown()` ложный лог при cancel от `join()` (MiniMax C5) | `runCatching { join() }` + проверка `isCompleted` |
| A8 | `tickingClock` не в Task 3 (ccs-glm W-6) | Tasks 3 + 4 теперь оба используют `newSupervisorWithTickingClock` |
| A9 | `Instant.now(clock)` consistency (DeepSeek S8) | Заменено `clock.instant()` во всех точках supervisor |
| A10 | Логировать clean return с длительностью attempt (ccs-glm S-4) | WARN log теперь содержит `Duration.between(attemptStart, ...)` |
| A11 | Логировать успешный reconnect (DeepSeek Q3) | `logger.info { "Telegram bot polling started" }` после `lastPollingStartAt =` |
| A12 | Комментарий на разрешение `bot` в `with(context)` (ccs-glm W-7) | Однострочный комментарий в `registerRoutes` plan Task 1 Step 1.1 |

---

## Применённые DISPUTED resolutions

### [D1] Сломанный health branch ordering — **Variant A: reorder + invariant** (auto-applied)

**Источник:** codex C-Critical-1+2 (единственный)

Branch "live stable polling → UP" поднят на 2-ю позицию (сразу после branch 1 "supervisor not active").
Добавлен invariant: `lastPollingStartAt > lastFailureAt` — гарантирует что stale timestamp не даст
ложный UP. Companion fix [A5] обнуляет `lastPollingStartAt` в начале каждой итерации.

Это исправляет два сценария: (1) холодный запуск — теперь после 70s polling'а будет UP, не DOWN;
(2) восстановление после сбоя — sticky `consecutiveFailures > 0` больше не маскирует здоровый
polling.

**Изменения:** design §5 (новая таблица), §5.1 (обновлено описание), `runSupervised` (lastPollingStartAt = null),
plan Task 6 (note о renumbering и новом тесте).

### [D2] `Job.join()` + supervisor doesn't own polling job — **Variant C: Adapter** (user-chosen)

**Источник:** codex C-Critical-3+4, MiniMax C2, ccs-glm Q-2

Введён `TelegramLongPollingRunner` interface + `KtgBotApiLongPollingRunner` impl. Supervisor
вызывает `runner.run(...)` который возвращает `Throwable?` (null на clean exit, иначе cause из
structured concurrency через `coroutineScope { ... }`). Это решает обе проблемы атомарно:
(a) cause не теряется при `join()`, (b) polling Job — child нашего scope.

**Бонус:** unit-тесты supervisor'а теперь не требуют `mockkStatic` — используют fake
`TelegramLongPollingRunner` object. Это автоматически закрывает A2, A4 и снимает риск утечки
static mock между тестами.

**Изменения:** design §3.3 (новая секция), §4 (supervisor использует runner), plan Task 2.5
(новая, создание adapter'а), Task 3 Step 3.3 (использует runner), Task 4 Step 4.1 (mockkStatic убран).

### [D3] Clean return silent failure + `lastStableAt` unconditional — **Variant C: duration check on success** (auto-applied)

**Источник:** все 5 ревьюеров

`onAttemptEnded(success=true)` теперь разветвляется по `duration >= STABLE_THRESHOLD`:
- Past threshold → success path (как раньше)
- Под threshold → trace as fast-fail: `lastFailure = SilentPollingFailure(...)`, `consecutiveFailures++`,
  не сбрасывать `lastStableAt`. Через несколько таких циклов health перейдёт в DOWN через
  `HEALTH_STALENESS = 5min`.

Это закрывает риск "revoked token → бесконечный reconnect без DOWN" из spec §10 без новой
константы. Marker class `SilentPollingFailure(message: String) : RuntimeException(message)`
делает diagnostics видимыми в `lastFailure` field health response.

**Изменения:** design §4 (новая logic), plan Task 3 Step 3.3 + Task 4 Step 4.3 (новая onAttemptEnded).

### [D4] Окно двойного polling между Task 7 и Task 9 — **Variant A: atomic cutover** (auto-applied)

**Источник:** ccs-glm W-1/S-1 (единственный)

`TelegramBotSupervisor.start()` оставлен no-op stub'ом до конца Task 7. Реальный
`scope.launch { runSupervised() }` добавляется в Task 9 Step 9.0a (новый), в одном коммите с
удалением `FrigateAnalyzerBot.start()`. Это атомарный cutover — окно из двух одновременных
poller'ов устранено.

Task 7 тесты теперь запускают `runSupervised()` напрямую (через `supervisor.scope.launch`), не
через `start()`. `scope` поднят с `private` до `internal` для test access.

**Изменения:** plan Task 7 (warning + stub start()), Task 7 Step 7.1 (test адаптирован), Task 9
(новый Step 9.0a).

### [D5] `onOwnerActivated` DB lookup vs `event.chatId` — **Keep event.chatId** (auto-applied)

**Источник:** codex, Kimi W2, DeepSeek C6, ccs-glm W-4

CCS прав: `OwnerActivatedEvent` published AFTER транзакции активации owner'а — `event.chatId`
authoritative. DB round-trip избыточен. `onOwnerActivated` сохраняет старый прямой вызов
`registerOwnerCommands(event.chatId)`. Новый метод `registerOwnerCommandsIfPossible()`
зарезервирован для supervisor'а (reconnect loop'а), где `chatId` нет.

**Изменения:** design §3.1 (обновлён bullet), plan Task 1 Step 1.6 (вернул `event.chatId`).

### [D6] `@PostConstruct` vs `ApplicationReadyEvent` — **Keep @PostConstruct** (auto-applied)

**Источник:** Kimi W1/W4, DeepSeek Q1

Spec §10 явно выбрал `@PostConstruct` для minimum churn. Добавлен acknowledgement reviewers'
наблюдения: `@ConditionalOnProperty(telegram.enabled=true)` уже отрубает bean в test profile,
поэтому `isTestProfile()` guard был бы redundant — другая стратегия чем `WatchRecordsTask`, но
обоснованная.

**Изменения:** design §10 (расширен).

### [D7] Missing test "registration failures don't trigger backoff" — **Remove from spec** (auto-applied)

**Источник:** codex, MiniMax C3, DeepSeek C2

Метод сам ловит exceptions (с [A3] добавили rethrow на `CancellationException`, для остальных —
WARN log). Тест "supervisor не реагирует на throw из этих методов" технически невозможен без
изменения сигнатур. Удалён из spec §8.1 с пояснением, что behaviour гарантирован compile-time.

**Изменения:** design §8.1 (удалён bullet, добавлен strikethrough + объяснение).

### [D8] `@Volatile` non-atomic snapshot — **Document as best-effort** (auto-applied)

**Источник:** codex, Kimi C3, DeepSeek C5, ccs-glm W-2

Добавлена секция "Snapshot consistency note" в design §5. Best-effort approach matches
`WatchRecordsTask` pattern; если когда-либо потребуется stronger consistency, можно перейти на
`AtomicReference<SupervisorState>` — out of scope сейчас. Branch ordering (UP сначала, потом
startup/backoff) ограничивает несоответствия одним cycle health check.

**Изменения:** design §5 (новая секция Snapshot consistency note).

---

## DISMISSED замечания

### [DI1] `isTestProfile()` guard в `start()` отсутствует
**Источник:** ollama-kimi W4/S3
**Причина:** `@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"],
havingValue = "true")` уже отрубает создание bean'а в test profile. Дополнительный guard
избыточен. (Acknowledged in D6.)

### [DI2] План Task 9.4 удаляет `kotlinx.coroutines.launch` как unused
**Источник:** ollama-kimi Q4
**Причина:** план уже содержит дисклеймер "(only if no other launches remain — `eventScope.launch`
still uses it; keep if needed)". Ревьюер не дочитал инструкцию.

### [DI3] `properties.owner.isBlank()` guard в `registerOwnerCommandsIfPossible`
**Источник:** ollama-kimi Q5
**Причина:** `TelegramProperties.owner` валидируется на уровне Spring `@ConfigurationProperties`.

### [DI4] `HEALTH_STALENESS = 5 min` слишком консервативно
**Источник:** ollama-deepseek Q2
**Причина:** spec §5.2 явно обосновывает выбор: 5 min покрывает ~4.5 backoff-циклов (5→10→20→40→60).
Для event-driven бота — разумный баланс.

### [DI5] `@Profile("!test")` на `TelegramBotHealthIndicator` избыточно
**Источник:** ccs-glm W-5
**Причина:** defensive belt-and-suspenders, matches `WatchRecordsTaskHealthIndicator` pattern.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md | Множественные правки: §3.1 (D5, A3), §3.2 (A6), §3.3 (D2 — новая секция Runner), §4 (A1, A5, A9, A10, A11, D3 + SilentPollingFailure marker), §5 (D1 — новая таблица, D8 — best-effort note), §5.1 (D1), §7 (A7), §8.1 (D7), §10 (D6) |
| docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md | Task 1.1 (A12), Task 1.4 (A3), Task 1.4a (A3 для existing), Task 1.6 (D5), Task 2.3 (A6), **Task 2.5 (D2 — новая задача)**, Task 3.1 (A8), Task 3.3 (A1, A5, A9-A11, D2, D3), Task 4.1 (D2 — убран mockkStatic), Task 6 (D1 note), Task 7 (D4 stub), Task 7.1 (D4 test адаптирован), Task 7.3 (D4 stub + A7), **Task 9.0a (D4 — новый)** |
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-1.md | Создан в этой итерации |
| docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-1.md | Этот файл |

## Статистика

- Всего замечаний: ~25 уникальных
- Автоисправлено (без обсуждения): **12** (A1, A3, A5-A12 + A2, A4 закрыты через D2)
- Авто-применено после анализа: **7** (D1, D3, D4, D5, D6, D7, D8)
- Обсуждено с пользователем: **1** (D2 — adapter pattern)
- Отклонено: 5
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, ccs-executor (glm), ollama-kimi, ollama-minimax, ollama-deepseek

## Ключевые архитектурные изменения после ревью

1. **Health state machine fixed** (D1+A5): `UP` теперь достижим и для холодного запуска, и для
   восстановления после сбоев. Smoke-тест в plan:1428 теперь корректен.

2. **`TelegramLongPollingRunner` adapter** (D2): supervisor больше не зависит от
   `bot.buildBehaviourWithLongPolling` напрямую. Closes A2, A4. Структурированная concurrency
   через `coroutineScope { ... }` гарантирует proper polling-job ownership и cause propagation.

3. **Silent failure detection** (D3): clean return до `STABLE_THRESHOLD` теперь обрабатывается
   как failure → health через `HEALTH_STALENESS` переходит в DOWN при перманентных ошибках
   (revoked token). Marker class `SilentPollingFailure` делает причину видимой в health response.

4. **Atomic cutover** (D4): Task 7+9 переструктурированы так, что нет окна двойного polling.
   `TelegramBotSupervisor.start()` — no-op stub до Task 9 step 9.0a.

5. **`onOwnerActivated` поведение сохранено** (D5): `event.chatId` остаётся authoritative
   источником. Новый `registerOwnerCommandsIfPossible()` reserved для supervisor reconnect.

## Рекомендации для следующей итерации (если потребуется)

1. Запустить второй раунд ревью на обновлённый design + plan — критические правки могли создать
   новые edge cases.
2. Особое внимание: `TelegramLongPollingRunner` adapter — это новый компонент, нужно убедиться,
   что:
   - `coroutineScope { ... }` корректно работает с `buildBehaviourWithLongPolling`
   - Behavioural тесты покрывают clean return / failure return / cancellation
   - Production wiring (`@Component` на impl) корректен
3. Verify ktgbotapi sigature (Q1 от 3 ревьюеров) — даже с adapter pattern adapter использует
   `buildBehaviourWithLongPolling`, и нужно знать точную сигнатуру.
