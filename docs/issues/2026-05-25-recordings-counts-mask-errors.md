# `/status` recordings counts молча маскируют ошибки

**Дата создания:** 2026-05-25
**Источник:** external code review (codex, feat/status-command branch)
**Severity:** Important (но не блокер — унаследовано из `/statistics`)
**Связанный PR:** `feat/status-command` (REST `/status` + Telegram `/status`)

## Краткая суть

REST `/frigate-analyzer/status` (и Telegram `/status`) показывает три счётчика recordings: `total`, `processed`, `unprocessed`. Запись с ошибкой обработки попадает в `processed` — `/status` молча скрывает реальное число неудачных recordings. Контракт текущий: `processed = total - unprocessed`, без учёта error-state.

## Доказательство

### Repository queries

`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NOT NULL")
suspend fun countProcessed(): Long

@Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NULL")
suspend fun countUnprocessed(): Long
```

### Что делает `markProcessedWithError`

В том же файле, `:89-95`:

```sql
SET process_timestamp = :processTimestamp,
    error_message = :errorMessage
```

То есть при ошибке обработки запись получает И `process_timestamp` (через который попадает в `countProcessed`), И `error_message`. Текущий SQL `countProcessed` НЕ фильтрует по `error_message IS NULL`.

### Кто маркирует ошибки

`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/RecordingEntityServiceImpl.kt:145-151`:

```kotlin
override suspend fun markProcessedWithError(
    id: UUID,
    errorMessage: String,
) {
    val truncated = ...
    repository.markProcessedWithError(id, Instant.now(clock), truncated)
}
```

Вызывается из pipeline'а при сбое детекции/визуализации/AI-описания.

### Что видит пользователь

При 100 recordings, из которых 90 успешных и 10 с ошибками детекции:
- `total: 100` ✓
- `processed: 100` ← скрывает 10 ошибок
- `unprocessed: 0` ✓

Реальный pipeline-health показатель невидим без прямого SQL-запроса.

## Почему это унаследовано (а не регрессия `/status`)

Старый `/statistics` использовал ровно эти же `countAll/countProcessed/countUnprocessed`. `feat/status-command` перенёс контракт 1:1 (см. `docs/superpowers/specs/2026-05-25-status-telegram-design.md` § Out of scope: "Транзакционная консистентность 5-запросного сбора `recordings.*` — наследуется от текущего `/statistics`"). Review iter-1 явно зафиксировал это в CONCERN-11.

Codex в финальном внешнем ревью feat/status-command поднял это как **Important** с пометкой "do NOT merge as production-ready until...". Решение по результатам обсуждения: **отложить в отдельный issue**, потому что:
1. Не регрессия фичи `/status`.
2. Требует продуктового design'а (что показывать и как).
3. Backwards-compatible (новые поля → старые консьюмеры не ломаются).

## Open questions для /brainstorming

Эти вопросы нужно решить в design-фазе follow-up'а:

### Q1. Какие счётчики добавить?

Варианты:
- (a) Только `errors: Long` — минимум, оставляем `processed` неизменным (включая errors). User должен сам считать `analyzed = processed - errors`.
- (b) `errors` + `analyzed: Long` (= `process_timestamp NOT NULL AND error_message IS NULL`). Чище, но 2 новых поля.
- (c) **Reinterpret** `processed`: change semantics to "successfully analyzed" (исключить errors). Breaking change для существующих JSON-консьюмеров, но семантически правильно. Согласовано с интуитивным значением слова "processed".
- (d) Полный pipeline state breakdown: `downloaded` (file_path NOT NULL) + `analyzed` (... AND error_message IS NULL) + `errors` (error_message NOT NULL) + `pending` (process_timestamp NULL). Соответствует фазам pipeline'а явно.

### Q2. Категоризация ошибок — нужна ли?

Сейчас `error_message` — это плоский TEXT. Если в будущем добавить enum (`ERROR_TYPE`), можно показать breakdown:
- `errors.detection: N`
- `errors.visualization: N`
- `errors.ai_description: N`

Это требует миграции БД (добавление столбца, обратное заполнение из текстов error_message по prefix-match) — отдельная история.

В этой итерации, скорее всего, plain `errors: Long` достаточно. Open: смотрит ли уже сейчас error_message паттерны таких, что бы можно было разнести?

### Q3. Retry-pending state

Если pipeline когда-нибудь поддержит автоматический retry (после фикса проблемы), нужен ещё state "marked-with-error, но ожидает retry". Сейчас error → terminal, повторно не обрабатывается. **На текущий момент не нужно**, но дизайн должен оставлять место (не делать `analyzed + errors = processed` жёстким инвариантом).

### Q4. Telegram-формат

В `<pre>`-блоке секции Recordings сейчас:
```
Total:        12 450
Processed:    12 380 (99.4%)
Unprocessed:      70
Rate (5 min):   2.3 rec/min
```

С variants Q1:
- (a): добавить `Errors: 10` строкой.
- (b): `Analyzed: 90 (90%) / Errors: 10 (10%) / Pending: 0` — компактнее, но требует пересчёта label-paddings.
- (d): 4 строки `Downloaded/Analyzed/Errors/Pending`.

Нужно прикинуть, влезает ли в "mobile-readable" формат без overflow.

### Q5. i18n строки

При добавлении полей:
- `status.recordings.label.errors=Errors` / `Ошибки`
- `status.recordings.label.analyzed=Analyzed` / `Обработано` (если меняется семантика — тут конфликт с "Processed" текущим).
- Возможно — `status.recordings.label.pending=Pending` / `Ожидает`.

### Q6. Удалить `unprocessed` или оставить?

Если переходим на семантику Q1(d) с явными `pending` / `analyzed` / `errors`, то `unprocessed` становится == `pending`. Дубликат. Стоит deprecate'нуть в response (или сразу удалить — single-deployment).

### Q7. SQL производительность

Текущий `StatusService.collect()` делает 5 запросов serial (iter-1 CONCERN-11 left as-is). Каждый новый счётчик += 1 query. С Q1(d) станет 8 запросов. Стоит:
- (a) Параллелить через `coroutineScope { async { ... } }`, чтобы не ухудшать latency. (Iter-3 M8 уже предлагал это для текущих 5.)
- (b) Объединить count'ы в один SQL: `SELECT COUNT(*) FILTER (WHERE ...) AS ...`. PostgreSQL поддерживает FILTER aggregate.

Скорее всего (b) — один query вместо N — правильное решение. Меняется сигнатура репозитория.

## Затронутые файлы (план изменений на момент решения design'а)

**Service:**
- `modules/service/.../repository/RecordingEntityRepository.kt` — новые методы или объединённый FILTER-aggregate
- `modules/service/.../impl/RecordingEntityServiceImpl.kt` — если меняется service-API

**Model:**
- `modules/model/.../response/RecordingsStatistics.kt` — новые поля (или замена существующих)
- Возможно `modules/model/.../dto/...` — если нужен новый DTO для combined-count'а

**Core:**
- `modules/core/.../service/StatusService.kt` — `buildRecordings()` обновить
- `modules/core/.../controller/StatusController.kt` — без изменений (структурный контракт меняется через response model)

**Telegram:**
- `modules/telegram/.../service/impl/StatusMessageFormatter.kt` — обновить `appendRecordings()`
- `modules/telegram/src/main/resources/messages_en.properties` — новые ключи
- `modules/telegram/src/main/resources/messages_ru.properties` — новые ключи

**Tests:**
- `modules/core/.../service/StatusServiceTest.kt` — новые сценарии (with errors / without)
- `modules/core/.../controller/StatusControllerTest.kt` — assertion на новые поля
- `modules/telegram/.../service/impl/StatusMessageFormatterTest.kt` — новый layout
- `modules/telegram/.../service/impl/StatusMessageFormatterI18nTest.kt` — новые i18n-ключи EN+RU

**Docs:**
- `docs/superpowers/specs/...-recordings-error-counts-design.md` (новый) — design этого follow-up'а
- `docs/superpowers/plans/...-recordings-error-counts.md` (новый) — task breakdown

## Связанные документы

- Design `/status`: `docs/superpowers/specs/2026-05-25-status-telegram-design.md` § Out of scope
- Plan `/status`: `docs/superpowers/plans/2026-05-25-status-command.md` § Task 13 (final review reference)
- Iter-1 review: `docs/superpowers/specs/2026-05-25-status-telegram-review-iter-1.md` § CONCERN-11
- Codex external review of feat/status-command: см. summary в continuation prompt для текущей сессии

## Suggested follow-up flow

1. Запустить `/brainstorming` на этой issue (использовать ответы выше как input).
2. Получить `docs/superpowers/specs/...-recordings-error-counts-design.md`.
3. Запустить `superpowers:writing-plans` на дизайне → получить plan.
4. (Опц.) ревью дизайна через `/external-code-review default` до имплементации.
5. Запустить `/do-plan` или `superpowers:subagent-driven-development`.
6. Финальный `/external-code-review default` на готовой ветке.
7. Merge в master.
