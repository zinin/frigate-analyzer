# Review Iteration 1 — 2026-02-18 12:05

## Источник

- Design: `docs/plans/2026-02-18-timezone-support-design.md`
- Plan: `docs/plans/2026-02-18-timezone-support-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-18-timezone-support-review-merged-iter-1.md`

## Замечания

### [A] Date "Сегодня/Вчера" uses server clock, not user timezone

> `LocalDate.now(clock)` в `FrigateAnalyzerBot.kt:474` использует серверный clock вместо userZone. Пользователи в другом часовом поясе получают неправильный день.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Critical
**Ответ:** Исправить. Использовать `Instant.now(clock).atZone(userZone).toLocalDate()`.
**Действие:** Исправить в коде.

---

### [B] ZoneId.of() catches only ZoneRulesException

> В `handleTimezone` (`FrigateAnalyzerBot.kt:411`) ловится только `ZoneRulesException`, но `ZoneId.of()` может бросить `DateTimeException` для невалидного формата ввода.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Исправить. Ловить `DateTimeException` (родительский класс).
**Действие:** Заменить `catch (e: ZoneRulesException)` на `catch (e: DateTimeException)`.

---

### [C] Invalid olson_code in DB crashes service methods

> `getUserZone` и `getAuthorizedUsersWithZones` в `TelegramUserServiceImpl.kt` вызывают `ZoneId.of()` без try/catch. `updateTimezone` не валидирует input. Одно битое значение в БД может сорвать рассылку всем.

**Источник:** codex-executor, ccs-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Исправить. Добавить валидацию в `updateTimezone` и try/catch с fallback на UTC в `getUserZone`/`getAuthorizedUsersWithZones`.
**Действие:** Исправить в коде.

---

### [D] No tests for TZ logic

> В плане нет unit-тестов для конвертации времени и форматирования уведомлений.

**Источник:** codex-executor, gemini-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Добавить тесты.
**Действие:** Создать unit-тесты для TZ-конвертации и сервисных методов.

---

### [E] No timeout for /timezone waiter

> Нет `withTimeoutOrNull` для `/timezone` waiter.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Пропустить — осознанное решение из session context. Для 1-2 пользователей приемлемо.
**Действие:** Нет.

---

### [F] INTERVAL '10 seconds' not documented

> `INTERVAL '10 seconds'` в SQL-запросах не задокументирован. Pre-existing логика из старых запросов.

**Источник:** codex-executor, gemini-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Добавить комментарий в SQL-запрос объясняющий зачем 10 секунд.
**Действие:** Добавить SQL-комментарий в RecordingEntityRepository.

---

### [G] updateTimezone doesn't check affected rows

> `updateTimezone` не проверяет return value — успех логируется даже если запись не обновлена.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Добавить проверку affected rows и warn при 0.
**Действие:** Исправить в коде.

---

### [H] Fixed offset ZoneIds (GMT+3) accepted

> `ZoneId.of()` принимает фиксированные offset-ы (GMT+3, UTC+03:00), которые теряют DST.

**Источник:** gemini-executor, ccs-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Блокировать offset-based зоны. Принимать только корректные Olson ID (содержащие `/`).
**Действие:** Добавить валидацию в handleTimezone и updateTimezone.

---

### [I] Pair<Long, ZoneId> code smell

> `getAuthorizedUsersWithZones` возвращает `List<Pair<Long, ZoneId>>` — невыразительная сигнатура.

**Источник:** gemini-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Создать data class `UserZoneInfo`.
**Действие:** Создать DTO и обновить сигнатуру.

---

### [J] Hardcoded 8 CIS timezone list

> Жёстко заданный список ограничивает пользователей.

**Источник:** codex-executor, ccs-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Пропустить — осознанное дизайн-решение + ручной ввод.
**Действие:** Нет.

---

### [K] Export filename depends on user timezone

> Имя файла экспорта зависит от timezone пользователя.

**Источник:** ccs-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Пропустить — файл отправляется пользователю, не хранится на сервере.
**Действие:** Нет.

---

### [L] No logging of previous timezone value

> При изменении timezone логируется только новое значение.

**Источник:** ccs-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Пропустить — nice to have, не критично.
**Действие:** Нет.

---

### [M-P] Over-engineering / Design decisions

> M: Отдельный TimezoneService, N: NOT NULL DEFAULT 'UTC', O: N+1 notifications, P: Russian locale

**Источник:** codex-executor, gemini-executor, ccs-executor
**Статус:** Новое
**Уровень:** Suggestion
**Ответ:** Пропустить — ложные срабатывания / уже принятые решения.
**Действие:** Нет.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/plans/2026-02-18-timezone-support-design.md` | [A] Data Flow: date buttons use userZone |
| `docs/plans/2026-02-18-timezone-support-design.md` | [B] Manual input: catch `DateTimeException` instead of `ZoneRulesException` |
| `docs/plans/2026-02-18-timezone-support-design.md` | [C] Error handling: fallback to UTC for invalid olson_code in DB |
| `docs/plans/2026-02-18-timezone-support-design.md` | [F] SQL: added comment explaining 10-second buffer |
| `docs/plans/2026-02-18-timezone-support-design.md` | [H] Validation: reject offset-based zone IDs (no `/`) |
| `docs/plans/2026-02-18-timezone-support-design.md` | [I] Changed `List<Pair<Long, ZoneId>>` → `List<UserZoneInfo>` |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [A] Task 7: `Instant.now(clock).atZone(userZone).toLocalDate()` for date buttons |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [B] Task 7: `catch (e: DateTimeException)` in handleTimezone |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [C] Task 4: validation + try/catch fallback in service methods |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [F] Task 5: SQL comments for INTERVAL '10 seconds' |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [G] Task 4: affected rows check in updateTimezone |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [H] Task 4+7: reject offset-based zones, require `/` in Olson ID |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [I] Task 4+8: `UserZoneInfo` DTO replaces `Pair<Long, ZoneId>` |

## Статистика

- Всего замечаний: 16
- Новых: 16
- Повторов (автоответ): 0
- К исправлению: 8 (A, B, C, D, F, G, H, I)
- Пропущено: 8 (E, J, K, L, M, N, O, P)
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
