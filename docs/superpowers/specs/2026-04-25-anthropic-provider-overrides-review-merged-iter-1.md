# Merged Design Review — Iteration 1

## gemini-executor

### Critical Issues

**1. Сломанный тест в Task 5 (`env map omits oauth token when blank`)**

Тест не выполнит свою задачу и упадёт с `IllegalArgumentException` на этапе подготовки данных. Вспомогательная функция `props(token = "")` создаёт объект, где `oauthToken` пустой, а `authToken` по умолчанию тоже равен `""`. В результате блок `init` внутри `ClaudeProperties` сработает до вызова `buildEnvMap()` и выбросит исключение.

**Решение:** Чтобы протестировать именно отсутствие OAuth-токена в мапе, необходимо передать фиктивный `authToken`, чтобы пройти валидацию: `props(token = "", authToken = "dummy")`.

### Concerns

**1. Блокировка провайдеров без аутентификации (Validation Logic)**

Жёсткое условие `require(oauthToken.isNotBlank() || anthropic.authToken.isNotBlank())` может стать проблемой для локальных Anthropic-совместимых провайдеров (например, LiteLLM, Ollama, локальный прокси). Если сервер не требует авторизации, пользователю придётся вводить "костыльный" токен (например, `ANTHROPIC_AUTH_TOKEN=dummy`), что неочевидно и ухудшает Developer Experience.

**2. Использование блока `init` вместо Spring Bean Validation**

Выбрасывание исключения в блоке `init` происходит на этапе инстанцирования Kotlin-класса. Из-за этого Spring Boot не сможет собрать все ошибки конфигурации одновременно. Разработчик увидит только первую ошибку из `init`, исправит её, перезапустит сервис и лишь затем столкнётся со следующей ошибкой валидации.

**3. Отсутствие значений по умолчанию в Data Classes**

В `ClaudeProperties` и `AnthropicSection` поля не имеют значений по умолчанию (например, `val authToken: String = ""`). Если пользователь решит настроить приложение только через переменные окружения и полностью удалит блок `claude` из `application.yaml`, Spring Boot не сможет инстанцировать класс `AnthropicSection` (получим `BindException` из-за отсутствия обязательных аргументов конструктора).

### Suggestions

**1. Использовать `@AssertTrue` для кросс-полевой валидации**

Вместо блока `init` перенести логику в метод с аннотацией `@AssertTrue`.

**2. Добавить значения по умолчанию**

Сделать конфигурацию более отказоустойчивой, добавив значения по умолчанию для всех опциональных полей.

**3. Избавиться от фабрики `props()` в тестах**

При использовании значений по умолчанию, громоздкая вспомогательная функция `props()` станет не нужна.

---

## ccs-executor (glm)

### Critical Issues

### 1. Существующая валидация в `ClaudeDescriptionAgent` проигнорирована

`ClaudeDescriptionAgent.kt:49` уже содержит валидацию:
```kotlin
init {
    check(claudeProperties.oauthToken.isNotBlank()) { ... }
}
```

Ни дизайн, ни план не упоминают этот блок. После добавления `require()` в `ClaudeProperties.init` получится **дублирование валидации**. Существующий тест `ClaudeDescriptionAgentValidationTest` ожидает `IllegalStateException`.

**Нужно:** либо удалить `check()` из агента и обновить тест, либо явно задокументировать, почему обе проверки остаются.

### 2. Четыре тестовых файла сломаются -- план их не упоминает

`ClaudeProperties` -- data class без default-параметров для `anthropic`. После добавления `AnthropicSection` **все** вызовы конструктора без этого параметра перестанут компилироваться:

| Файл | Проблема |
|------|--------|
| `ClaudeDescriptionAgentTest.kt:49` | Конструирует `ClaudeProperties` без `anthropic` |
| `ClaudeDescriptionAgentValidationTest.kt:28` | То же самое + тестирует `IllegalStateException`, а не `IllegalArgumentException` |
| `ClaudeDescriptionAgentIntegrationTest.kt:79` | То же самое |
| `AiDescriptionAutoConfigurationTest.kt` | Три теста, все конструируют `ClaudeProperties` без `anthropic` |

План содержит только Task 5 (обновление `ClaudeAsyncClientFactoryTest`), но не покрывает остальные файлы.

### 3. Валидация сломает `enabled=false` конфигурации

`ClaudeProperties` регистрируется через `@EnableConfigurationProperties`, то есть бин создаётся **всегда**, независимо от `enabled`. С новым `require()` любой пользователь с `enabled=false` и пустыми токенами получит падение при старте. Это breaking change.

**Решение:** вынести `require()` в компоненты, активные только при `enabled=true` (например, `ClaudeAsyncClientFactory`).

### 4. `AnthropicSection` не имеет default-значений

Все шесть полей -- `String` без default-значений. Spring Boot property binding не сможет создать `AnthropicSection` без блока в YAML.

**Нужно:** добавить `= ""` ко всем полям `AnthropicSection`.

### Concerns

5. Поведенческое изменение: `CLAUDE_CODE_OAUTH_TOKEN` теперь опускается при пустом значении
6. Тест `env map does not leak unrelated vars` удалён без объяснения
7. Сообщение об ошибке в `require()` раскрывает внутренние пути свойств

---

## ccs-executor (albb-glm)

### Critical Issues

### 1. Test `env map does not leak unrelated vars` removed without explanation

The existing test at line 52-55 verifies that no unexpected env vars leak into the map. The plan replaces the entire test file, deleting this regression test.

**Fix:** Add it back with an explicit assertion.

### 2. Bug fix hidden inside feature

The current code at line 41 of `ClaudeAsyncClientFactory.kt` unconditionally puts `CLAUDE_CODE_OAUTH_TOKEN` even when blank. The plan fixes this with `isNotBlank()` check -- but this is a bug fix, not part of the feature.

### 3. Validation placement -- Spring Boot binding edge case

The design uses `require()` in a data class `init` block. If both tokens are blank, bean creation throws `IllegalArgumentException` before Spring's `@Validated` can run.

### Concerns

- No default values in AnthropicSection
- Model aliases hardcoded -- 3 fixed aliases mean code changes for any new model
- No Spring Boot context test
- Plan Task 3 line numbers incorrect

---

## ccs-executor (albb-qwen)

### Critical Issues

### 1. Удаление теста `env map does not leak unrelated vars` без обоснования

Существующий тест проверяет, что в env map попадают только ожидаемые переменные. Предложенный план удаляет этот тест без замены.

### 2. Validation gap: `baseUrl` без `authToken` (и наоборот) не валидируется

Сценарий, где пользователь задал `anthropic.base-url`, но **не** задал `anthropic.auth-token`, будет молча пропущен.

### Concerns

3. YAML indentation в плане не совпадает с реальным файлом
4. Нет integration-теста с реальными данными конфигурации
5. Порядок секций в ClaudeProperties
6. `.env.example` использует `qwen3.5-plus` как пример -- привязка к конкретному провайдеру

---

## ccs-executor (albb-kimi)

### Critical Issues

**1. Нарушение принципа SRP в ClaudeProperties**

`ClaudeProperties` — это класс конфигурации для *Claude* CLI SDK. Внедрение `AnthropicSection` превращает его в "конфигурацию для любого Anthropic-совместимого провайдера", что противоречит названию.

**2. Некорректная валидация в `init` блоке**

`require()` происходит *после* создания объекта Spring'ом, но *до* валидации `@Validated`. Порядок валидации станет непредсказуемым.

**3. Тестовый файл Task 5 содержит логическую ошибку**

Тест `throws when no token configured` вызывает `props(token = "", authToken = "")`. `assertFailsWith` перехватит *любое* `IllegalArgumentException`, включая те, что могут возникнуть из других причин. Тест не проверяет сообщение об ошибке.

**4. План не учитывает backward compatibility существующих тестов**

Существующий тест `env map does not leak unrelated vars` проверяет `env.keys == setOf("CLAUDE_CODE_OAUTH_TOKEN")`. После изменений этот тест сломается.

### Concerns

1. Непоследовательность в именовании переменных окружения
2. Модельные алиасы Opus/Sonnet/Haiku — привязка к Anthropic
3. Отсутствие валидации URL формата
4. Отсутствие документации о приоритете токенов
5. Task 2 в плане дублирует код

---

## ccs-executor (albb-minimax)

### Critical Issues

1. **Существующий тест сломается** -- тест `env map does not leak unrelated vars` ожидает ровно один ключ. После добавления 6 ANTHROPIC* переменных тест упадёт.
2. **Изменение поведения OAuth token не документировано** -- текущий код всегда помещает `CLAUDE_CODE_OAUTH_TOKEN` (даже пустой). План меняет на `isNotBlank()`.
3. **Нет валидации model при альтернативном провайдере** -- если пользователь использует `ANTHROPIC_AUTH_TOKEN` + `ANTHROPIC_BASE_URL`, поле `claude.model` всё ещё обязательно (`@field:NotBlank`), но может не поддерживаться провайдером.
4. **Конфликт полей model -- неясно, какое приоритетнее** -- два места для указания модели без документированного приоритета.

---

## ccs-executor (deepseek)

### Critical Issues

**1. План ломает 3 других тестовых файла**

Добавление `anthropic: AnthropicSection` как обязательного параметра сломает компиляцию в трёх файлах:

| Файл | Строка |
|------|--------|
| `ClaudeDescriptionAgentValidationTest.kt` | 28 |
| `ClaudeDescriptionAgentTest.kt` | 49 |
| `ClaudeDescriptionAgentIntegrationTest.kt` | 79 |

План в разделе "Self-Review" утверждает: "The 3 existing tests still compile" -- но это относится только к `ClaudeAsyncClientFactoryTest`.

**2. План удаляет существующий тест `env map does not leak unrelated vars`**

Текущий тест (строка 52-55) проверяет важный инвариант. План заменяет файл целиком -- этот тест исчезает.

**3. `AnthropicSection.model` конфликтует по имени с `ClaudeProperties.model`**

В коде `claudeProperties.model` и `claudeProperties.anthropic.model` -- это два разных значения с разной семантикой.

**Рекомендация:** переименовать в `AnthropicSection` поле `model` --> `modelOverride`.

### Concerns

4. Неясное взаимодействие `claude.model` и `anthropic.model`
5. Нет теста на частичную конфигурацию anthropic
6. Поведенческое изменение `CLAUDE_CODE_OAUTH_TOKEN` не задокументировано
7. Отсутствует `@field:Valid` на `AnthropicSection`
8. Интеграционный тест остаётся `@Disabled`
