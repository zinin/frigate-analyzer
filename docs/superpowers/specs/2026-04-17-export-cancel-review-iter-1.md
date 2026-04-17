# Review Iteration 1 — 2026-04-17

## Источник

- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Review agents: codex-executor (gpt-5.4), gemini-executor, ccs-executor (albb-glm, albb-qwen, albb-kimi, albb-minimax). `ccs/glm` (direct) зависал 15 минут — пропущен.
- Merged output: `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] Удаление ActiveExportTracker ломает /export dialog

> Codex: `ActiveExportTracker` защищает фазу диалога; его удаление позволит двум параллельным `/export`-диалогам в одном DM перехватывать ответы друг у друга.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** ActiveExportTracker сохраняется для dialog-phase lock. Registry отвечает только за execution-phase.
**Действие:** design.md §2.1, §2.2 — описана двухуровневая схема локов. plan.md File Structure — убран delete. Task 10 Step 2 — обновлён: `ActiveExportTracker` остаётся в конструкторе `ExportCommandHandler`, `tryAcquire`/`release` вокруг `runDialog`. Task 11 переведён в SKIPPED.

---

### [CRITICAL-2] Обещание отмены на MERGING/COMPRESSING не выполнимо

> Codex: `VideoMergeHelper.process.waitFor(...)` блокирующий, `CancellationException` не прерывает ffmpeg синхронно. Дизайн обещает отмену на всех этапах.

**Источник:** codex-executor
**Статус:** Автоисправлено (сужен scope, задокументировано честно)
**Ответ:** Оставляем coroutine-level отмену. Явно документируем в Non-Goals и Known Limitations, что ffmpeg не прерывается мгновенно — пользователь увидит "Отменяется…" сразу, но финальный "Отменён" — после завершения текущей ffmpeg-операции.
**Действие:** design.md §1 Non-Goals — новая bullet про ffmpeg best-effort. §11 Known Limitations — новая секция. §5.3 edge cases — отдельная строка про MERGING/COMPRESSING.

---

### [CRITICAL-3] Catch-ordering в DetectService.cancelJob (TimeoutCancellationException)

> Codex BUG-1, Gemini BUG-1: `catch (CancellationException)` ловит `TimeoutCancellationException` раньше (subtype), timeout не логируется как WARN.

**Источник:** codex-executor, gemini-executor
**Статус:** Автоисправлено
**Ответ:** Переставлен `catch (TimeoutCancellationException)` выше `catch (CancellationException)`.
**Действие:** plan.md Task 2 Step 4 — обновлён блок catch.

---

### [CRITICAL-4] markCancelling vs release — TOCTOU на уровне map-entry

> Codex BUG-2, Kimi CRITICAL-1: `val entry = byExportId[exportId]; synchronized(entry)` — между чтением и synchronized может произойти `release()`.

**Источник:** codex-executor, ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Переписано на `ConcurrentHashMap.computeIfPresent` + `synchronized(entry)` — атомарно на уровне map operation. release() также обёрнут в `synchronized(entry)` для атомарности удаления из всех индексов.
**Действие:** plan.md Task 5 Step 3 — новая реализация markCancelling и release. design.md §4 — уточнено про computeIfPresent и synchronized.

---

### [CRITICAL-5] LAZY cancel before body → registry leak

> albb-glm CRITICAL-2: если cancel до первого suspension point, `finally { release() }` не отработает — запись остаётся в registry навсегда.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Добавлен `job.invokeOnCompletion { registry.release(exportId) }` сразу после создания LAZY-job. `release` идемпотентен, двойной вызов безопасен.
**Действие:** plan.md Task 9 Step 1 (QuickExport), Task 10 Step 1 (ExportExecutor) — `invokeOnCompletion` после `exportScope.launch(start=LAZY)`. design.md §5.3 edge cases — новая строка.

---

### [CRITICAL-6] Partial init race в tryStart* → permanent lock

> albb-glm CRITICAL-1: `putIfAbsent(byRecordingId)` succeeds, но до `byExportId[...] = Entry` поток отменён — recordingId залочен навсегда, потому что release() early-return не чистит secondary index.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** `tryStartQuickExport` / `tryStartDialogExport` обёрнуты в `synchronized(startLock)` — атомарно относительно `release()` под `synchronized(entry)`.
**Действие:** plan.md Task 5 Step 3 — добавлен `private val startLock`.

---

### [CRITICAL-7] Race `submitWithRetry` → `attachCancellable` → orphan job на vision server

> Qwen CRITICAL-1, MiniMax CRITICAL-2: если cancel пришёл ровно между `jobId = job.jobId` и `attachCancellable`, `cancellable == null` при чтении handler'ом — vision server не получит cancel.

**Источник:** ccs/albb-qwen, ccs/albb-minimax (и упомянут как MINOR в gemini)
**Статус:** Автоисправлено (check-on-attach retry)
**Ответ:** `attachCancellable` теперь проверяет `entry.state == CANCELLING` на момент публикации; если CANCELLING — запускает `cancellable.cancel()` в ExportCoroutineScope.
**Действие:** plan.md Task 5 Step 3 — реализация `attachCancellable` с check-on-attach. design.md §2.3 — описание. §11 Known Limitations — остаточный risk задокументирован.

---

### [BUG-1] Dead code в buildCancellingKeyboard (проверка по mode)

> Gemini BUG-2, Kimi BUG-2: `ExportMode` всегда попадает в if-ветку — else-ветка недостижима. Для различения QuickExport vs /export нужен `recordingId != null`.

**Источник:** gemini-executor, ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Сигнатура `buildCancellingKeyboard(exportId, recordingId: UUID?, lang)` — различаем по `recordingId`.
**Действие:** plan.md Task 7 Step 3 — обновлена реализация.

---

### [BUG-2] parseExportId double-strip пропускает "xc:np:uuid"

> Qwen DESIGN-2: `data.removePrefix(CANCEL).removePrefix(NOOP)` парсит "xc:np:uuid" → "uuid", что семантически неверно.

**Источник:** ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** Используем явную `startsWith` проверку, выбираем точно один префикс.
**Действие:** plan.md Task 7 Step 3 — new `parseExportId` with strict prefix check.

---

### [BUG-3] DuplicateChat branch в QuickExport недостижим

> Kimi BUG-1: `tryStartQuickExport` не возвращает `DuplicateChat`. Ветка игнорирует error и шлёт неверное сообщение.

**Источник:** ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Ветка заменена на `error("Unexpected DuplicateChat from tryStartQuickExport")` — fail-loudly.
**Действие:** plan.md Task 9 Step 1. Симметрично Task 10 Step 1 — `DuplicateRecording` в `/export` заменён на `error(...)`.

---

### [BUG-4] Cancel button остаётся на финальном экране /export при early-return timeouts

> Codex BUG-3: `processing timeout` и `send timeout` делают `sendTextMessage` + `return` без `editMessageText(statusMessage, replyMarkup = null)`. Живая кнопка "Отмена" остаётся.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Обе early-return ветки теперь делают `editMessageText(statusMessage, errorText, replyMarkup = null)` с fallback на `sendTextMessage` при ошибке редактирования.
**Действие:** plan.md Task 10 Step 1 — обновлены обе timeout-ветки. design.md §3.3 — новая строка в таблице `/export`.

---

### [BUG-5] Temp-file leak при CancellationException

> albb-glm BUG-1, BUG-2: `catch (Exception)` в `downloadJobResult` и `VideoExportServiceImpl.annotate` не ловит `CancellationException`. Temp-файл осиротеет при cancel/shutdown.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Добавлены явные `catch (CancellationException) { cleanup; throw }` до `catch (Exception)` в обоих местах.
**Действие:** plan.md Task 2 Step 4b (новый шаг для downloadJobResult) и Task 4 Step 2 — отдельный catch-блок в `annotate()`. design.md §5.3 — две новые строки.

---

### [BUG-6] authFilter.getRole — suspend, тесты используют every вместо coEvery

> Codex TEST-2: проверено в коде — метод действительно `suspend`.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** `every { authFilter.getRole(...) }` → `coEvery { ... }` во всех местах тестов.
**Действие:** plan.md Task 7 Step 1 — все вызовы заменены.

---

### [TEST-1] ExportExecutorTest mandatory (было optional)

> Codex TEST-1, Qwen TEST-2, MiniMax TEST-2: cancel-flow для `/export` должен тестироваться, сейчас не покрыт.

**Источник:** codex-executor, ccs/albb-qwen, ccs/albb-minimax
**Статус:** Автоисправлено
**Ответ:** `ExportExecutorTest` — новый файл с mandatory cancellation test. Optional-языка удалён из Task 10 Step 3.
**Действие:** plan.md Task 10 Step 3 — полный mandatory тест на cancel → editMessageText(replyMarkup=null) + registry released. Plus smoke test на DuplicateChat.

---

### [TEST-2] QuickExport cancellation test mandatory (был optional)

> Codex TEST-1, albb-glm MINOR-2, minimax TEST-1: Task 9 Step 3 допускал "только форма клавиатуры" — слишком слабо.

**Источник:** codex-executor, ccs/albb-glm, ccs/albb-minimax
**Статус:** Автоисправлено
**Ответ:** Task 9 Step 3 теперь содержит два обязательных теста: форма клавиатуры + end-to-end cancel flow. Добавлен `internal snapshotForTest()` в registry для тестов.
**Действие:** plan.md Task 9 Step 3 — обновлён. Task 5 Step 3 — добавлен `snapshotForTest()`.

---

### [TEST-3] Отсутствуют тесты на TOCTOU и attachCancellable

> Qwen TEST-1, albb-glm TEST-1, albb-glm TEST-2: критические race'ы не покрыты.

**Источник:** ccs/albb-qwen, ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Добавлены тесты: `markCancelling after release returns null`, `attachCancellable fires cancel when CANCELLING`, `attachCancellable does not fire when ACTIVE`. Jobs в `@AfterEach` очищаются.
**Действие:** plan.md Task 5 Step 1 — три новых теста + `@AfterEach`.

---

### [DOC-1] i18n key naming inconsistency

> albb-glm MINOR-1: `quickexport.cancelled` vs `export.cancelled.by.user`. Kimi BUG-3: `export.cancelled` уже занят dialog-cancel path.

**Источник:** ccs/albb-glm, ccs/albb-kimi
**Статус:** Отклонено (уже учтено)
**Ответ:** `export.cancelled.by.user` был выбран специально из-за существующего `export.cancelled` (используется диалоговым cancel). Namings не симметричны намеренно — `quickexport.cancelled` не конфликтует, поэтому единого суффикса не требуется. Разница зафиксирована в Task 6.

---

### [DOC-2] Known Limitations секция

> MiniMax DOC-1: orphan jobs и ffmpeg явно не в "Known Limitations".

**Источник:** ccs/albb-minimax
**Статус:** Автоисправлено
**Ответ:** Добавлена новая секция §11 Known Limitations в design с тремя пунктами.
**Действие:** design.md §11 — новая секция. Bumped §11 Out-of-Scope → §12.

---

### [DISMISSED] MiniMax CRITICAL-1: multiple onDataCallbackQuery конфликтуют

**Источник:** ccs/albb-minimax
**Статус:** Отклонено
**Ответ:** ktgbotapi штатно поддерживает множественные `onDataCallbackQuery` с разными `initialFilter`; filter'ы для `qe:/qea:` и `xc:/np:` не пересекаются. False positive.

### [DISMISSED] MiniMax DESIGN-1: LAZY + withTimeoutOrNull

**Ответ:** План уже размещает `withTimeoutOrNull` внутри `runExport` (тела корутины). `job.cancel()` извне корректно прерывает. False positive.

### [DISMISSED] MiniMax DESIGN-2: recordingId dedup semantics

**Ответ:** Registry держит `byRecordingId` индекс и возвращает `DuplicateRecording` — семантика сохранена. False positive.

### [DISMISSED] Qwen DESIGN-1: ExportCoroutineScope избыточен

**Ответ:** Единая точка graceful shutdown — ценность есть (заменяет три независимых scope в разных handler'ах). Сохранён.

### [DISMISSED] Kimi DESIGN-1: ExportCallbacks object

**Ответ:** Два необязательных коллбека — не телескопический конструктор. YAGNI.

### [DISMISSED] Kimi DESIGN-3: disabled keyboard after cancel

**Ответ:** Non-blocking UX improvement, вне scope. Зафиксировано в §12 Out of Scope.

### [DISMISSED] albb-glm DESIGN-1: temp file cleanup strategy (duplicate)

**Ответ:** Уже покрыто BUG-5 (CancellationException cleanup). Duplicate of BUG-5 — решено вместе с ним.

### [DISMISSED] albb-glm DESIGN-2: CAS через AtomicReference

**Ответ:** Текущий `computeIfPresent + synchronized(entry)` семантически эквивалентен `AtomicReference.compareAndSet`. Оставлено как есть.

### [DISMISSED] Qwen BUG-1: парсинг 409 body

**Ответ:** 409 редкий; статус не критичен для UX (UI всё равно показывает "Отменён"). Логируется на INFO с exit-статусом — достаточно. Не применяю.

### [DISMISSED] Qwen MINOR-2: magic numbers timeouts

**Ответ:** Существующее поведение не в scope текущей задачи. Отдельная инициатива.

### [DISMISSED] MiniMax MINOR-1: commit order

**Ответ:** План задачи 10 и 11 уже в корректном порядке (миграция сначала, тогда Task 11 был бы удалением). Task 11 теперь SKIPPED.

### [DISMISSED] MiniMax MINOR-2: e2e mandatory

**Ответ:** Task 13 Step 4 уже описывает e2e как manual validation (documented). Обязательность для реализатора — вне контроля плана.

### [DISMISSED] albb-glm TEST-3: shutdown integration test

**Ответ:** Интеграционный тест shutdown с реальным scope — тяжёлый, мало-value. Graceful shutdown покрывается unit-тестом `ExportCoroutineScope.shutdown()` (неявный через @PreDestroy).

### [DISMISSED] Qwen DOC-1: migration grep

**Ответ:** Task 11 больше не удаляет ActiveExportTracker; grep-before-delete не актуален.

### [DISMISSED] Kimi DOC-2: backward compatibility legacy messages

**Ответ:** Включено в §11 Known Limitations design.

### [DISMISSED] Kimi DESIGN-2: task order (ExportCoroutineScope into Task 1)

**Ответ:** Task 5 теперь зависит от Task 6 (ExportCoroutineScope передаётся в ActiveExportRegistry); зависимость явно указана в plan, рекомендовано выполнять Task 6 перед Task 5. Task reorder не нужен — зависимость документирована.

### [DISMISSED] MiniMax SECURITY-1: chatId leak

**Ответ:** Уже сделано: generic "не активно" без раскрытия chatId из entry. False positive.

### [DISMISSED] albb-glm BUG-3: TOCTOU UI message

**Ответ:** Minor acceptable inconsistency, documented как `cancel.error.not.active` fallback. Не исправляем.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-17-export-cancel-design.md` | §1 Non-Goals: ffmpeg best-effort bullet. §2.1: ActiveExportTracker keep + registry execution-phase only. §2.2: deleted files clarification. §2.3: check-on-attach retry для attachCancellable. §3.3: early-return `/export` table row. §4: computeIfPresent + synchronized для markCancelling и release, startLock для tryStart*. §5.3: CancellationException cleanup для download/annotate, ffmpeg-stage note, invokeOnCompletion fallback. §11 Known Limitations: новая секция. §12 Out of Scope: переномерована, добавлены bullets. |
| `docs/superpowers/plans/2026-04-17-export-cancel.md` | Task 2 Step 4: catch reorder + Step 4b (downloadJobResult). Task 4 Step 2: CancellationException catch в annotate. Task 5 Step 1: новые тесты (after-release, attachCancellable). Task 5 Step 3: computeIfPresent, synchronized release, startLock, attachCancellable check-on-attach, snapshotForTest, ExportCoroutineScope DI. Task 7 Step 1: coEvery для suspend authFilter.getRole. Task 7 Step 3: buildCancellingKeyboard по recordingId, strict parseExportId. Task 9 Step 1: invokeOnCompletion, DuplicateChat → error(). Task 9 Step 3: mandatory cancellation tests. Task 10 Step 1: invokeOnCompletion, DuplicateRecording → error(), early-return replyMarkup=null. Task 10 Step 2: keep ActiveExportTracker для dialog-phase. Task 10 Step 3: mandatory ExportExecutorTest. Task 11: SKIPPED. Task 12: telegram.md structure updated с Known Limitations. File Structure: deleted files — none. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-1.md` | Новый файл: все 6 ревью собраны + consolidated summary. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-1.md` | Этот файл. |

## Статистика

- Всего замечаний: 35 (6 reviewers, частично пересекающихся)
- CRITICAL автоисправлено: 7
- BUG автоисправлено: 6
- TEST автоисправлено: 3
- DOC автоисправлено: 1
- Отклонено (false positives / out-of-scope / duplicate / already-covered): 18
- Повторов (автоответ): 0 (итерация 1)
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Пропущено: ccs-executor (glm direct profile) — hung 15+ min, skipped per skill's error-handling policy
