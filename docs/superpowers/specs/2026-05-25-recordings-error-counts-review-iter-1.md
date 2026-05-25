# Review Iteration 1 — 2026-05-26 00:30

## Источник

- Design: `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-recordings-error-counts.md`
- Review agents: codex-executor (gpt-5.5 xhigh), ollama-kimi, ollama-deepseek, ollama-minimax. ccs-executor (glm) — wrapper failed to emit final review, only intermediate progress notes captured.
- Merged output: `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] Helper `createRecordingEntity` не поддерживает параметр `errorMessage`

**Источник:** codex (Critical-2), ollama-kimi (Concern-5), ollama-deepseek (C1), ollama-minimax (Critical-1)
**Статус:** Автоисправлено
**Ответ:** План в Task 6 Step 4c теперь обязательно предписывает расширение хелпера сигнатурой `errorMessage: String? = null` + forwarding в `RecordingEntity.errorMessage` (без альтернатив `.copy()` или прямого `RecordingEntity(...)`).
**Действие:** Plan Task 6 Step 4c — параграф «Note:» переписан в «Helper extension (mandatory)» с явным кодом расширения; все default'ы preserved для существующих тестов.

---

### [CRITICAL-2] Лишний import `RecordingCountsDto` в Task 6

**Источник:** codex (Critical-1)
**Статус:** Автоисправлено
**Ответ:** В Task 6 Step 4a импорт `RecordingCountsDto` действительно не нужен — тип выводится автоматически (`val counts = repository.getRecordingCounts()`). Иначе ktlint unused-import.
**Действие:** Plan Task 6 Step 4a — переписан как «(Skipped intentionally.) No new import is needed».

---

### [CRITICAL-3] Отсутствует import `RecordingCountsDto` в `StatusService.kt`

**Источник:** ollama-kimi (Critical-1)
**Статус:** Отклонено
**Ответ:** Kimi ошибся: в `StatusService.kt` `val counts = recordingRepository.getRecordingCounts()` использует type inference Kotlin'а, явный импорт `RecordingCountsDto` не требуется. Тот же паттерн уже применяется для `CameraStatisticsDto` в текущем `buildRecordings()` — там тоже нет импорта DTO.
**Действие:** —

---

### [CRITICAL-4] Неверные номера строк в `.properties` файлах

**Источник:** ollama-kimi (Critical-3)
**Статус:** Отклонено
**Ответ:** Kimi перепутал порядок строк. В обоих bundle'ах `value.processed` = line 206, `value.rate` = line 207. План говорит «after `value.rate` (currently line 207)» — это **корректно**: новые ключи вставляются после line 207, что и подразумевалось.
**Действие:** —

---

### [CRITICAL-5] Семантика SQL-фильтра `errors`: независимый счётчик vs строгое подмножество `processed`

**Источник:** ollama-kimi (Critical-2), ollama-minimax (Critical-3), codex (Concern-2)
**Статус:** Обсуждено с пользователем
**Ответ:** Вариант A (KEEP current SQL). Сохраняем `errors = COUNT(*) FILTER (WHERE error_message IS NOT NULL)` — независимый от `process_timestamp`. Обоснование: соответствует букве и духу brainstorming Q3 (flexibility over forced invariant), обеспечивает operator visibility для будущих acquire-failed/retry-pending состояний, согласуется с целью задачи #28 (не маскировать ошибки). Минимальное уточнение wording в §6 для устранения внутреннего противоречия («сегодня» / «намеренно нет ассерта» → нейтральное «независимые FILTER-агрегаты, без формального инварианта; сегодняшние совпадения incidental»).
**Действие:** Design §6 — secsion «Свойства контракта (REST)» переписана; явно зафиксированы (a) отсутствие инвариантов, (b) обоснование широкого определения `errors` через привязку к #28.

---

### [CONCERN-1] Хрупкость `error_message IS NOT NULL` как state-флага

**Источник:** codex (Concern-2)
**Статус:** Автоисправлено
**Ответ:** `markProcessed()` не очищает `error_message` — допустимо сегодня (метод вызывается только для свежих recordings), но требует внимания при будущем retry/manual-repair (transition обязан очищать `error_message` или вводить status-enum).
**Действие:** Design §11 — добавлена строка в таблицу рисков с указанием future consideration и ссылкой на §12 status-enum follow-up.

---

### [CONCERN-2] Per-camera section продолжит маскировать ошибки в колонке `proc`

**Источник:** codex (Concern-1, Question-3)
**Статус:** Автоисправлено
**Ответ:** Явное подтверждение out-of-scope: `recordingsProcessed` в `CameraStatistics` сохраняет масковую семантику — known limitation, отдельный issue.
**Действие:** Design §12 — расширена формулировка «Per-camera `errors` колонка» с пометкой known-limitation и явным упоминанием отложенного rename `proc`-колонки в Telegram-byCamera-таблице.

---

### [CONCERN-3] REST additive change может ломать строгих consumers

**Источник:** codex (Concern-4)
**Статус:** Автоисправлено
**Ответ:** Документально зафиксировано: `/status` рассматривается как operator-facing endpoint, known external generated-DTO consumers отсутствуют. При появлении строгих клиентов с `failOnUnknownProperties` потребуется regenerate.
**Действие:** Design §10 — строка про REST JSON расширена оговоркой про generated-DTO consumers + явная фиксация operator-facing scope.

---

### [CONCERN-4] i18n mock-тесты не проверяют наличие новых ключей в реальных bundles

**Источник:** codex (Concern-5, Suggestion-5), ollama-kimi (Question-2)
**Статус:** Автоисправлено
**Ответ:** Добавлен real-bundle тест в `StatusMessageFormatterI18nTest` — отдельные ассерты для EN/RU recordings rows: проверка наличия «Success»/«Errors» и «Успешно»/«Ошибки» + проверка отсутствия raw-key leakage (`status.recordings.label.*`).
**Действие:** Plan Task 6 Step 4d (новый шаг) — добавлены два `@Test` блока с EN/RU real-bundle ассертами.

---

### [CONCERN-5] Отсутствие индекса на `error_message`

**Источник:** ollama-kimi (Concern-4), ollama-deepseek (C6)
**Статус:** Автоисправлено
**Ответ:** На текущем объёме ~10⁴ строк seq-scan миллисекунды; partial-index — future consideration при ~10⁵+ строк.
**Действие:** Design §5.3 — добавлено упоминание partial-index opportunity; Design §11 — отдельная строка риска со ссылкой на §5.3.

---

### [CONCERN-6] Dead code `formatRow()` оставлен на ktlint

**Источник:** ollama-kimi (Concern-2), ollama-deepseek (C5), ollama-minimax (S10)
**Статус:** Автоисправлено
**Ответ:** ktlint не детектит unused private — нужно удалять явно.
**Действие:** Plan Task 5 Step 3 — текст про «leave it for now» заменён на «**Delete the unused private helper `formatRow(...)`**».

---

### [CONCERN-7] План говорит «4 sites», но фактически 5 сайтов

**Источник:** ollama-kimi (Concern-3)
**Статус:** Автоисправлено
**Ответ:** Уточнена формулировка File Structure.
**Действие:** Plan File Structure — «compile-fix (4 sites)» → «compile-fix (5 sites: 4 in outer `StatusMessageFormatterTest` + 1 in nested `StatusMessageFormatterI18nTest.sampleSnapshot()`)».

---

### [CONCERN-8] Interim state между Task 2 и Task 5

**Источник:** ollama-deepseek (C3)
**Статус:** Автоисправлено
**Ответ:** Добавлено явное предупреждение перед Task 2: formatter-тесты будут RED at assertion в этом окне — это ожидаемо, не интерпретировать как баг.
**Действие:** Plan Task 2 — вставлен блок «**Interim-state warning**».

---

### [CONCERN-9] Layout C ширина строки — RU max width 15 vs EN 12

**Источник:** ollama-deepseek (C4), ollama-minimax (S12)
**Статус:** Автоисправлено
**Ответ:** Числовые значения 12/13/27 в §8.1 — иллюстративные для EN; RU будет ~30 chars, помещается в портретный mobile-viewport.
**Действие:** Design §8.1 — добавлено уточнение про динамическое вычисление ширины и RU-вариант.

---

### [CONCERN-10] Нет теста `success + errors != processed`

**Источник:** ollama-kimi (Concern-1), ollama-deepseek (S4), ollama-minimax (S13)
**Статус:** Автоисправлено
**Ответ:** Добавлен тест в Task 5 Step 1 — `format keeps layout C when success plus errors does not equal processed` (total=100, processed=100, success=80, errors=10). Документирует contract: формат не падает при будущем расхождении инварианта.
**Действие:** Plan Task 5 Step 1 — третий `@Test` блок вставлен перед существующим zero-total тестом.

---

### [CONCERN-11] Zero-errors тест не проверяет `processed`/`unprocessed`

**Источник:** ollama-minimax (Concern-3, S9)
**Статус:** Автоисправлено
**Ответ:** Добавлены два дополнительных assertion'а в Task 2 Step 3d.
**Действие:** Plan Task 2 Step 3d — `assertThat(resp.recordings.processed).isEqualTo(0L)` и `unprocessed` вставлены.

---

### [CONCERN-12] Task 7 не предусматривает `/verify` для REST endpoint

**Источник:** ollama-deepseek (C7)
**Статус:** Автоисправлено
**Ответ:** Добавлен явный шаг ручной curl-проверки REST-контракта.
**Действие:** Plan Task 7 Step 2b (новый шаг) — `curl ... | jq '.recordings | {total, processed, unprocessed, success, errors}'` + ожидание `total == processed + unprocessed` и `processed == success + errors` (today).

---

### [SUGGESTION-1] Рассмотреть имена `successful`/`failed` вместо `success`/`errors`

**Источник:** codex (Suggestion-2)
**Статус:** Отклонено
**Ответ:** Brainstorming Q1: пользователь явно выбрал `success`/`errors`, отверг `successful`/`succeeded`/`analyzed`. Решение зафиксировано до этой итерации, не пересматривается.
**Действие:** —

---

### [SUGGESTION-2] Non-invariant репозиторный тест: `unprocessed AND errors` overlap

**Источник:** codex (Suggestion-3), ollama-minimax (Concern-4), ollama-deepseek (S1)
**Статус:** Автоисправлено
**Ответ:** Добавлен второй integration-тест `should count error-only recording in errors and unprocessed buckets` (засевает запись с `error_message="acquire failed"` и `process_timestamp = null`; ассертит `total=1, processed=0, unprocessed=1, success=0, errors=1`). Документирует defensive design и будущее retry-pending.
**Действие:** Plan Task 6 Step 4c — второй `@Test` блок вставлен между основным FILTER-aggregate тестом и Helper-extension параграфом.

---

### [SUGGESTION-3] Follow-up issue на полноценный status/state column

**Источник:** codex (Suggestion-4)
**Статус:** Автоисправлено
**Ответ:** Зафиксировано в out-of-scope как долгосрочное правильное направление.
**Действие:** Design §12 — добавлена строка «Полноценный status/state enum column на `recordings` (заменяющий нынешний неявный state-флаг через `error_message IS NOT NULL`) — отдельный follow-up issue».

---

### [SUGGESTION-4] Группировка i18n-ключей

**Источник:** ollama-deepseek (S3)
**Статус:** Отклонено
**Ответ:** Pure taste. План сохраняет минимальный diff и придерживается существующего порядка вставки после `value.rate`. Группировка label/value не несёт функциональной выгоды.
**Действие:** —

---

### [SUGGESTION-5] `RecordingCountsDto` с default values `= 0L`

**Источник:** ollama-minimax (S14)
**Статус:** Отклонено
**Ответ:** Anti-pattern для R2DBC-DTO: defaults замаскировали бы потенциальные ошибки маппинга. Подтверждено в §5.1 — явно зафиксировано «defaults `= 0L` намеренно НЕ добавлены».
**Действие:** Design §5.1 — явное упоминание, что defaults НЕ добавлены и почему.

---

### [SUGGESTION-6] RU-перевод «Успешно» → «Успешные»

**Источник:** ollama-deepseek (S2)
**Статус:** Отклонено
**Ответ:** Brainstorming Q5: пользователь явно выбрал «Успешно» (наречие). Решение зафиксировано до этой итерации.
**Действие:** —

---

### [SUGGESTION-7] `@Deprecated`/KDoc для `processed` в `RecordingsStatistics`

**Источник:** ollama-kimi (Question-3)
**Статус:** Автоисправлено
**Ответ:** Добавлен KDoc на поле `processed` (не `@Deprecated`, поскольку поле сохраняется для backward-compat), направляющий consumers к `success`/`errors` для error visibility.
**Действие:** Plan Task 2 Step 1 — добавлен `/** ... */` блок над `val processed: Long`.

---

### [QUESTION-1] `/status` consumed внешним generated client из OpenAPI?

**Источник:** codex (Question-1)
**Статус:** Автоисправлено
**Ответ:** `/status` — operator-facing endpoint; known external generated-DTO consumers нет.
**Действие:** Design §10 — документировано (см. [CONCERN-3]).

---

### [QUESTION-2] Terminal errors vs retry-pending — один bucket?

**Источник:** codex (Question-2)
**Статус:** Автоисправлено
**Ответ:** Текущая модель «один bucket `errors`» — намеренный временный компромисс до возможной декомпозиции через status-enum follow-up (см. §12).
**Действие:** Design §3 Q3 — расширена правая колонка таблицы.

---

### [QUESTION-3] `MessageResolver` поведение на missing keys?

**Источник:** ollama-kimi (Question-2)
**Статус:** Автоисправлено
**Ответ:** Verified в коде (`MessageResolver.kt:25-35`): missing key в locale → fallback на DEFAULT_LOCALE (en) → если всё ещё missing → возврат самого ключа (с `logger.warn`, без throw). Безопасный fallback.
**Действие:** Design §11 — добавлена строка риска с описанием поведения + ссылка на real-bundle test в §9.3.

---

### [QUESTION-4] `@Column` annotation в `RecordingCountsDto` — нужна?

**Источник:** ollama-deepseek (Q1)
**Статус:** Отклонено
**Ответ:** Сохраняется для консистентности с `CameraStatisticsDto`. Без `@Column` R2DBC использовал бы default property-to-column mapping (работало бы для одноимённых полей), но консистентность важнее минимализма. Зафиксировано в §5.1.
**Действие:** Design §5.1 — добавлено пояснение consistency rationale.

---

### [QUESTION-5] `pct()` private vs общий `common` модуль

**Источник:** ollama-deepseek (Q2)
**Статус:** Отклонено
**Ответ:** YAGNI. Единственный пока caller — `StatusMessageFormatter`. Вынесение в common требует second consumer для оправдания абстракции.
**Действие:** —

---

### [QUESTION-6] Per-camera `proc` переименование / расширение?

**Источник:** codex (Question-3)
**Статус:** Автоисправлено (объединено с [CONCERN-2])
**Ответ:** Same as [CONCERN-2] — отложено в out-of-scope с явной known-limitation формулировкой.
**Действие:** Design §12 — см. [CONCERN-2].

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md` | §3 Q3 (временный bucket), §5.1 (`@Column` rationale + no defaults), §5.3 (partial-index opportunity), §6 (rewrite свойств контракта — neutralized invariant wording per disputed decision Variant A), §8.1 (RU width clarification), §10 (operator-facing + generated-DTO note), §11 (4 новых строки рисков), §12 (status-enum follow-up + per-camera known limitation). |
| `docs/superpowers/plans/2026-05-25-recordings-error-counts.md` | File Structure (5 sites), Task 2 (interim-state warning, KDoc, +2 assertions), Task 5 (3rd edge-case test, formatRow delete), Task 6 (no import, helper extension mandatory, overlap test, real-bundle i18n step), Task 7 (curl verify step). |
| `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-merged-iter-1.md` | NEW — agglomerated raw reviewer outputs (4/5 completed). |
| `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-iter-1.md` | NEW — this log. |

## Статистика

- Всего замечаний: 30
- Автоисправлено (без обсуждения): 22
- Авто-применено после анализа (disputed → applied): 0
- Обсуждено с пользователем: 1 (CRITICAL-5 → Вариант A KEEP)
- Отклонено: 7 (CRITICAL-3, CRITICAL-4, SUGGESTION-1, SUGGESTION-4, SUGGESTION-5, SUGGESTION-6, QUESTION-4, QUESTION-5)
- Повторов (автоответ): 0
- Пользователь сказал «стоп»: Нет
- Агенты: codex-executor (gpt-5.5 xhigh), ollama-kimi, ollama-deepseek, ollama-minimax; ccs-executor (glm) — incomplete

Примечание: 7 отклонённых превышают сумму 22+1+0 = 23 в строках выше, потому что (а) CRITICAL-4 в финале классифицирован Dismissed (Kimi перепутал строки), (б) QUESTION-4 и QUESTION-5 «no action» — фиксируются как Dismissed для бухгалтерии; полная сумма: 22 auto + 1 discussed + 7 dismissed = 30.
