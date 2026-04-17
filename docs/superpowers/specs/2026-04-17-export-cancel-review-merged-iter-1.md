# Merged Design Review — Iteration 1 (2026-04-17)

Source documents:
- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`

Reviewers executed in parallel; 6 of 7 completed, `ccs/glm` profile hung (skipped).

## codex-executor (gpt-5.4, reasoning xhigh)

Source: `/home/zinin/.claude/codex-interaction/2026-04-17-19-14-11-design-review-export-cancel-iter-1/output.txt`

[CRITICAL-1] Удаление chat-lock для `/export` ломает сам диалог. `ActiveExportTracker` защищает текущий `ExportCommandHandler` от двух параллельных диалогов в одном DM. План полностью удаляет tracker и запускает dedup только после `runDialog()`, что позволит двум параллельным `/export`-диалогам перехватывать ответы друг у друга.

[CRITICAL-2] Обещание "отмена на MERGING/COMPRESSING" не подтверждено текущим пайплайном: `VideoMergeHelper.process.waitFor(...)` блокирующий, `CancellationException` не прерывает ffmpeg. Либо сузить scope, либо добавить cancellation-aware wrapper.

[BUG-1] `DetectService.cancelJob` ловит `CancellationException` раньше `TimeoutCancellationException` (subtype), поэтому WARN для timeout не сработает.

[BUG-2] `markCancelling` не атомарен относительно `release` на уровне map-entry: cancel в момент success-пути может перевести только что отпущенную запись в CANCELLING.

[BUG-3] Ранние timeout-ветки `/export` (processing/send timeout) не обнуляют `replyMarkup` на status-message — на финальном экране останется живая кнопка «✖ Отмена».

[TEST-1] Обязательный `ExportExecutorTest` и cancel-flow тесты обозначены в плане как optional. Race "cancel-at-completion" не покрыт.

[TEST-2] Черновик `CancelExportHandlerTest` использует `every { authFilter.getRole(...) }`, но метод `suspend` — нужен `coEvery`/`coVerify`.

---

## gemini-executor

Source: `/home/zinin/.claude/gemini-interaction/2026-04-17-19-14-12-design-review-export-cancel-iter-1/output.txt`

[BUG-1] Catch-ordering в `DetectService.cancelJob`: `TimeoutCancellationException` subtype of `CancellationException`, поэтому перехватывается raise-веткой и не логируется как WARN.

[BUG-2] `buildCancellingKeyboard` содержит мёртвый код: проверка `mode == ANNOTATED || mode == ORIGINAL` всегда `true`, так как enum имеет только эти 2 значения. Для различения QuickExport vs `/export` нужен `recordingId != null`.

[MINOR] Race `submitWithRetry` → `attachCancellable`: если cancel пришёл ровно между этими шагами, cancel к vision-серверу не отправится. Дизайн явно принимает этот риск; можно улучшить через check-on-attach retry.

---

## ccs-executor — albb-glm (GLM-5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-14-29-design-review-export-cancel-iter-1-ccs-albb-glm/output.txt`

[CRITICAL-1] Race в `tryStart*`: `putIfAbsent(byRecordingId)` succeeds, затем до `byExportId[...] = Entry` поток cancelled — `recordingId` залочен навсегда. `release()` early-return не почистит secondary index.

[CRITICAL-2] Registry Entry leak при cancel LAZY-job ДО начала body: `finally { registry.release(...) }` никогда не запустится, если coroutine отменена до первой suspension point. Использовать `invokeOnCompletion`.

[BUG-1] Temp file leak при `CancellationException` в `downloadJobResult`: `catch (Exception)` не ловит `CancellationException`, temp-файл осиротел.

[BUG-2] Аналогично в `VideoExportServiceImpl.annotate`: orphan merged/compressed video file при cancel/shutdown.

[BUG-3] TOCTOU между `registry.get()` и `markCancelling()`: minor UI misleading — acceptable.

[DESIGN-1] Отсутствует явная стратегия temp-file cleanup при cancel/shutdown.

[DESIGN-2] CAS correctness в `markCancelling` — documented, но можно упростить через `AtomicReference`.

[TEST-1] Missing test для partial-init race.

[TEST-2] Missing test для cancel-queued-LAZY-job.

[TEST-3] Missing shutdown integration test.

[MINOR-1] i18n naming inconsistency: `quickexport.cancelled` vs `export.cancelled.by.user`.

[MINOR-2] Task 9 cancellation test marked optional — несоответствует критичности feature.

---

## ccs-executor — albb-qwen (Qwen3.5-Plus)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-14-33-design-review-export-cancel-iter-1-ccs-albb-qwen/output.txt`

[CRITICAL-1] Race между `submitWithRetry` → `attachCancellable`: `NonCancellable` гарантирует выполнение callback, но `CancelExportHandler` уже прочитал `cancellable == null`. Решить через `CompletableDeferred<CancellableJob?>`.

[CRITICAL-2] Потенциальный index leak в registry при shutdown: если `finally` не отработал (scope cancelled), `byRecordingId`/`byChat` не очистятся.

[BUG-1] `DetectService.cancelJob` на 409: не парсим тело, не знаем текущий статус job.

[BUG-2] Тестовые `Job()` в `ActiveExportRegistryTest` не отменяются в `@AfterEach` — потенциальная утечка корутин в CI.

[DESIGN-1] `ExportCoroutineScope` как отдельный bean — избыточная сложность. Use `CoroutineScope(...)` в хендлерах.

[DESIGN-2] `parseExportId` использует `removePrefix` для обоих префиксов, `xc:np:uuid` парсится как valid. Нужна explicit check на starts-with.

[TEST-1] Missing test на TOCTOU `markCancelling` ↔ `release`.

[TEST-2] Тест для ORIGINAL mode не проверяет, что `onJobSubmitted` не-вызван вовсе.

[DOC-1] Task 11 удаляет `ActiveExportTracker` без Grep-сканирования внешних использований.

[MINOR-1] Несоответствие дизайн/план по UI для `/export` в CANCELLING.

[MINOR-2] Хардкод таймаутов `QUICK_EXPORT_*_TIMEOUT_MS` — дизайн упоминает конфигурируемость.

---

## ccs-executor — albb-kimi (Kimi-K2.5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-14-39-design-review-export-cancel-iter-1-ccs-albb-kimi/output.txt`

[CRITICAL-1] Race в `markCancelling`: между `byExportId[exportId]` и `synchronized(entry)` другой поток может вызвать `release()`. CHM-level CAS через `computeIfPresent`.

[CRITICAL-2] `release()` из трёх операций не атомарна — промежуточное исключение оставит висячий индекс.

[BUG-1] Ветка `DuplicateChat` в `QuickExportHandler` — misleading (не может произойти по дизайну). Лучше `error(...)`.

[BUG-2] Недостижимая ветка `else` в `buildCancellingKeyboard`.

[BUG-3] i18n ключ `export.cancelled.by.user` vs существующий `export.cancelled` — унифицировать.

[DESIGN-1] Длинные сигнатуры `exportVideo` (6 параметров, 2 коллбека) — рассмотреть `ExportCallbacks` объект.

[DESIGN-2] Task order: `ExportCoroutineScope` перенести в Task 1.

[DESIGN-3] UX: после cancel QuickExport восстанавливает ту же клавиатуру — user может не понять, что произошло. Non-blocking.

[PERF-1] Contention в `synchronized(entry)` — маловероятно на одном exportId, acceptable.

[TEST-1] Race-тест использует `Thread`+`runBlocking` вместо coroutine primitives.

[TEST-2] Missing orphan-job restart test.

[DOC-1] `release()` в `finally` — неявный контракт, стоит явно verifry в Task 9/10.

[DOC-2] Backward compatibility с legacy-сообщениями без `exportId`.

---

## ccs-executor — albb-minimax (MiniMax-M2.5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-14-45-design-review-export-cancel-iter-1-ccs-albb-minimax/output.txt`

[CRITICAL-1] Два `onDataCallbackQuery` в `FrigateAnalyzerBot` — неопределён порядок при пересечении префиксов. (Анализ: ktgbotapi поддерживает multiple triggers, filter'ы не пересекаются — false positive.)

[CRITICAL-2] Race между `markCancelling` и `attachCancellable` → orphan job на vision-сервере. Документировать как Known Limitation. (Уже частично описано в дизайне §4.)

[DESIGN-1] LAZY launch + job.join(): `withTimeoutOrNull` должен находиться внутри launched coroutine. (Анализ: план уже размещает `withTimeoutOrNull` внутри `runExport`, который запускается launched-корутиной — false positive.)

[DESIGN-2] QuickExport dedup по exportId: может ослабить защиту от быстрых повторных нажатий. (Анализ: registry держит `byRecordingId` index — дедуп сохранён через recordingId.)

[DESIGN-3] Secция 2.1 `tryStartQuickExport` неявно ссылается на dedup ключ. Уточнить.

[TEST-1] Task 9 cancellation test — не-полный flow, только форма клавиатуры.

[TEST-2] Task 10 не включает cancel-сценарий для `/export`.

[SECURITY-1] chatId mismatch: generic "не активно" — good. Не логировать реальный chatId из entry. (Анализ: план уже так делает.)

[DOC-1] Явно вынести orphan-job risk в "Known Limitations" секцию.

[MINOR-1] Commit order для удаления `ActiveExportTracker`.

[MINOR-2] E2e verification не-обязательна.

---

## Consolidated Summary

**Genuine CRITICAL** (multiple reviewers or confirmed high-impact):
- C1. `ActiveExportTracker` удаление ломает `/export` dialog (codex).
- C2. ffmpeg cancellation не работает (codex).
- C3. `markCancelling` ↔ `release` race (codex BUG-2, kimi CRITICAL-1).
- C4. LAZY cancel before body → registry leak (albb-glm CRITICAL-2).
- C5. Partial init race в `tryStart*` → permanent lock (albb-glm CRITICAL-1).
- C6. Catch-ordering в `cancelJob` (codex BUG-1, gemini BUG-1).

**Genuine BUG** (auto-fix):
- B1. Dead code in `buildCancellingKeyboard` (gemini BUG-2, kimi BUG-2).
- B2. `parseExportId` double-strip (qwen DESIGN-2).
- B3. `DuplicateChat` misleading in QuickExport (kimi BUG-1).
- B4. Cancel button leak on `/export` early-return timeouts (codex BUG-3).
- B5. Temp-file leak на CancellationException (albb-glm BUG-1, BUG-2).
- B6. 409 тело не парсится (qwen BUG-1) — дополнительная фиксация статуса; minor.
- B7. Race между cancel click и `attachCancellable` → orphan job (gemini MINOR, qwen CRITICAL-1, minimax CRITICAL-2) — mitigate через attach-time check.

**Test gaps (make mandatory, not optional)**:
- T1. `ExportExecutorTest` mandatory с cancel-сценарием (codex TEST-1, qwen TEST-2, minimax TEST-2).
- T2. QuickExport cancellation test mandatory (codex TEST-1, albb-glm MINOR-2, minimax TEST-1).
- T3. `markCancelling`-after-release test (qwen TEST-1, albb-glm TEST-1).
- T4. LAZY-cancel-before-body test (albb-glm TEST-2).
- T5. ORIGINAL mode onJobSubmitted не-вызван test (qwen TEST-2).
- T6. `authFilter.getRole` suspend → `coEvery` в тестах (codex TEST-2).

**Doc improvements**:
- D1. i18n унифицировать naming (albb-glm MINOR-1, kimi BUG-3).
- D2. Явно описать Known Limitations (orphan job, ffmpeg) в design (minimax DOC-1).
- D3. Явная verify-проверка `release` в `finally` в Task 9/10 (kimi DOC-1).

**False positives / already addressed**:
- minimax CRITICAL-1 (multiple onDataCallbackQuery) — ktgbotapi supports it.
- minimax DESIGN-1 (LAZY + withTimeoutOrNull) — уже правильно в плане.
- minimax SECURITY-1 — уже generic message.
- minimax MINOR-1 (commit order) — план корректен.
- minimax MINOR-2 (e2e) — уже Task 13 Step 4.
- kimi DESIGN-1 (ExportCallbacks class) — YAGNI, пропустить.
- kimi DESIGN-3 (disabled keyboard after cancel) — non-blocking, пропустить.
- qwen DESIGN-1 (ExportCoroutineScope overkill) — единая точка shutdown — ценность есть, keep.
- qwen MINOR-2 (configurable timeouts) — существующее поведение, не в scope.
- qwen DOC-1 (migration grep) — handled in Task 11 Step 2.
