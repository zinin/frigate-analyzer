# Review Iteration 2 — 2026-05-24

## Источник

- Design: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md`
- Review agents: codex-executor (gpt-5.5, xhigh), ccs-executor (glm-5.1), ollama-executor (ollama-kimi), ollama-executor (ollama-deepseek)
- **Skipped (failed):** ollama-executor (ollama-minimax) — timeout, рецидив iter-1 проблемы (silent stall после reading docs)
- Merged output: `docs/superpowers/specs/2026-05-23-watch-records-supervisor-review-merged-iter-2.md`

## Замечания

### Auto-fixes (11 — применены без обсуждения)

#### Главное расхождение Design↔Plan (4/4 reviewers convergent)

- **[CRITICAL-1]** План откатился к pre-D2 5-полевой модели (codex/ccs-glm/kimi/deepseek). **Действие:** plan Step 6.1 переписан под design §4.1/§5.2 — 10 `@Volatile` полей (`lastSuccessfulPollAt`, `lastEventProcessedAt`, `lastSuccessfulRegistrationAt`, `consecutive{Event,Registration,}Failures`, `startupAt` + existing), 3 транзишн-метода (`onPollCompleted`/`onRegistrationSuccess`/`onRegistrationFailure` + `onIterationFailureLegacy` для ClosedWatchService/generic + extracted `maybeResetBackoff`), 8-ветвевая `computeHealth` per design §5.2, константы `STARTUP_GRACE=2m` + `STARTUP_FAILURE_THRESHOLD=5L`. `IterationResult` расширен до 3 полей (`eventsProcessed`, `eventFailures`, `lastCleanupAt`). `WatchRecordsLoop.runIteration` ловит per-event exceptions внутри и считает в `eventFailures` (ClosedWatchService / Cancellation bubble up). Task 8 health tests переписаны под все 8 веток (через helper `taskWithActiveJob(...)` с extended signature).

#### Уникальные находки CCS-GLM

- **[CRITICAL-4]** `WatchRecordsTaskHealthIndicator` без `@Profile("!test")` сломает `FrigateAnalyzerApplicationTests.actuatorHealth()` (ccs-glm). В test profile `WatchRecordsTask.start()` short-circuits → `supervisorJob=null` → indicator вернёт DOWN → агрегированный health DOWN → тест-ожидание UP падает. **Действие:** Step 9.3 — добавлен `@Profile("!test")` (тот же паттерн что у `StartupTelegramNotifier`).

#### Convergent infrastructure findings

- **[CONCERN-1]** Lifecycle test (Step 8.7) deadlock'нется на `runBlocking + StandardTestDispatcher` (codex+ccs-glm). **Действие:** добавлен `suspend fun stopAndJoin()` helper в `WatchRecordsTask` (production по-прежнему использует `shutdown()` через `@PreDestroy`); Step 8.7 переключён на `task.stopAndJoin()`.

- **[CONCERN-2]** Docs drift: `@PostConstruct`/`ReactiveHealthIndicator` mentions в нескольких местах (codex+kimi+deepseek). **Действие:** sweep по plan + design — обновлены design §3 tree, §4.1 intro, §5.1 reaction table, plan Task 6 heading, Step 6.4 commit, Task 7 context, Step 7.2 expected, Step 7.3 commit, `.claude/rules/pipeline.md` ссылки в плане, manual sanity. Удалён unused `jakarta.annotation.PostConstruct` import.

- **[CONCERN-3]** Missing imports в plan Step 6.1: `EventListener`, `ApplicationReadyEvent`, `ConditionalOnBean` (codex+ccs-glm+deepseek). **Действие:** добавлены в imports.

#### Singleton findings

- **[CONCERN-4]** `TelegramNotificationService.sendOwnerMessage` смешивает user-facing и operator/admin channels (codex). **Действие:** design §4.5 — note о small trade-off, формализован как "future-refactor если admin-сигналов станет больше".

- **[CONCERN-5]** `StartupTelegramNotifier` не валидирует null `BuildProperties`/`GitProperties` (kimi). **Действие:** Step 3.3 — null-safe formatting (`?: "<unknown>"`).

- **[CONCERN-6]** Task 6 intro "supervisor пока пустой" противоречит Step 6.1 (kimi). **Действие:** intro переписан.

- **[CONCERN-7]** `scope.cancel()` ordering invariant не документирован (deepseek). **Действие:** inline-комментарий в design §4.1 shutdown + plan §6.1 shutdown.

- **[QUESTION-1]** Design §5.2 branch 6 может быть dead code (deepseek). **Действие:** design §5.2 — note что fires transiently при `ClosedWatchServiceException`-recovery (намеренно отдельная ветка для operator signal).

- **[QUESTION-2]** Design §5.2 branch 7 redundant predicate `lastSuccessfulPollAt != null` (deepseek). **Действие:** упрощено в design §5.2 (supervisor.isActive гарантирует хотя бы один poll после grace).

---

### Disputed (3 кластера — обсуждены с пользователем)

#### [CRITICAL-2] Transient DB/IO failure теряет событие навсегда — нет retry queue
> Источник: codex (unique finding)
> **Решение пользователя:** Variant A — accept gap + smoke test.
> **Обоснование:** Consistent с уже принятым D1 (passive health signal + manual operator recovery). Существующий `FirstTimeScanTask` уже back-fill'ит при restart — variant B (retry queue) дублировал бы mechanism (и сам терялся бы при перезапуске). Variant C переоткрывает D6 ordering invariant и tightly couples `WatchRecordsTask` к `FirstTimeScanTask`.
> **Действие в design:** §2 non-goals — добавлен пункт о per-event retry/rescan как accepted trade-off, ссылка на manual recovery через `docker restart` + `FirstTimeScanTask` back-fill.
> **Действие в plan:** Step 11.2 — добавлен smoke-сценарий "PG outage → events lost → restart → FirstTimeScanTask back-fill". Сценарий verifies accepted gap operationally.

#### [CRITICAL-3] `@EventListener` ordering не гарантирует FirstTimeScan-before-watcher (D6 rationale неверен)
> Источник: codex CRITICAL-4 + deepseek C5
> **Решение пользователя:** Variant B — accept concurrency, update D6 rationale.
> **Обоснование:** Real ordering требует касания `FirstTimeScanTask` (`@Async` removal или `awaitFirstScanCompletion()`) — out-of-scope per §2 non-goals. DB unique constraint на `recordings.file_path` уже mitigates duplicate processing на старте — defense-in-depth достаточен. Variant A гарантирует только dispatch ordering, не execution (тот же practical outcome при больших изменениях). Variant C misleading (`@Order` не решает `@Async`).
> **Действие в design:** §4.1 D6 note полностью переписан — убран ложный claim "preserves ordering", explicit acknowledgement что Spring не гарантирует ordering между `@EventListener`-методами без `@Order` и что `FirstTimeScanTask.run()` `@Async` всё равно run'ит scan параллельно. Documented mitigation: DB unique constraint + per-event try/catch (iter-2 CRITICAL-1).
> **Действие в plan:** Step 6.1 comment у `start()` обновлён; File Structure table — note "concurrent start с FirstTimeScanTask принят".

#### [CRITICAL-5] Task 6 → Task 7 commit ordering всё ещё ломает компиляцию (iter-1 CCS#8 fix был cosmetic)
> Источник: ccs-glm Concern #2 (disputes iter-1 fix)
> **Решение пользователя:** Variant A — swap Task 6 ↔ Task 7 (execution order, not file order).
> **Обоснование:** Iter-1 fix перенумеровал tasks но coupling остался — Step 6.4 commit удаляет `WatchRecordsTask.run()` пока `ApplicationListener.kt` ещё на него ссылается. Variant A (swap) даёт true compile-after-each-commit invariant минимальной правкой. Variant B (megacommit) нарушает iter-1 separation; Variant C (accept broken) отменяет iter-1 намерение.
> **Действие в plan:** Task 6 header — добавлен ⚠️-warning с explicit execution order (Task 7 → Task 6 → Task 8) и инструкцией для sub-agent executor'а; Step 6.2 compile expectation обновлён; Task 7 header — note "выполнять первым". Numbering задач в файле сохранено для backward compatibility ссылок.

---

### Dismissed (1 — не требует действия)

- **deepseek S2** (Promote SupervisorState to data class + AtomicReference) — **DISMISSED** (covered by iter-1 D4 Variant B — explicitly chose eventual consistency over AtomicReference). Reviewer re-предлагает уже отвергнутый Variant; обоснование принятия eventual consistency остаётся valid (single-user, no Prometheus scraping). Если в будущем появится active scraping — пересмотрим D4.

### Repeats / partial-repeats with new dimensions

- **CONCERN-2** (docs drift) — partial repeat: iter-1 D6+D9 уже принимали решения, но не все ссылки в обоих документах обновились. Дочищено в iter-2.
- **CRITICAL-5** (commit ordering) — disputes iter-1 CCS#8 fix как cosmetic. По существу — NEW finding (что fix не сработал), хотя проблема та же.

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-23-watch-records-supervisor-design.md` | §3 tree — `ReactiveHealthIndicator` → `HealthIndicator`; ApplicationListener row — `@PostConstruct` → `@EventListener`; §4.1 intro — `@PostConstruct` → `@EventListener`; §4.1 shutdown — inline комментарий про scope.cancel() ordering invariant (CONCERN-7); §4.1 supervisor body — обновлён к 3-методной transition модели (CRITICAL-1); §4.1 D6 note — полностью переписан (CRITICAL-3 Variant B — убран ложный claim ordering, accepted concurrency + DB constraint mitigation); §4.3 — computeHealth ссылается на 8-branch таблицу §5.2 (вместо inline pre-D2 кода) + iter-2 CRITICAL-4 note о `@Profile("!test")`; §4.5 — CONCERN-4 acknowledgement про owner-notification coupling; §5.1 reaction table — обновлена под per-event catch (CRITICAL-1) + onRegistrationFailure/onIterationFailureLegacy split; §5.2 branch 6 — QUESTION-1 note (transient cycle); §5.2 branch 7 — QUESTION-2 simplified predicate; §5.2.1 transition methods — design-internal contradictions устранены через ссылку на plan Step 6.1 как sole source; §2 non-goals — CRITICAL-2 Variant A accepted gap explicitly documented. |
| `docs/superpowers/plans/2026-05-23-watch-records-supervisor-plan.md` | Step 3.3 — null-safe `BuildProperties`/`GitProperties` interpolation (CONCERN-5); Step 4.3 — `IterationResult` extended до 3 полей (`eventsProcessed`, `eventFailures`, `lastCleanupAt`); Step 4.3+5.5 — обновлены constructors; Step 5.2 — per-event try/catch внутри `runIteration` body + eventFailures counter (CRITICAL-1); Step 5.6 — test переписан под new semantics (count, не propagate); Step 6.1 — полная замена кода под D2: imports (CONCERN-3), constants (+STARTUP_GRACE +STARTUP_FAILURE_THRESHOLD), 10 fields, 3 transitions, runSupervised с `onPollCompleted`, ensureWatchService с registration transitions, 8-branch computeHealth (CRITICAL-1); добавлен `stopAndJoin()` suspend helper (CONCERN-1); shutdown — invariant комментарий (CONCERN-7); start() comment — CRITICAL-3 rationale; Step 6.4 commit message — без `@PostConstruct`; Task 6 heading — CRITICAL-5 ⚠️ warning + execution-order swap notes + CRITICAL-1 D2 model summary; Task 7 — CRITICAL-5 warning "выполнять первым"; Step 7.2 — обновлён expected (CRITICAL-4 reference); Step 7.3 commit — `@EventListener` вместо `@PostConstruct`; Step 8.6 — 8-branch tests через `taskWithActiveJob(...)` helper с extended signature (CRITICAL-1); Step 8.7 — `task.stopAndJoin()` вместо `shutdown()` (CONCERN-1); Step 8.8 — pending-note Step 8.8 #3 заменён на final D2 spec; Step 9.3 — `@Profile("!test")` на `WatchRecordsTaskHealthIndicator` (CRITICAL-4); Step 11.1 — note про health-indicator profile gating; Step 11.2 — добавлен CRITICAL-2 smoke-сценарий (PG outage → manual restart → back-fill); File Structure table — CRITICAL-3 concurrent-start note; `.claude/rules/pipeline.md` section — `lifecycle via @EventListener(ApplicationReadyEvent) / @PreDestroy`. |

## Статистика

- Всего замечаний от reviewer'ов: **16 уникальных** (после merge/deduplication; original raw count ~50+ от 4 агентов)
- Автоисправлено (без обсуждения): **11** (CRITICAL-1 + CRITICAL-4 + CONCERN-1..7 + QUESTION-1, QUESTION-2)
- Обсуждено с пользователем: **3** (CRITICAL-2 Variant A, CRITICAL-3 Variant B, CRITICAL-5 Variant A)
- Применено без вопроса (один адекватный variant): **0** (все спорные имели реальные trade-offs)
- Отклонено (ложные/N/A): **1** (deepseek S2 — D4 already rejected)
- Повторов (автоответ): **0** (но 2 partial-repeat с новыми dimensions — CONCERN-2, CRITICAL-5)
- Пользователь сказал «стоп»: **Нет**
- Агенты: codex-executor (gpt-5.5/xhigh), ccs-executor (glm-5.1), ollama-executor (kimi/deepseek)
- Пропущен из-за timeout (повторно): ollama-executor (minimax) — known issue
