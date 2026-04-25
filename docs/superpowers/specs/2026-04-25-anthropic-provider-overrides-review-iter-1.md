# Review Iteration 1 — 2026-04-25 22:00

## Источник

- Design: `docs/superpowers/specs/2026-04-25-anthropic-provider-overrides-design.md`
- Plan: `docs/superpowers/plans/2026-04-25-anthropic-provider-overrides.md`
- Review agents: codex-executor, gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
- Merged output: `docs/superpowers/specs/2026-04-25-anthropic-provider-overrides-review-merged-iter-1.md`

## Замечания

### CRITICAL-1 Тест `env map does not leak unrelated vars` удалён без замены

**Источник:** 6 рецензентов (CCS: glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
**Статус:** Автоисправлено
**Ответ:** Вернуть тест с адаптированным assertion — проверять что env map содержит только ожидаемые ключи при дефолтных значениях
**Действие:** Добавлен тест `env map contains only expected keys when anthropic vars blank` в Task 9 (план)

---

### CRITICAL-2 План ломает компиляцию в 4+ тестовых файлах

**Источник:** CCS glm, albb-kimi, deepseek, Codex
**Статус:** Автоисправлено
**Ответ:** Добавить новые задачи в план для каждого сломанного файла
**Действие:** Добавлены Task 5-8 (ClaudeDescriptionAgentTest, ValidationTest, IntegrationTest, AiDescriptionAutoConfigurationTest)

---

### CRITICAL-3 Сломанный тест `env map omits oauth token when blank`

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** Передать `authToken = "dummy"` для прохождения валидации
**Действие:** Исправлено в Task 9 — `props(token = "", authToken = "dummy")`

---

### CRITICAL-4 Существующая валидация в ClaudeDescriptionAgent проигнорирована

**Источник:** CCS glm, Codex
**Статус:** Автоисправлено
**Ответ:** Обновить `check()` в ClaudeDescriptionAgent для поддержки обоих токенов
**Действие:** Добавлен Task 3 в план, обновлён дизайн

---

### CRITICAL-5 Валидация сломает `enabled=false` конфигурации

**Источник:** CCS glm, Codex
**Статус:** Обсуждено с пользователем
**Ответ:** Перенести валидацию в `ClaudeAsyncClientFactory` (активен только при enabled=true)
**Действие:** Удалён `require()` из ClaudeProperties.init, validation добавлена в ClaudeAsyncClientFactory.create() (Task 2) и ClaudeDescriptionAgent.init (Task 3)

---

### CRITICAL-6 Поля AnthropicSection не имеют default-значений

**Источник:** gemini-executor, CCS albb-glm, deepseek
**Статус:** Автоисправлено
**Ответ:** Добавить `= ""` ко всем полям AnthropicSection
**Действие:** Все 6 полей в AnthropicSection имеют default-значения (Task 1)

---

### CRITICAL-7 Конфликт имён поля `model`

**Источник:** CCS albb-minimax, deepseek
**Статус:** Обсуждено с пользователем
**Ответ:** Переименовать `AnthropicSection.model` в `modelOverride`
**Действие:** Все упоминания `model` → `modelOverride` (design, plan, YAML, test)

---

### CRITICAL-8 Validation gap: baseUrl без authToken

**Источник:** CCS albb-qwen
**Статус:** Обсуждено с пользователем
**Ответ:** Только authToken обязателен, baseUrl опционален
**Действие:** Валидация проверяет только authToken, baseUrl не валидируется

---

### CONCERN-1 Блокировка провайдеров без аутентификации

**Источник:** gemini-executor
**Статус:** Автоисправлено (решено в CRITICAL-5)
**Ответ:** Валидация перенесена в ClaudeAsyncClientFactory — не ломает старт при enabled=false

---

### CONCERN-2 Использование блока `init` вместо Spring Bean Validation

**Источник:** gemini-executor, CCS albb-glm, albb-kimi
**Статус:** Автоисправлено (решено в CRITICAL-5)
**Ответ:** `init` block удалён из ClaudeProperties, валидация в агентах

---

### CONCERN-3 Изменение поведения CLAUDE_CODE_OAUTH_TOKEN не задокументировано

**Статус:** Автоисправлено
**Ответ:** Добавлен раздел "Breaking Changes" в дизайн-документ

---

### CONCERN-4 Нет integration теста

**Статус:** Отклонено
**Причина:** Не критично для первой итерации, можно добавить позже

---

### CONCERN-5 YAML indentation в плане не совпадает с реальным файлом

**Статус:** Автоисправлено
**Ответ:** YAML отступы в плане соответствуют реальному application.yaml (8 пробелов для `claude:`)

---

### CONCERN-6 Модельные алиасы Opus/Sonnet/Haiku привязаны к Anthropic

**Статус:** Отклонено
**Причина:** Это non-goal по дизайну — алиасы нужны для CLI, не для провайдера

---

### CONCERN-7 `.env.example` использует `qwen3.5-plus` — привязка к провайдеру

**Статус:** Автоисправлено
**Ответ:** Заменён на `<model-name>` placeholder

---

### CONCERN-8 План Task 3 line numbers incorrect

**Статус:** Автоисправлено
**Ответ:** Исправлены номера строк и контекстные якоря в плане

---

### CONCERN-9 Тест `throws when no token configured` не проверяет сообщение

**Статус:** Автоисправлено
**Ответ:** Тест перемещён в ClaudeDescriptionAgentValidationTest, где используется assertion на `IllegalStateException`

---

### CONCERN-10 SRP: ClaudeProperties превращается в универсальный конфиг

**Статус:** Отклонено
**Причина:** AnthropicSection — расширение для CLI-совместимости, не нарушение SRP

---

### SUGGESTION-1 Использовать `@AssertTrue`

**Статус:** Автоисправлено (решено в CRITICAL-5)
**Ответ:** Validation перенесена в `check()` в ClaudeAsyncClientFactory и ClaudeDescriptionAgent

---

### SUGGESTION-2 Избавиться от фабрики `props()` в тестах

**Статус:** Отклонено
**Причина:** Не нужно сейчас — helper с defaults достаточен

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `design.md` | Удалён init validation, добавлены defaults, modelOverride, ClaudeDescriptionAgent changes, ClaudeAsyncClientFactory validation, Breaking Changes, Files Modified |
| `plan.md` | Переработана архитектура (10 задач вместо 5), added Tasks 3/5/6/7/8, updated tests for modelOverride, fixed assertions |

## Статистика

- Всего замечаний: 18 (8 critical, 10 concerns)
- Автоисправлено: 10
- Обсуждено с пользователем: 3 (CRITICAL-5, CRITICAL-7, CRITICAL-8)
- Отклонено: 5
- Повторов (автоответ): 0
- Агенты: codex-executor, gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
