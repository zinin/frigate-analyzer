# Review Iteration 2 — 2026-04-17 19:49

## Источник

- Design: `docs/superpowers/specs/2026-04-17-export-cancel-design.md`
- Plan: `docs/superpowers/plans/2026-04-17-export-cancel.md`
- Previous iter: `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-1.md`
- Review agents: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax). Все 7 reviewer'ов успешно завершили работу (в этот раз `ccs/glm` direct тоже уложился в 10-минутный бюджет).
- Merged output: `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-2.md`

## Замечания

### [CRITICAL-8] Deadlock между `release()` и `markCancelling()` в ActiveExportRegistry

> Gemini: `release()` берёт `synchronized(entry)` и внутри делает `byExportId.remove(exportId)` (берёт bucket lock CHM). `markCancelling()` делает `computeIfPresent()` (держит bucket lock) и внутри — `synchronized(entry)`. Это классический dual-lock deadlock с обратным порядком захвата.

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** `release()` теперь **сначала** делает `byExportId.remove(exportId)` (без `synchronized(entry)`), и только после успешного remove'а берёт `synchronized(entry)` для очистки вторичных индексов. Concurrent `markCancelling` после `remove` видит null через `computeIfPresent` и безопасно возвращается.
**Действие:** plan.md Task 5 Step 3 — реализация `release()` переписана. design.md §4 — обновлено описание порядка операций с обоснованием.

---

### [CRITICAL-9] Утечка temp-файлов: suspend cleanup без NonCancellable

> Gemini: `tempFileHelper.deleteIfExists` и `videoExportService.cleanupExportFile` — обе `suspend` (подтверждено в коде). В уже отменённой корутине любой suspend-вызов мгновенно бросает новый `CancellationException` без реальной работы — cleanup не выполняется, temp-файлы утекают на диск.

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** Добавлен `withContext(NonCancellable)` вокруг всех suspend cleanup-вызовов в `catch (CancellationException)` и `finally` блоках.
**Действие:** plan.md Task 2 Step 4b — `downloadJobResult` cleanup в NonCancellable. plan.md Task 9 Step 1 — finally block QuickExportHandler в NonCancellable. plan.md Task 10 Step 1 — finally block ExportExecutor в NonCancellable. (Task 4 Step 2 уже был корректен с iter-1.) design.md §5.3 — обновлены две строки с обоснованием.

---

### [CRITICAL-10] Финальный UI "❌ Отменён" не доходит до пользователя

> Gemini: `bot.editMessageText`, `bot.sendTextMessage`, `restoreButton` в ветке `catch (CancellationException)` — все suspend. В cancelled-корутине они моментально бросают `CancellationException`, UI-сообщение не отправляется, пользователь остаётся на "⏹ Отменяется…".

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** UI-обновления в ветке `catch (CancellationException)` для user-cancel обёрнуты в `withContext(NonCancellable)`. После завершения блока cancellation восстанавливается, финальный `return` срабатывает как ожидается.
**Действие:** plan.md Task 9 Step 1 — `QuickExportHandler.runExport` catch-ветка CANCELLING. plan.md Task 10 Step 1 — `ExportExecutor.runExport` catch-ветка CANCELLING. design.md §5.2 — обновлён скелет с явным `withContext(NonCancellable)` и пояснением.

---

### [BUG-7] ExportCoroutineScope на Dispatchers.Default блокирует cancel path

> Codex: `Dispatchers.Default` — CPU-bound пул (ограничен числом ядер). Export-корутины вызывают блокирующий `ffmpeg.waitFor(...)`. При нескольких параллельных экспортах worker'ы Default заняты ffmpeg-ом, а cancel-корутины (`exportScope.launch { cancellable.cancel() }`) начинают ждать освобождения тех же потоков. Отмена на vision server опаздывает именно тогда, когда она нужна.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Переключено на `Dispatchers.IO` (дефолтный cap 64 потока, sized для blocking). Cancel-корутины изолированы от блокировок ffmpeg-пула.
**Действие:** plan.md Task 6 Step 1 — `ExportCoroutineScope` теперь `Dispatchers.IO + SupervisorJob()`; добавлен комментарий с обоснованием.

---

### [BUG-8] runCatching проглатывает CancellationException

> Gemini: `runCatching { bot.answer(callback) }` ловит `Throwable` включая `CancellationException`. При graceful shutdown corоутина получит cancel, runCatching его съест, корутина продолжит выполнение в cancelled состоянии.

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** Все `runCatching` вокруг suspend bot-вызовов заменены на явный `try/catch` с rethrow `CancellationException`. Для многократного использования добавлен `answerSafely(...)` helper в `CancelExportHandler`.
**Действие:** plan.md Task 7 Step 3 — `CancelExportHandler.handle()` обновлён, добавлен `answerSafely()` helper; `editMessageReplyMarkup` теперь в try/catch с rethrow. plan.md Task 5 Step 3 — `attachCancellable` runCatching заменён на try/catch.

---

### [BUG-9] CommonUser 10-параметровый constructor не совпадает с проектом

> ccs/glm: Все 8 использований `CommonUser(...)` в `CancelExportHandlerTest` используют 10-параметровый позиционный constructor, а существующие тесты проекта (`QuickExportHandlerTest:305-308`) используют 3-параметровый named-arg pattern `CommonUser(id=, firstName=, username=)`.

**Источник:** ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Проверено: существующий pattern в `QuickExportHandlerTest` действительно 3-параметровый с именованными аргументами. Все 6 блоков `CommonUser(...)` в Task 7 Step 1 переписаны.
**Действие:** plan.md Task 7 Step 1 — 6 `CommonUser(...)` блоков обновлены (5× Alice + 1× Bob). Убраны лишние nullable позиционные аргументы.

---

### [BUG-10] ExportExecutorTest second parallel execute — пустое тело теста

> ccs/glm: Тест `second parallel execute — returns DuplicateChat and sends concurrent message` имел только комментарий `// (Abbreviated: implementation mirrors the test above; verify concurrent path.)` — тривиально пройдёт без проверок, критический путь дедупликации `/export` не покрыт.

**Источник:** ccs/glm
**Статус:** Автоисправлено
**Ответ:** Полное тело теста прописано: первый `execute()` в scope.launch с suspend-exportVideo, ожидание появления записи через snapshotForTest, второй синхронный `execute()`, `coVerify` на `bot.sendTextMessage` с "already running/уже".
**Действие:** plan.md Task 10 Step 3 — второй тест теперь полностью реализован.

---

### [BUG-11] Test accessors: `registry.get(recordingId)` и `byExportIdForTest()` не существуют; `ActiveExportRegistry()` без ctor-аргумента

> Codex (TEST-1), ccs/glm (BUG-11), ccs/kimi (BUG-14), ccs/minimax (TEST-1): план ссылается на несуществующие методы test accessor. `ActiveExportRegistry()` в Task 9 Step 2 без `ExportCoroutineScope` не скомпилируется. `registry.get(recordingId)` — метод принимает exportId. `byExportIdForTest()` не определён в Task 5.

**Источник:** codex-executor, ccs/glm, ccs/albb-kimi, ccs/albb-minimax
**Статус:** Автоисправлено
**Ответ:** `snapshotForTest(): Map<UUID, Entry>` теперь добавлен **в Task 5 Step 3** (где регистр создаётся). Task 9 Step 2 использует `ActiveExportRegistry(exportScope)`. Task 9 Step 3 использует `registry.snapshotForTest().values.first { it.recordingId == ... }.exportId`. Task 10 Step 3 `firstActiveExport()` реализован через `snapshotForTest().keys.firstOrNull()`.
**Действие:** plan.md Task 5 Step 3 — добавлен `internal fun snapshotForTest()`. plan.md Task 9 Step 2 — `ActiveExportRegistry(exportScope)`. plan.md Task 9 Step 3 — использование snapshotForTest. plan.md Task 10 Step 3 — реализация firstActiveExport + удалён hint про snapshotForTest (он уже в Task 5).

---

### [TEST-4] Отсутствуют mandatory тесты, объявленные в design §8

> Codex (TEST-2): design §8 требует:
> - `DetectServiceTest`: `CancellationException rethrown`
> - `VideoVisualizationServiceTest`: `onJobSubmitted` под `NonCancellable` (concurrent cancel)
> - `CancelExportHandlerTest`: malformed callback data, Telegram API errors при edit keyboard
>
> В плане эти кейсы отсутствовали.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Добавлены три теста в соответствующие файлы.
**Действие:** plan.md Task 2 Step 2 — `cancelJob rethrows parent CancellationException` тест. plan.md Task 3 Step 1 — `annotateVideo invokes onJobSubmitted even when parent coroutine is being cancelled (NonCancellable)` тест. plan.md Task 7 Step 1 — `handle answers with format error on malformed cancel data` + `handle survives editMessageReplyMarkup failure and still cancels the job` тесты.

---

### [TEST-5] Concurrency-тест содержит race в harness'е: mutableListOf<Job>()

> Codex: `ActiveExportRegistryTest` хранит `private val jobs = mutableListOf<Job>()`, 32 параллельных thread'а зовут `newJob()` → `jobs.add()`. Несинхронизированная shared mutation — jobs могут потеряться при cleanup, тест flaky.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** `mutableListOf<Job>()` заменён на `ConcurrentLinkedQueue<Job>()`. Все add/forEach/clear теперь thread-safe. Добавлен комментарий объясняющий почему.
**Действие:** plan.md Task 5 Step 1 — `jobs` теперь `ConcurrentLinkedQueue`.

---

### [TEST-6] Flaky `delay(50)` / `repeat(50) + delay(10)` для coroutine sync

> Gemini, ccs/qwen, ccs/kimi: Тесты используют `delay(50)` против real `Dispatchers.Default` для ожидания запуска launched coroutines. На медленном CI 50ms может не хватить — flaky tests.

**Источник:** gemini-executor, ccs/albb-qwen, ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** `delay(50)` в positive-check заменён на `CompletableDeferred + withTimeout(5_000)` — fail fast, но генеральный запас 5с для busy CI. Negative-check (отсутствие сигнала) оставлен с `delay(100)` — достаточно. `repeat(50) + delay(10)` в ExportExecutorTest заменён на `withTimeout(5_000) while-loop`.
**Действие:** plan.md Task 5 Step 1 — два теста `attachCancellable ... CANCELLING` и `... ACTIVE` переписаны. plan.md Task 10 Step 3 — первый тест polling-цикл обновлён.

---

### [TEST-7] Отсутствует тест LAZY cancel до первого suspension point

> ccs/qwen: Design §5.3 явно описывает edge-case ("LAZY-корутина отменена до первого suspension point — `finally { release() }` не стартует, страховка через `invokeOnCompletion`"). Теста нет — при будущем рефакторинге легко сломать.

**Источник:** ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** Добавлен тест `release invoked via invokeOnCompletion when LAZY job cancelled before first suspension` — создаёт LAZY job, регистрирует в registry + `invokeOnCompletion`, cancel до start, проверяет что `registry.get()` возвращает null.
**Действие:** plan.md Task 5 Step 1 — новый тест.

---

### [DOC-3] "replacing ActiveExportTracker" stale в synopsis и Self-Review

> Codex (DOC-1), ccs/kimi (MINOR-4), ccs/albb-glm (Minor Notes): synopsis (plan:7) говорит "replacing ActiveExportTracker"; Self-Review (plan:3176) — "| 2.2 Delete ActiveExportTracker | Task 11 |". Противоречит iter-1 CRITICAL-1 ("Tracker is KEPT").

**Источник:** codex-executor, ccs/albb-kimi, ccs/albb-glm
**Статус:** Автоисправлено
**Ответ:** Synopsis переписан: чётко указано, что Registry — **новый** execution-phase lock, а Tracker **сохранён** для dialog-phase lock. Self-Review row для 2.2 обновлён на "Task 11 — SKIPPED (tracker retained for `/export` dialog-phase lock per iter-1 CRITICAL-1)".
**Действие:** plan.md line 7 (synopsis), plan.md Self-Review table row "2.2".

---

### [DOC-4] Design §7 vs plan: `export.cancelled` vs `export.cancelled.by.user`

> ccs/glm (DOC-3), ccs/qwen (DOC-3): design §7 (line 241/242) декларирует `export.cancelled = ❌ Экспорт отменён`; plan Task 6 использует `export.cancelled.by.user`. Design не обновлён после iter-1 решения сохранить asymmetric naming.

**Источник:** ccs/glm, ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** design §7 обновлён: `export.cancelled.by.user`. Добавлен пояснительный абзац "Naming asymmetry by design" с указанием, что `export.cancelled` уже занят dialog-cancel path (существующий ключ до этого плана).
**Действие:** design.md §7 — ключ переименован + пояснение.

---

### [DESIGN-2] Lock ordering invariant между Tracker и Registry не задокументирован

> ccs/qwen (DESIGN-1): Plan упоминает "they don't deadlock — registry's synchronized(startLock) is short-lived", но нет code-level инварианта. Сегодня риска deadlock нет, но при будущем расширении легко сломать.

**Источник:** ccs/albb-qwen
**Статус:** Автоисправлено
**Ответ:** Добавлена секция "Lock Ordering Invariant (Tracker + Registry)" в описание для `.claude/rules/telegram.md` (Task 12 Step 1). Четыре явных правила: acquire Tracker first; не звать Registry под Tracker's lock; внутренние locks registry — short-lived, не пересекаются; `release()` — byExportId.remove ДО synchronized(entry).
**Действие:** plan.md Task 12 Step 1 — новая секция в markdown-блоке для telegram.md.

---

### [MINOR-3] Task 5/6 ordering неочевиден

> ccs/glm (MINOR-2), ccs/kimi (BUG-7): Task 5 зависит от Task 6 (конструктор Registry требует Scope). План упоминает это в Step 3 note, но заголовок Task 5 идёт первым — исполнитель по порядку наткнётся на compile error.

**Источник:** ccs/glm, ccs/albb-kimi
**Статус:** Автоисправлено
**Ответ:** В заголовке Task 5 добавлен явный **blockquote warning** о зависимости: "Task 5 depends on Task 6 — run Task 6 first." Numeric-порядок сохранён для spec traceability, но выделено что dependency-порядок overrides it.
**Действие:** plan.md — добавлен блок `> **Ordering:** ...` сразу после `### Task 5:`.

---

### [DISMISSED] gemini BUG-1: Jackson 3 imports неверны для Jackson 2 проекта

**Источник:** gemini-executor
**Статус:** Отклонено (false positive)
**Ответ:** Верификация: проект использует `tools.jackson.databind.*` в 6 существующих файлах (`DetectService.kt`, `VideoVisualizationServiceTest.kt`, `DetectServiceTest.kt`, `JacksonConfiguration.kt`, `WebClientConfiguration.kt`, `JobStatus.kt`). Это корректные импорты для Jackson 3 через Spring Boot 4.0.

---

### [DISMISSED] ccs/glm BUG-8: Timeout constants могут отличаться от current code

**Источник:** ccs/glm
**Статус:** Отклонено (false positive)
**Ответ:** Верификация: `QuickExportHandler.kt:322,326` содержит `QUICK_EXPORT_ORIGINAL_TIMEOUT_MS = 300_000L // 5 minutes` и `QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 3_000_000L // 50 minutes`. Значения в плане (строки 2379-2380) совпадают.

---

### [DISMISSED] ccs/glm BUG-9, BUG-12

**Ответ:** Self-retracted автором ("снимаю замечание", "не баг").

---

### [DISMISSED] ccs/glm DESIGN-1: Duplicate catch blocks в annotate()

**Источник:** ccs/glm
**Статус:** Отклонено (намеренный выбор)
**Ответ:** `catch (CancellationException)` и `catch (Exception)` выглядят одинаково, но в Kotlin корутинах второй не ловит CancellationException (это отдельная иерархия через `kotlinx.coroutines.NonCancellable`). Объединение через `catch (Throwable)` было бы опаснее — легко пропустить rethrow. Дублирование намеренно, minor code smell принят.

---

### [DISMISSED] ccs/glm MINOR-1: DetectServiceCancelJobTest избыточный setup

**Источник:** ccs/glm
**Статус:** Отклонено (consistency с проектом)
**Ответ:** Сам reviewer отметил, что этот pattern используется во всех existing тестах с MockWebServer. Оставляем consistency.

---

### [DISMISSED] ccs/kimi CRITICAL-9: Циклическая зависимость модулей

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** CLAUDE.md: `Dependencies: core -> telegram -> service -> model -> common` — стрелка означает "core depends on telegram". `core` имеет право импортировать из `telegram`. Reviewer неверно интерпретировал направление стрелки.

---

### [DISMISSED] ccs/kimi CRITICAL-8: @VisibleForTesting для snapshotForTest()

**Источник:** ccs/albb-kimi
**Статус:** Отклонено
**Ответ:** Проект не использует androidx (нет зависимости от `androidx.annotation.VisibleForTesting`). Kotlin `internal` modifier достаточен — ограничивает visibility модулем telegram, тесты в этом же модуле имеют доступ, внешние модули — нет.

---

### [DISMISSED] ccs/kimi BUG-10: coVerify с 12 any() может не сматчиться

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** `coVerify(exactly = 0) { ... }` означает "метод не вызывался ни разу". mockk не сравнивает аргументы — если метод не вызывался, matcher-форма вообще не важна. Тест корректен.

---

### [DISMISSED] ccs/kimi BUG-19: Race reading state в onProgress

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** `Entry.state` объявлен `@Volatile` — JMM гарантирует visibility. Use-case: "пропустить UI-апдейт если идёт cancel". Worst case — один лишний UI update до того, как state пропагандирует. Приемлемо; добавление `synchronized(entry)` в горячий hot-path onProgress создаст contention без реальной защиты.

---

### [DISMISSED] ccs/kimi BUG-20: NPE в cleanupExportFile(videoPath)

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** Верификация: в обоих Task 9 Step 1 и Task 10 Step 1 проверка `if (videoPath == null) return` делается ПЕРЕД вызовом `cleanupExportFile(videoPath)` в inner try{}. NPE невозможен.

---

### [DISMISSED] ccs/kimi MINOR-8: quickexport.error.concurrent missing

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** Верификация: `messages_ru.properties:112` содержит `quickexport.error.concurrent=Экспорт уже выполняется.` Ключ существует.

---

### [DISMISSED] ccs/kimi MINOR-12: quickexport.error.annotation.timeout missing

**Источник:** ccs/albb-kimi
**Статус:** Отклонено (false positive)
**Ответ:** Верификация: `messages_ru.properties:117` содержит `quickexport.error.annotation.timeout=Аннотация видео не успела завершиться за отведённое время...` Ключ существует.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-17-export-cancel-design.md` | §4 — переписан release() description с обоснованием порядка. §5.2 — явный `withContext(NonCancellable)` вокруг финального UI + cleanup в finally + пояснение "Почему NonCancellable обязателен". §5.3 — две строки (download cleanup, SENDING cleanup) обновлены с NonCancellable. §7 — `export.cancelled` → `export.cancelled.by.user` + новый абзац "Naming asymmetry by design". |
| `docs/superpowers/plans/2026-04-17-export-cancel.md` | Synopsis — чёткое разделение Registry (new) vs Tracker (kept). Task 2 Step 4b — `withContext(NonCancellable)` вокруг deleteIfExists + import hints. Task 2 Step 2 — новый тест `cancelJob rethrows parent CancellationException`. Task 3 Step 1 — новый тест на `NonCancellable` race. Task 5 — header warning про dependency с Task 6. Task 5 Step 1 — `ConcurrentLinkedQueue` вместо `mutableListOf`, LAZY-cancel-before-start test, `CompletableDeferred + withTimeout` вместо `delay(50)`. Task 5 Step 3 — release() переписан (remove-before-synchronized); добавлен `internal fun snapshotForTest()`; `attachCancellable` runCatching → try/catch. Task 6 Step 1 — `Dispatchers.IO` вместо `Default` + обоснование. Task 7 Step 1 — все `CommonUser(...)` переписаны на 3-param named args (6 мест); добавлены тесты на malformed data и edit-keyboard failure. Task 7 Step 3 — `runCatching` → `answerSafely()` helper + явные try/catch; импорты NonCancellable/withContext. Task 9 Step 1 — finally NonCancellable, catch(CancellationException) NonCancellable; импорты. Task 9 Step 2 — `ActiveExportRegistry(exportScope)` + напоминание про `@AfterEach shutdown`. Task 9 Step 3 — `snapshotForTest()` вместо несуществующих accessor'ов. Task 10 Step 1 — finally NonCancellable, catch(CancellationException) NonCancellable; импорты. Task 10 Step 3 — `firstActiveExport()` реализован; второй тест ("second parallel execute") полностью написан. Task 12 Step 1 — новая секция "Lock Ordering Invariant" для telegram.md. Self-Review — row 2.2 уточнён про SKIPPED. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-merged-iter-2.md` | Новый файл: все 7 ревью + consolidated summary. |
| `docs/superpowers/specs/2026-04-17-export-cancel-review-iter-2.md` | Этот файл. |

## Статистика

- Всего замечаний (unique, after dedup): 17
- CRITICAL автоисправлено: 3 (deadlock, cleanup NonCancellable, UI NonCancellable)
- BUG автоисправлено: 5 (Dispatchers.IO, runCatching, CommonUser, empty test body, test accessors)
- TEST автоисправлено: 4 (missing mandatory tests, harness race, flaky delays, LAZY-cancel test)
- DESIGN автоисправлено: 1 (lock ordering invariant)
- DOC автоисправлено: 2 (stale "replacing", i18n naming)
- MINOR автоисправлено: 1 (Task 5/6 ordering)
- Повторов из iter-1 (автоответ): 0
- Отклонено (false positives / uninformed / duplicate / intentional): 10
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4 xhigh), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Все 7 agents завершили работу успешно (в iter-1 ccs/glm direct hung — в iter-2 уложился в 5m23s).
