# Merged Design Review — Iteration 3 (2026-04-17)

Источники:
- codex-executor (gpt-5.4 xhigh) — completed (~11 min, 4 tools)
- gemini-executor — completed (~6.5 min, 4 tools)
- ccs-executor (glm direct) — partial (hung mid-analysis; findings captured from log stream)
- ccs-executor (albb-glm / glm-5) — completed (~6 min)
- ccs-executor (albb-qwen / qwen3.5-plus) — completed (~2 min)
- ccs-executor (albb-kimi / kimi-k2.5) — completed but response truncated at output token boundary
- ccs-executor (albb-minimax / MiniMax-M2.5) — completed (~5.5 min), **no new findings**

---

## codex-executor (gpt-5.4 xhigh)

### [CRITICAL-1] `/export` снова удерживает dialog-lock на весь execution phase
Заявлено: ActiveExportTracker остаётся только dialog-phase lock, а дальше lifecycle забирает registry (design:31). Но в плане ExportExecutor.execute() делает job.join() (plan:2845), а ExportCommandHandler отпускает tracker только после возврата execute() (plan:3092). Это схлопывает двухуровневую модель обратно в один долгоживущий lock и держит /export заблокированным до конца экспорта. Текущий код как раз специально делает handoff в фон (ExportCommandHandler.kt:58).

Фикс: либо вернуть fire-and-forget handoff из ExportCommandHandler, либо сделать ExportExecutor.execute() неблокирующим после успешного tryStartDialogExport/job.start(). ActiveExportTracker должен освобождаться сразу после передачи управления execution-phase.

### [BUG-1] В ActiveExportRegistry остаётся окно на ложный Duplicate*
Заявлено: release() удаляет byExportId первым, потом чистит вторичные индексы (plan:1419), а tryStartQuickExport/tryStartDialogExport доверяют byRecordingId/byChat напрямую (plan:1342). Между byExportId.remove(...) и byChat.remove(...)/byRecordingId.remove(...) новый старт может увидеть висячий secondary index и вернуть DuplicateChat/DuplicateRecording, хотя экспорт уже освобождён.

Фикс: при Duplicate* проверять, жив ли existing в byExportId, и self-heal stale secondary entry перед окончательным отказом; либо сериализовать cleanup вторичных индексов через startLock после primary remove. Нужен race-test start vs release.

### [BUG-2] Happy-path cancelJob не бьётся ни с моделью ответа, ни с текущим ObjectMapper
Тест шлёт `{"status":"cancelled"}` (plan:146), cancelJob читает JobStatusResponse. Но JobStatus не знает cancelled (JobStatus.kt:5), десериализация 200-body сломается. Плюс тест строит tools.jackson.databind.ObjectMapper (plan:362), а DetectService принимает com.fasterxml.jackson.databind.ObjectMapper (DetectService.kt:3, DetectServiceTest.kt:41).

Фикс: не десериализовать body в cancelJob (bodiless response), тогда тесту достаточно method/path/status. Либо добавить CANCELLED в JobStatus и выровнять тестовый buildObjectMapper() под Fasterxml-type.

### [TEST-1] Миграция QuickExportHandlerTest не полна — после замены handler тесты не соберутся
QuickExportHandler.kt заменяется целиком, старый createProcessingKeyboard(...) исчезает, вместо него createProgressKeyboard(...). Но в тест-файле есть отдельный блок CreateProcessingKeyboardTest, вызывающий старый API (QuickExportHandlerTest.kt:197), а Step 2 плана просит обновить только конструктор и сигнатуры exportByRecordingId (plan:2660).

Фикс: явно заменить/удалить CreateProcessingKeyboardTest и все ссылки на createProcessingKeyboard(...).

### [DOC-1] Self-Review и File Structure расходятся с самим планом
Self-Review: Task 9 Step 3 "optional/flexible" (plan:3457), хотя Task 9 помечает оба теста mandatory (plan:2666). File Structure всё ещё ссылается на ключ export.cancelled.action (plan:41), тогда как в Task 6 и в дизайне используется export.cancelled.by.user.

Фикс: синхронизировать Self-Review и таблицу modified files с фактическим содержимым Task 6/9.

**Вывод codex:** первые три пункта блокирующие.

---

## gemini-executor

### [TYPE-BUG] Блокировка ExportCommandHandler и нарушение контракта удержания ActiveExportTracker
Task 10: ExportExecutor.execute() делает job.start() + job.join(). Это делает execute блокирующим на всё время экспорта (до 50 мин), нарушает design §2.1 ("Lock освобождается при передаче в ExportExecutor.execute()"), делает byChat-дедуп registry мёртвым кодом для production (tracker уже не пустит), и подвешивает корутину обработчика Telegram-сообщения.

Решение: убрать job.join() из execute(). Метод должен работать fire-and-forget: job.start() и сразу вернуть управление.

### [TYPE-TEST] Ошибка assertion в тесте cancelJob rethrows parent CancellationException
`assertEquals(true, job.isCancelled)` — тавтология. Состояние isCancelled устанавливается в true в ту же миллисекунду, когда вызывается job.cancel(). Даже если cancelJob поймает CancellationException и НЕ сделает throw e, тест всё равно пройдёт (False Positive).

Решение: проверять факт проброса исключения напрямую — try/catch с установкой флага, либо fail() после подвешенного вызова.

### [TYPE-BUG] Некорректный текст ошибки при гонке (TOCTOU) в CancelExportHandler
Между registry.get и registry.markCancelling существует окно: если экспорт завершится естественно в эту микросекунду, markCancelling правомерно вернёт null. Пользователь получит "Отмена уже выполняется" вместо "Экспорт уже завершён".

Решение: дополнить проверку результата markCancelling дополнительным чтением registry.get для дифференциации причин.

**Вывод gemini:** за исключением C1 план готов.

---

## ccs-executor (glm direct, from log stream — agent hung mid-analysis)

### [PLAN-1] Internal inconsistency: export.cancelled.action vs export.cancelled.by.user
File Structure table references export.cancelled.action but Task 6 and design §7 use export.cancelled.by.user.

### [CONSISTENCY-1] runCatching on suspend calls in ExportExecutor timeout paths
Строки 2928 и 2969 используют runCatching { bot.editMessageText(...) }.onFailure { ... }. Inconsistent with answerSafely() pattern (iter-2): runCatching ловит Throwable including CancellationException. Следует использовать try/catch + rethrow.

### [CODE-1] Unused recordingId parameter in createProgressKeyboard
Function accepts @Suppress("unused") recordingId: UUID but never uses it. Dead parameter should be removed.

### [MINOR-1] Test count mismatch in Task 2 Step 3
Says "all 4 tests fail" but Step 2 defines 5 test methods.

### [STYLE-1] Variable shadows function name in ExportExecutor
val cancelKeyboard = cancelKeyboard(exportId, lang) shadows private fun cancelKeyboard.

---

## ccs-executor (albb-glm / glm-5)

### [BUG-1] Task 5 Step 3 — бессмысленный stub в catch-блоке
```kotlin
} catch (e: Exception) {
    @Suppress("ktlint:standard:no-unused-imports")
    Unit
}
```
Design §9 Observability требует WARN для unexpected errors. Заменить на logger.warn(e) { ... }.

### [TEST-1] Task 10 Step 3 — ExportExecutorTest без @AfterEach cleanup
Тест создаёт ExportCoroutineScope и вызывает scope.shutdown() в конце каждого теста вручную. Не следует pattern из Task 5 Step 1 (@AfterEach).

Фикс: добавить @AfterEach с cleanup для consistency.

### [DOC-1] Self-Review missing Lock Ordering Invariant
Self-Review (3426-3469) покрывает spec coverage, placeholder scan, type consistency, но не упоминает Lock Ordering Invariant (Task 12 Step 1).

Фикс: добавить пункт 5.

### [STYLE] dismissed / minor

---

## ccs-executor (albb-qwen / qwen3.5-plus)

### [BUG-1] answerSafely молча глотает ошибки Telegram API
Silent swallowing → пользователь может увидеть висящий spinner.

### [BUG-2] Потенциальная гонка между job.start() и job.join() в ExportExecutor.execute()
(Subset of codex CRITICAL-1.)

### [DESIGN-1] Неявный контракт между ExportExecutor.execute() и ActiveExportTracker.tryAcquire() не документирован
Предлагается добавить require или KDoc.

### [TEST-1] Отсутствует тест проверки restoreButton() с правильной клавиатурой после отмены

### [DOC-1] Формат callback np: (noop-ack) не упомянут в .claude/rules/telegram.md

### [MINOR-1] Consistency моков

---

## ccs-executor (albb-kimi / kimi-k2.5) — response truncated at token limit

### [CRIT-1] Race condition в attachCancellable без synchronized
attachCancellable читает entry.state без synchronized. @Volatile но два @Volatile поля могут иметь несоответствие happens-before.

### [WARN-2] exportScope.launch в attachCancellable без structured concurrency
Fire-and-forget без Job reference. Добавить комментарий или возвращать Job.

### [CRIT-3] Несоответствие порядка catch в cancelJob
(Duplicate of iter-1 CRITICAL-3 — already resolved.)

### [WARN-4] runCatching vs try/catch в ExportExecutor непоследователен
(Duplicate of glm direct CONSISTENCY-1.)

### [CRIT-5] @JvmDefaultWithCompatibility для SAM CancellableJob
(Irrelevant for Kotlin-only usage.)

### [WARN-6] VideoExportProgress.Stage не содержит CANCELLING
UI показывает "Отменяется…", но progress callback говорит "ANNOTATING 45%". Несоответствие.

### [CRIT-7] restoreButton ordering после cancel
В Task 9, restoreButton вызывается ДО sendTextMessage (line 2493 vs 2489). Если sendTextMessage упадёт, кнопка уже восстановлена.

### [WARN-8] Дублирование invokeOnCompletion + finally release
Не объяснено, почему оба механизма нужны одновременно.

### [CRIT-9] Тест cancelJob rethrows parent CancellationException ненадёжен
Thread.sleep(10_000) дольше чем cancelTimeout. TimeoutCancellationException всё равно сработает. assertEquals(true, job.isCancelled) тавтология. (Duplicate of gemini TEST.)

### [WARN-10] ActiveExportRegistryTest использует GlobalScope
Антипаттерн. Лучше test scope.

### [WARN-11] Нет теста для cleanupExportFile при CancellationException в downloadJobResult

### [INFO-12] Дублирование Known Limitations между спецификацией и планом

### [WARN-13] В Task 12 отсутствует обновление i18n для export.error.concurrent
(False positive — ключ exists в messages_ru.properties:76.)

### [CRIT-14] Циклическая зависимость Task 5 и Task 6
(Already documented with blockquote.)

### [WARN-15] Task 9 Step 3 содержит runTest — проверить classpath
(runTest is in kotlinx-coroutines-test, standard.)

### [CRIT-16] Не обработан случай markCancelling во время attachCancellable
(Same as CRIT-1.)

---

## ccs-executor (albb-minimax / MiniMax-M2.5)

**Вердикт:** Новых замечаний нет. План готов к реализации. Верифицировано 50 tool calls.

---

## Consolidated summary

**Валидные новые замечания:** 15 уникальных после dedup.

- CRITICAL: 1 (codex C1 / gemini / qwen BUG-2 — job.join() collapses two-tier lock)
- BUG: 7 (release race, cancelJob deserialization, stub Unit, TOCTOU error text, runCatching timeout, stale CreateProcessingKeyboardTest, unused recordingId)
- TEST: 4 (tautology assertion, Thread.sleep timing, missing @AfterEach, GlobalScope)
- DOC: 5 (export.cancelled.action, Self-Review mandatory/optional, missing Lock Ordering bullet, np: format, 4-vs-5 test count)
- STYLE: 1 (cancelKeyboard shadow)

**Отклонено:** 15+ замечаний (false positives / duplicates / already resolved / out of scope / over-engineering).

**Блокирующие:** 3 (codex C1, codex B1, codex B2).
