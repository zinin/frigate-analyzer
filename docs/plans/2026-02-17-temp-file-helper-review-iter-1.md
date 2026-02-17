# Review Iteration 1 — 2026-02-17 10:05

## Источник

- Design: `docs/plans/2026-02-17-temp-file-helper-design.md`
- Plan: `docs/plans/2026-02-17-temp-file-helper-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (PROFILE=glmt)
- Merged output: `docs/plans/2026-02-17-temp-file-helper-review-merged-iter-1.md`

## Замечания

### #1 [CONCURRENCY] emit() внутри withContext(Dispatchers.IO) нарушает Flow invariant

> В `readFile` вызывается `emit(...)` внутри `withContext(Dispatchers.IO)`. Для `flow {}` это нарушает контекстный инвариант и приведёт к `IllegalStateException` в runtime.

**Источник:** Codex (CONCURRENCY-1 Critical), CCS (DESIGN-2 Minor)
**Статус:** Новое
**Ответ:** Исправить — использовать `.flowOn(Dispatchers.IO)` вместо `withContext` внутри `flow {}`
**Действие:** Обновлён код `readFile` в плане: убран `withContext`, добавлен `.flowOn(Dispatchers.IO)`

---

### #2 [PLAN] VideoVisualizationServiceTest не указан в плане обновления

> `DetectService(...)` создаётся также в `VideoVisualizationServiceTest` (строка 82). План упоминает только `DetectServiceTest` — compile-break.

**Источник:** Codex (PLAN-1), CCS (PLAN-1)
**Статус:** Новое
**Ответ:** Исправить — добавить VideoVisualizationServiceTest в Task 7
**Действие:** Добавлен файл в Task 7, добавлен Step 3b

---

### #3 [CONSISTENCY] deleteFiles считает несуществующие файлы как удалённые

> `count++` выполняется без проверки возврата `Files.deleteIfExists()`. Противоречит API и тесту.

**Источник:** Codex (CONSISTENCY-1)
**Статус:** Новое
**Ответ:** Исправить — `if (Files.deleteIfExists(file)) count++`
**Действие:** Обновлён код `deleteFiles` в плане

---

### #4 [DESIGN] findOldFiles падает на невалидном timestamp

> `LocalDateTime.parse(...)` без try-catch. Один файл с битым именем роняет весь cleanup.

**Источник:** Codex (DESIGN-1)
**Статус:** Новое
**Ответ:** Исправить — try-catch DateTimeParseException per-file, log + skip
**Действие:** Обновлён код `findOldFiles`, добавлен тест `findOldFiles skips files with malformed timestamp`

---

### #5 [DESIGN] Утечка temp file при ошибке записи контента

> `createTempFile` с content: при ошибке записи файл не удаляется.

**Источник:** Codex (DESIGN-2)
**Статус:** Новое
**Ответ:** Исправить — try-catch с deleteIfExists в catch
**Действие:** Обновлён код Task 2 и Task 3 с try-catch cleanup

---

### #6 [TEST] Недостаточное покрытие негативных сценариев

> Нет тестов на: невалидный timestamp, ошибки удаления с продолжением cleanup.

**Источник:** Codex (TEST-1), Gemini (TEST), CCS (TEST-1)
**Статус:** Новое
**Ответ:** Добавить тест на malformed timestamp (остальные негативные сценарии сложно тестировать без моков)
**Действие:** Добавлен тест `findOldFiles skips files with malformed timestamp`

---

### #7 [DESIGN] Формат timestamp dd-MM-yyyy не сортируется лексикографически

> `ls` показывает файлы не по дате. yyyy-MM-dd лучше.

**Источник:** Gemini (DESIGN)
**Статус:** Новое
**Ответ:** Исправить — yyyy-MM-dd-HH-mm-ss
**Действие:** Обновлён формат в дизайне, плане, тестах и regex

---

### #8 [API] Нет валидации bufferSize в readFile

> `bufferSize <= 0` может вызвать бесконечный цикл.

**Источник:** Codex (API-2)
**Статус:** Новое
**Ответ:** Исправить — `require(bufferSize > 0)`
**Действие:** Добавлен `require` в код `readFile`

---

### #9 [CONSISTENCY] Error Handling: дизайн обещает логирование, план не добавляет

> В дизайне "I/O errors logged and re-thrown", но реализация без логирования.

**Источник:** Codex (CONSISTENCY-2)
**Статус:** Новое
**Ответ:** Обновить дизайн — логирование только в cleanup и deleteFiles (где продолжается обработка). Остальные методы пробрасывают без логирования — caller решает.
**Действие:** Обновлена секция Error Handling в дизайне

---

### #10 [PLAN] Clock.systemDefaultZone() в DetectServiceTest — недетерминированный

> План Task 7 предлагает `Clock.systemDefaultZone()` для TempFileHelper в тесте.

**Источник:** CCS (PLAN-2)
**Статус:** Новое
**Ответ:** Исправить — использовать Clock.fixed()
**Действие:** Обновлён код в Task 7 Step 3/3b

---

### #11 [CONCURRENCY] runBlocking(Dispatchers.IO) блокирует scheduler thread

> `@Scheduled` метод блокирует планировщик Spring.

**Источник:** Gemini (CONCURRENCY), Codex (CONCURRENCY-2)
**Статус:** Новое
**Ответ:** Оставить как есть — осознанное решение из brainstorming, аналогично ServerHealthMonitor
**Действие:** Без изменений

---

### #12 [API] Path validation: read/delete принимают произвольные пути

> Методы не проверяют что путь внутри tempFolder.

**Источник:** Codex (API-1)
**Статус:** Новое
**Ответ:** Добавить валидацию
**Действие:** Добавлен `requirePathInTempDir()` в readFile, deleteIfExists, deleteFiles

---

### #13 [DESIGN] deleteFiles/deleteIfExists не проверяют directory vs file

> Могут случайно удалить пустую директорию.

**Источник:** CCS (DESIGN-3)
**Статус:** Новое
**Ответ:** Добавить проверку
**Действие:** Добавлена проверка `Files.isDirectory()` в deleteIfExists и deleteFiles, `Files.isRegularFile()` в findOldFiles

---

### #14 [PLAN] @Scheduled запускается в тестах

> Race condition — cleanup может удалить файлы тестов.

**Источник:** CCS (PLAN-3)
**Статус:** Новое
**Ответ:** Ложное срабатывание — тесты unit (без Spring context), @Scheduled не запускается
**Действие:** Без изменений

---

### #15 [DESIGN] Перенести TempFileHelper в common для переиспользуемости

> Компонент привязан к core через ApplicationProperties.

**Источник:** Gemini (DESIGN)
**Статус:** Новое
**Ответ:** Ложное срабатывание — решение из brainstorming: core, т.к. нужен ApplicationProperties.tempFolder
**Действие:** Без изменений

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | Формат timestamp → yyyy-MM-dd-HH-mm-ss |
| design.md | Описание API: добавлены path validation, directory check, bufferSize validation, flowOn, cleanup on error |
| design.md | Добавлена секция Path Validation |
| design.md | Уточнена секция Error Handling |
| design.md | Добавлены негативные тесты в Testing |
| plan.md | Task 1: обновлён DATE_FORMAT и тест assertion |
| plan.md | Task 2: добавлен try-catch cleanup при ошибке записи |
| plan.md | Task 3: добавлен try-catch cleanup при ошибке записи |
| plan.md | Task 4: readFile переписан с flowOn, добавлены require и requirePathInTempDir |
| plan.md | Task 5: deleteFiles — if(deleteIfExists) count++, path validation, directory check |
| plan.md | Task 6: findOldFiles — try-catch DateTimeParseException, regex обновлён, timestamps в тестах обновлены, добавлен негативный тест |
| plan.md | Task 7: добавлен VideoVisualizationServiceTest, Clock.fixed() вместо systemDefaultZone |

## Статистика

- Всего замечаний: 15
- Новых: 15
- Повторов (автоответ): 0
- Справедливых (исправлено): 12
- Оставлено без изменений: 1 (runBlocking — осознанное решение)
- Ложных срабатываний: 2
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, gemini-executor, ccs-executor
