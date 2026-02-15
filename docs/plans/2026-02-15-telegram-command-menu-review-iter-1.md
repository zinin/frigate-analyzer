# Review Iteration 1 — 2026-02-15

## Источник

- Design: `docs/plans/2026-02-15-telegram-command-menu-design.md`
- Plan: `docs/plans/2026-02-15-telegram-command-menu.md`
- Review agents: codex-executor (gemini-executor и ccs-executor не выполнились — ошибка Bash)
- Merged output: `docs/plans/2026-02-15-telegram-command-menu-review-merged-iter-1.md`

## Замечания

### DESIGN-1: Отсутствие обработки ошибок при вызове setMyCommands

> Если Telegram API вернет ошибку, исключение может прервать запуск бота.

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Высокая
**Ответ:** Да, добавить try-catch
**Действие:** Обновлены дизайн и план — методы обёрнуты в try-catch, ошибки логируются как warn

---

### DESIGN-2: Дублирование списка команд

> Команды /start и /help дублируются в registerDefaultCommands() и registerOwnerCommands().

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Средняя
**Ответ:** Да, вынести в константы
**Действие:** Обновлены дизайн и план — добавлены DEFAULT_COMMANDS и OWNER_COMMANDS как companion object константы

---

### PLAN-1: Сигнатура setMyCommands с vararg

> Проверить что ktgbotapi 30.0.2 предоставляет vararg-версию setMyCommands.

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Средняя
**Ответ:** Проверено по исходникам — есть обе версии (vararg и List). В плане используем List-версию (через константы).
**Действие:** План уже обновлён с List-версией

---

### DESIGN-3: Гонка между startup и /start

> Два параллельных вызова setMyCommands теоретически возможны.

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Низкая
**Ответ:** Крайне маловероятный сценарий, не требует изменений
**Действие:** Нет

---

### DESIGN-4: Удаление команд при деактивации owner

> Меню owner останется после деактивации до перезапуска.

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Низкая
**Ответ:** Пропустить — out of scope
**Действие:** Добавлено как known limitation в дизайн-документ

---

### PLAN-2: Не указано точное место вставки в handleStart()

> registerOwnerCommands должен быть только внутри ветки if (username == properties.owner).

**Источник:** codex-executor
**Статус:** Новое
**Серьёзность:** Низкая
**Ответ:** Принято, уточнено в плане
**Действие:** План обновлён — явно указано "inside the if (username == properties.owner) block"

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| telegram-command-menu-design.md | Добавлены: companion object константы, try-catch, known limitations |
| telegram-command-menu.md | Полностью обновлён: константы, try-catch, уточнено место вставки в handleStart |

## Статистика

- Всего замечаний: 6
- Новых: 6
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gemini-executor и ccs-executor — ошибка Bash)
