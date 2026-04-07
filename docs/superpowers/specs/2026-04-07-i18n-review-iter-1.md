# Review Iteration 1 — 2026-04-07

## Источник

- Design: `docs/superpowers/specs/2026-04-07-i18n-design.md`
- Plan: `docs/superpowers/plans/2026-04-07-i18n.md`
- Review agents: codex-executor (gpt-5.4), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)

## Замечания

### [ERR-1] NoSuchMessageException не обрабатывается в MessageResolver

> MessageResolver.get() вызывает messageSource.getMessage() без обработки NoSuchMessageException. Любая опечатка в ключе крашит handler.

**Источник:** codex, gemini, glm, albb-glm, albb-qwen, albb-kimi, albb-minimax (7/7)
**Статус:** Автоисправлено
**Ответ:** Добавлен try-catch с fallback: requested locale → ru → raw key + logging
**Действие:** Обновлены design spec (MessageResolver) и plan (Task 4)

---

### [PERF-1] N+1 DB запросы для языка

> Каждый handler вызывает getUserLanguage(chatId) — отдельный SELECT. Авторизация уже загружает user entity.

**Источник:** codex, gemini, glm, albb-qwen, albb-kimi, albb-minimax (6/7)
**Статус:** Обсуждено с пользователем
**Ответ:** Передавать TelegramUserDto? в CommandHandler.handle() вместо UserRole?
**Действие:** Добавлен Task 2.5 в план — изменение интерфейса CommandHandler

---

### [DATA-1] VARCHAR(10) для language_code

> 4/7 ревьюеров указали на недостаточность для BCP 47 тегов.

**Источник:** gemini, albb-glm, albb-kimi, albb-qwen (4/7)
**Статус:** Обсуждено с пользователем
**Ответ:** VARCHAR(64)
**Действие:** Обновлены design spec и plan (Task 1)

---

### [INTEG-1] /language handler использует ключ timezone timeout

> LanguageCommandHandler использует msg.get("command.timezone.timeout", lang) — семантически некорректно.

**Источник:** albb-glm, glm (2/7)
**Статус:** Автоисправлено
**Ответ:** Добавлен ключ command.language.timeout
**Действие:** Обновлены properties в design spec и plan, исправлен handler

---

### [ERR-2] catch(Exception) глушит CancellationException

> В QuickExportHandler language fallback: catch(_: Exception) перехватывает CancellationException.

**Источник:** glm, albb-kimi (2/7)
**Статус:** Автоисправлено
**Ответ:** Добавлен rethrow CancellationException
**Действие:** Обновлен план (Task 14 и Task 16)

---

### [EDGE-1] Owner language перезаписывается при /start

> Если owner вручную установил язык через /language, повторный /start перезапишет его.

**Источник:** glm, codex (2/7)
**Статус:** Автоисправлено (уже корректно)
**Ответ:** updateLanguage вызывается только внутри if (existing?.status != UserStatus.ACTIVE) — при повторном /start не сработает
**Действие:** Проверено — план уже корректен, изменения не требуются

---

### [INTEG-2] restoreButton в QuickExportHandler не получает lang

> createExportKeyboard(recordingId) вызывается из restoreButton() без lang после перехода на instance method.

**Источник:** glm, codex (2/7)
**Статус:** Автоисправлено
**Ответ:** Добавлен Step 5 в Task 14 — передавать lang в restoreButton
**Действие:** Обновлен план (Task 14)

---

### [ERR-3] updateLanguage return value игнорируется

> LanguageCommandHandler не проверяет результат updateLanguage(), подтверждает смену даже при ошибке.

**Источник:** gemini (1/7)
**Статус:** Автоисправлено
**Ответ:** Добавлена проверка return value с generic error
**Действие:** Обновлен план (Task 6)

---

### [TEST-1] Отсутствуют тесты MessageResolver и key parity

> MessageResolver — критический компонент без unit tests. Нет проверки что оба .properties файла содержат одинаковые ключи.

**Источник:** albb-glm, glm, codex (3/7)
**Статус:** Автоисправлено
**Ответ:** Добавлен Task 17.5 с MessageResolverTest и MessageKeyParityTest
**Действие:** Обновлен план

---

### [EDGE-2] Текст "отмена" в ExportDialogRunner

> dateInput.equals("отмена") не работает для англоязычных пользователей.

**Источник:** glm (1/7)
**Статус:** Автоисправлено
**Ответ:** Добавлена заметка в Task 12 — удалить проверку русского слова, оставить /cancel
**Действие:** Обновлен план (Task 12)

---

### [SEC-1] Format string injection через { в username

> MessageFormat интерпретирует { как спецсимвол.

**Источник:** glm, gemini, albb-qwen (3/7)
**Статус:** Отклонено
**Ответ:** MessageFormat НЕ интерпретирует { внутри аргументов, только в pattern. Telegram username: только a-z, 0-9, _. Реальный вектор атаки отсутствует.

---

### [EDGE-3] Смена языка mid-dialog

> Если пользователь сменит язык через /language во время /export, сообщения будут на разных языках.

**Источник:** codex, gemini, albb-minimax, albb-kimi, albb-qwen, glm (6/7)
**Статус:** Отклонено
**Ответ:** Язык определяется один раз при старте диалога (snapshot). Это корректное и ожидаемое поведение — защищает от смешения языков в рамках одного диалога.

---

### [ARCH-1] MessageResolver без интерфейса

> Затрудняет мокирование в тестах.

**Источник:** albb-kimi (1/7)
**Статус:** Отклонено
**Ответ:** YAGNI. Open class легко мокается через mockk. Интерфейс добавит сложности без пользы.

---

### [ARCH-2] Hardcoded "ru"/"en" строки

> Строки "ru" и "en" разбросаны по коду. Нужен enum.

**Источник:** glm, gemini (2/7)
**Статус:** Отклонено
**Ответ:** При 2 языках enum добавит boilerplate без пользы. Если появится 3-й язык, можно добавить.

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design spec | VARCHAR(10) → VARCHAR(64), MessageResolver fallback chain, CommandHandler.handle(user: TelegramUserDto?), command.language.timeout key |
| plan Task 1 | VARCHAR(10) → VARCHAR(64) |
| plan Task 2.5 | NEW: Change CommandHandler interface to pass TelegramUserDto? |
| plan Task 4 | MessageResolver with NoSuchMessageException handling |
| plan Task 5 | Added command.language.timeout key to both properties |
| plan Task 6 | Check updateLanguage return value |
| plan Task 12 | Note to remove "отмена" check |
| plan Task 14 | Fix restoreButton lang, CancellationException rethrow |
| plan Task 16 | CancellationException rethrow in error handler |
| plan Task 17.5 | NEW: MessageResolverTest and MessageKeyParityTest |

## Статистика

- Всего замечаний: 13
- Автоисправлено: 8
- Обсуждено с пользователем: 2
- Отклонено: 4 (включая 1 уже корректно)
- Повторов: 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
