# Двойной Jackson stack: legacy `com.fasterxml.jackson` + новый `tools.jackson`

**Дата создания:** 2026-05-25
**Источник:** external code review (codex, feat/status-command branch)
**Severity:** Important (архитектурный долг; production работает, но конфиг лжёт)
**Связанный PR:** `feat/status-command` (после `5327ba4` остаётся как known issue)

## Краткая суть

В проекте сосуществуют **два разных Jackson-стека**:

| Пакет | Используется в | Зачем |
|---|---|---|
| `com.fasterxml.jackson.*` (Jackson 2, legacy) | `JacksonConfiguration` @Bean `ObjectMapper` | Internal parsers: `DetectService`, `ClaudeResponseParser` |
| `tools.jackson.*` (Jackson 3, новый) | `WebClientConfiguration` `JacksonJsonEncoder/Decoder` + Spring Boot 4 auto-config | WebClient outbound + WebFlux REST inbound/outbound (`/status` и др.) |

Это привело к скрытому несоответствию: **наш `JacksonConfiguration` не контролирует wire-format REST endpoint'ов**. Контракт ISO-8601 для `/status` сейчас обеспечивается **дефолтами** `tools.jackson` (которые случайно совпадают с design'ом), а не нашим конфигом. `JacksonConfigurationTest` тестирует bean в изоляции — не доказывает реальный wire.

## Доказательства

### Где legacy fasterxml инжектится

`grep -rn 'import com.fasterxml.jackson' modules/ --include="*.kt"`:

- `modules/core/.../config/JacksonConfiguration.kt` — определяет `@Bean fun objectMapper(): ObjectMapper`
- `modules/core/.../service/DetectService.kt:48` — `private val objectMapper: ObjectMapper` (constructor injection)
- `modules/ai-description/.../claude/ClaudeResponseParser.kt:16` — `private val objectMapper: ObjectMapper` (constructor injection)
- `modules/model/.../response/JobStatus.kt:3` — `@JsonProperty` аннотации
- `modules/ai-description/.../claude/ClaudeExceptionMapper.kt:3` — `JsonProcessingException`, `ClaudeResponseParser.kt:3` — `JsonNode`
- Тесты `DetectServiceTest`, `DetectServiceCancelJobTest`, `VideoVisualizationServiceTest`, `ClaudeDescriptionAgent*Test`, `ClaudeResponseParserTest`, `AiDescriptionAutoConfigurationTest` — собирают свои тестовые `ObjectMapper` от fasterxml

### Где новый tools.jackson

`modules/core/.../config/WebClientConfiguration.kt:20-22`:
```kotlin
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
```

`JacksonJsonEncoder`/`JacksonJsonDecoder` (line 63-78) построены из `tools.jackson.databind.json.JsonMapper` — это они отвечают за WebClient JSON и Spring Boot 4 WebFlux также использует `tools.jackson` для своих codec'ов.

## Историческая справка (нужна верификация)

По воспоминаниям владельца проекта: "старый jackson по каким-то причинам не получалось убрать". Возможные причины:

1. **kotlin-jackson модуль.** Legacy `com.fasterxml.jackson.module:jackson-module-kotlin` — известный JSR-310 + data class конструктор support. Tools.jackson может не иметь полного эквивалента / иметь другую API.
2. **Spring annotations / R2DBC mapping.** Возможно где-то используется `@JsonProperty` из com.fasterxml для R2DBC entity mapping.
3. **Третьи стороны.** Любая lib, которая принимает `com.fasterxml.jackson.databind.ObjectMapper` параметром — заставляет нас держать legacy. Например, проверить какой mapper использует `springdoc-openapi` (для Swagger).
4. **Транзитивные зависимости.** `spring-boot-starter-webflux` в Spring Boot 4 пришивает оба пакета: legacy для совместимости + новый. Полное удаление legacy = удалить транзитив, что часто невозможно.

## Open questions для /brainstorming

### Q1. Что *именно* в проекте требует legacy fasterxml ObjectMapper?

Точный inventory: для каждого `import com.fasterxml.jackson` записать, **почему** нельзя перейти на tools.jackson. Возможные ответы:
- "Нет API эквивалента в tools.jackson"
- "Транзитивная зависимость от X (например springdoc-openapi)"
- "Просто никогда не мигрировали, можно сейчас"

### Q2. Какие риски миграции `DetectService` и `ClaudeResponseParser`?

Эти два инжектят `com.fasterxml.jackson.databind.ObjectMapper`. Если переписать на `tools.jackson.databind.ObjectMapper`:
- Меняются ли сигнатуры методов парсинга (`readTree`, `treeToValue`)?
- Сохраняется ли поведение `findAndAddModules()` (auto-register JSR-310, kotlin module)?
- Не сломается ли парсинг ответов detect-сервера / Claude API на каких-то edge cases?

### Q3. Можно ли удалить kotlin-module транзитивно?

`com.fasterxml.jackson.module.kotlin.registerKotlinModule` используется в тестах. У tools.jackson есть свой kotlin module (`tools.jackson.module.kotlin`). Проверить наличие и совместимость API.

### Q4. Что с `@JsonProperty` в JobStatus.kt?

`modules/model/.../response/JobStatus.kt:3` импортирует `com.fasterxml.jackson.annotation.JsonProperty`. У tools.jackson — `tools.jackson.annotation.JsonProperty` (другой пакет). Замена тривиальная, но затрагивает model-модуль, который шарится между core/telegram/ai-description.

### Q5. Swagger / springdoc-openapi совместимость

Springdoc-openapi для OpenAPI 3.x в Spring Boot 4 — какой Jackson использует? Если legacy — мы обязаны держать legacy для генерации спецификаций. Если новый — мы свободны.

### Q6. Двойной тестовый стек

Тесты собирают свои `ObjectMapper`'ы:
- `VideoVisualizationServiceTest.kt:488 buildObjectMapper()` — fasterxml
- `DetectServiceTest`, `DetectServiceCancelJobTest` — fasterxml через aliased import (`com.fasterxml.jackson.databind.ObjectMapper as FasterxmlObjectMapper`)
- `VideoVisualizationServiceTest.kt:36-38` — tools.jackson импорты

Эти тесты явно различают два mapper'а. **Это уже признак** наличия двойного стека на тестовом уровне. Понять, почему `as FasterxmlObjectMapper` aliasing нужен — какой второй mapper мы импортируем в этих тестах?

## Затронутые файлы (план изменений на момент решения design'а)

**Полная миграция (если решим):**
- `modules/core/.../config/JacksonConfiguration.kt` — переписать на `tools.jackson`
- `modules/core/.../config/JacksonConfigurationTest.kt` — переписать тест
- `modules/core/.../service/DetectService.kt` — обновить import + проверить API парсинга
- `modules/core/src/test/.../service/DetectServiceTest.kt`, `DetectServiceCancelJobTest.kt`, `VideoVisualizationServiceTest.kt` — обновить тесты
- `modules/ai-description/.../claude/ClaudeResponseParser.kt` — обновить import
- `modules/ai-description/.../claude/ClaudeExceptionMapper.kt` — обновить import (JsonProcessingException)
- `modules/ai-description/src/test/...` — обновить все тесты
- `modules/model/.../response/JobStatus.kt` — обновить `@JsonProperty` import
- `build.gradle.kts` / `libs.versions.toml` — возможно, удалить транзитивные зависимости legacy

**Минимальная очистка (если миграция невозможна):**
- Документировать каждое legacy использование KDoc'ом "почему именно legacy"
- Удалить дубли тестовых mapper-сборок, ввести test-utility

## Связанные документы

- Design `/status`: `docs/superpowers/specs/2026-05-25-status-telegram-design.md` § Implementation notes — REST
- Plan `/status`: `docs/superpowers/plans/2026-05-25-status-command.md` § Task 10b
- Iter-1 review: `docs/superpowers/specs/2026-05-25-status-telegram-review-iter-1.md` § CRITICAL-5
- Codex external review of feat/status-command — Important #2 (this issue's source)

## Suggested follow-up flow

1. **Inventory first.** Перечислить все legacy `com.fasterxml.jackson` использования с обоснованием "почему legacy". Без этого design не имеет смысла — может оказаться, что 90% мигрируется тривиально, либо что 90% не мигрируется вообще.
2. Запустить `/brainstorming` на этой issue.
3. Принять решение: полная миграция, частичная, или явно зафиксировать "оба стека останутся" + почему.
4. (Если миграция) сделать в одной small PR с явным design'ом.
