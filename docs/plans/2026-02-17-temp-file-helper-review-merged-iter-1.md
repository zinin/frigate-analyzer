# Merged Design Review — Iteration 1

## codex-executor (gpt-5.3-codex)

### [CONCURRENCY-1] Некорректная эмиссия `Flow` из `withContext`
**Серьёзность:** Critical
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 4 (`readFile` implementation)
**Описание:** В примере `readFile` вызывается `emit(...)` внутри `withContext(Dispatchers.IO)`. Для `flow {}` это нарушает контекстный инвариант и может привести к `IllegalStateException` (эмиссия из другого coroutine context).
**Рекомендация:** Делать чтение в `flow {}` и переносить upstream на IO через `.flowOn(Dispatchers.IO)`, либо использовать `channelFlow`/`withContext` без `emit` внутри переключённого контекста.

### [CONSISTENCY-1] Неверный подсчёт удалённых файлов
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 5 (`deleteFiles`)
**Описание:** В предложенном коде `count++` выполняется даже если `Files.deleteIfExists(file)` вернул `false` (файл отсутствовал). Это противоречит API ("count of successfully deleted") и вашему же тесту `assertEquals(2, count)`.
**Рекомендация:** Инкрементировать счётчик только при `true`: `if (Files.deleteIfExists(file)) count++`.

### [DESIGN-1] Очистка может падать на одном «битом» имени и прерывать весь проход
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 6 (`findOldFiles`)
**Описание:** `LocalDateTime.parse(...)` не защищён от `DateTimeParseException`. Если в каталоге появится файл с подходящим regex, но невалидной датой, весь `findOldFiles` упадёт, а `cleanOldFiles` не обработает остальные файлы.
**Рекомендация:** Обрабатывать parse-ошибки per-file (лог + skip), не роняя весь cleanup.

### [PLAN-1] Неполный план по обновлению тестов после смены конструктора `DetectService`
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 7 Step 3
**Описание:** Указан только `DetectServiceTest`, но `DetectService(...)` создаётся также в `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt` (несколько мест). В текущем виде план оставляет compile-break.
**Рекомендация:** Явно включить обновление `VideoVisualizationServiceTest` во все места создания `DetectService`.

### [DESIGN-2] Утечки временных файлов при ошибке записи контента
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 2/3 (`createTempFile(..., content)`)
**Описание:** Файл создаётся заранее, но при ошибке `Files.write`/`collect` остаётся пустой или частично записанный файл. Это против цели централизованного безопасного temp-management.
**Рекомендация:** В `catch/finally` удалять созданный файл (`deleteIfExists`) и пробрасывать исключение дальше.

### [API-1] API позволяет читать/удалять произвольные пути вне `tempFolder`
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-design.md`, API (`readFile`, `deleteIfExists`, `deleteFiles`)
**Описание:** Методы принимают любой `Path` без ограничений. Ошибка вызывающего кода может удалить/прочитать не-temp файлы.
**Рекомендация:** Добавить проверку, что путь внутри `tempFolder` (normalize + startsWith), либо явно разделить API на "managed temp only" и "generic file ops".

### [TEST-1] Недостаточное покрытие негативных сценариев cleanup/IO
**Серьёзность:** Important
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Testing + Task 6 tests
**Описание:** Нет тестов на: невалидный timestamp, parse-error, ошибку удаления отдельного файла с продолжением cleanup, и cleanup частично созданного файла после сбоя записи.
**Рекомендация:** Добавить отдельные тесты на устойчивость `findOldFiles/cleanOldFiles` и rollback/cleanup при ошибках записи.

### [API-2] Нет валидации `bufferSize` в `readFile`
**Серьёзность:** Minor
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-plan.md`, Task 4 (`readFile(path, bufferSize)`)
**Описание:** При `bufferSize <= 0` возможны некорректные сценарии (например, `0` может привести к бесконечному циклу чтения).
**Рекомендация:** `require(bufferSize > 0)` в начале метода.

### [CONCURRENCY-2] `runBlocking(Dispatchers.IO)` в scheduler блокирует поток планировщика
**Серьёзность:** Minor
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-design.md`, Periodic Cleanup
**Описание:** Для больших каталогов cleanup может блокировать scheduler thread на длительное время; вложенные `withContext(IO)` внутри `runBlocking(IO)` также избыточны.
**Рекомендация:** Минимизировать блокировки scheduler (например, `runBlocking` без явного IO + IO внутри suspend-методов, либо неблокирующий подход).

### [CONSISTENCY-2] Несоответствие между секцией Error Handling и планом реализации
**Серьёзность:** Minor
**Файл/секция:** `docs/plans/2026-02-17-temp-file-helper-design.md` (Error Handling) vs `docs/plans/2026-02-17-temp-file-helper-plan.md` (Tasks 2–6)
**Описание:** В дизайне заявлено "I/O errors logged and re-thrown", но в большинстве предложенных реализаций логирования I/O-ошибок нет.
**Рекомендация:** Либо добавить единообразное логирование в методы helper, либо скорректировать дизайн-док, чтобы ожидания и код совпадали.

---

## gemini-executor

### [DESIGN] Ограниченная переиспользуемость компонента (Dependency Inversion)
**Серьёзность:** Minor
**Файл/секция:** `TempFileHelper.kt` / Location
**Описание:** Компонент размещается в модуле `core` и зависит от `ApplicationProperties` (также в `core`). Это делает невозможным его использование в модуле `service`.
**Рекомендация:** Рассмотреть возможность перемещения в `common` с внедрением `Path tempFolder` вместо `ApplicationProperties`.

### [CONCURRENCY] Блокировка планировщика задач
**Серьёзность:** Important
**Файл/секция:** `cleanOldFiles` / Periodic Cleanup
**Описание:** `runBlocking` внутри `@Scheduled` блокирует поток планировщика Spring (однопоточный по умолчанию).
**Рекомендация:** Использовать `CoroutineScope` с `scope.launch(Dispatchers.IO)` или настроить `TaskScheduler` с пулом > 1.

### [DESIGN] Формат метки времени в имени файла
**Серьёзность:** Minor
**Файл/секция:** File Naming Convention
**Описание:** Формат `{dd-MM-yyyy-HH-mm-ss}` неудобен для лексикографической сортировки.
**Рекомендация:** Использовать ISO-8601 совместимый формат: `{yyyy-MM-dd-HH-mm-ss}`.

### [TEST] Отсутствие негативных сценариев удаления
**Серьёзность:** Minor
**Файл/секция:** Testing
**Описание:** Нет тестов для случаев, когда удаление файла невозможно (файл заблокирован, нет прав).
**Рекомендация:** Добавить тест-кейс с "сбойным" файлом.

---

## ccs-executor (glmt)

### [DESIGN-1] Противоречие в API readFile
**Серьёзность:** Important
**Файл/секция:** Design Document → API
**Описание:** `readFile` не `suspend`, но выполняет I/O. Может ввести в заблуждение пользователя API.
**Рекомендация:** Добавить KDoc с указанием, что I/O происходит в `Dispatchers.IO` при collect.

### [DESIGN-2] Потенциальная утечка ресурсов в readFile
**Серьёзность:** Minor
**Файл/секция:** Plan Task 4
**Описание:** `withContext(Dispatchers.IO)` внутри `flow {}` — переключение контекста при каждом emit. Не оптимально.
**Рекомендация:** Перенести `withContext(Dispatchers.IO)` наружу или использовать `.flowOn(Dispatchers.IO)`.

### [DESIGN-3] Отсутствует обработка случая directory вместо file
**Серьёзность:** Important
**Файл/секция:** Error Handling
**Описание:** В `deleteFiles`/`deleteIfExists` нет проверки, что путь — файл, а не директория.
**Рекомендация:** Добавить проверку `Files.isDirectory()` перед удалением.

### [PLAN-1] DetectServiceTest использует applicationProperties для DetectServerLoadBalancer
**Серьёзность:** Critical
**Файл/секция:** Task 7, DetectServiceTest.kt:79
**Описание:** План предлагает заменить `ApplicationProperties` на `TempFileHelper`, но `DetectServerLoadBalancer` также использует `ApplicationProperties`.
**Рекомендация:** Оставить `ApplicationProperties` в `DetectService` или указать, что тест нуждается в обоих зависимостях.

### [PLAN-2] Некорректная инициализация TempFileHelper в тесте
**Серьёзность:** Important
**Файл/секция:** Task 7, Step 3
**Описание:** `Clock.systemDefaultZone()` — тесты недетерминированные.
**Рекомендация:** Использовать `Clock.fixed()`.

### [PLAN-3] Отсутствует проверка isEnabled для @Scheduled в test profile
**Серьёзность:** Important
**Файл/секция:** Periodic Cleanup
**Описание:** `@Scheduled` будет выполняться даже в тестах, потенциально удаляя временные файлы тестов.
**Рекомендация:** Использовать `@ConditionalOnProperty` или проверку `springProfileHelper.isTestProfile()`.

### [CONSISTENCY-1] Противоречие между Design и Plan по поводу cleanOldFiles test
**Серьёзность:** Minor
**Описание:** Тест вызывает `cleanOldFiles()` напрямую, что не отражает `runBlocking` wrapper в production.

### [TEST-1] Отсутствует тест для частичной ошибки в deleteFiles
**Серьёзность:** Minor

### [TEST-2] Circular dependency в readFile test
**Серьёзность:** Minor

### [TEST-3] Отсутствует тест concurrent cleanOldFiles
**Серьёзность:** Minor

### [API-1] createTempFile с Flow — не указан coroutine context
**Серьёзность:** Minor

### [API-2] Отсутствует метод getTempFolder для health check
**Серьёзность:** Minor

### [CONCURRENCY-1] readFile Flow не cancellation-safe между emit
**Серьёзность:** Minor
