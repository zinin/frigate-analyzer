# Review Iteration 3 — 2026-04-17 20:40

## Источник

- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Previous iters: iter-1, iter-2.
- Review agents: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm direct, albb-glm, albb-qwen, albb-kimi, albb-minimax). `ccs/glm` direct повис в середине анализа (output.txt не сгенерирован), но findings из потокового лога захвачены и обработаны. `albb-kimi` ответ обрезан на границе output-токенов — тело 16 замечаний получено полностью, финальный summary оборван.
- Merged output: `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-3.md`

## Замечания

### [CRITICAL-11] `/export` схлопывает двухуровневую lock-модель через `job.join()`

> codex / gemini / ccs-qwen: `ExportExecutor.execute()` делает `job.start()` + `job.join()` (plan:2845–2847), а `ExportCommandHandler.handle()` отпускает `ActiveExportTracker` в `finally` после возврата из `execute()` (plan:3092,3121). Это делает execute блокирующим на всё время экспорта (до 50 минут), нарушает design §2.1 ("Lock освобождается при передаче в ExportExecutor.execute()"), делает `byChat`-дедуп registry мёртвым кодом для production (tracker уже не пустит), и подвешивает корутину обработчика Telegram-сообщения. Текущий код (в master) специально делает fire-and-forget handoff через `exportScope.launch` в ExportCommandHandler (ExportCommandHandler.kt:58).

**Источник:** codex-executor, gemini-executor, ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** Убран `job.join()` из `ExportExecutor.execute()`. После `tryStartDialogExport == Success` вызывается только `job.start()`, метод возвращает управление немедленно (fire-and-forget). ExportCommandHandler `finally { tracker.release }` срабатывает сразу, двухуровневая модель сохранена: tracker удерживается только на dialog-phase, registry.byChat владеет execution-phase дедупом.
**Действие:** plan.md Task 10 Step 1 — `is Success -> { job.start() }` (удалён `job.join()`), добавлен комментарий с обоснованием. plan.md File Structure table (line 38) — обновлено описание `ExportExecutor.kt`. design.md §2.1 ActiveExportTracker — обновлена строка с явным указанием fire-and-forget семантики и предупреждением о последствиях блокировки.

---

### [BUG-12] Окно на stale secondary indices в `ActiveExportRegistry.release()` → ложный `Duplicate*`

> codex: `release()` удаляет `byExportId` первым (для предотвращения dual-lock deadlock с `markCancelling`), потом под `synchronized(entry)` чистит `byRecordingId`/`byChat`. Между двумя шагами новый `tryStart*` проверяет secondary index напрямую (plan:1342) и видит висячий `exportId`, возвращая `DuplicateChat`/`DuplicateRecording`, хотя экспорт уже освобождён.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** `tryStartQuickExport` и `tryStartDialogExport` теперь делают self-heal: после `putIfAbsent` вернул не-null, проверяют `byExportId.containsKey(existing)`. Если primary index уже не содержит существующий exportId — это leftover, перезаписывают secondary index и продолжают как Success. Race-test добавлен.
**Действие:** plan.md Task 5 Step 3 — `tryStartQuickExport` и `tryStartDialogExport` обновлены с self-heal логикой. plan.md Task 5 Step 1 — новый тест `start-vs-release race — tryStart after release returns Success, not a false Duplicate`.

---

### [BUG-13] Happy-path `cancelJob` не совместим с моделью ответа и `ObjectMapper`

> codex: план использует `bodyToMono<JobStatusResponse>()` (plan:408), но `JobStatus` enum (JobStatus.kt) не знает `CANCELLED`, так что десериализация `{"status":"cancelled"}` провалится. Плюс тест строит `buildObjectMapper(): tools.jackson.databind.ObjectMapper` (plan:362), а `DetectService` принимает `com.fasterxml.jackson.databind.ObjectMapper` (DetectService.kt:3).

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:**
1. `cancelJob` переведён на bodiless response: `.retrieve().toBodilessEntity().awaitSingleOrNull()` — клиенту неважно, что в body, важен только HTTP-status (200 = принято, 409 = already terminal).
2. Тест теперь имеет **два** mapper-helper'а (в соответствии с pattern'ом существующего `DetectServiceTest`): `buildJsonMapper()` возвращает `tools.jackson.databind.json.JsonMapper` для WebClient codecs (Spring Boot 4.0 Jackson 3 integration), `buildObjectMapper()` возвращает `com.fasterxml.jackson.databind.ObjectMapper` для DetectService ctor. Импорты выровнены.
**Действие:** plan.md Task 2 Step 4 — реализация `cancelJob` использует `toBodilessEntity()` с комментарием про отсутствие `CANCELLED` в enum. plan.md Task 2 Step 2 — имена helper'ов и типы ObjectMapper выровнены с DetectServiceTest.kt:494/501; WebClient использует `buildJsonMapper()`, DetectService — `buildObjectMapper()`; импорт `FasterxmlObjectMapper` добавлен.

---

### [BUG-14] Stub `Unit` с `@Suppress` в `attachCancellable` catch-ветке

> ccs/albb-glm: `catch (e: Exception) { @Suppress("ktlint:standard:no-unused-imports") Unit }` — design §9 Observability требует WARN для unexpected errors.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Заменено на `logger.warn(e) { "Vision server cancel failed on attach-retry path, exportId=$exportId" }`. Consistent with design §9 observability table.
**Действие:** plan.md Task 5 Step 3 — `attachCancellable` catch-блок обновлён.

---

### [BUG-15] TOCTOU в `CancelExportHandler`: неверный текст ошибки при естественной гонке завершения

> gemini: между `registry.get(exportId)` и `registry.markCancelling(exportId)` окно — если экспорт завершится естественно в эти микросекунды, `markCancelling` правомерно вернёт null, но handler покажет "Отмена уже выполняется" вместо "Экспорт уже завершён".

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** После `markCancelling == null` делается второе чтение: `val stillPresent = registry.get(exportId) != null`. Если запись ещё есть — она в `CANCELLING` (показать `cancel.error.already.cancelling`), иначе — уже освобождена (показать `cancel.error.not.active`).
**Действие:** plan.md Task 7 Step 3 — реализация `handle()` обновлена с disambiguation.

---

### [BUG-16] `runCatching` вокруг suspend `bot.editMessageText` в ExportExecutor timeout paths

> ccs/albb-kimi WARN-4, ccs/glm direct CONSISTENCY-1: строки 2928 и 2969 используют `runCatching { bot.editMessageText(...) }.onFailure { ... }`. Inconsistent с iter-2 BUG-8 / `answerSafely` pattern — runCatching ловит Throwable including CancellationException, что может подавить propagation отмены.

**Источник:** ccs/albb-kimi, ccs/glm direct
**Статус:** Автоисправлено
**Ответ:** Оба блока (processing-timeout и send-timeout early-return) переписаны на `try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { logger.warn(...); bot.sendTextMessage(fallback) }`. Консистентность с answerSafely восстановлена.
**Действие:** plan.md Task 10 Step 1 — оба runCatching-блока заменены.

---

### [BUG-17] Миграция `QuickExportHandlerTest` не полна — stale `CreateProcessingKeyboardTest` не компилируется

> codex: Task 9 Step 1 переименовывает `createProcessingKeyboard(...)` → `createProgressKeyboard(...)` с иной сигнатурой, но в тест-файле есть отдельный `@Nested inner class CreateProcessingKeyboardTest` с четырьмя ссылками на старый API (QuickExportHandlerTest.kt:197–236). Step 2 просит обновить только ctor и mock-expectations, — компиляция после Task 9 Step 1 упадёт.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Task 9 Step 2 дополнен явной инструкцией: удалить целиком `@Nested inner class CreateProcessingKeyboardTest` — keyboard-shape покрывается end-to-end тестами в Step 3, отдельный unit-тест старого API даст только compile error.
**Действие:** plan.md Task 9 Step 2 — добавлен явный абзац "Also: remove the obsolete CreateProcessingKeyboardTest inner class...".

---

### [BUG-18] Unused `recordingId` параметр в `createProgressKeyboard`

> ccs/glm direct CODE-1: `createProgressKeyboard` принимает `@Suppress("unused") recordingId: UUID`, но нигде его не использует. Dead parameter + silenced suppress — bad pattern.

**Источник:** ccs/glm direct
**Статус:** Автоисправлено
**Ответ:** Параметр удалён из сигнатуры, из 3-х call-sites в Task 9 Step 1, из unit-теста в Step 3. Документация функции поясняет, почему `recordingId` не нужен (initial keyboard восстанавливается отдельно через `restoreButton(message, recordingId, ...)`).
**Действие:** plan.md Task 9 Step 1 — функция `createProgressKeyboard` обновлена; все 3 call-site внутри `runExport` / `onProgress` скорректированы; unit-тест в Step 3 перепроверен.

---

### [TEST-8] `assertEquals(true, job.isCancelled)` — тавтология

> gemini, ccs/albb-kimi CRIT-9: `job.cancel()` всегда ставит `isCancelled = true`, независимо от того, проглотил ли `cancelJob` CancellationException или пробросил. Тест всегда проходит как false positive. Дополнительно kimi отметил хрупкость Thread.sleep(10_000) vs cancelTimeout=2s.

**Источник:** gemini-executor, ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Тест переписан: launched coroutine теперь оборачивает `service.cancelJob(...)` в `try/catch`, завершает `CompletableDeferred<Throwable>` либо с пойманным CancellationException (успех), либо с `AssertionError("returned normally")` (fail). Основная секция `runBlocking` ждёт deferred и утверждает `caught is CancellationException && caught !is TimeoutCancellationException`. Плюс sleep увеличен до 60s (far longer than cancelTimeout=2s), чтобы внешний cancel точно опередил timeout.
**Действие:** plan.md Task 2 Step 2 — тест `cancelJob rethrows parent CancellationException` переписан.

---

### [TEST-9] `ExportExecutorTest` без `@AfterEach` cleanup

> ccs/albb-glm TEST-1: тест создаёт `ExportCoroutineScope` per-test и вручную вызывает `scope.shutdown()`. Нет защиты от leak, если тест упадёт до shutdown. Inconsistent с Task 5 Step 1 `@AfterEach tearDown()`.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Добавлен class-level `ConcurrentLinkedQueue<ExportCoroutineScope>` для tracking scopes, вспомогательная функция `newScope()` регистрирует созданный scope, `@AfterEach tearDown()` делает `shutdown()` для каждого. Оба существующих теста переведены с `ExportCoroutineScope()` на `newScope()`. Per-test `scope.shutdown()` в конце happy path оставлены — double-shutdown безопасен через `runCatching`.
**Действие:** plan.md Task 10 Step 3 — `ExportExecutorTest` получил `createdScopes`/`newScope()`/`@AfterEach tearDown()`; два call-site `ExportCoroutineScope()` заменены на `newScope()`.

---

### [TEST-10] `GlobalScope` в `ActiveExportRegistryTest` LAZY-cancel тесте

> ccs/albb-kimi WARN-10: `kotlinx.coroutines.GlobalScope.launch(...)` — антипаттерн (leak coroutines across JVM).

**Источник:** ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Заменено на локальный `CoroutineScope(SupervisorJob())` с `localScope.cancel()` в `finally`. Комментарий объясняет выбор.
**Действие:** plan.md Task 5 Step 1 — тест `release invoked via invokeOnCompletion when LAZY job cancelled before first suspension` переписан.

---

### [DOC-6] File Structure: `export.cancelled.action` расходится с реальным ключом `export.cancelled.by.user`

> codex DOC-1, ccs/glm direct PLAN-1: File Structure table (plan:41) перечисляет `export.cancelled.action`, а Task 6 и design §7 создают/используют `export.cancelled.by.user`.

**Источник:** codex-executor, ccs/glm direct
**Статус:** Автоисправлено
**Ответ:** File Structure row обновлён на `export.cancelled.by.user`. Self-Review получил новый пункт 6 про i18n key consistency.
**Действие:** plan.md line 41, Self-Review section — обновлены.

---

### [DOC-7] Self-Review противоречит Task 9 Step 3 (optional vs mandatory)

> codex DOC-1: Self-Review §2 Placeholder scan (plan:3522) называет Task 9 Step 3 тест "optional / flexible", но сам Task 9 Step 3 помечает оба теста как mandatory (plan:2716 после iter-1 TEST-2).

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Self-Review §2 переписан — явно указано, что оба теста mandatory per iter-1 TEST-2.
**Действие:** plan.md Self-Review §2 Placeholder scan — обновлён.

---

### [DOC-8] Self-Review не упоминает Lock Ordering Invariant

> ccs/albb-glm DOC-1: Self-Review покрывает spec coverage, placeholders, type consistency, methods/types — но не упоминает Lock Ordering Invariant, добавленный в iter-2 (Task 12 Step 1).

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Добавлен пункт 5 в Self-Review с сжатой формулировкой инварианта: tracker before registry; release() remove-before-synchronized.
**Действие:** plan.md Self-Review — добавлен §5 Lock Ordering Invariant, плюс §6 i18n key consistency.

---

### [DOC-9] `np:` (noop-ack) callback формат не задокументирован в telegram.md

> ccs/albb-qwen DOC-1: Task 12 Step 1 описывает `xc:` cancel, но `np:` noop (для inline-spinner Telegram-клиента) — не упомянут.

**Источник:** ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** Секция "Callback payload formats" в markdown-блоке Task 12 Step 1 дополнена двумя пунктами: `xc:` и `np:` с пояснением, зачем `np:` нужен (ktgbotapi требует callback-data для каждой inline-кнопки).
**Действие:** plan.md Task 12 Step 1 — описание callback-форматов расширено.

---

### [DOC-10] "all 4 tests fail" vs actual 5 test methods

> ccs/glm direct MINOR-1: Task 2 Step 3 говорит "Expected: all 4 tests fail", но Step 2 определяет 5 test methods.

**Источник:** ccs/glm direct
**Статус:** Автоисправлено
**Ответ:** "all 4 tests fail" → "all 5 tests fail".
**Действие:** plan.md Task 2 Step 3 — строка исправлена.

---

### [TEST-11] `delay(50)` в CancelExportHandlerTest happy-path flakyy на реальном Dispatchers.IO

> ccs/glm direct (retry): тест `handle for cancel happy path marks registry cancelling...` использует `runTest` (виртуальное время), но `attachCancellable` запускает `cancellable.cancel()` через `exportScope.launch` на `Dispatchers.IO` (реальный диспетчер, не контролируемый runTest). Виртуальный `delay(50)` — это 0 мс реального времени; IO-потоку может не хватить времени. В ActiveExportRegistryTest тот же паттерн уже переведён на `CompletableDeferred + withTimeout(5_000)` (iter-2 TEST-6).

**Источник:** ccs/glm direct (retry)
**Статус:** Автоисправлено
**Ответ:** `var cancellableCalled = false` заменено на `CompletableDeferred<Unit>()`; `delay(50) + assertTrue` заменено на `withTimeout(5_000) { cancellableCalled.await() }`. Паттерн синхронизирован с ActiveExportRegistryTest.
**Действие:** plan.md Task 7 Step 1 — тест `handle for cancel happy path...` переписан.

---

### [BUG-19] `catch(e: Exception)` в ExportExecutor progress-edit без явного `CancellationException`

> ccs/glm direct (retry): два места в `runExport` (строки ~3032 и ~3079 после iter-3 правок) оборачивают `bot.editMessageText(...)` в `try/catch(e: Exception)`, который в suspend-контексте ловит и `CancellationException`. Это задерживает propagation отмены на один bot-вызов, и нарушает конвенцию самого плана (answerSafely / onProgress catch-блок в том же файле делает явный `catch(CancellationException) { throw e }`).

**Источник:** ccs/glm direct (retry)
**Статус:** Автоисправлено
**Ответ:** Оба catch-блока получили явный `catch(e: CancellationException) { throw e }` перед `catch(e: Exception) { logger.warn(...) }`. Консистентность конвенции восстановлена.
**Действие:** plan.md Task 10 Step 1 — SENDING-progress и DONE-progress блоки обновлены.

---

### [BUG-20] `catch(e: Exception)` в QuickExportHandler при обновлении processing-кнопки

> ccs/glm direct (retry): строка ~2417 в `QuickExportHandler.handle()` оборачивает `bot.editMessageReplyMarkup(...)` в `try/catch(e: Exception)`. Единственное место в `QuickExportHandler`, где конвенция `answerSafely` / explicit CancellationException-rethrow нарушена.

**Источник:** ccs/glm direct (retry)
**Статус:** Автоисправлено
**Ответ:** Добавлен `catch(e: CancellationException) { throw e }` перед `catch(e: Exception)`.
**Действие:** plan.md Task 9 Step 1 — processing-keyboard update block обновлён.

---

### [STYLE-2] `val cancelKeyboard = cancelKeyboard(...)` shadows private fun

> ccs/glm direct STYLE-1: локальная переменная затеняет имя private fun — запутывает reader'а.

**Источник:** ccs/glm direct
**Статус:** Автоисправлено
**Ответ:** Переменная переименована в `cancelKeyboardMarkup` (все 3 use site обновлены).
**Действие:** plan.md Task 10 Step 1 — variable renamed.

---

### [DISMISSED] ccs/albb-qwen BUG-1: answerSafely молча глотает ошибки

**Источник:** ccs/albb-qwen
**Статус:** Отклонено
**Ответ:** `answerSafely()` (iter-2 BUG-8) намеренно ловит non-CancellationException и логирует WARN — пользователь может увидеть inline-spinner ещё ~3с, но критический путь cancel не блокируется. Предложенный reviewer'ом возврат Boolean — over-engineering: текущий helper сохраняет 1-to-1 с design §5 "CancelExportHandler.handle()". Spinner на Telegram-клиенте очищается через taptimeout, влияние на UX незначительно.

---

### [DISMISSED] ccs/albb-qwen DESIGN-1: контракт ExportExecutor/ActiveExportTracker не задокументирован

**Источник:** ccs/albb-qwen
**Статус:** Отклонено
**Ответ:** Покрыто Lock Ordering Invariant (Task 12 Step 1, добавлено в iter-2) и усилено CRITICAL-11 auto-fix — ExportExecutor.execute() теперь явно fire-and-forget с комментарием про обоснование. Дополнительный KDoc — noise.

---

### [DISMISSED] ccs/albb-qwen TEST-1: отсутствует тест restoreButton с правильной клавиатурой

**Источник:** ccs/albb-qwen
**Статус:** Отклонено
**Ответ:** Task 9 Step 3 имеет end-to-end тест `when user cancels — registry released and cancelled message sent`, который через coVerify проверяет `bot.editMessageReplyMarkup(..., replyMarkup = <initial keyboard>)`. Отдельный тест только для restoreButton — дублирование.

---

### [DISMISSED] ccs/albb-qwen BUG-2: race между job.start() и job.join()

**Источник:** ccs/albb-qwen
**Статус:** Отклонено (автоматически разрешено CRITICAL-11)
**Ответ:** job.join() удалён в CRITICAL-11. Race больше не существует.

---

### [DISMISSED] ccs/albb-qwen MINOR-1: consistency моков

**Источник:** ccs/albb-qwen
**Статус:** Отклонено
**Ответ:** Сам reviewer отметил "по факту не ошибка".

---

### [DISMISSED] ccs/albb-kimi CRIT-1 / CRIT-16: attachCancellable race без synchronized

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (уже учтено в iter-2)
**Ответ:** `Entry.state` объявлен `@Volatile`, JMM гарантирует, что volatile write/read формируют happens-before. `attachCancellable` сначала пишет `cancellable` (volatile), потом читает `state` (volatile) — volatile-volatile reordering запрещён. Эквивалент iter-2 BUG-19 dismissal. Добавление `synchronized(entry)` в горячий путь hurt производительности без реальной защиты.

---

### [DISMISSED] ccs/albb-kimi WARN-2: exportScope.launch без Job-reference

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** Fire-and-forget — намеренный дизайн. На graceful shutdown весь exportScope cancelled, все child-job'ы (в т.ч. этот запуск) корректно прерываются. Возврат Job для tracking не даёт ценности — caller только бы сразу форget'ил её.

---

### [DISMISSED] ccs/albb-kimi CRIT-3: порядок catch в cancelJob

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (дубликат iter-1 CRITICAL-3)
**Ответ:** Уже исправлено в iter-1. `catch (TimeoutCancellationException)` идёт раньше `catch (CancellationException)`.

---

### [DISMISSED] ccs/albb-kimi CRIT-5: @JvmDefaultWithCompatibility для SAM CancellableJob

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** `CancellableJob` — SAM interface, используется только из Kotlin (service/telegram слои). Java-interop — не-goal. Аннотация не даёт value.

---

### [DISMISSED] ccs/albb-kimi WARN-6: VideoExportProgress.Stage не содержит CANCELLING

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** UI состояние CANCELLING — orthogonal к progress-stage. Разделение преднамеренное: Stage описывает, что делает backend (PREPARING/MERGING/COMPRESSING/ANNOTATING/SENDING), CANCELLING — это UI overlay, управляется через registry.state. Добавление в enum усложнит backend-протокол без value.

---

### [DISMISSED] ccs/albb-kimi CRIT-7: restoreButton ordering после cancel

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** В случае падения sendTextMessage клавиатура уже восстановлена — end-state корректен. В случае успеха — видео отправлено отдельным сообщением, клавиатура "[Оригинал][Annotated]" на оригинальной notification восстановлена для возможного повтора. Inverse ordering (sendTextMessage → restoreButton) создал бы окно, когда пользователь видит "❌ Отменён" но клавиатура всё ещё с "⏹ Отменяется...". Текущий порядок optimal.

---

### [DISMISSED] ccs/albb-kimi WARN-8: дубликат invokeOnCompletion + finally release

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** Намеренная безопасная сетка: `finally { release() }` покрывает happy path и все exceptions, `invokeOnCompletion { release() }` покрывает LAZY-cancel-before-body (где тело корутины не запускается вообще, finally не срабатывает). Двойной вызов release() идемпотентен (iter-1 CRITICAL-4 + iter-2 CRITICAL-8). Документировано в design §5.3.

---

### [DISMISSED] ccs/albb-kimi WARN-11: отсутствует тест cleanupExportFile при CancellationException в downloadJobResult

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** iter-1 BUG-5 добавил explicit catch(CancellationException) + NonCancellable cleanup в downloadJobResult (Task 2 Step 4b). Design §5.3 явно документирует это edge case. Unit-тест для этого точного race'а тяжёл — requires MockWebServer с частичным ответом, CancellationException в середине. Покрывается integration validation (e2e в Task 13).

---

### [DISMISSED] ccs/albb-kimi INFO-12: дубликат Known Limitations между спецификацией и планом

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (намеренно)
**Ответ:** design §11 — для архитектурного аудита, plan Task 12 Step 1 — для документации в `.claude/rules/telegram.md` (runtime-документация разработчика). Разные аудитории, разные file-lifetimes (plan удаляется перед PR).

---

### [DISMISSED] ccs/albb-kimi WARN-13: отсутствует `export.error.concurrent`

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** Проверено: `export.error.concurrent=Экспорт уже выполняется. Дождитесь завершения текущего экспорта.` существует в `modules/telegram/src/main/resources/messages_ru.properties:76`.

---

### [DISMISSED] ccs/albb-kimi CRIT-14: циклическая зависимость Task 5 / Task 6

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (уже задокументировано)
**Ответ:** iter-2 MINOR-3 добавил явное blockquote warning в header Task 5: "Task 5 depends on Task 6 — run Task 6 first." Numeric ordering сохранён для spec traceability, dependency ordering документирован.

---

### [DISMISSED] ccs/albb-kimi WARN-15: runTest зависимость

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (стандартная зависимость)
**Ответ:** `runTest` — из `kotlinx-coroutines-test`, уже используется существующими тестами в проекте (`modules/telegram/src/test/kotlin/.../QuickExportHandlerTest.kt` и др.). Gradle BOM управляет версией через Spring Boot parent.

---

### [DISMISSED] ccs/albb-glm STYLE-1/STYLE-2

**Источник:** ccs/albb-glm
**Статус:** Отклонено (self-retracted + trivial)
**Ответ:** STYLE-1 сам reviewer отметил как "Dismiss: Test isolation важнее DRY". STYLE-2 (imports for existing test file) — реализатор добавит при IDE-hint; plan хранит content pattern, не exhaustive impl file.

---

### [DISMISSED] ccs/albb-minimax

**Источник:** ccs/albb-minimax
**Статус:** Новых замечаний нет
**Ответ:** Верифицировано 50 tool calls, все решения iter-1/iter-2 проверены against source. Чистый pass.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-17-export-cancel-design.md` | §2.1 — ActiveExportTracker row уточнён: fire-and-forget `job.start()` без `job.join()`, явно указана опасность блокировки для двухуровневой модели. |
| `docs/superpowers/plans/2026-04-17-export-cancel.md` | **File Structure table** — (a) ExportExecutor row обновлён с упоминанием fire-and-forget; (b) `export.cancelled.action` → `export.cancelled.by.user`. **Task 2 Step 2** — имена mapper-helper'ов выровнены с DetectServiceTest pattern (buildJsonMapper для codecs / buildObjectMapper для DetectService), импорт FasterxmlObjectMapper добавлен. **Task 2 Step 2** — тест `cancelJob rethrows parent CancellationException` переписан через CompletableDeferred<Throwable> с явной проверкой типа исключения. **Task 2 Step 3** — "all 4 tests fail" → "all 5 tests fail". **Task 2 Step 4** — `cancelJob` использует `toBodilessEntity()` вместо `bodyToMono<JobStatusResponse>()`. **Task 5 Step 1** — добавлен race-test `start-vs-release`, LAZY-cancel-before-first-suspension переведён с GlobalScope на локальный SupervisorJob. **Task 5 Step 3** — `tryStartQuickExport`/`tryStartDialogExport` получили self-heal при stale secondary index; `attachCancellable` catch-блок заменён на `logger.warn(...)`. **Task 7 Step 3** — `handle()` получил TOCTOU disambiguation для `cancel.error.*`. **Task 9 Step 1** — `createProgressKeyboard` без параметра `recordingId` (3 call-site обновлены, unit-test скорректирован). **Task 9 Step 2** — добавлена явная инструкция удалить `CreateProcessingKeyboardTest` inner class. **Task 10 Step 1** — убран `job.join()` из execute() (fire-and-forget); `val cancelKeyboard` → `val cancelKeyboardMarkup`; два `runCatching` в timeout paths заменены на try/catch с rethrow CancellationException. **Task 10 Step 3** — `ExportExecutorTest` получил class-level `createdScopes`/`newScope()`/`@AfterEach tearDown()`. **Task 12 Step 1** — callback формат секция дополнена подробностями `np:` noop-ack. **Self-Review** — §2 Placeholder scan переписан (mandatory); §5 Lock Ordering Invariant добавлен; §6 i18n key consistency добавлен. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-3.md` | Новый файл. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-3.md` | Этот файл. |

## Статистика

- Всего замечаний (unique, after dedup): 18 актуальных + 14 dismissed/duplicates
- CRITICAL автоисправлено: 1 (job.join() collapse)
- BUG автоисправлено: 9 (release race, cancelJob deserialize, stub Unit, TOCTOU error, runCatching timeout, stale test, unused param, два catch(Exception) без CancellationException)
- TEST автоисправлено: 4 (tautology, @AfterEach, GlobalScope, delay(50) на real Dispatchers.IO) + 1 test added (start-vs-release race)
- DOC автоисправлено: 5 (export.cancelled key, Self-Review mandatory, Self-Review invariant, np: format, 4→5 test count)
- STYLE автоисправлено: 1 (variable shadow)
- Повторов из iter-1/iter-2 (автоответ): 1 (kimi CRIT-3 = iter-1 CRITICAL-3)
- Отклонено (false positive / уже учтено / намеренный дизайн / out-of-scope): 14
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm direct, albb-glm, albb-qwen, albb-kimi, albb-minimax). minimax — clean pass (0 findings). glm direct — повис на первой попытке, retry уложился в 14 мин и выдал 3 замечания (TEST-11, BUG-19, BUG-20). kimi — output обрезан на границе токенов (тело всех 16 findings получено).
