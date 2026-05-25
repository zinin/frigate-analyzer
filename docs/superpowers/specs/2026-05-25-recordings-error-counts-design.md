# `/status` recordings: явные счётчики success / errors

**Дата:** 2026-05-25
**Issue:** [#28 `/status recordings counts silently mask processing errors`](https://github.com/zinin/frigate-analyzer/issues/28)
**Источник:** external codex review ветки `feat/status-command` (Important, не блокер)
**Тип:** добавление функциональности + исправление непрозрачного контракта
**Связанные документы:**
- `docs/issues/2026-05-25-recordings-counts-mask-errors.md` — внутренняя постановка задачи
- `docs/superpowers/specs/2026-05-25-status-telegram-design.md` § Out of scope — исходное решение отложить
- `docs/superpowers/specs/2026-05-25-status-telegram-review-iter-1.md` § CONCERN-11 — атомарность снимка

## 1. Проблема

REST `GET /frigate-analyzer/status` и Telegram `/status` отображают три счётчика recordings: `total`, `processed`, `unprocessed`. Запись с ошибкой обработки молча попадает в `processed`, т.к. `RecordingEntityRepository.countProcessed()` фильтрует только по `process_timestamp IS NOT NULL` и не учитывает `error_message`.

При 100 recordings (90 успешных, 10 с ошибками детекции) `/status` показывает:

- `total: 100` ✓
- `processed: 100` ← скрывает 10 ошибок
- `unprocessed: 0` ✓

Реальная pipeline-health недоступна без прямого SQL.

## 2. Решение (краткое)

Additive curated подход:

- В `RecordingsStatistics` добавляются **`success: Long`** и **`errors: Long`**; поля `processed`/`unprocessed` сохраняются с прежней семантикой (backward-compat REST).
- Все 5 счётчиков считаются **одним SQL** с PostgreSQL `COUNT(*) FILTER (WHERE ...)` — атомарный снимок, 1 round-trip.
- В Telegram-`<pre>` Recordings строка `Processed` убирается; видны `Total / Success (%) / Errors (%) / Unprocessed / Rate`.
- Категоризация ошибок и retry-pending state — вне scope (отдельные issue в будущем).

## 3. Решения по open-questions issue

| Q | Решение | Обоснование |
|---|---|---|
| Q1 (счётчики) | (b) добавить `success` + `errors`, сохранить `processed`/`unprocessed` | Backward-compat. Открывает обе метрики: видимость ошибок и явный счёт успешных. Не фиксирует invariant `success+errors=processed` → оставляет место для Q3. |
| Q2 (категоризация) | Нет, только `errors: Long` | Issue сам рекомендует ("plain errors likely enough"). Категоризация требует миграции и backfill — отдельная история. |
| Q3 (retry-pending) | Не сейчас. Не делаем `success+errors=processed` инвариантом | `success` и `errors` считаются независимыми FILTER-агрегатами; добавление retry-pending state в будущем не сломает текущий контракт. Текущая модель «единый bucket `errors`» — намеренный временный компромисс до возможной декомпозиции через status-enum follow-up (см. § 12). |
| Q4 (Telegram layout) | C — 5 строк: Total / Success / Errors / Unprocessed / Rate | Убирает избыточную `Processed = Success + Errors`. Максимум сигнала, минимум строк. REST-ответ остаётся полным (5 счётчиков). |
| Q5 (i18n) | Удалить `label.processed`+`value.processed`; добавить `label.success`, `label.errors`, общий `value.withPct` | DRY: общий шаблон `{0} ({1}%)` для success/errors. Будущая дивергенция возможна через label-ключи. |
| Q6 (drop `unprocessed`) | Оставить | Поле осмысленное ("в очереди"), независимо от success/errors. Удаление было бы breaking change без выгоды. |
| Q7 (SQL стратегия) | Один `COUNT(*) FILTER (WHERE ...)` запрос | Решает производительность (1 round-trip вместо 7) и атомарность снимка (iter-1 CONCERN-11). |

## 4. Архитектура

Изменения по модулям:

| Module | Файлы | Что меняется |
|---|---|---|
| `model` | `dto/RecordingCountsDto.kt` (new) | SQL-projection DTO для 5 counts |
| `model` | `response/RecordingsStatistics.kt` | + `success: Long`, + `errors: Long` |
| `service` | `repository/RecordingEntityRepository.kt` | + `getRecordingCounts()`; удалить `countAll`/`countProcessed`/`countUnprocessed` |
| `core` | `service/StatusService.kt` | `buildRecordings()` использует один новый метод |
| `telegram` | `service/impl/StatusMessageFormatter.kt` | `appendRecordings()` под layout C |
| `telegram` | `resources/messages_en.properties`, `messages_ru.properties` | удалить 2 ключа, добавить 3 |
| тесты | `core/.../StatusServiceTest.kt`, `StatusControllerTest.kt`, `telegram/.../StatusMessageFormatterTest.kt` | обновить ассерты, добавить сценарии |

БД-схема **не меняется** — миграций нет.

## 5. Data layer

### 5.1 DTO

`modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column

data class RecordingCountsDto(
    @Column("total") val total: Long,
    @Column("processed") val processed: Long,
    @Column("unprocessed") val unprocessed: Long,
    @Column("success") val success: Long,
    @Column("errors") val errors: Long,
)
```

Паттерн совпадает с существующим `CameraStatisticsDto` (data class + `@Column` per field). Spring Data R2DBC выполняет column-to-property mapping. `@Column` сохранена для всех полей ради консистентности с существующим паттерном, хотя для полей, чьё имя совпадает с column-alias'ом, R2DBC мог бы использовать default property-to-column mapping. Defaults `= 0L` намеренно НЕ добавлены — иначе они замаскировали бы потенциальные ошибки маппинга.

### 5.2 Repository

В `RecordingEntityRepository.kt`:

```kotlin
@Query(
    """
    SELECT
        COUNT(*)                                                AS total,
        COUNT(*) FILTER (WHERE process_timestamp IS NOT NULL)   AS processed,
        COUNT(*) FILTER (WHERE process_timestamp IS NULL)       AS unprocessed,
        COUNT(*) FILTER (WHERE process_timestamp IS NOT NULL
                           AND error_message IS NULL)           AS success,
        COUNT(*) FILTER (WHERE error_message IS NOT NULL)       AS errors
    FROM recordings
    """,
)
suspend fun getRecordingCounts(): RecordingCountsDto
```

Удаляются как unused (after migration):

```kotlin
@Query("SELECT COUNT(*) FROM recordings")
suspend fun countAll(): Long

@Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NOT NULL")
suspend fun countProcessed(): Long

@Query("SELECT COUNT(*) FROM recordings WHERE process_timestamp IS NULL")
suspend fun countUnprocessed(): Long
```

**Pre-commit check:** `grep -r "countAll\|countProcessed\|countUnprocessed"` по модулям, чтобы убедиться, что других потребителей нет.

### 5.3 SQL properties

- `COUNT(*) FILTER (WHERE ...)` — SQL standard since PostgreSQL 9.4; поддерживается всеми актуальными PG-версиями.
- Aliases (`total`, `processed`, `unprocessed`, `success`, `errors`) не пересекаются с reserved words в PG.
- На текущем объёме (~10⁴ строк) один table scan укладывается в миллисекунды; нового индекса не требуется. При росте до ~10⁵+ строк стоит рассмотреть partial-index `CREATE INDEX ... ON recordings(error_message) WHERE error_message IS NOT NULL` для ускорения `errors`-фильтра (см. §11 — future consideration). Существующий `idx_recordings_process_timestamp` (`docker/liquibase/migration/1.0.0.xml`) не помогает single-FILTER-aggregate сценарию (он построен для range-сканов rate-запроса).
- Все 5 чисел из одного снапшота → consistent view (закрывает iter-1 CONCERN-11 для recordings counts; `byCameras` и `processingRatePerMinute` остаются независимыми запросами, что приемлемо — иная shape агрегации).

## 6. Response model

`modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt`:

```kotlin
data class RecordingsStatistics(
    val total: Long,
    val processed: Long,
    val unprocessed: Long,
    val success: Long,           // NEW
    val errors: Long,            // NEW
    val byCameras: List<CameraStatistics>,
    val processingRatePerMinute: Double,
)
```

Порядок: новые поля размещены сразу после `unprocessed`, до тяжеловесных `byCameras`/`processingRatePerMinute`. JSON-prefix `total/processed/unprocessed` сохраняется → старые консьюмеры не ломаются.

**Свойства контракта (REST):**

- `total = processed + unprocessed` (всегда — по определению `process_timestamp NULL` vs `NOT NULL`).
- `processed = success + errors` (**сегодня** — т.к. `markProcessedWithError` всегда соседом ставит `process_timestamp` и `error_message`; всякая ошибка попадает в `processed`). Будущая state-machine для retry-pending может нарушить это равенство в любую сторону (например, error без `process_timestamp`) — в коде **намеренно нет ассерта** этого инварианта; счётчики `success`/`errors` независимы.
- `errors` — `error_message IS NOT NULL` (terminal или будущий retry-pending — без различения).

## 7. StatusService

`modules/core/.../service/StatusService.kt`, `buildRecordings()`:

```kotlin
private suspend fun buildRecordings(): RecordingsStatistics {
    val counts = recordingRepository.getRecordingCounts()
    val byCameras = recordingRepository.getStatisticsByCameras().map { dto ->
        CameraStatistics(
            camId = dto.camId,
            recordingsCount = dto.recordingsCount,
            recordingsProcessed = dto.recordingsProcessed,
            detectionsCount = dto.detectionsCount,
        )
    }
    val rate = recordingRepository.getProcessingRatePerMinuteLast5Minutes()
    return RecordingsStatistics(
        total = counts.total,
        processed = counts.processed,
        unprocessed = counts.unprocessed,
        success = counts.success,
        errors = counts.errors,
        byCameras = byCameras,
        processingRatePerMinute = rate,
    )
}
```

Было 5 SQL-запросов; станет **3** (combined counts + byCameras + rate). Сигнатура метода и его caller (`collect()`) не меняются.

## 8. Telegram layout

### 8.1 Визуал (5 строк)

```
📹 Recordings
Total                 12450
Success       12370 (99.3%)
Errors            10 (0.1%)
Unprocessed              70
Rate (5 min)    2.3 rec/min
```

Ширина каждой строки 27 chars (`padEnd(13) + " " + padStart(13)`); `labelWidth=12` (от "Rate (5 min)"), `valueWidth=13` (от "12370 (99.3%)"). Числовые значения 12/13/27 — иллюстративные для EN; реальная ширина вычисляется динамически (`rows.maxOf { it.first.length }` / `rows.maxOf { it.second.length }`). Для RU max-label = «Скорость (5 мин)» = 15 символов, поэтому RU-блок будет ~30 chars шириной — обе локали помещаются в портретный mobile-viewport.

- Числа без thousand-separator (`Long.toString()`) — сохраняется текущее поведение.
- Выравнивание — существующим алгоритмом `padEnd(maxLabel+1) + " " + padStart(maxValue)`; новые строки автоматически учитываются.
- Самая длинная строка ~27 chars EN / ~30 chars RU — помещается в портретный mobile-viewport.

### 8.2 Конвенция процентов

- `Success`: `(success / total) * 100`, formatted `%.1f`.
- `Errors`: `(errors / total) * 100`, formatted `%.1f`.
- При `total == 0` — обе строки показывают `"0.0"` (защита от div-by-zero, аналогично текущему guard'у `processed`).
- Строка `Errors` показывается **всегда**, в т.ч. при `errors == 0` (`Errors: 0 (0.0%)`), как операционный сигнал.

### 8.3 Реализация

Helper для процента — рядом с `appendRecordings()`:

```kotlin
private fun pct(part: Long, total: Long): String =
    if (total > 0) "%.1f".format(Locale.ROOT, part.toDouble() * 100.0 / total.toDouble()) else "0.0"
```

`appendRecordings()` переписывается на 5-строковый layout (см. секцию 4 design-обсуждения; код будет в plan).

### 8.4 i18n

**Удалить** (после layout C `Processed` row не используется):

- `status.recordings.label.processed`
- `status.recordings.value.processed`

**Добавить:**

| Ключ | EN | RU |
|---|---|---|
| `status.recordings.label.success` | `Success` | `Успешно` |
| `status.recordings.label.errors` | `Errors` | `Ошибки` |
| `status.recordings.value.withPct` | `{0} ({1}%)` | `{0} ({1}%)` |

`value.withPct` — общий шаблон для обеих строк, DRY (паттерн идентичен). Future-divergence (например, `⚠` префикс) достижима через label-ключи.

## 9. Тесты

Не создаём новых тест-классов; обновляем существующие.

### 9.1 `StatusServiceTest.kt`

- Заменить моки `countAll/countProcessed/countUnprocessed` на:
  ```kotlin
  coEvery { getRecordingCounts() } returns
      RecordingCountsDto(total = 100, processed = 95, unprocessed = 5, success = 85, errors = 10)
  ```
- Расширить `collect populates recordings counters and rate` ассертами по `success` и `errors`.
- Добавить новый сценарий: пустая БД (`RecordingCountsDto(0, 0, 0, 0, 0)`) → все поля 0, rate=0.0.

### 9.2 `StatusControllerTest.kt`

- В JSON-ассертах добавить `$.recordings.success` и `$.recordings.errors`.

### 9.3 `StatusMessageFormatterTest.kt`

В файле два тест-класса: `StatusMessageFormatterTest` (mock-based, основной) и `StatusMessageFormatterI18nTest` (real ResourceBundle, snapshot-нити).

- В `StatusMessageFormatterTest`:
  - `snapshot()` helper и inline-конструкторы `RecordingsStatistics(...)` дополнить новыми required-полями `success`, `errors` (compile-fix).
  - Добавить тест layout C: ожидаемый `<pre>`-блок содержит 5 строк (Total / Success / Errors / Unprocessed / Rate).
  - Добавить кейс `errors=0`: строка `Errors: 0 (0.0%)` присутствует.
  - Добавить кейс `total=0`: обе % строки → `0.0`.
  - Опционально: кейс `success + errors != processed` (имитация будущего retry-pending) — формат не падает.
- В `StatusMessageFormatterI18nTest`:
  - `sampleSnapshot()` обновить (compile-fix).
  - Существующие EN/RU тесты на cameras и servers не трогать (не зависят от recordings counts).

### 9.4 `RecordingEntityRepositoryTest.kt` (доп.)

Integration-тест против реальной PostgreSQL (через `IntegrationTestBase`). Сейчас содержит три теста `should count {all,processed,unprocessed} recordings` (lines ~437-498), вызывающих удаляемые методы. После удаления этих методов:

- Тесты `should count all/processed/unprocessed recordings` удалить.
- Добавить один тест `should return recording counts via FILTER aggregate` (создаёт 4 recordings: один без `process_timestamp` → unprocessed; один с `process_timestamp` без `error_message` → success; один с `process_timestamp` + `error_message` → errors; один без `process_timestamp` для baseline) → ассерт `RecordingCountsDto(total=4, processed=2, unprocessed=2, success=1, errors=1)`.

Это даёт реальное покрытие SQL FILTER aggregate — паттерн совпадает с существующими integration-тестами repository.

## 10. Backward compatibility

| Слой | Compat |
|---|---|
| REST JSON | Additive: добавлены 2 поля, существующие неизменны. Старые non-strict consumers не ломаются. Известных external generated-DTO consumers (через Swagger/OpenAPI client codegen) у проекта нет — `/status` рассматривается как operator-facing endpoint; если такой consumer появится позже, его строгая модель (`failOnUnknownProperties`) может потребовать regenerate. |
| Telegram | OWNER-only, нет внешнего API. Внутренний формат сообщения изменён (убрана строка `Processed`). |
| DB schema | Без изменений — нулевой риск. |

## 11. Риски и mitigations

| Риск | Митигация |
|---|---|
| Удалённые i18n-ключи всё ещё где-то ссылаются | `grep "status.recordings.label.processed\|status.recordings.value.processed"` по `modules/**/src/**` перед коммитом. Если найдены вне формат-теста — keep keys. |
| R2DBC column mapping для нового DTO | Паттерн совпадает с `CameraStatisticsDto`; risk низкий. Реальная гарантия маппинга обеспечивается **integration-тестом** `should return recording counts via FILTER aggregate` в `RecordingEntityRepositoryTest` против реального PG (через `IntegrationTestBase`/Testcontainers). Mock-тест в `StatusServiceTest` покрывает только wiring, но не сам маппинг. |
| FILTER aggregate не поддерживается | PG 9.4+. Проект уже на современной версии (Liquibase migrations подтверждают). |
| Будущий retry → `processed != success + errors` | Допустимо в любую сторону. В коде нет ассерта инварианта (см. секцию 6). |
| Документация рассинхронизация | `.claude/rules/database.md` не меняется (схема та же). Internal docs о пайплайне не затрагиваются. |
| `markProcessed()` не очищает `error_message` | Сегодня нерелевантно, т.к. `markProcessed` вызывается только для свежих recordings без `error_message`. При появлении retry/manual-repair в будущем corresponding transition обязан очищать `error_message` (или вводить status-enum). Зафиксировано как future consideration, отдельный issue (см. § 12). |
| `error_message` не индексирован | На ~10⁴ строк seq-scan FILTER-aggregate миллисекунды. На ~10⁵+ строк стоит рассмотреть partial-index `CREATE INDEX ... ON recordings(error_message) WHERE error_message IS NOT NULL`. Не добавляется сейчас (дизайн без миграций), но зафиксировано как future consideration. |
| i18n missing-key регрессия после удаления `label.processed`/`value.processed` | `MessageResolver.get()` при отсутствии ключа во всех locales возвращает сам ключ (с warn-логом, без throw — `MessageResolver.kt:25-35`). Безопасный fallback — пользователь увидит `status.recordings.label.processed` как plain text, но приложение не упадёт. Дополнительно: real-bundle `StatusMessageFormatterI18nTest` ассертит присутствие новых ключей в EN/RU bundles (см. § 9.3). |

## 12. Out of scope

- Категоризация ошибок (`error_type` enum, breakdown в `/status`) — отдельный issue.
- Retry-pending state — отдельный issue, когда появится автоматический retry в pipeline.
- Полноценный status/state enum column на `recordings` (заменяющий нынешний неявный state-флаг через `error_message IS NOT NULL`) — отдельный follow-up issue. Считается долгосрочным правильным направлением, если retry-pending станет реальным design driver'ом.
- Per-camera `errors` колонка в `byCameras`-секции — отдельный issue. Текущее `recordingsProcessed` в `CameraStatistics` сохраняет масковую семантику (включает успешные + ошибочные) — рассматривается как known limitation; явный rename/расширение `proc`-колонки в Telegram-byCamera-таблице отложены.
- Любые БД-миграции.
- Любая работа с `error_message` text (truncation, parsing, etc.).

## 13. Affected files checklist

```
modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt        (new)
modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt (+2 fields)
modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt (+ getRecordingCounts; − countAll/countProcessed/countUnprocessed)
modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt           (buildRecordings rewrite)
modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt (appendRecordings rewrite + pct helper)
modules/telegram/src/main/resources/messages_en.properties                                     (−2 keys, +3 keys)
modules/telegram/src/main/resources/messages_ru.properties                                     (−2 keys, +3 keys)
modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt       (update mock + scenarios)
modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt (assert new fields)
modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTestConfig.kt (compile-fix: + success/errors)
modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt (replace 3 count tests with 1 FILTER-aggregate test)
modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt (compile-fix + layout C + edge cases; same file contains StatusMessageFormatterI18nTest)
```

## 14. Suggested execution flow

1. `superpowers:writing-plans` на этот документ → получить task-breakdown.
2. (Опц.) `/external-code-review default` на дизайн до имплементации.
3. `superpowers:subagent-driven-development` или `/do-plan` для выполнения.
4. Pre-PR: `git rm` из `docs/superpowers/`, отдельный commit.
5. Финальный `/external-code-review default` на готовой ветке.
6. PR → review → merge.
