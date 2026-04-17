# Merged Design Review — Iteration 4

## Meta

- Date: 2026-04-17
- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Agents launched: 7 (codex, gemini, ccs × 5 profiles)
- Agents succeeded: 6 (ccs/glm timeout @ 15 min — recurring issue, also happened in iter-1 and iter-3)

Failed agent trace: ccs/glm session `2026-04-17-23-33-47-design-review-export-cancel-iter-4-ccs-glm/` — model read spec + paged through the plan, spawned 3 sub-agents, verified source files, but hit `timeout 900` before emitting findings. No output.txt generated.

---

## codex-executor (gpt-5.4 xhigh)

### [TEST-1] Тест не фиксирует ключевое требование "сразу после submit"

> "Callback вызывается ровно один раз сразу после успешного `submitWithRetry()`"
> `docs/superpowers/specs/2026-04-17-export-cancel-design.md:48-58`

**Severity:** TEST
**Location:** design.md:48-58; plan.md:512-525
**Issue:** В Task 3 happy-path тест проверяет только `assertEquals(1, invocations.size)`. Он не проверяет, что `onJobSubmitted` вызывается именно между `submitWithRetry()` и первым poll/download. Реализация может ошибочно вызвать callback заметно позже, и тест всё равно пройдёт.
**Impact:** Главная защита от race между submit и cancel ослабляется без сигналов от тестов: `CancellableJob` может публиковаться слишком поздно, и удалённый job на vision server останется жить после пользовательской отмены.
**Suggested fix:** Сделать тест порядковым, а не только счётчиковым: задержать первый `/jobs/{id}` poll или download и явно проверить, что `onJobSubmitted` успел сработать до него.

### [TEST-2] Проверка `NonCancellable` даёт ложноположительный результат

> "Successful await above proves the callback fully ran." — plan.md:558-565

**Severity:** TEST
**Location:** plan.md:533-565
**Issue:** В тесте `callbackFired.complete(Unit)` вызывается до единственной suspension-point (`delay(50)`), а parent cancel происходит уже после `callbackFired.await()`. То есть тест доказывает только "callback начался", но не "callback дошёл до конца под `NonCancellable`".
**Impact:** Если убрать `withContext(NonCancellable)`, callback может рваться ровно на `delay(50)`, но тест всё равно останется зелёным. Критичный инвариант публикации `(server, jobId)` под конкурентной отменой не защищён.
**Suggested fix:** Использовать две синхронизации: `entered` и `finished`. Внутри callback: `entered.complete(Unit)` → suspend на gate → `finished.complete(Unit)`. Снаружи: дождаться `entered`, отменить parent, открыть gate, затем требовать `finished`.

### [TEST-3] QuickExport-путь через `exportByRecordingId` не закрыт тестом

> "VideoExportService.exportVideo(...) и exportByRecordingId(...) ... onJobSubmitted ..." — design.md:60-68

**Severity:** TEST
**Location:** design.md:60-68; plan.md:858-897 (exportByRecordingId); plan.md:905-958 (VideoExportServiceImplTest); current QuickExport uses exportByRecordingId in QuickExportHandler.kt:178-180
**Issue:** План меняет и `exportVideo`, и `exportByRecordingId`, но Task 4 тестирует только `service.exportVideo(...)`. Отдельной проверки, что `exportByRecordingId` реально прокидывает `onJobSubmitted` дальше, нет.
**Impact:** Можно случайно реализовать cancellable-публикацию только для `/export`, а QuickExport останется без `CancellableJob`. Пользователь увидит "Отмена", но vision job не будет отменяться.
**Suggested fix:** Добавить отдельный тест на `service.exportByRecordingId(..., onJobSubmitted=...)` для `ANNOTATED` и негативный тест для `ORIGINAL` на том же API.

### [DESIGN-1] Инвариант fire-and-forget всё ещё описан и тестируется двусмысленно

> "ExportExecutorTest ... dedup через registry; LAZY launch + join" — design.md:268

**Severity:** DESIGN
**Location:** design.md:268; plan.md:2927-2933; plan.md:3288-3334
**Issue:** Design всё ещё содержит формулировку `LAZY launch + join`, хотя план одновременно подчёркивает, что `execute()` не должен делать `job.join()`. Более того, mandatory test в Task 10 ждёт `executeJob.join()`, то есть внешнюю корутину `executor.execute(...)`, а не реальный export job из registry.
**Impact:** Критичный инвариант двухуровневого lock-моделя остаётся плохо защищён от регрессии: можно снова вернуть `job.join()` в `execute()`, а предложенные тесты это не поймают или будут ждать не тот job.
**Suggested fix:** Исправить wording в design на fire-and-forget без `join`, а в тесте ждать именно `registry.get(exportId)!!.job` либо дождаться `registry.get(exportId) == null`/финального `editMessageText`, не опираясь на `executeJob.join()`.

### [BUG-1] Task 4 использует несуществующий `RecordingEntity` и неверную форму конструктора

> `val recordingEntity = ru.zinin.frigate.analyzer.service.entity.RecordingEntity(...)` — plan.md:913-918

**Severity:** BUG
**Location:** plan.md:913-918; real entity at RecordingEntity.kt:13-44 is in `model.persistent`; VideoExportServiceImplTest.kt:19 already imports correct type; helpers at VideoExportServiceImplTest.kt:132-171
**Issue:** В репозитории нет `ru.zinin.frigate.analyzer.service.entity.RecordingEntity`; актуальный тип находится в `model.persistent`. Дополнительно plan создаёт его четырьмя полями, тогда как реальный constructor требует полный набор полей.
**Impact:** Task 4 Step 3 не компилируется в текущем коде и блокирует внедрение теста ровно в том месте, где должен проверяться plumbing `onJobSubmitted`.
**Suggested fix:** Использовать существующий `ru.zinin.frigate.analyzer.model.persistent.RecordingEntity` и переиспользовать уже имеющиеся helper-методы `recording(...)` / `recordingWithTimestamp(...)` из текущего `VideoExportServiceImplTest`.

---

## gemini-executor

### [CRITICAL-12] Утечка LAZY-корутины и перманентная блокировка реестра при отмене `QuickExportHandler`

> Plan: Task 9, `QuickExportHandler.kt`, блок `is ActiveExportRegistry.StartResult.Success`

**Severity:** CRITICAL
**Location:** Task 9 Step 1, блок `is ActiveExportRegistry.StartResult.Success -> { ... }` внутри `QuickExportHandler.handle`
**Issue:** `job` создаётся с `CoroutineStart.LAZY` в глобальном `exportScope`. После успешного добавления в `registry` (которое блокирует `recordingId`), обработчик выполняет suspend-вызовы `bot.answer(callback)` и `bot.editMessageReplyMarkup(...)`. Если область видимости текущего обработчика (request-handler scope бота) отменяется по таймауту или дисконнекту, эти вызовы бросают `CancellationException`. Оно ловится и перебрасывается дальше (`throw e`), минуя вызов `job.start()`.
**Impact:** Глобальный `exportScope` ничего не знает об отмене локального скоупа бота. `job` навсегда остаётся в нём в состоянии `NEW` (так как он LAZY и не был стартован). Соответственно, его код не выполняется, а `invokeOnCompletion` никогда не срабатывает (корутина не стартовала → не может завершиться). Запись в `ActiveExportRegistry` зависает навсегда, из-за чего любые последующие попытки экспортировать это видео будут падать с `DuplicateRecording` до рестарта приложения.
**Suggested fix:** В блоке `Success` добавить явную отмену `job` перед перебросом `CancellationException`. Альтернативно — запускать `job.start()` до любых suspend-операций с UI.
```kotlin
try {
    bot.editMessageReplyMarkup(...)
} catch (e: CancellationException) {
    job.cancel() // ОБЯЗАТЕЛЬНО: триггерит invokeOnCompletion и освобождает реестр
    throw e
} catch (e: Exception) { ... }
```
(Также стоит убедиться, что `bot.answer(callback)` обёрнут в `try/catch (e: CancellationException)`, так как он тоже является suspend-вызовом и может привести к такому же early return).

### [BUG-21] Ошибка компиляции: неполный вызов конструктора `ApplicationProperties` в `DetectServiceCancelJobTest`

> Plan Task 2 Step 2, `DetectServiceCancelJobTest.kt`, функция `setUp()`

**Severity:** BUG
**Location:** Task 2 Step 2, функция `setUp()`
**Issue:** В новом тестовом классе `DetectServiceCancelJobTest` инстанс `ApplicationProperties` создаётся как `val appProps = ApplicationProperties(detectServers = mapOf("test" to serverProps))`. Однако `ApplicationProperties` — это `@ConfigurationProperties` data class, который содержит множество обязательных `val`-полей без значений по умолчанию (например, `@field:NotNull val tempFolder: Path`, `ffmpegPath`, `connectionTimeout` и т.д.).
**Impact:** Этот шаг приведёт к ошибке компиляции в Task 2.
**Suggested fix:** Добавить в `DetectServiceCancelJobTest.kt` приватную функцию `applicationProperties(...)` с dummy-заглушками для обязательных параметров, скопировав её из соседнего `DetectServiceTest.kt`.

### [DOC-11] Неактуальное ожидаемое количество успешных тестов в Task 2

> Plan Task 2 Step 5: "Expected: all 4 tests PASS"

**Severity:** DOC
**Location:** Task 2 Step 5
**Issue:** В плане указано `Expected: all 4 tests PASS.`. Однако в предыдущем шаге (Step 2) мы создаём класс `DetectServiceCancelJobTest`, который содержит ровно **5** методов. В итерации 3 аналогичная проблема (DOC-10) была исправлена для Step 3 ("all 5 tests fail"), но Step 5 забыли обновить.
**Suggested fix:** Изменить текст в Task 2 Step 5 на `Expected: all 5 tests PASS.`.

---

## ccs-executor (albb-glm / glm-5)

### [BUG-21] ActiveExportRegistry — отсутствуют imports и declaration logger

> Plan Task 5 Step 3 (lines 1351-1360, 1446-1460)

**Severity:** BUG
**Location:** plan.md:1351-1360, 1446-1460
**Issue:** Код `ActiveExportRegistry` использует `logger.warn(e) { ... }` (line 1456), `catch (e: CancellationException)` (line 1451) и `exportScope.launch { ... }` (line 1446), но imports не включают необходимые зависимости:
- `import io.github.oshai.kotlinlogging.KotlinLogging`
- `private val logger = KotlinLogging.logger {}`
- `import kotlinx.coroutines.CancellationException`
- `import kotlinx.coroutines.launch`
**Impact:** Compilation error — класс не компилируется. Реализатор не сможет собрать проект после Task 5.
**Suggested fix:** Добавить imports и logger declaration.

### [BUG-22] DetectService.downloadJobResult — catch(Exception) cleanup без NonCancellable

> Plan Task 2 Step 4b (lines 479-482)

```kotlin
} catch (e: Exception) {
    tempFileHelper.deleteIfExists(tempFile)
    throw e
}
```

**Severity:** BUG
**Location:** plan.md:479-482
**Issue:** Suspend cleanup `tempFileHelper.deleteIfExists(tempFile)` в `catch(Exception)` НЕ wrapped в `withContext(NonCancellable)`. Если parent coroutine cancelled во время выполнения `deleteIfExists` (например, user cancel arrived после того как vision server returned 500), `CancellationException` бросается из suspend-вызова, но `catch(Exception)` НЕ ловит `CancellationException` в Kotlin coroutines. Cleanup не завершается, temp file leaks.
**Impact:** Temp file leak при race между server error и user/shutdown cancel.
**Suggested fix:**
```kotlin
} catch (e: Exception) {
    withContext(NonCancellable) { tempFileHelper.deleteIfExists(tempFile) }
    throw e
}
```

### [TEST-12] CancelExportHandlerTest — отсутствует @AfterEach cleanup

> Plan Task 7 Step 1 (lines 1699-1714, 1771-1810)

**Severity:** TEST
**Location:** plan.md:1699-1810
**Issue:** Test class создаёт `ExportCoroutineScope` и использует `registry.attachCancellable` в тесте "handle for cancel happy path" (line 1786), который `exportScope.launch { ... }` корутины на `Dispatchers.IO`. После теста корутины не cleanup — отсутствует `@AfterEach` с `scope.shutdown()`. Compare to `ActiveExportRegistryTest` (Task 5 Step 1, line 1061-1066) и `ExportExecutorTest` (Task 10 Step 3, lines 3275-3285), которые имеют explicit @AfterEach cleanup pattern.
**Impact:** Coroutine leak across tests на CI. Flaky tests если leaked coroutines interfere с subsequent tests.
**Suggested fix:** Добавить `@AfterEach fun tearDown() { scope.shutdown() }`.

---

## ccs-executor (albb-qwen / qwen3.5-plus)

### Общий комментарий
Reviewer проверял состояние реализации против design/plan и сформулировал все findings как "implementation not started". Это неверная интерпретация задачи (review — на design & plan, а не на implementation). Findings отклоняются как out-of-scope этой итерации, но фиксируются для справки.

### (CRITICAL-12) Отсутствует реализация ActiveExportRegistry, ExportCoroutineScope, CancelExportHandler
**Severity:** CRITICAL → **DISMISSED (out-of-scope):** implementation not yet started; it's a design/plan review, not an impl review.

### (DESIGN-3) Несоответствие между дизайном и планом: порядок задач Task 5/Task 6
**Severity:** DESIGN → **DISMISSED (duplicate of iter-2 MINOR-3):** уже задокументирован blockquote warning в header Task 5.

### (BUG-21/BUG-22/BUG-23) Отсутствующие методы/параметры в коде
**Severity:** BUG → **DISMISSED (out-of-scope):** pre-implementation state; plan описывает, что нужно добавить.

### (TEST-12) Отсутствуют тесты на новые компоненты
**Severity:** TEST → **DISMISSED (out-of-scope):** тесты будут добавлены в рамках реализации задач по плану.

### (DOC-11) i18n ключи отсутствуют в обоих файлах сообщений
**Severity:** DOC → **DISMISSED (out-of-scope):** ключи будут добавлены в Task 6; plan это уже описывает.

### (MINOR-4) ExportExecutor не использует ExportCoroutineScope
**Severity:** MINOR → **DISMISSED (out-of-scope):** ExportExecutor получит scope в рамках Task 10; в плане это уже описано.

---

## ccs-executor (albb-kimi / kimi-k2.5)

### [CRITICAL-12] `VideoExportServiceImpl.annotate()` не перехватывает CancellationException отдельно — merged/compressed файл утекает при отмене

> Plan: Task 4 Step 2, VideoExportServiceImpl.kt:109-142

**Severity:** CRITICAL
**Issue:** В текущей реализации `annotate()` план в Task 4 Step 2 добавляет catch(CancellationException) с cleanup для `originalPath`. Проблема: если `CancellationException` произойдёт ДО вызова `annotateVideo`, или ПОСЛЕ него но до возврата, merged/compressed file может не очиститься; в `exportVideo` вокруг `annotate()` нет try/finally или use-блока, который гарантирует удаление mergedFile при исключениях.
**Impact:** Temp-файлы (merged/compressed/annotated) могут осиротеть при отмене экспорта на этапе аннотации.
**Suggested fix:** Добавить explicit try-finally вокруг `annotate()` в `exportVideo()`, который гарантирует удаление `mergedFile` при любом исключении, включая `CancellationException`.

### [CRITICAL-13] Потеря CancellableJob при race между markCancelling и attachCancellable

> Design §4: "Race: click «Отмена» между submitWithRetry и attachCancellable"

**Severity:** CRITICAL
**Issue:** Design заявляет что `attachCancellable` делает check-on-attach. Однако:
1. Thread A: `submitWithRetry()` завершается, `jobId = job.jobId` присвоен
2. Thread B: пользователь кликает "Отмена", `markCancelling()` видит `entry.cancellable == null`, выставляет state=CANCELLING
3. Thread A: `attachCancellable(cancellable)` — если сначала пишет `cancellable`, потом читает `state`, то видит CANCELLING и запускает `cancellable.cancel()` ✓
4. Но если порядок в attachCancellable другой: читает state до markCancelling, затем пишет cancellable — cancellable.cancel() не вызовется.
**Impact:** Vision server job продолжает работать впустую, GPU-ресурсы.
**Suggested fix:** В attachCancellable использовать atomic CAS на паре (state, cancellable), либо synchronized(entry) вокруг всей операции чтения-записи.

### [BUG-21] Несоответствие типов VideoVisualizeConfig.cancelTimeout — Duration vs Long в withTimeout

> Plan: Task 2 Step 4, line 423

**Severity:** BUG
**Issue:** withTimeout принимает Long (milliseconds). В DetectService уже есть withTimeout(timeoutMs) вызовы с Long. Смешение типов может привести к ошибкам, если разработчик забудет toMillis().
**Suggested fix:** Явно указать тип в сигнатуре VideoVisualizeConfig.

### [BUG-22] Отсутствие обработки CancellationException в DetectService.downloadJobResult

> Plan: Task 2 Step 4b

**Severity:** BUG
**Issue:** План правильно указывает на необходимость отдельного catch(CancellationException) перед catch(Exception). В текущем коде метод может отсутствовать — нужно проверить.
**Suggested fix:** Убедиться, что метод downloadJobResult существует и имеет корректную обработку CancellationException.

### [BUG-23] Неправильный порядок cleanup в VideoExportServiceImpl.exportVideo — mergedFile может быть null при отмене

> Current code: VideoExportServiceImpl.kt:40-107

**Severity:** BUG
**Issue:** mergedFile не удаляется при CancellationException из annotate(). exportVideo имеет catch(Exception) в конце, но CancellationException не перехватывается.
**Impact:** Утечка temp-файлов при отмене аннотированного экспорта.
**Suggested fix:** Обернуть вызов annotate() в try-finally.

### [TEST-12] DetectServiceCancelJobTest использует runBlocking без runTest

> Plan: Task 2 Step 2, lines 183-394

**Severity:** TEST
**Issue:** Тест `cancelJob tolerates timeout` ожидает 5 секунд server hang, тест за 2 секунды таймаут. С runBlocking это реальное ожидание. Тест `cancelJob rethrows parent CancellationException` использует `Thread.sleep(60_000)` — блокирует поток на 60 секунд.
**Impact:** Тесты выполняются очень медленно (минуты вместо миллисекунд), могут флакать на медленных CI.
**Suggested fix:** Заменить runBlocking на runTest, использовать StandardTestDispatcher/UnconfinedTestDispatcher.

### [TEST-13] Отсутствие теста для race markCancelling vs attachCancellable при одновременном вызове

> Plan: Task 5 Step 1, lines 1223-1238

**Severity:** TEST
**Issue:** Тест "attachCancellable fires cancel when entry is already CANCELLING" проверяет случай когда markCancelling вызван ДО attachCancellable. Но обратный race не проверяется: когда attachCancellable начинает выполнение (читает state), затем markCancelling вызывается и завершается, и только потом attachCancellable записывает cancellable.
**Impact:** Race из CRITICAL-13 не покрыт тестами.
**Suggested fix:** Добавить тест с CountDownLatch или CompletableDeferred для синхронизации.

### [DESIGN-3] Несоответствие i18n ключей

> Plan Task 6, lines 1593-1633

**Severity:** DESIGN
**Issue:** План добавляет cancel.error.*, quickexport.button.cancel, export.cancelled.by.user. При проверке: export.cancelled уже существует (line 78 messages_en.properties). Также в плане Unicode-эскейпы \u2716\uFE0F, но в существующих файлах есть эскейпы \uD83D\uDCF9.
**Suggested fix:** Унифицировать формат Unicode.

### [DOC-11] Task 9 Step 3 — ссылка на несуществующий helper makeQuickExportCallback

> Plan Task 9 Step 3, line 2794

**Severity:** DOC
**Issue:** Тест использует makeQuickExportCallback(recordingId), но helper не определён в плане.
**Suggested fix:** Добавить определение либо указать, что он должен быть из существующего кода.

### [DOC-12] План ссылается на ActiveExportRegistry.get(recordingId) которого нет

> Plan Task 9 Step 3, lines 2797-2800

**Severity:** DOC
**Issue:** Комментарий в плане правильный, но устаревший.
**Suggested fix:** Удалить устаревший комментарий.

### [DOC-13] Task 2 Step 5 — expected 4 tests vs 5 tests

> Plan Task 2 Step 5, line 488

**Severity:** DOC
**Issue:** Дубликат gemini DOC-11.

### [DOC-14] Неправильный формат git add в Task 10 Step 5

> Plan Task 10 Step 5, lines 3400-3404

**Severity:** DOC
**Issue:** В списке присутствует уже закоммиченный ActiveExportRegistry.kt (commit Task 5), отсутствует ExportExecutor.kt.
**Impact:** ExportExecutor.kt не будет включён в коммит.
**Suggested fix:** Исправить список.

### [STYLE-3] assertEquals → assertIs

> Plan Task 2 Step 2, lines 371-372

**Severity:** STYLE
**Issue:** assertEquals(true, caught is CancellationException) — менее идиоматично.
**Suggested fix:** assertIs<CancellationException>(caught).

### [MINOR-4] Logger import missing

> Plan Task 5 Step 3

**Severity:** MINOR
**Issue:** Дубликат albb-glm BUG-21.

### [MINOR-5] Несоответствие типа возвращаемого значения exportByRecordingId — default value

> Plan Task 4 Step 2

**Severity:** MINOR
**Issue:** В плане сигнатура exportByRecordingId без default value для duration.
**Suggested fix:** Добавить `duration: Duration = Duration.ofMinutes(1)`.

---

## ccs-executor (albb-minimax / MiniMax-M2.5)

### Проверка пройдена — Clean pass

После тщательного анализа design и plan **новых проблем не выявлено**.

**Проверенные области:** Observability (§9), Testing (§8), i18n (§7), Error Handling (§5), Lock Ordering (§4), State Machine, NonCancellable, Fire-and-forget, Dispatchers, Inter-module.

**Все 17 inherited invariants из предыдущих итераций подтверждены.**

**Total findings:** 0

---

## Consolidated Summary

### Unique issues (after dedup between reviewers):

| # | Issue | Sources | Severity |
|---|-------|---------|----------|
| CRITICAL-12 | LAZY-job не стартует если parent scope cancelled до job.start() → permanent registry lock | gemini | CRITICAL |
| CRITICAL-13-kimi | Race markCancelling/attachCancellable → lost cancel signal (NEW argument) | kimi | CRITICAL (likely dismiss-duplicate) |
| CRITICAL-12-kimi | mergedFile не cleanup'ится при CancellationException из annotate() | kimi | CRITICAL |
| DESIGN-1 | Design §8 "LAZY launch + join" противоречит fire-and-forget; mandatory test ждёт executeJob.join() | codex | DESIGN |
| BUG-1 | RecordingEntity wrong package + wrong constructor in Task 4 test | codex | BUG |
| BUG-21-gemini | ApplicationProperties ctor incomplete in DetectServiceCancelJobTest | gemini | BUG |
| BUG-21-albb-glm | ActiveExportRegistry missing logger/imports | albb-glm, kimi (MINOR-4 dup) | BUG |
| BUG-22-albb-glm | downloadJobResult catch(Exception) without NonCancellable | albb-glm | BUG |
| BUG-21-kimi | VideoVisualizeConfig.cancelTimeout Duration/Long | kimi | BUG |
| BUG-23-kimi | mergedFile cleanup order in exportVideo | kimi (overlaps with kimi CRITICAL-12) | BUG |
| TEST-1 | Task 3 happy-path не проверяет порядок "after submit, before poll" | codex | TEST |
| TEST-2 | NonCancellable test — ложноположительный (complete до suspend) | codex | TEST |
| TEST-3 | exportByRecordingId не покрыт тестом на onJobSubmitted | codex | TEST |
| TEST-12-albb-glm | CancelExportHandlerTest missing @AfterEach | albb-glm | TEST |
| TEST-12-kimi | DetectServiceCancelJobTest uses runBlocking not runTest | kimi | TEST |
| TEST-13-kimi | Missing test для race markCancelling before attachCancellable writes cancellable | kimi | TEST |
| DOC-11 | Expected "4 tests PASS" vs actual 5 tests (Task 2 Step 5) | gemini, kimi (DOC-13 dup) | DOC |
| DOC-11-kimi | makeQuickExportCallback helper не определён | kimi | DOC |
| DOC-12-kimi | Stale comment про registry.get(recordingId) | kimi | DOC |
| DOC-14-kimi | git add Task 10 Step 5 — лишний ActiveExportRegistry.kt + пропущен ExportExecutor.kt | kimi | DOC |
| DESIGN-3-kimi | i18n Unicode encoding inconsistency + export.cancelled conflict | kimi | DESIGN (likely dismiss) |
| STYLE-3-kimi | assertEquals → assertIs (idiomatic Kotlin) | kimi | STYLE |
| MINOR-5-kimi | exportByRecordingId — default value для duration в Task 4 сигнатуре | kimi | MINOR |

**Qwen's 8 findings** — all out-of-scope (confused implementation-review with design-review). Dismissed en masse.

### Statistics

- Reviewers completed: 6 of 7 (minimax clean pass; qwen out-of-scope; kimi 14 findings; glm direct timeout)
- Total raw findings: 5 (codex) + 3 (gemini) + 3 (albb-glm) + 8 (qwen dismissed) + 14 (kimi) + 0 (minimax) = **33**
- Unique after dedup (excluding qwen): **~20-22**
- Clear autocorrectable: ~12 (logger imports, ApplicationProperties helper, wrong entity package, expected test count, etc.)
- Needs discussion with user: ~3 (CRITICAL-12 fix strategy, CRITICAL-12-kimi/BUG-23-kimi merge cleanup, TEST-13 new race test)
- Likely repeat/dismiss: ~5 (CRITICAL-13-kimi attachCancellable race = iter-2/iter-3 dismissed; BUG-22-kimi duplicate of iter-1 BUG-5; etc.)
