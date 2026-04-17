# Review Iteration 4 — 2026-04-17 23:33

## Источник

- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Previous iters: iter-1, iter-2, iter-3.
- Review agents: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm direct, albb-glm, albb-qwen, albb-kimi, albb-minimax). `ccs/glm` direct снова повис на 15-минутном таймауте (третий раз из четырёх итераций — известная проблема профиля). `albb-qwen` неверно интерпретировал тип review'а (design-phase vs implementation) и выдал 8 out-of-scope findings. `minimax` — clean pass (0 findings).
- Merged output: `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-4.md`

## Замечания

### [CRITICAL-12] Утечка LAZY-job в QuickExportHandler при отмене handler-scope между tryStart и job.start()

> gemini: `bot.answer(callback)` и `bot.editMessageReplyMarkup(...)` между `registry.tryStart*` и `job.start()` — оба suspend. При cancel handler-scope (bot timeout / disconnect) их `CancellationException` пробрасывается (`throw e`) до того, как `job.start()` вызвался. LAZY job остаётся в NEW навсегда, `invokeOnCompletion` не срабатывает, registry entry висит навечно.

**Источник:** gemini-executor
**Статус:** Автоисправлено (пользователь выбрал option A)
**Ответ:** Добавлены `try/catch (CancellationException) { job.cancel(); throw e }` вокруг обоих suspend-вызовов: `bot.answer(callback)` получил полный блок try/catch (CancellationException) + catch (Exception); существующий catch(CancellationException) для `bot.editMessageReplyMarkup` дополнен `job.cancel()` перед `throw e`. `job.cancel()` на LAZY-NEW job срабатывает корректно: job переходит в CANCELLED без запуска body, `invokeOnCompletion { registry.release(exportId) }` запускается. Симметричная правка НЕ требуется в `ExportExecutor.execute()` — там нет suspend-вызовов между `tryStart` и `job.start()` в Success-ветке (только `job.start()`), а в DuplicateChat-ветке `job.cancel()` вызывается ПЕРЕД suspend `bot.sendTextMessage`.
**Действие:** plan.md Task 9 Step 1 — block `is Success ->` переписан с обрамлением suspend-вызовов в try/catch с `job.cancel()` перед throw.

---

### [CRITICAL-12-kimi] Утечка mergedFile при CancellationException между mergeVideos и annotate

> kimi: В `VideoExportServiceImpl.exportVideo(...)` внешний `catch (Exception)` (VideoExportServiceImpl.kt:102-106) НЕ ловит `CancellationException`. Если cancel приходит между `mergeVideos()` и `annotate()` (во время compress / size-check), `mergedFile` осиротеет.

**Источник:** ccs/albb-kimi (CRITICAL-12 + BUG-23 — дубликат той же проблемы)
**Статус:** Автоисправлено (пользователь выбрал option A — defense-in-depth)
**Ответ:** В Task 4 Step 2 добавлена инструкция добавить dedicated `catch (CancellationException) { withContext(NonCancellable) { tempFileHelper.deleteIfExists(mergedFile) }; throw e }` ПЕРЕД существующим `catch (Exception)` в outer try-block `exportVideo(...)`. Симметрично iter-1 BUG-5 для `annotate(...)`. В ANNOTATED-пути annotate() также имеет собственный CE catch, так что двойной delete возможен — `deleteIfExists` идемпотентен.
**Действие:** plan.md Task 4 Step 2 — добавлен блок с инструкцией и обоснованием.

---

### [TEST-1] Happy-path Task 3 не проверяет порядок "после submit, до первого poll"

> codex: `assertEquals(1, invocations.size)` только счётчик. Не защищает ключевой инвариант "ровно один раз сразу после submitWithRetry()". Callback может публиковаться позже — race submit/cancel не ловится тестом.

**Источник:** codex-executor
**Статус:** Автоисправлено (пользователь выбрал "усилить тест")
**Ответ:** Happy-path тест переименован в `annotateVideo invokes onJobSubmitted after submit and before first job status poll` и усилен: внутри callback захватывается `mockWebServer.requestCount`. Submit POST — request #1; если onJobSubmitted срабатывает строго после submit и до первого `GET /jobs/{id}` poll, счётчик равен 1. Assertion: `assertEquals(1, requestCountAtCallback.get())`.
**Действие:** plan.md Task 3 Step 1 — первый тест переписан с добавлением `AtomicInteger`, захватом request count внутри callback, assertion на точное значение 1.

---

### [TEST-2] NonCancellable-тест — ложноположительный (isCompleted всегда true)

> codex: `callbackFired.complete(Unit)` вызывается ДО suspension (`delay(50)`); assertion `callbackFired.isCompleted` всегда true независимо от NonCancellable. Если убрать NonCancellable, `delay(50)` бросит CancellationException, но тест всё равно пройдёт.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Тест переписан с gate pattern: callback выполняет (a) `entered.complete(Unit)`, (b) suspend через `delay(100)`, (c) `finished.complete(Unit)`. Снаружи: `entered.await()` → `job.cancel()` → `withTimeout(5_000) { finished.await() }`. Если NonCancellable удалить, шаг (b) бросит CancellationException, шаг (c) не выполнится, `finished.await()` упадёт по timeout — тест fails.
**Действие:** plan.md Task 3 Step 1 — второй тест `annotateVideo invokes onJobSubmitted even when parent coroutine is being cancelled (NonCancellable)` переписан с двумя CompletableDeferred вместо одного.

---

### [TEST-3] Path через exportByRecordingId не покрыт тестом

> codex: Task 4 меняет и `exportVideo`, и `exportByRecordingId`, но Task 4 Step 3 тестирует только `exportVideo`. QuickExportHandler вызывает именно `exportByRecordingId` — регрессия "silently dropped onJobSubmitted" не будет обнаружена тестом.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** В Task 4 Step 3 добавлены два дополнительных теста: `exportByRecordingId plumbs onJobSubmitted through exportVideo for ANNOTATED mode` (позитивный, mock `findById` + `findByCamIdAndInstantRange`, captured callback) и `exportByRecordingId does not invoke onJobSubmitted for ORIGINAL mode` (негативный, `coVerify(exactly = 0)` на annotateVideo).
**Действие:** plan.md Task 4 Step 3 — два новых теста добавлены после существующих.

---

### [DESIGN-1] Design §8 "LAZY launch + join" противоречит fire-and-forget; тест ждёт неверный job

> codex: design.md:268 всё ещё содержит "LAZY launch + join". plan.md Task 10 Step 3 тест делает `executeJob.join()`, но `executeJob` — внешняя corутина `scope.launch { executor.execute(...) }`. Поскольку `execute()` fire-and-forget (iter-3 CRITICAL-11), `executeJob` завершается сразу после `job.start()`, не дожидаясь `finally`-блока export-job'а. `assertNull(registry.get(exportId))` может race с `release()`.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** (1) design.md:268 переписан: "fire-and-forget launch (без executeJob.join(): ждём освобождения registry через exportJob.join())". (2) plan.md Task 10 Step 3 тест переписан: сохраняется `val exportJob = registry.get(exportId)!!.job` ДО cancel, затем `exportJob.cancel()` + `exportJob.join()` — ждёт finally + invokeOnCompletion. `executeJob.join()` остаётся как clean-up launcher-корутины.
**Действие:** design.md §8 table row ExportExecutorTest; plan.md Task 10 Step 3 переделан с exportJob.join() вместо полагания на executeJob.join().

---

### [BUG-1] Task 4 Step 3 использует RecordingEntity в неверном пакете и конструктор с 4 полями

> codex: `ru.zinin.frigate.analyzer.service.entity.RecordingEntity(id=, camId=, filePath=, recordTimestamp=)` — реальный класс в `model.persistent` и имеет 15 полей.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** В обоих тестах Task 4 Step 3 заменено на `RecordingEntity(...)` (импорт уже присутствует в существующем `VideoExportServiceImplTest.kt:19`) с полным списком 15 полей (id, creationTimestamp, filePath, fileCreationTimestamp, camId, recordDate, recordTime, recordTimestamp, startProcessingTimestamp, processTimestamp, processAttempts, detectionsCount, analyzeTime, analyzedFramesCount, errorMessage), значимые значения (`camId`, `filePath`, `recordTimestamp`) заданы per-test, остальные `null`. FQN `ru.zinin.frigate.analyzer.service.entity.RecordingEntity` удалён.
**Действие:** plan.md Task 4 Step 3 — оба теста обновлены.

---

### [BUG-21-gemini] ApplicationProperties constructor incomplete в DetectServiceCancelJobTest

> gemini: `val appProps = ApplicationProperties(detectServers = mapOf(...))` — класс имеет 6 обязательных полей без defaults (tempFolder, ffmpegPath, connectionTimeout, readTimeout, writeTimeout, responseTimeout). Не скомпилируется.

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** Добавлены: (1) `@TempDir lateinit var tempDir: Path` в class-body; (2) замена вызова на `applicationProperties(serverProps)`; (3) private helper `applicationProperties(serverProps)` с dummy-значениями (скопирован из `DetectServiceTest.kt:510-522`); (4) импорты `java.nio.file.Path`, `org.junit.jupiter.api.io.TempDir`.
**Действие:** plan.md Task 2 Step 2 — class-level добавлен `@TempDir`, helper, импорты. Вызов constructor'а заменён на helper.

---

### [BUG-21-albb-glm / MINOR-4-kimi] ActiveExportRegistry — отсутствуют imports и declaration logger

> albb-glm: Plan Task 5 Step 3 использует `logger.warn(e) { ... }`, `catch (e: CancellationException)`, `exportScope.launch { ... }`, но imports не включают `KotlinLogging`, `CancellationException`, `launch`. Compile error.

**Источник:** ccs/albb-glm (+ kimi MINOR-4 дубликат)
**Статус:** Автоисправлено
**Ответ:** В блоке imports `ActiveExportRegistry.kt` (plan.md:1573-1581) добавлены: `io.github.oshai.kotlinlogging.KotlinLogging`, `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.launch`. После блока imports добавлен `private val logger = KotlinLogging.logger {}` (top-level, перед `@Component`).
**Действие:** plan.md Task 5 Step 3 — impor block и logger declaration обновлены.

---

### [BUG-22-albb-glm] downloadJobResult catch(Exception) без NonCancellable

> albb-glm: В Task 2 Step 4b:
> ```kotlin
> } catch (e: Exception) {
>     tempFileHelper.deleteIfExists(tempFile)
>     throw e
> }
> ```
> Если parent cancelled во время `deleteIfExists` (suspend, wraps withContext(IO)), suspend-вызов мгновенно бросит CancellationException без реальной работы. Temp file leak.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Блок `catch (Exception)` обёрнут в `withContext(NonCancellable)`:
```kotlin
} catch (e: Exception) {
    withContext(NonCancellable) { tempFileHelper.deleteIfExists(tempFile) }
    throw e
}
```
Симметрично существующему `catch (CancellationException)` (iter-1 BUG-5).
**Действие:** plan.md Task 2 Step 4b — блок переписан.

---

### [TEST-12-albb-glm] CancelExportHandlerTest — отсутствует @AfterEach cleanup

> albb-glm: Test class создаёт `ExportCoroutineScope` и использует `attachCancellable` → `Dispatchers.IO` coroutines. После теста scope не shutdown'ится, leak на CI. Compare to ActiveExportRegistryTest и ExportExecutorTest — обе имеют explicit @AfterEach.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** В `CancelExportHandlerTest` после declaration `handler` добавлен `@org.junit.jupiter.api.AfterEach fun tearDown() { scope.shutdown() }` с комментарием про happy-path test и Dispatchers.IO leak.
**Действие:** plan.md Task 7 Step 1 — класс-level tearDown добавлен.

---

### [DOC-11-gemini / DOC-13-kimi] Task 2 Step 5 — "Expected: all 4 tests PASS" (их 5)

> gemini: Step 2 создаёт 5 test methods (happy-200, 409, 500, timeout, rethrow-CE). Step 5 ожидает 4 — осталось от iter-3 DOC-10 (который починил только Step 3).

**Источник:** gemini-executor (+ kimi DOC-13 дубликат)
**Статус:** Автоисправлено
**Ответ:** Строка plan.md:488 обновлена на "Expected: all 5 tests PASS (happy-200, 409-terminal, 500, timeout, rethrow-parent-cancellation)".
**Действие:** plan.md Task 2 Step 5 — "4 tests" → "5 tests" + перечисление тестовых ID.

---

### [DOC-11-kimi] makeQuickExportCallback helper не определён

> kimi: В Task 9 Step 3 тест `when user cancels ...` вызывает `makeQuickExportCallback(recordingId)`, но helper не определён в плане.

**Источник:** ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** После `@Nested inner class CancellationTest` добавлена секция "Also add the following helper at the top-level of the test class (outside `CancellationTest`)..." с полным определением `makeQuickExportCallback(recordingId)`. Helper возвращает `MessageDataCallbackQuery` с моками `it.data = qea:{recordingId}`, `it.id = CallbackQueryId("cbq-<random>")`, `it.message = mock`, `it.user = CommonUser(id=, firstName=, username=)`. Use `QuickExportHandler.CALLBACK_PREFIX_ANNOTATED` для консистентности.
**Действие:** plan.md Task 9 Step 3 — helper добавлен после CancellationTest, перед Implementor note.

---

### [DOC-14-kimi] Task 10 Step 5 git add — лишний ActiveExportRegistry.kt

> kimi: git add list в Step 5 Task 10 содержит ActiveExportRegistry.kt, хотя файл уже коммитится в Task 5 Step 5. (Заявление "пропущен ExportExecutor.kt" — false positive, файл есть).

**Источник:** ccs/albb-kimi
**Статус:** Автоисправлено (частично — только remove ActiveExportRegistry.kt)
**Ответ:** Строка `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt \` (plan.md:3687) удалена из git add. Финальный список: ExportExecutor.kt + ExportCommandHandler.kt + ExportExecutorTest.kt. Замечание про "пропущен ExportExecutor.kt" от kimi отклоняется — файл присутствовал.
**Действие:** plan.md Task 10 Step 5 — строка ActiveExportRegistry.kt удалена.

---

### [STYLE-3-kimi] assertEquals(true, x is T) → assertIs<T>(x)

> kimi: `assertEquals(true, caught is CancellationException)` / `assertEquals(false, caught is TimeoutCancellationException)` (plan.md:371-372) — менее идиоматично.

**Источник:** ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** Заменено на `assertIs<CancellationException>(caught)` + `assertTrue(caught !is kotlinx.coroutines.TimeoutCancellationException)`. Импорты `kotlin.test.assertIs` и `kotlin.test.assertTrue` добавлены в import block.
**Действие:** plan.md Task 2 Step 2 — две assertion'а обновлены, imports добавлены.

---

### [REPEAT] kimi CRITICAL-13: Race markCancelling/attachCancellable → lost cancel signal

**Источник:** ccs/albb-kimi
**Статус:** Повтор (iter-2 BUG-19, iter-3 CRIT-1/CRIT-16)
**Ответ:** Дубликат ранее dismissed. `Entry.state` и `Entry.cancellable` — оба `@Volatile`. В `attachCancellable` volatile-write `cancellable` happens-before volatile-read `state` в той же thread (JMM). В `markCancelling` volatile-write `state` happens-before последующих volatile-reads в других threads. attachCancellable сначала пишет cancellable, потом читает state — volatile-volatile reordering запрещён JMM. Добавление `synchronized(entry)` в горячий путь hurt производительности без реальной защиты. Answer унаследован.

---

### [REPEAT] kimi BUG-22: downloadJobResult CancellationException handling

**Источник:** ccs/albb-kimi
**Статус:** Повтор (iter-1 BUG-5)
**Ответ:** Уже исправлено в iter-1 BUG-5 (Task 2 Step 4b): explicit `catch (CancellationException) { withContext(NonCancellable) { cleanup }; throw }` ДО `catch (Exception)`. Reviewer сам признал "план правильно указывает на необходимость" — повтор уже-решённой проблемы. Answer унаследован.

---

### [REPEAT] kimi TEST-13: Missing test для race markCancelling/attachCancellable

**Источник:** ccs/albb-kimi
**Статус:** Повтор (связан с CRITICAL-13 repeat)
**Ответ:** Race не существует (см. CRITICAL-13 repeat). Существующий тест `attachCancellable fires cancel when CANCELLING` (iter-1 TEST-3) покрывает forward-direction; reverse-direction гарантируется volatile ordering. Дополнительный тест для несуществующего race'а — noise. Answer унаследован.

---

### [REPEAT] kimi DESIGN-3: i18n Unicode encoding + export.cancelled conflict

**Источник:** ccs/albb-kimi
**Статус:** Повтор (iter-1 DOC-1 naming asymmetry + Unicode format false positive)
**Ответ:** (1) `export.cancelled` конфликт уже разрешён в iter-1 DOC-1: ключ `export.cancelled.by.user` выбран специально, асимметрия naming задокументирована в design.md §7 "Naming asymmetry by design". (2) Unicode format в `.properties` принимает как BMP-escapes (`\u2716`), так и supplementary-pair (`\uD83D\uDCF9`) — оба используются в существующих файлах валидно. Никакой унификации не требуется.

---

### [DISMISSED] qwen en masse: 8 findings о "implementation not started"

**Источник:** ccs/albb-qwen
**Статус:** Отклонено (out-of-scope)
**Ответ:** Reviewer неверно интерпретировал тип review (design & plan, а не implementation). Все 8 findings (CRITICAL-12, DESIGN-3, BUG-21/22/23, TEST-12, DOC-11, MINOR-4) — о том, что код классов ещё не написан. Это pre-implementation state, подразумеваемый design-phase review. Для следующих итераций рассмотреть уточнение промпта для qwen об этом.

---

### [DISMISSED] kimi BUG-21: VideoVisualizeConfig.cancelTimeout Duration vs Long

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** Plan.md:423 использует `cancelTimeout.toMillis()` — явная конверсия `Duration` → `Long`. Тип `cancelTimeout: Duration = Duration.ofSeconds(10)` явно декларирован в Task 1 Step 2 (plan.md:91). `withTimeout(Long)` принимает миллисекунды. Нет type-confusion: тип декларирован, конверсия явная, pattern идиоматичен для проекта.

---

### [DISMISSED] kimi TEST-12: DetectServiceCancelJobTest использует runBlocking без runTest

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive — несовместимость с MockWebServer)
**Ответ:** (1) Тесты работают с реальным `WebClient` + `MockWebServer`, которые требуют реального сетевого времени — `runTest`'s virtual time несовместим. (2) `Thread.sleep(5_000)` и `Thread.sleep(60_000)` — SERVER-side (внутри Dispatcher), блокирует MockWebServer thread; CLIENT имеет `withTimeout(cancelTimeout=2s)` (тест 4) или external `job.cancel()` после 100ms (тест 5) — фактическое время теста ~2 секунды. (3) Pattern идентичен существующему `DetectServiceTest.kt` с MockWebServer (consistency с проектом).

---

### [DISMISSED] kimi DOC-12: Устаревший комментарий про registry.get(recordingId)

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (intentional rationale documentation)
**Ответ:** Комментарий на plan.md:2797-2800 объясняет реализатору, почему используется `snapshotForTest()` вместо `get(recordingId)` (последний принимает только `exportId`). Это НЕ устаревший "stale" комментарий — это active documentation of design rationale, помогающее избежать типичной ошибки. Удаление сделает код менее понятным.

---

### [DISMISSED] kimi MINOR-5: exportByRecordingId — default value для duration

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive — default уже есть)
**Ответ:** plan.md:833 уже содержит `duration: Duration = Duration.ofMinutes(1)` в сигнатуре `exportByRecordingId`. Default присутствует.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-17-export-cancel-design.md` | §8 table row `ExportExecutorTest`: "LAZY launch + join" → "fire-and-forget launch (без `executeJob.join()`…)". |
| `docs/superpowers/plans/2026-04-17-export-cancel.md` | **Task 2 Step 2** — (a) `@TempDir tempDir: Path` class-level, (b) `val appProps = applicationProperties(serverProps)` вместо прямого constructor, (c) helper `applicationProperties(...)` добавлен (скопирован из DetectServiceTest), (d) impor ts `java.nio.file.Path`, `org.junit.jupiter.api.io.TempDir`, `kotlin.test.assertIs`, `kotlin.test.assertTrue`, (e) `assertEquals(true, ...is CancellationException)` → `assertIs<CancellationException>(caught)`, `assertEquals(false, ...)` → `assertTrue(caught !is ...)`. **Task 2 Step 4b** — `catch(Exception)` cleanup обёрнут в `withContext(NonCancellable)` (симметрично существующему catch(CancellationException)). **Task 2 Step 5** — "all 4 tests PASS" → "all 5 tests PASS (happy-200, 409-terminal, 500, timeout, rethrow-parent-cancellation)". **Task 3 Step 1** — (a) happy-path тест переименован и усилен: `AtomicInteger requestCountAtCallback`, захват `mockWebServer.requestCount` в callback, assertion `requestCountAtCallback.get() == 1`; (b) NonCancellable-тест переписан с gate pattern: `entered` + `finished` CompletableDeferred'ы, `delay(100)` между ними, assertion через `withTimeout { finished.await() }`. **Task 4 Step 2** — добавлена инструкция добавить `catch(CancellationException)` в `exportVideo(...)` с NonCancellable cleanup `mergedFile` (defense-in-depth против cancel между mergeVideos и annotate). **Task 4 Step 3** — (a) оба существующих теста: `ru.zinin.frigate.analyzer.service.entity.RecordingEntity(...)` с 4 полями → `RecordingEntity(...)` с полными 15 полями (правильный пакет `model.persistent`, существующий импорт); (b) два новых теста: `exportByRecordingId plumbs onJobSubmitted through exportVideo for ANNOTATED mode` (позитивный) и `exportByRecordingId does not invoke onJobSubmitted for ORIGINAL mode` (негативный). **Task 5 Step 3** — imports `ActiveExportRegistry.kt`: добавлены `io.github.oshai.kotlinlogging.KotlinLogging`, `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.launch`; top-level `private val logger = KotlinLogging.logger {}`. **Task 7 Step 1** — `CancelExportHandlerTest` получил `@org.junit.jupiter.api.AfterEach fun tearDown() { scope.shutdown() }` с обоснованием. **Task 9 Step 1** — Success ветка переписана: `bot.answer(callback)` обёрнут в try/catch(CancellationException) { job.cancel(); throw e } / catch(Exception); существующий catch(CancellationException) для `bot.editMessageReplyMarkup` дополнен `job.cancel()` перед `throw e`. **Task 9 Step 3** — добавлено определение helper'а `makeQuickExportCallback(recordingId)` после CancellationTest (top-level, shared across tests). **Task 10 Step 3** — cancellation test: `executeJob.join()` заменён на `val exportJob = registry.get(exportId)!!.job; exportJob.cancel(); exportJob.join(); executeJob.join()`; комментарий объясняет зачем. **Task 10 Step 5** — `git add ... ActiveExportRegistry.kt` удалён (файл коммитится в Task 5 Step 5). |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-4.md` | Новый файл: 6 из 7 review-outputs объединены (ccs/glm failed — timeout). |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-4.md` | Этот файл. |

## Статистика

- Всего замечаний (unique, after dedup): **14 actionable + 4 repeat + 13 dismissed (qwen 8 out-of-scope + 5 kimi false-positives)**
- CRITICAL автоисправлено: 2 (gemini LAZY-leak, kimi mergedFile leak — оба user-approved option A)
- BUG автоисправлено: 4 (codex RecordingEntity package, gemini ApplicationProperties ctor, albb-glm ActiveExportRegistry imports, albb-glm downloadJobResult NonCancellable)
- TEST автоисправлено: 4 (codex TEST-1 ordering, codex TEST-2 gate pattern, codex TEST-3 exportByRecordingId, albb-glm TEST-12 @AfterEach)
- DESIGN автоисправлено: 1 (codex DESIGN-1 fire-and-forget wording + exportJob.join())
- DOC автоисправлено: 2 (gemini DOC-11 4→5 tests, kimi DOC-11 makeQuickExportCallback helper)
- STYLE автоисправлено: 1 (kimi STYLE-3 assertIs)
- Повторов из iter-1/iter-2/iter-3 (автоответ): 4 (kimi CRITICAL-13, kimi BUG-22, kimi TEST-13, kimi DESIGN-3)
- Отклонено (false positives / uninformed / out-of-scope): 13 (qwen 8 + kimi 5: BUG-21, TEST-12, DOC-12, MINOR-5 + BUG-23 duplicate)
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm direct, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Итоги reviewer'ов: minimax — clean pass (0 findings); qwen — out-of-scope (8 findings); glm direct — failed (timeout, 3-й раз из 4); codex — 5 findings; gemini — 3 findings; albb-glm — 3 findings; kimi — 14 findings (3 новых actionable + 4 repeat + 5 dismissed + 2 duplicates).
- Количество новых actionable findings сократилось с **17-18 в iter-2/3 до 14 в iter-4**. Качественные regression-сюрпризы (CRITICAL-12 gemini + кимики) всё ещё появляются — итерация оправдала себя.
