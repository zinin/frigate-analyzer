# Review Iteration 1 — 2026-05-23

## Источник

- Design: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md`
- Review agents: codex-executor (gpt-5.5, xhigh), ccs-executor (glm), ollama-executor (ollama-kimi), ollama-executor (ollama-deepseek)
- Skipped (in-flight, terminated at user request): ollama-executor (ollama-minimax)
- Merged output: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-review-merged-iter-1.md`

## Замечания

### Auto-fixes (26 — применены без обсуждения)

#### Compile blocker'ы тестов

- **[CRITICAL-1]** `CreateRecordingRequest` имена полей (ccs-glm/kimi/deepseek). Plan §5.1/§5.2 использовали `creationTimestamp/date/time` — реальные `fileCreationTimestamp/recordDate/recordTime`. **Действие:** переписать все вызовы с verified-сигнатурой; удалить «hypothesis» warning блок.
- **[CRITICAL-2]** `TelegramUserDto` поля (ccs-glm/kimi/deepseek). Plan §2.1 использовал `activatedAt/invitedAt/timezone` — реальные `status: UserStatus, creationTimestamp, activationTimestamp?`. **Действие:** переписать конструктор в тесте, добавить `status = UserStatus.ACTIVE`.
- **[CRITICAL-11]** `throwsMany`/`andThenMany` — не существуют в MockK 1.14.9 (ccs-glm/deepseek). **Действие:** заменить на `coAnswers { ... iterations counter ... }` паттерн во всех supervisor tests (Steps 7.2-7.6).
- **[CRITICAL-15]** `any()` внутри data class constructor — не matcher (kimi). **Действие:** заменить на `match<SimpleTextNotificationTask> { ... }`.

#### Compile / runtime correctness

- **[CRITICAL-3]** `chatId: Long?` vs `SimpleTextNotificationTask.chatId: Long` (codex/ccs/kimi). **Действие:** `owner.chatId ?: run { warn; return }` + комментарий.
- **[CRITICAL-13]** Mock `WatchService` + real `Path.register` — provider mismatch (codex). **Действие:** real `FileSystems.getDefault().newWatchService()` в tempdir.
- **[CRITICAL-16]** `scope` не отменяется в `shutdown()` — leak `limitedParallelism(1)` (ccs-glm). **Действие:** добавить `scope.cancel()` после `join()`.
- **[CRITICAL-18]** Конструкторный break `TelegramNotificationServiceImpl` — нет списка тестов (ccs/kimi/deepseek). **Действие:** Step 2.5 — явный список: `TelegramNotificationServiceImplTest`, `TelegramNotificationServiceImplSignalLossTest` + grep команда.
- **[CONCERN-13]** `RecordingFileDto` мок через `mockk { ... }` (kimi). **Действие:** real instance.

#### Robustness исходного кода

- **[CRITICAL-4]** `ensureWatchService` partial failure — orphan WatchService + поврежденный `registeredDirs` (codex/ccs/deepseek). **Действие:** try-catch + `ws.close()` + `watchService = null` + clear registeredDirs.
- **[CRITICAL-5]** `key.reset()` не в `finally` — выключение доставки событий при exception (codex). **Действие:** try/catch/finally в `runIteration`, key.reset() в finally, captured throwable rethrown.
- **[CRITICAL-14]** `supervisorJob` не `@Volatile` — data race (kimi). **Действие:** `@Volatile internal var supervisorJob`.
- **[CONCERN-5]** `Files.walk(start)` не закрывается — FD leak (codex). **Действие:** `Files.walk(start).use { ... }`.
- **[CONCERN-6]** `catch (Throwable)` проглатывает OOM/StackOverflow/LinkageError (codex). **Действие:** `catch (Exception)`.
- **[CONCERN-10]** `nextBackoff` двойной guard (ccs). **Действие:** упростить до `minOf(current.multipliedBy(2), MAX_BACKOFF)`.

#### Тестовый каркас

- **[CRITICAL-9]** Тесты вызывают `runSupervised()` напрямую — `supervisorJob` null → все active-state ветки в `computeHealth` непокрываемы (codex). **Действие:** `taskWithActiveJob()` helper + `internal var supervisorJob` для прямой подмены.
- **[CRITICAL-10]** Supervisor tests с infinite loop + `advanceUntilIdle()` зависнут (codex). **Действие:** mock loop self-terminates через `iterations` counter → `CancellationException` после N.
- **[CONCERN-14]** Нет теста на `@PreDestroy.shutdown()` (deepseek). **Действие:** новый Step 8.7.

#### Документация / стиль / план

- **[CRITICAL-17]** `SignalLossMonitorTask` НЕ использует supervisor scope — design вводил в заблуждение (kimi/deepseek). **Действие:** neutral note про новый паттерн.
- **[CONCERN-3]** Task 6 → 7 → 8 ordering (4/4 reviewers — топ по голосам). **Действие:** перенумеровать. Old: Task 7=supervisor tests, Task 8=ApplicationListener cleanup. New: Task 7=ApplicationListener cleanup, Task 8=supervisor tests. Ветка компилируется после каждого commit'а.
- **[CONCERN-8]** `docker exec touch` на `:ro` volume не сработает (codex). **Действие:** host-side touch в plan §11.2.
- **[CONCERN-11]** `@ConditionalOnProperty` не учитывает test profile (deepseek). **Действие:** добавить `@Profile("!test")`.
- **[CONCERN-15]** `ConcurrentHashMap.removeIf` thread-safety нюанс (deepseek). **Действие:** комментарий про single-writer invariant.
- **[CONCERN-17]** `consecutiveFailures: Int` overflow через 94 года (deepseek). **Действие:** `Long`.
- **[SUGGESTION-6]** `POLL_PERIOD_MS` top-level vs companion (deepseek). **Действие:** `private companion object`.

#### Дополнительные тесты

- **[SUGGESTION-5]** Mandatory tests scaffolding (codex). **Действие:** новая секция в Task 8 (Step 8.8) с 3 тестами на CRITICAL-4/5/8 follow-up.

---

### Disputed (9 кластеров — обсуждены с пользователем)

#### [D1] Self-heal механизм
> Источник: codex (CRITICAL-6, CRITICAL-12, QUESTION-2, QUESTION-3)
> **Решение пользователя:** Variant C — оставить как есть.
> **Обоснование:** `restart: unless-stopped` НЕ рестартует unhealthy контейнеры. Реализация автоматического self-healing требует либо `System.exit` (Variant A), либо autoheal sidecar (Variant B). Пользователь принял trade-off: health DOWN = пассивный сигнал, оператор мониторит вручную через `docker ps`/`curl /actuator/health`. Сценарий 2026-05-17 (30ч простоя) превращается в «оператор увидит unhealthy/ERROR логи раньше, чем сейчас, но всё ещё ручной восстановление».
> **Действие в design:** §1 и §2 переписаны — убран claim про автоматический restart, явное «self-healing вне scope». §4.3 — health DOWN = пассивный сигнал, не trigger. §2 non-goals — добавлен пункт с ссылкой на iter-1 §D1.
> **Действие в plan:** Goal/Architecture переписаны. Step 9.5 commit message обновлён. Step 10.1 (pipeline.md) — раздел «Health & self-healing» переименован в «Health (passive signal, no automatic restart)». PR body обновлён.

#### [D2] Empty poll = success? + Startup failure → DOWN
> Источник: codex (CRITICAL-7, CRITICAL-8, SUGGESTION-1)
> **Решение пользователя:** Variant A — расширенный state.
> **Обоснование:** Текущий дизайн считает empty poll успехом → persistent DB failure маскируется и не доходит до DOWN. Startup failure (registerAllDirs падает навсегда) — навсегда `OUT_OF_SERVICE`. Пользователь выбрал полный раздельный учёт: `lastSuccessfulPollAt` (heartbeat), `lastEventProcessedAt` (только при processed > 0), `lastSuccessfulRegistrationAt`, `consecutiveEventFailures`, `consecutiveRegistrationFailures`, `consecutiveFailures` (для backoff), `startupAt`. Транзишн-методы: `onPollCompleted(events, failures)`, `onRegistrationSuccess()`, `onRegistrationFailure(t)`.
> **Действие в design:** §4.1 state-поля расширены до 9 полей с детальным описанием. §5.2 — health state-таблица переписана как 8 веток в указанном порядке (см. §5.2.1 для транзишн-методов). Введены константы `STARTUP_GRACE=2m`, `STARTUP_FAILURE_THRESHOLD=5`.
> **Действие в plan:** Task 6 — добавлена ВАЖНАЯ заметка про §D2 (раздельный state + расширенный `IterationResult(eventsProcessed, eventFailures, lastCleanupAt)` + per-event exception catch внутри loop).

#### [D3] Stale-branch тест
> Источник: ccs-glm, kimi (CONCERN-1, QUESTION-11)
> **Решение:** Variant A — оставить test seam (уже применён в auto-fix CRITICAL-9).
> **Обоснование:** `internal var supervisorJob` + `taskWithActiveJob()` helper покрывают все 8 веток (D2-расширенной) state-таблицы напрямую и быстро через unit-тесты. `ApplicationContextRunner` (Variant B) — overkill, replaced Variant C (accept gap) поскольку test seam уже сделан.
> **Действие:** никаких новых правок (auto-fix CRITICAL-9 уже всё закрывает).

#### [D4] Snapshot consistency: AtomicReference vs @Volatile
> Источник: codex, deepseek (CONCERN-2)
> **Решение:** Variant B — принять eventual consistency.
> **Обоснование:** Single-user проект без external alerting (Prometheus/Alertmanager). Transient flapping на ~100мс невидим — следующий poll вернёт согласованную картину. `AtomicReference<SupervisorState>` оправдан только при active scraping; YAGNI.
> **Действие в design:** §4.3 — заметка про trade-off и условия пересмотра.

#### [D5] `runBlocking` в `StartupTelegramNotifier`
> Источник: все 4 reviewer'а (CONCERN-4, SUGGESTION-7)
> **Решение:** Variant A — `runBlocking + withTimeout(5s)`.
> **Обоснование:** На практике `sendOwnerMessage` вызывает только `notificationQueue.enqueue(...)` (без сетевого вызова — отправка асинхронная в queue-consumer'е), занимает микросекунды. `withTimeout` страхует от регрессии (если в будущем enqueue станет блокирующим). Сохраняет семантику «попытались отправить до ready», не теряет гарантию. Variant B (fire-and-forget) теряет гарантию, Variant C (@Async) возвращает к источнику оригинального бага.
> **Действие:** design §4.4 — `runBlocking + withTimeout(STARTUP_NOTIFICATION_TIMEOUT=5s)`. Plan §3.3 — то же + companion object с константой.

#### [D6] `@PostConstruct` vs `ApplicationReadyEvent`
> Источник: codex (CONCERN-7, QUESTION-4)
> **Решение:** Variant A — вернуть `@EventListener(ApplicationReadyEvent)`.
> **Обоснование:** Сохраняет проверенный порядок старта — `FirstTimeScanTask` (тоже из ApplicationReadyEvent) гарантированно успеет до того как watcher примет первое событие. Иначе один файл мог бы пройти и через scan, и через ENTRY_CREATE → дубль-recordings, exception в DB при unique constraint, шум в логах. Variant B (`@PostConstruct`) меняет lifecycle без выгоды для основной цели.
> **Действие:** design §4.1 — `@PostConstruct` заменён на `@EventListener(ApplicationReadyEvent::class)`. Plan §6.1 — то же + комментарий. Plan File Structure — `@EventListener` упомянут в Modify-таблице.

#### [D7] BuildProperties инъекция
> Источник: ccs-glm, kimi (CONCERN-12, SUGGESTION-2, QUESTION-8)
> **Решение:** Variant — `@ConditionalOnBean(GitProperties::class, BuildProperties::class)` (применено без вопроса — единственный адекватный variant в комбинации с уже принятым `@Profile("!test")`).
> **Обоснование:** Защищает от `NoSuchBeanDefinitionException` в минимальных context'ах. Проще `ObjectProvider`, потому что у нас бинарный сценарий «есть/нет», не runtime-обработка optional bean'а.
> **Действие:** design §4.4 — аннотации `@Component @Profile("!test") @ConditionalOnProperty(...) @ConditionalOnBean(GitProperties::class, BuildProperties::class)`. Plan §3.3 — то же.

#### [D8] `HEALTH_STALENESS=2m` vs `MAX_BACKOFF=60s`
> Источник: kimi, deepseek (CONCERN-16)
> **Решение:** оставить 2m (применено без вопроса — единственный адекватный variant в свете D2-расширения).
> **Обоснование:** С D2 staleness применяется к `lastEventProcessedAt` (обновляется ТОЛЬКО при processed > 0), а не к heartbeat'у poll'а. При потоке frigate-recordings (несколько камер пишут непрерывно) 2 минуты без обработанного события + растущий `consecutiveEventFailures` — достаточный сигнал. `MAX_BACKOFF` влияет на retry-частоту, не на staleness-окно. Если в production окажется слишком чувствительной — поднимем до 3m отдельной правкой.
> **Действие:** design §4.1 — заметка после блока констант.

#### [D9] `HealthIndicator` vs `ReactiveHealthIndicator`
> Источник: deepseek (SUGGESTION-3)
> **Решение:** Variant A — упростить до sync `HealthIndicator` (применено без вопроса).
> **Обоснование:** `computeHealth()` — pure sync function без I/O/suspend. Spring Boot Actuator на WebFlux адаптирует автоматически через `HealthIndicatorReactiveAdapter`. Убирает reactor-core import, упрощает тест (assertEquals вместо StepVerifier).
> **Действие:** design §4.3 + plan §9.1 (тест) + plan §9.3 (imp) + File Structure все ссылки `ReactiveHealthIndicator` → `HealthIndicator`. Тест переписан с `StepVerifier` на прямой `assertEquals`.

---

### Dismissed (14 — не требуют действия)

**Standalone false-positives / out-of-scope (5):**
- `SUGGESTION-4` (handle `key.reset() == false`) — текущий код уже удаляет key; reviewer сам говорит «не критично».
- `QUESTION-6` (RecordingFileDto fields) — будет верифицировано имплементатором (паттерн как в CRITICAL-1).
- `QUESTION-10` (orphaned `task-1` thread) — N/A, JVM restart убивает thread'ы.
- `QUESTION-12` (WatchService.close() idempotency) — `runCatching` корректно для cleanup, не требует изменений.
- `QUESTION-13` (defensive `if (!enabled) return`) — over-engineering поверх `@ConditionalOnProperty`.

**Дубли вопросов, ответ даёт соответствующий CRITICAL/CONCERN (9):**
- `Q1` → D2 (Variant A — empty poll более не сбрасывает counter)
- `Q2` → D1 (Variant C — нет внешнего autoheal)
- `Q3` → D1 (mapping не меняем — раз self-healing вне scope, флапание healthcheck immaterial)
- `Q4` → D6 (Variant A — оставлен `ApplicationReadyEvent`)
- `Q5` → CRITICAL-18 (auto-fix добавил полный список)
- `Q7` → CRITICAL-16 (auto-fix добавил `scope.cancel()`)
- `Q8` → D7 (`@ConditionalOnBean` защищает)
- `Q9` → CRITICAL-4 (auto-fix добавил clear+throw)
- `Q11` → D3 (Variant A — test seam достаточен)

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md` | §1 — убран claim про auto-restart; §2 — расширенные non-goals; §4.1 — state-поля расширены до 9 (D2), `@EventListener` вместо `@PostConstruct` (D6), `@Volatile internal var supervisorJob`, `scope.cancel()`, simplified `nextBackoff`, `catch (Exception)`, atomic `ensureWatchService`, `key.reset()` в finally; §4.3 — sync `HealthIndicator` (D9), snapshot-consistency note (D4); §4.4 — `runBlocking + withTimeout(5s)` (D5), `@ConditionalOnBean(GitProperties::class, BuildProperties::class) + @Profile("!test")` (D7); §5.2 — переписана как 8-ветка state-таблица (D2) + §5.2.1 транзишн-методы. |
| `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md` | Goal/Architecture — убрана self-healing claim; File Structure — `@EventListener` + `HealthIndicator`; Task 2.1 — корректный `TelegramUserDto` + `match {}` matcher; Task 2.3 — chatId null-check; Task 2.5 — explicit list of tests; Task 3.3 — три аннотации + `runBlocking + withTimeout`; Task 5.1/5.2 — корректный `CreateRecordingRequest` + real `RecordingFileDto` + try/finally for key.reset(); Task 5.8 — single-writer comment; Task 6 — `@EventListener`, расширенный state + D2 заметка, ensureWatchService try/catch, Files.walk.use, `consecutiveFailures: Long`, `catch (Exception)`, simplified `nextBackoff`, `scope.cancel()`, `companion object` для `POLL_PERIOD_MS`; Tasks 7/8 — перенумерованы (Task 7 = ApplicationListener cleanup, Task 8 = supervisor tests); Task 8 — `throwsMany` → `coAnswers`, `taskWithActiveJob()` helper, новый Step 8.7 (shutdown test), новый Step 8.8 (3 mandatory tests CRITICAL-4/5/8 follow-up); Task 9 — `HealthIndicator` + simplified test; Task 10 — `Health (passive signal)` раздел; Task 11.2 — host-side touch; PR body — passive signal language. |

## Статистика

- Всего замечаний: **47**
- Автоисправлено (без обсуждения): **26** (~55%)
- Обсуждено с пользователем: **2** (D1 Variant C, D2 Variant A)
- Применено без вопроса (один адекватный variant): **7** (D3, D4, D5, D6, D7, D8, D9)
- Отклонено (ложные/N/A/duplicate of CRITICAL): **14** (5 standalone + 9 question-duplicates)
- Повторов (автоответ): **0** (iter-1)
- Пользователь сказал «стоп»: **Нет**
- Агенты: codex-executor, ccs-executor (glm), ollama-executor (kimi/deepseek)
- Пропущен из-за timeout/inactivity: ollama-executor (minimax)
