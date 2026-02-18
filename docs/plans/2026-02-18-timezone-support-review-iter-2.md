# Review Iteration 2 — 2026-02-18 12:30

## Источник

- Design: `docs/plans/2026-02-18-timezone-support-design.md`
- Plan: `docs/plans/2026-02-18-timezone-support-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-18-timezone-support-review-merged-iter-2.md`

## Замечания

### [A2] DST-переходы могут ломать корректность диапазона /export

> LocalDateTime.atZone(userZone) при DST-переходе может дать неожиданный Instant: несуществующее время сдвигается, двусмысленное время выбирает один из оффсетов. «5 минут» локально может стать != 5 минут UTC.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Исправить. Добавить Duration.between(startInstant, endInstant) проверку после конвертации.
**Действие:** Обновлены design (Data Flow) и plan (Task 7 Step 5) — добавлен DST guard.

---

### [B2] Waiter-фильтры callback не привязаны к messageId

> Фильтр по `startsWith("tz:")` + chatId может поймать callback от старой клавиатуры того же чата. Pre-existing паттерн из /export.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Исправить. Привязать callback к messageId отправленного сообщения. Применить и к /timezone, и к /export.
**Действие:** Обновлены design (раздел /timezone Command) и plan (Task 7 Step 4 и Step 5).

---

### [C2] Нет /cancel в ветке ручного ввода timezone

> После выбора "Ввести вручную" пользователь не может корректно отменить шаг. `/cancel` интерпретируется как невалидный timezone.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Исправить (только cancel, без цикла повторного ввода).
**Действие:** Обновлены design (Manual input validation) и plan (Task 7 Step 4 — добавлена проверка `/cancel` перед валидацией).

---

### [D2] Task 9 плана упоминает ZoneRulesException

> В Task 9 указан `ZoneRulesException` как "common issue", что конфликтует с принятым решением ловить `DateTimeException`.

**Источник:** codex-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Исправить. Заменить на DateTimeException в плане.
**Действие:** Обновлён plan (Task 9 Step 3) — заменено на DateTimeException.

---

### [E2] chatId.chatId.long — некорректный синтаксис

> `chatId.chatId.long` в FrigateAnalyzerBot.kt выглядит как опечатка.

**Источник:** gemini-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Ложное срабатывание. Проверено по коду: `ChatId` → `.chatId` → `RawChatId` → `.long` → `Long`. Тот же паттерн используется на строке 169 того же файла. Синтаксис корректен.
**Действие:** Нет.

---

### [F2] Формулировка Task 4 — "Implement" vs "Update"

> Методы `getUserZone`, `updateTimezone`, `getAuthorizedUsersWithZones` уже существуют. "Add methods" может сбить с толку.

**Источник:** gemini-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Пропустить — косметическое замечание к тексту плана, задачи уже реализованы.
**Действие:** Нет.

---

### [G2] Размещение UserZoneInfo в dto-пакете

> Plan предлагает поместить UserZoneInfo в TelegramUserService.kt. В проекте есть отдельный пакет dto.

**Источник:** gemini-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Исправить. Создать отдельный файл UserZoneInfo.kt в пакете `telegram/dto/`.
**Действие:** Обновлены design (Changed Components) и plan (Task 4 Step 1, Summary table).

---

### [H2] Сообщение об ошибке при сжатии видео не user-friendly

> `IllegalStateException("Video too large...")` в VideoExportServiceImpl всплывает как общая ошибка.

**Источник:** gemini-executor
**Статус:** Новое
**Уровень:** Suggestion
**Ответ:** Отложить. Pre-existing код, не относится к timezone фиче.
**Действие:** Нет (создать отдельную задачу).

---

### [I2] Liquibase rollback отсутствует

> Миграция 1.0.1.xml не имеет rollback инструкции.

**Источник:** ccs-executor
**Статус:** Новое
**Уровень:** Important
**Ответ:** Пропустить. Rollback не используется в проекте, Liquibase auto-rollback для addColumn работает.
**Действие:** Нет.

---

### [J2] getUserZone молча возвращает UTC для несуществующего пользователя

> Если `findByChatId` вернёт null, метод вернёт UTC без предупреждения, что может скрыть ошибку.

**Источник:** ccs-executor
**Статус:** Новое
**Уровень:** Minor
**Ответ:** Исправить. Добавить logger.warn для null user.
**Действие:** Обновлены design (Error Handling) и plan (Task 4 Step 2 — getUserZone проверяет user == null).

---

### [CCS-Q1..Q8] Код не соответствует плану

> CCS прочитал реальный код и нашёл расхождения с планом по фиксам A, B, C, G, H, I из итерации 1.

**Источник:** ccs-executor
**Статус:** Повтор (iter-1, Fixes A/B/C/G/H/I)
**Уровень:** Important
**Ответ:** Автоматически применён — это pending code fixes из итерации 1, которые ещё не применены к коду. Документированы и запланированы.
**Действие:** Нет (фиксы будут применены к коду).

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/plans/2026-02-18-timezone-support-design.md` | [A2] Data Flow: добавлен DST guard после Instant конвертации |
| `docs/plans/2026-02-18-timezone-support-design.md` | [B2] /timezone Command: добавлено требование callback messageId binding |
| `docs/plans/2026-02-18-timezone-support-design.md` | [C2] Manual input validation: добавлена обработка /cancel |
| `docs/plans/2026-02-18-timezone-support-design.md` | [G2] Changed Components: UserZoneInfo размещён в telegram/dto/ |
| `docs/plans/2026-02-18-timezone-support-design.md` | [J2] Error Handling: добавлена строка для null user |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [A2] Task 7 Step 5: DST guard с Duration.between() |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [B2] Task 7 Step 4: callback фильтр по sentMessage.messageId |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [B2] Task 7 Step 5: note о callback messageId binding в handleExport |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [C2] Task 7 Step 4: проверка /cancel перед валидацией ввода |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [D2] Task 9 Step 3: ZoneRulesException → DateTimeException |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [G2] Task 4 Step 1: UserZoneInfo в отдельном файле в dto/ |
| `docs/plans/2026-02-18-timezone-support-plan.md` | [J2] Task 4 Step 2: getUserZone warns for null user |
| `docs/plans/2026-02-18-timezone-support-plan.md` | Summary table: обновлены UserZoneInfo и FrigateAnalyzerBot строки |

## Статистика

- Всего замечаний: 18 (10 новых + 8 повторов CCS)
- Новых: 10 (A2-J2)
- Повторов (автоответ): 8 (CCS Q1-Q8 = iter-1 Fixes A/B/C/G/H/I)
- К исправлению: 6 (A2, B2, C2, D2, G2, J2)
- Ложное срабатывание: 1 (E2)
- Пропущено: 2 (F2 — косметика, I2 — rollback не используется)
- Отложено: 1 (H2 — video compression, не относится к фиче)
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
