# Merged Design Review — Iteration 1

**Topic:** recordings-error-counts (#28)
**Date:** 2026-05-26
**Design:** `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`
**Plan:** `docs/superpowers/plans/2026-05-25-recordings-error-counts.md`
**Reviewers (4/5 completed):** codex-executor (gpt-5.5 xhigh), ollama-kimi, ollama-deepseek, ollama-minimax. ccs-executor (glm) — wrapper failed to finalize output, partial transcripts only.

---

## codex-executor (gpt-5.5, xhigh)

### Critical Issues

1. **План в Task 6 добавляет лишний import `RecordingCountsDto`, но тест его не использует:** `docs/superpowers/plans/2026-05-25-recordings-error-counts.md:643`. В предложенном тесте тип выводится из `repository.getRecordingCounts()` и явно не упоминается (`docs/superpowers/plans/2026-05-25-recordings-error-counts.md:678`). Это почти наверняка даст ktlint/unused-import failure. Import надо убрать из плана.

2. **Код-блок нового repository-теста не компилируется без доработки helper'а:** план вызывает `createRecordingEntity(..., errorMessage = "boom")` (`plan:668`), но текущий helper не принимает `errorMessage` и всегда пишет `errorMessage = null` (`modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt:36` и `:63`). План помечает это как uncertainty (`plan:690`), но теперь uncertainty снята: helper надо явно расширить в плане или заменить seed на `copy(errorMessage = "boom")`.

### Concerns

1. **Одного global `errors` может быть недостаточно для операторской диагностики:** per-camera section продолжит скрывать ошибки в колонке `proc`, потому что `getStatisticsByCameras()` считает `process_timestamp IS NOT NULL` без `error_message` (`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt:112`). Дизайн честно выносит per-camera errors из scope (`design:296`), но проблема «какая камера даёт ошибки» останется.

2. **Отказ от инварианта `success + errors == processed` defensible, но текущая модель с `error_message IS NOT NULL` как state-флаг хрупкая.** `markProcessed()` не очищает `error_message` (`RecordingEntityRepository.kt:56`), а `markProcessedWithError()` выставляет и timestamp, и error (`RecordingEntityRepository.kt:88`). Если появится retry/manual repair, успешная повторная обработка может остаться counted as error, если transition не очистит `error_message`.

3. **Дизайн говорит, что R2DBC DTO mapping риск покрыт `StatusServiceTest` через mock (`design:287`):** это неверно: mock покрывает только wiring. Реально mapping покрывает только новый integration-тест в Task 6. Сам DTO-подход нормальный, потому что аналогичные DTO уже используются (`CameraStatisticsDto.kt:5`).

4. **REST additive change в целом безопасен, но «не ломает consumers» не абсолютно.** `/status` публичен через OpenAPI (`StatusController.kt:24`). Строгие клиенты с generated DTO могут не любить дополнительные поля.

5. **i18n mock-тесты не докажут, что новые ключи реально добавлены в bundles:** mock возвращает сам key. Существующий real-bundle тест форматирует recordings, но не проверяет новые recording labels/values (`StatusMessageFormatterTest.kt:294`). При пропущенном ключе `MessageResolver` вернёт key string (`MessageResolver.kt:28`).

### Suggestions

1. Single `COUNT(*) FILTER (WHERE ...)` query — правильный выбор для этого scope. Один snapshot, один scan; на ~10⁴ строк индексы не нужны.

2. Рассмотреть имена `successful`/`failed` (или `succeeded`/`failed`) вместо `success`/`errors`. `success` как count слегка awkward, `errors` хуже описывает state, чем `failed`. Если новые поля ещё не отгружены — это последний дешёвый момент сменить имена.

3. Добавить отдельный non-invariant репозиторный тест: засеять `process_timestamp IS NULL AND error_message IS NOT NULL` и assert ожидаемый overlap (`unprocessed` + `errors`). Это задокументирует будущую retry-pending семантику.

4. Если retry — реальный design driver, открыть follow-up issue на полноценный status/state column. `error_message IS NOT NULL` ок для этой итерации, но это не чистый state machine.

5. Добавить real-bundle formatter assertion для EN/RU recordings rows: «Success/Errors» и «Успешно/Ошибки» + проверка отсутствия raw `status.recordings.label.*` leakage.

### Questions

1. Потребляется ли `/status` каким-то external generated client из Swagger/OpenAPI, или только operator-facing?
2. Должны ли terminal errors и retry-pending errors навсегда быть одним bucket `errors`, или это временный компромисс до появления status enum?
3. Будет ли per-camera `proc` переименован или расширен, чтобы не продолжать masking problem внутри `byCameras`?

---

## ollama-kimi (Kimi K2.6 cloud)

### Critical Issues

1. **Missing `RecordingCountsDto` import in `StatusService.kt`** — Task 2 Step 2 шоу the full `buildRecordings()` body but never adds `import ru.zinin.frigate.analyzer.model.dto.RecordingCountsDto`. Без него код не компилируется.

2. **`errors` filter does not require `process_timestamp IS NOT NULL`** — `COUNT(*) FILTER (WHERE error_message IS NOT NULL) AS errors` делает `errors` независимым от `processed`. Сегодня `markProcessedWithError` ставит оба поля атомарно, но любая будущая retry-pending state (или ручные DB-правки) поместит ту же строку одновременно в `unprocessed` и `errors`. REST consumers увидят `processed=90, success=85, errors=15` и не поймут, почему числа не сходятся. Telegram layout C скрывает `Processed`, но REST оставляет.

3. **Wrong line number in properties files** — Plan Task 4 Step 1 говорит «immediately after `status.recordings.value.rate=...` (currently line 207)», но в `modules/telegram/.../messages_en.properties:206` `status.recordings.value.rate` это line 206; line 207 уже `status.recordings.value.processed`. То же для RU bundle.

### Concerns

1. No test for `success + errors != processed` scenario — Design §9.3 marks it optional; plan omits it. Хотя инвариант намеренно не enforce'ится, хотя бы один formatter-тест должен доказать что layout C не падает на `processed=100, success=80, errors=10`.

2. Dead `formatRow()` not removed — Plan Task 5 Step 3 leaves `StatusMessageFormatter.kt:234-241` as dead code, полагаясь на ktlint. Явное удаление чище.

3. Plan summary says «compile-fix (4 sites)» but there are 5 — 4 in `StatusMessageFormatterTest` outer class + 1 in nested `StatusMessageFormatterI18nTest.sampleSnapshot()`. Detailed steps cover all 5; summary misleading.

4. `error_message` index not discussed — Design §5.3 утверждает, что индекс не нужен, но `idx_recordings_process_timestamp` существует (`docker/liquibase/migration/1.0.0.xml:76`), тогда как у `error_message` индекса нет. Выше ~10⁵ rows `COUNT(*) FILTER (WHERE error_message IS NOT NULL)` пойдёт в seq scan. Стоит зафиксировать как future consideration.

5. `createRecordingEntity` hardcodes `errorMessage = null` — In `modules/core/.../RecordingEntityRepositoryTest.kt:36-65` хелпер жёстко-кодирован; Task 6 Step 4c требует `errorMessage = "boom"`. План флагает это, но «match whichever pattern the file already uses» оставляет ambiguity автономному исполнителю.

### Suggestions

1. Tighten `errors` filter to `process_timestamp IS NOT NULL AND error_message IS NOT NULL` так, чтобы `errors ⊆ processed` семантически; добавить отдельный `pendingRetry` field позже если retry-pending заземлится.
2. Explicitly remove `formatRow()` in Task 5/6.
3. Add `import ru.zinin.frigate.analyzer.model.dto.RecordingCountsDto` step in Task 2 Step 2.
4. Add a formatter test covering `processed != success + errors` (e.g., `total=100, processed=100, success=80, errors=10`).
5. Correct line refs in properties to line 206 (not 207) in both bundles.

### Questions

1. Почему `errors` defined without `process_timestamp` correlation? Если retry-pending — future work, делая `errors` strict subset of `processed` сейчас (и вводя отдельный счётчик позже) keeps REST contract intuitive.
2. Как ведёт себя `MessageResolver` на missing keys? Tasks 5 и 6 оставляют окно где старые ключи ушли но formatter в mid-migration — fallback безопасен или throw'ит?
3. Should `processed` be `@Deprecated`/KDoc-flagged in `RecordingsStatistics`? Telegram больше не показывает; signal to REST consumers, что `success`/`errors` preferred.

---

## ollama-deepseek (DeepSeek-V4 Pro cloud)

### Critical Issues

**Отсутствуют.** Документы качественные.

### Concerns

1. **C1. Plan Task 6 Step 4c — хелпер `createRecordingEntity` не принимает `errorMessage`** (`RecordingEntityRepositoryTest.kt:36-65`, hardcoded `errorMessage = null` at line 63). План честно признаёт это и даёт два варианта, но решение оставлено исполнителю — нет конкретного указания, какой путь выбрать. В проекте хелпер уже имеет 9 параметров с default'ами; добавить `errorMessage: String? = null` — естественное расширение. **Рекомендация:** явно предписать расширение хелпера, а не оставлять выбор исполнителю.

2. **C2. Plan Task 5 Step 1 — тест layout C проверяет `"status.recordings.value.withPct[85,85.0]"`.** Формат значения в identity-mock: `key[arg0,arg1]`. `joinToString(",")` не экранирует запятые внутри аргументов. Для чисел безопасно, но паттерн хрупкий — будущий рефакторинг мока может сломать ассерты.

3. **C3. Plan Task 2 Step 4-5 vs Task 5 — interim state.** Task 2 (Step 5) правит 4 construction sites в `StatusMessageFormatterTest`. Сам formatter всё ещё использует старый layout до Task 5. Это валидный interim state — компилируется. Однако если между Task 2 и Task 5 кто-то запустит тесты formatter, старые ассерты упадут из-за mismatch'а layout. План не предупреждает об этом.

4. **C4. Design §8.1 — ширина строки.** Утверждается `labelWidth=12` («Rate (5 min)»), но в RU max width label = «Скорость (5 мин)» = 15 символов. Алгоритм `rows.maxOf { it.first.length }` вычисляет динамически — ручные значения только иллюстративные. Не ошибка, но может сбить с толку при code review.

5. **C5. Plan — `formatRow(...)` dead code removal зависит от ktlint.** Но ktlint **не детектит** unused private methods — это compiler warning. `formatRow` (`StatusMessageFormatter.kt:234-241`) мёртв на 100%. Рекомендация: удалить безусловно в Task 5.

6. **C6. Design §5.3 — «нового индекса не требуется».** Утверждение базируется только на объёме данных (~10⁴ строк). Старые запросы `countProcessed()/countUnprocessed()` с `WHERE process_timestamp IS NOT NULL` могли использовать частичный индекс. Новый `COUNT(*) FILTER` делает один seq scan без WHERE.

7. **C7. Plan Task 7 — нет верификации через `/verify`.** После сборки план предлагает только ручное тестирование Telegram. Учитывая API+Telegram изменение, стоило бы добавить шаг верификации REST-ответа через `curl`.

### Suggestions

1. **S1.** Добавить тест на edge-case: `error_message IS NOT NULL AND process_timestamp IS NULL`. Хотя приложение сегодня не создаёт таких записей, FILTER-агрегат даст `processed=0, success=0, errors=1`. Корректно для defensive design.
2. **S2.** RU-перевод «Успешно» → «Успешные» (taste).
3. **S3.** Позиция новых i18n-ключей: логичнее сгруппировать label-ключи вместе, между `label.unprocessed` и `label.rate` (taste).
4. **S4.** Task 5 tests — добавить кейс `success + errors != processed` (явное дизайн-решение, тест документирует контракт).
5. **S5.** Plan Self-Review §3 — «named args throughout» утверждение точное.

### Questions

1. **Q1.** Почему `@Column` в `RecordingCountsDto`, а не `@JsonProperty`? Без `@Column` R2DBC использует property name → column name mapping. Для `total` → `total` он избыточен, но консистентность важнее. Проверяли ли поведение без `@Column` аннотаций?
2. **Q2.** Почему `pct()` приватный, а не вынесен в общий `common` модуль? YAGNI сейчас, но упомянуть как кандидат на вынос при первом reuse.
3. **Q3.** Обработка `total == 0` для `processingRatePerMinute` — корректна (rate=0.0).

---

## ollama-minimax (MiniMax M2.7 cloud)

### Critical Issues

1. **`createRecordingEntity` не поддерживает `errorMessage` — тест в Task 6 не скомпилируется.** В `RecordingEntityRepositoryTest.kt` (строки 36-65) хелпер имеет 9 параметров, включая `processTimestamp`, но **нет параметра `errorMessage`**. Тело: `errorMessage = null,  // hardcoded`. План в Task 6 Step 4c сам это признаёт и даёт инструкцию проверить и расширить. Это критично — без расширения хелпера integration-тест `should return recording counts via FILTER aggregate` не скомпилируется.

2. **План не упоминает `StatusControllerTest` — assertions неполны.** `StatusControllerTest.kt` (строки 44-53) проверяет JSON-path для `$.recordings.total`, `$.recordings.processed`, `$.recordings.unprocessed`, но **не проверяет** `byCameras` и `processingRatePerMinute` в recordings. План добавляет assertions для `success` и `errors` в Task 3, но не замечает, что `byCameras` и `processingRatePerMinute` уже пропущены — pre-existing gap, не блокер для этой задачи.

3. **`processed = success + errors` — намеренный invariant без assertion, но документация противоречива.** Design §6 говорит про «сегодня» и «намеренно нет ассерта», а §3 (Q3) говорит «не делаем инвариантом». Указание «сегодня» создаёт ложное впечатление, что инвариант будет проверен позже. Текущий контракт: `success = process_timestamp IS NOT NULL AND error_message IS NULL`, `errors = error_message IS NOT NULL`. Оба независимы. Сумма может быть меньше `processed`, если есть записи с `process_timestamp` без `error_message`. Однако инвариант `processed = success + errors` выполняется **всегда** при текущей логике. **Самый существенный architectural вопрос** — рекомендация: убрать из design §6 формулировку «сегодня» и «намеренно нет ассерта» → нейтральное «инвариант не закреплён в коде; допустимо нарушение в будущем (retry-pending)».

### Concerns

1. **`RecordingsStatistics` переходит с 5 на 7 positional-совместимых полей — риск регрессии при добавлении новых.** Plan использует named args всюду, что безопасно, но любая future addition нового поля сломает позиционные конструкции без warnings.

2. **Layout C удаляет `processed` из Telegram, но `processed` остаётся в REST JSON без декомпозиции.** Контракт REST становится несогласованным. Это осознанное решение, но в design (§10) указано «old consumers — не ломаются», хотя consumer, который делает `processed - success - errors` для мониторинга, получит ноль (если все errors учтены), что не информативно.

3. **Тест `collect populates recordings counters with zero errors` не проверяет `processed = 0` и `unprocessed = 0`.** В Task 2 Step 3d новый тест создаёт DTO с всеми нулями, но не проверяет `processed` и `unprocessed`. Для полноты картины стоит добавить assertions.

4. **FILTER aggregate edge case.** Если запись `error_message IS NOT NULL AND process_timestamp IS NULL` (error на этапе acquire, до startProcessing) — она попадёт в `errors` AND в `unprocessed`. Currently все errors из `markProcessedWithError` ставят оба поля; this is hypothetical.

5. **Grep проверка в Task 6 Step 1 — adequate scope.** Проверка по Kotlin + properties файлам — корректно.

### Suggestions

1. **S9.** Добавить assertion для `processed` и `unprocessed` в zero-errors тест.
2. **S10.** `formatRow` в `StatusMessageFormatter` — мёртвый код, явно удалить в Task 5.
3. **S11.** Рассмотреть документ-уровневый assertion `success + errors <= processed` в `StatusServiceTest` — даже если не enforce'ится в production.
4. **S12.** Layout C в spec (§8.1) — RU max-label width 15 (vs EN 12), 27-char ширина может не выдержаться. Стоит проверить.
5. **S13.** Добавить тест `success + errors != processed` (явное дизайн-решение из §9.3).
6. **S14.** `RecordingCountsDto` — рассмотреть default values `= 0L` для всех полей на уровне data class, чтобы сделать DTO более resilient к NULL column alias.

### Questions

1. Почему `byCameras` и `processingRatePerMinute` остаются отдельными запросами, а не включены в FILTER aggregate? (Design §5 объясняет — приемлемо.)
2. `git rm docs/superpowers/...` в Task 7 Step 5 — корректно. Documents остаются в git history.
3. Direct gradle invocation vs `/build` — план сам отмечает как исключение. Корректно.
4. Размещение `getRecordingCounts()` между `countUnprocessed()` (line 108) и `getStatisticsByCameras()` (line 110) — правильное место.
5. `process_timestamp NOT NULL` + `error_message NULL` для success — корректно.
6. `success` FILTER без `process_attempts` — корректно (успешная запись без оглядки на попытки).

---

## ccs-executor (glm) — INCOMPLETE

Wrapper subagent reported «I'll wait for the background task to complete», but CCS process actually ran 9.5 minutes reading files and never produced final review output. 3 assistant text blocks captured in log (intermediate progress notes only):
- «Начну с чтения обоих документов и исследования кодовой базы.»
- «Теперь исследую кодовую базу для верификации утверждений в документах.»
- «Проверю ещё несколько критичных деталей.»

No final critique was emitted. Coverage compensated by 4 other reviewers.
