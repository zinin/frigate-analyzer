# Merged Design Review — Iteration 2 (2026-04-17)

Source documents:
- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Iter-1 decisions: `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-1.md`

Reviewers executed in parallel; all 7 completed successfully (ccs/glm direct profile also completed this time in 5m23s, up from hung in iter-1).

## codex-executor (gpt-5.4, reasoning xhigh)

Source: `/home/zinin/.claude/codex-interaction/2026-04-17-19-50-36-design-review-export-cancel-iter-2/output.txt`

[BUG-1] `ExportCoroutineScope` на `Dispatchers.Default` блокирует сам путь отмены — remote-cancel POST может ждать тех же worker threads, которые заняты blocking ffmpeg `process.waitFor(...)`. Рекомендация: `Dispatchers.IO`.

[TEST-1] В обязательных тестовых шагах остались плейсхолдеры и несуществующие API — `ActiveExportRegistry()` без конструктора-аргумента (plan:2438), `byExportIdForTest()` помечен сам в плане как "not a real API" (plan:2538-2540), `// IMPLEMENTOR` заглушка (plan:3009-3019). Self-review ложно утверждает "No placeholders" (plan:3196-3205).

[TEST-2] План не покрывает несколько тестов, которые design объявляет обязательными — отсутствуют тесты на `CancellationException` rethrow для `cancelJob`, concurrent-cancel вокруг `NonCancellable` в `VideoVisualizationService`, и malformed callback data / Telegram API errors в `CancelExportHandlerTest`.

[DOC-1] В synopsis (plan:7) всё ещё написано "replacing `ActiveExportTracker`"; Self-Review table (plan:3176) тоже говорит "| 2.2 Delete ActiveExportTracker | Task 11 |". Противоречит design §2.1 и самому же плану ниже ("Do NOT remove `ActiveExportTracker`").

[TEST-3] Конкурентный тест registry сам содержит race: `mutableListOf<Job>()` с несинхронизированным `jobs.add(it)` из 32 параллельных thread-ов (plan:949-951, 1152-1161). Предложено `ConcurrentLinkedQueue`.

---

## gemini-executor

Source: `/home/zinin/.claude/gemini-interaction/2026-04-17-19-50-35-design-review-export-cancel-iter-2/output.txt`

[CRITICAL-1] Deadlock между `release()` и `markCancelling()` в `ActiveExportRegistry`. Обратный порядок захвата блокировок:
- `release()`: `synchronized(entry)` → внутри `byExportId.remove(exportId)` (берёт bucket lock CHM).
- `markCancelling()`: `computeIfPresent()` (держит bucket lock) → внутри `synchronized(entry)`.
Возможен классический dual-lock deadlock. Фикс: в `release()` сначала `byExportId.remove(exportId)` ДО `synchronized(entry)`.

[CRITICAL-2] Утечка temp-файлов при отмене: cleanup suspend-вызовы (`tempFileHelper.deleteIfExists`, `videoExportService.cleanupExportFile`) в `catch (CancellationException)` и `finally` не обёрнуты в `withContext(NonCancellable)`. Suspend-вызов в отменённой корутине мгновенно бросает новый CancellationException, cleanup не выполняется.

[CRITICAL-3] Финальный UI-статус "❌ Экспорт отменён" не доходит до пользователя: `bot.editMessageText` / `sendTextMessage` / `restoreButton` в ветке `catch (CancellationException)` — всё suspend, в отменённой корутине мгновенно ломаются. Нужен `withContext(NonCancellable)` вокруг UI-обновлений.

[BUG-1] Jackson 3 импорты (`tools.jackson.databind.*`) в `DetectServiceCancelJobTest` — проект якобы на Jackson 2. (Примечание: верификация показала, что проект *реально* использует `tools.jackson.*` в 6 существующих файлах — **false positive**).

[BUG-2] `runCatching { bot.answer(...) }` проглатывает `CancellationException` (Kotlin runCatching ловит `Throwable`). Сломает graceful shutdown.

[TEST-1] Flakiness: `delay(50)` в `ActiveExportRegistryTest` против реального `Dispatchers.Default` — на CI может не хватить 50ms. Рекомендовано `CompletableDeferred` или `Channel`.

---

## ccs-executor — glm (GLM-5 direct)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-50-42-design-review-export-cancel-iter-2-ccs-glm/output.txt`

[BUG-7] `CancelExportHandlerTest` использует 10-параметровый `CommonUser(UserId, String, null, Username, null, null, null, null, null, null)`, а существующие тесты проекта (`QuickExportHandlerTest:305-308`) используют 3-параметровый named-arg pattern. Сломает компиляцию 8 вхождений.

[BUG-8] `QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 3_000_000L` — проверить против текущего кода. (Верификация: значения совпадают с `QuickExportHandler.kt:322,326` — **false positive**).

[BUG-9] Self-retracted ("снимаю замечание").

[BUG-10] `ExportExecutorTest.\`second parallel execute — returns DuplicateChat and sends concurrent message\`` содержит пустое тело теста (только комментарий `// (Abbreviated...)`). Критический путь дедупликации `/export` не покрыт.

[BUG-11] В `QuickExportHandlerTest` (Task 9 Step 3) тест использует `registry.get(recordingId)` — но `get()` принимает `exportId`, а не `recordingId`. Сам план признаёт это "not a real API" (plan:2539) и предлагает test accessor, но не определяет его в Task 5.

[BUG-12] Self-retracted ("не баг").

[DESIGN-1] `VideoExportServiceImpl.annotate()`: `catch (CancellationException)` и `catch (Exception)` содержат идентичный cleanup. Запах дублирования, но логически верно. Minor.

[DOC-3] Design §7 (design:242) декларирует `export.cancelled`, plan Task 6 Step 2 (plan:1395) использует `export.cancelled.by.user`. Design не обновлён после plan review.

[TEST-4] `ExportExecutorTest.firstActiveExport` возвращает `null // IMPLEMENTOR` — тест не работает без реализации. `snapshotForTest()` определён в том же Task 10 *после* теста.

[MINOR-1] `DetectServiceCancelJobTest` — избыточный setup (~90 строк boilerplate для одного HTTP-вызова). Соответствует project pattern, не блокер.

[MINOR-2] Task 5 требует Task 6 (`ActiveExportRegistry` takes `ExportCoroutineScope`), но документ упорядочивает их 5 → 6. Исполнитель по порядку наткнётся на compile error.

---

## ccs-executor — albb-glm (GLM-5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-50-46-design-review-export-cancel-iter-2-ccs-albb-glm/output.txt`

**Новых blocking issues не найдено.**

Minor notes:
- Self-Review table row "| 2.2 Delete ActiveExportTracker | Task 11 |" не обновлён в свете SKIPPED. Cosmetic.
- Тесты используют `assertTrue` без явного `import kotlin.test.assertTrue` — routine.

Вердикт: план готов к реализации без blocking issues.

---

## ccs-executor — albb-qwen (Qwen3.5-Plus)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-50-49-design-review-export-cancel-iter-2-ccs-albb-qwen/output.txt`

[TEST-4] Missing тест на `release()` fallback через `invokeOnCompletion` при отмене LAZY-корутины ДО первого suspension point. Spec §5.3 явно описывает этот edge-case, но теста нет.

[DOC-3] Несоответствие spec vs plan по i18n: spec §7 пишет `export.cancelled`, plan использует `export.cancelled.by.user`. Рекомендация: обновить spec или добавить комментарий в plan.

[DESIGN-1] Двухуровневая блокировка (Tracker + Registry) — нет документированного lock-ordering invariant. Сегодня риска deadlock нет (Tracker.lock → Registry.startLock — одностороннее направление), но при будущем расширении легко нарушить. Рекомендация: зафиксировать инвариант в `.claude/rules/telegram.md`.

[MINOR-1] Polling pattern `repeat(50) { ... delay(10) }` в `ExportExecutorTest` — 500ms максимум, но flaky на медленном CI. Рекомендовано `CompletableDeferred` / `Channel`.

---

## ccs-executor — albb-kimi (Kimi-K2.5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-50-51-design-review-export-cancel-iter-2-ccs-albb-kimi/output.txt`

[CRITICAL-9] Циклическая зависимость: `VideoVisualizationService` (core) импортирует `CancellableJob` из telegram модуля. (Верификация: CLAUDE.md arrow `core → telegram` означает "core depends on telegram", т.е. core может импортировать из telegram — **false positive**).

[CRITICAL-8] `snapshotForTest()` без `@VisibleForTesting`. (Примечание: проект не использует androidx; `internal` visibility достаточен для kotlin.test — **false positive**).

[BUG-7] Task 5 требует Task 6, но порядок обратный. Дубликат ccs/glm MINOR-2.

[BUG-14] Тест использует `byExportIdForTest()` — метод не существует в Task 5. Дубликат ccs/glm BUG-11 и codex TEST-1.

[BUG-19] Race при чтении `state` в `onProgress`: `registry.get(exportId)?.state`. (Примечание: `state` объявлен `@Volatile`, visibility гарантирована; наш use-case — "пропустить UI-апдейт, если в процессе отмены" — терпим к гонкам — **false positive**).

[BUG-20] NPE при `cleanupExportFile(videoPath)` после `withTimeoutOrNull`. (Верификация: `videoPath` проверяется на null перед вызовом cleanup в plan:2243-2249 и plan:2721-2734 — **false positive**).

[BUG-10] `coVerify(exactly = 0)` с 12 `any()` может не сматчиться при default-параметрах. (Примечание: `exactly = 0` означает "не вызывался вообще", matcher-шаблон не критичен — **false positive**).

[MINOR-8] Отсутствует i18n ключ `quickexport.error.concurrent`. (Верификация: существует в `messages_ru.properties:112` — **false positive**).

[MINOR-12] Отсутствует i18n ключ `quickexport.error.annotation.timeout`. (Верификация: существует в `messages_ru.properties:117` — **false positive**).

[TEST-4] Flaky `delay(50)` — дубликат gemini TEST-1.

[MINOR-4] Self-Review table row для Task 11 не обновлён под SKIPPED. Дубликат codex DOC-1.

---

## ccs-executor — albb-minimax (MiniMax-M2.5)

Source: `/home/zinin/.claude/ccs-interaction/2026-04-17-19-50-53-design-review-export-cancel-iter-2-ccs-albb-minimax/output.txt`

[TEST-1] `snapshotForTest()` упоминается в Task 10 в конце, но по логике должен быть в Task 5 (где `ActiveExportRegistry` создаётся). Дубликат codex TEST-1 / ccs/glm BUG-11.

Вердикт: "План готов к выполнению. Все ключевые race condition'ы (markCancelling vs release, attachCancellable check-on-attach, TOCTOU) корректно обработаны в коде."

---

## Consolidated Summary

| Severity | Count | Source diversity |
|----------|-------|------------------|
| CRITICAL (real) | 3 | gemini ×3 |
| CRITICAL (false positive) | 2 | kimi ×2 |
| BUG (real) | 6 | codex, gemini, ccs/glm × many |
| BUG (false positive) | 6 | gemini, ccs/glm, kimi |
| DESIGN | 2 | ccs/glm, qwen |
| TEST (real) | 5 | codex ×2, gemini, qwen, kimi |
| DOC | 3 | codex, qwen+glm, kimi |
| MINOR (real) | 3 | ccs/glm, qwen, kimi, albb-glm |
| MINOR (false positive) | 2 | kimi |
| Self-retracted by reviewer | 2 | ccs/glm ×2 |

### Real issues (unique, after dedup)

1. **gemini CRITICAL-1** — Deadlock release/markCancelling
2. **gemini CRITICAL-2** — Temp-file leak при cancellation (suspend cleanup без NonCancellable)
3. **gemini CRITICAL-3** — UI финал "Отменён" не доходит (suspend UI в cancelled coroutine)
4. **codex BUG-1** — Dispatchers.Default может заблокировать cancel path
5. **gemini BUG-2** — runCatching проглатывает CancellationException
6. **codex DOC-1 + kimi MINOR-4 + albb-glm minor** — Synopsis / Self-Review stale про ActiveExportTracker
7. **codex TEST-1 + ccs/glm BUG-11 + BUG-14 + minimax TEST-1** — test accessors (`snapshotForTest()`, `byExportIdForTest()`, `ActiveExportRegistry()` без ctor arg, `registry.get(recordingId)`) не определены/неверны
8. **codex TEST-2** — Missing mandatory tests: CancellationException rethrow, NonCancellable race, malformed callback data / edit keyboard failures
9. **codex TEST-3** — Concurrency test harness race (mutableListOf<Job>)
10. **ccs/glm BUG-7** — CommonUser 10-параметровый constructor vs 3-params в project
11. **ccs/glm BUG-10** — ExportExecutorTest second parallel execute — пустое тело
12. **ccs/qwen DESIGN-1** — Lock ordering invariant не задокументирован
13. **ccs/glm DOC-3 + qwen DOC-3** — Design §7 не обновлён после plan renaming
14. **ccs/qwen TEST-4** — LAZY cancel before start тест отсутствует
15. **ccs/qwen MINOR-1 + gemini TEST-1 + kimi TEST-4** — Flaky delay(50) / polling
16. **ccs/glm MINOR-2 + kimi BUG-7** — Task 5/6 ordering
17. **ccs/glm DESIGN-1** — Duplicate catch-blocks (дизайн-запах, не блокер)
