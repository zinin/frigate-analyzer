# Review Iteration 1 — 2026-04-06 22:47

## Источник

- Design: `docs/superpowers/specs/2026-04-06-quick-export-annotated-design.md`
- Plan: `docs/superpowers/plans/2026-04-06-quick-export-annotated.md`
- Review agents: codex-executor (gpt-5.4), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Merged output: `docs/superpowers/specs/2026-04-06-quick-export-annotated-review-merged-iter-1.md`

## Замечания

### [LABEL] Кнопка "📹 Видео" → "📹 Оригинал"

> 5 из 7 агентов рекомендуют переименовать для консистентности с `/export` ("Оригинал" / "С объектами")

**Источник:** Codex, Gemini, glm, Kimi, albb-qwen
**Статус:** Автоисправлено
**Ответ:** Переименовано "📹 Видео" → "📹 Оригинал" в spec и плане
**Действие:** Обновлены оба документа

---

### [THROTTLING] Противоречие в описании throttling

> Spec говорит "3-5 секунд", план использует "stage change + 5% для ANNOTATING" (как ExportExecutor)

**Источник:** Codex, minimax, glm, albb-glm, Kimi
**Статус:** Автоисправлено
**Ответ:** Выровнял spec с подходом ExportExecutor: обновление по смене этапа + порог ≥5% для ANNOTATING
**Действие:** Обновлён spec (разделы "Прогресс в кнопке" и "Обработка ошибок")

---

### [PLAN-COMMENT] Некорректный комментарий о prefix ordering

> План утверждает `"qea:...".startsWith("qe:")` = true, но это FALSE (двоеточие на разных позициях)

**Источник:** Анализ при обработке — glm и Gemini правильно указали на различие
**Статус:** Автоисправлено
**Ответ:** Исправлен комментарий в плане
**Действие:** Обновлён план (Task 3, Step 4)

---

### [CONCURRENCY] activeExports keyed by UUID — блокировка обоих режимов

> Пользователь не может запустить оригинал и аннотированный экспорт одновременно для одной записи

**Источник:** albb-glm, minimax, glm, Kimi, albb-qwen (5 из 7 агентов)
**Статус:** Обсуждено с пользователем
**Ответ:** "Нет, блокировать (рекомендуется)" — один экспорт на запись, экономия ресурсов
**Действие:** Поведение оставлено как есть (spec уже это описывает)

---

### [THREAD-SAFETY] Потокобезопасность activeExports

> MutableSet не потокобезопасен

**Источник:** Gemini, albb-qwen
**Статус:** Отклонено
**Ответ:** Ложное срабатывание — код уже использует `ConcurrentHashMap.newKeySet()`
**Действие:** Нет изменений

---

### [NOTIFICATION-SENDER] TelegramNotificationSender нуждается в изменениях

> Файл должен быть обновлён для двух кнопок

**Источник:** Gemini
**Статус:** Отклонено
**Ответ:** Файл вызывает `QuickExportHandler.createExportKeyboard()` — companion object function, изменения подхватываются автоматически
**Действие:** Нет изменений

---

### [KEYBOARD-RESTORE] Гарантированное восстановление клавиатуры

> Нужен try-finally для 20-минутного экспорта

**Источник:** Gemini, minimax
**Статус:** Отклонено
**Ответ:** Уже реализовано — restoreButton вызывается в finally/catch блоках
**Действие:** Нет изменений

---

### [PREFIX-FORMAT] Callback prefix qe:/qea: → qe:o:/qe:a:

> Предлагают единый namespace для упрощения роутинга

**Источник:** Codex, glm, Kimi
**Статус:** Отклонено
**Ответ:** Префиксы `qe:` и `qea:` различаются (двоеточие на разных позициях), коллизии нет. Менять формат не нужно — текущий подход проще
**Действие:** Нет изменений

---

### [PROGRESS-DUPLICATION] Дублирование логики прогресса с ExportExecutor

> Рекомендуют вынести общий helper

**Источник:** Codex
**Статус:** Отклонено
**Ответ:** Разный рендеринг (текст сообщения vs текст кнопки). YAGNI для 2 call sites
**Действие:** Нет изменений

---

### [BACKWARD-COMPAT] Обратная совместимость со старыми callback

> Старые уведомления с qe: продолжат работать?

**Источник:** Kimi
**Статус:** Отклонено
**Ответ:** Префикс `qe:` не меняется, старые callback обрабатываются как ORIGINAL
**Действие:** Нет изменений

---

### [OUT-OF-SCOPE] Cancel, метрики, intermediate уведомления

> Добавить отмену, метрики, промежуточные уведомления при долгом экспорте

**Источник:** qwen, Kimi, glm
**Статус:** Отклонено
**Ответ:** Вне scope текущей задачи
**Действие:** Нет изменений

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-06-quick-export-annotated-design.md` | "📹 Видео" → "📹 Оригинал", throttling описание выровнено с ExportExecutor |
| `docs/superpowers/plans/2026-04-06-quick-export-annotated.md` | "📹 Видео" → "📹 Оригинал", исправлен комментарий о prefix ordering |

## Статистика

- Всего замечаний: 11
- Автоисправлено: 3
- Обсуждено с пользователем: 1
- Отклонено: 7
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.4), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
