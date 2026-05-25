# Merged Design Review — Iteration 1

**Date:** 2026-05-26
**Topic:** Jackson 3 migration (issue #29)
**Design:** `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`
**Plan:** `docs/superpowers/plans/2026-05-26-jackson-3-migration.md`

## Reviewer status

| Agent | Status | Notes |
|-------|--------|-------|
| ollama-minimax (minimax-m2.7) | ✅ Complete | Full review delivered |
| ollama-kimi (kimi-k2.6, first run) | ✅ Complete | Full review delivered |
| ollama-deepseek → fallback kimi | ✅ Complete | DeepSeek-v4-pro failed (empty output due to DSML-style tool_use in thinking); fallback to kimi-k2.6 succeeded |
| codex (gpt-5.5, xhigh) | ❌ Incomplete | CLI execution aborted mid-investigation (~10min in, last event 00:36 was javap exploration of `spring-boot-jackson-4.0.6.jar`); no final result event. stderr shows `failed to refresh available models: timeout waiting for child process to exit` |
| ccs-glm | ❌ Incomplete | CLI execution aborted mid-investigation (~8min in, last event 00:34 was reading `JacksonConfiguration.kt`); no final result event. stderr empty |

Note: kimi appears twice (first-run direct + deepseek-fallback). Their critique partially overlaps but is not identical — both included.

---

## ollama-minimax (minimax-m2.7:cloud)

### Critical Issues

#### 1. `JacksonJsonEncoder` НЕ принимает `ObjectMapper` — только `JsonMapper`

**Файл:** `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md` § 3.2

Дизайн утверждает:
```kotlin
configurer.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(internalObjectMapper))
```
где `internalObjectMapper` имеет тип `tools.jackson.databind.ObjectMapper`.

**Реальность:** Конструктор `JacksonJsonEncoder` в Spring 7 принимает **только** `JsonMapper` или `JsonMapper.Builder`:

```java
public JacksonJsonEncoder(tools.jackson.databind.json.JsonMapper mapper)
public JacksonJsonEncoder(tools.jackson.databind.json.JsonMapper.Builder builder)
```

Хотя `ObjectMapper` наследуется от `JsonMapper` в Jackson 3, API конструктора явно требует `JsonMapper`. Полиморфизм работает, но это неточное описание API.

**Рекомендация:** Явно привести тип или использовать `internalObjectMapper as JsonMapper` в комментарии, либо изменить тип бина на `JsonMapper`.

#### 2. `VideoVisualizationServiceTest` сломается после миграции — 4 вызова `buildObjectMapper()` с несовместимым типом

**Файл:** `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

Текущий код (строки 93, 210, 279, 447):
```kotlin
val detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
```

План Task 4 Step 6 заменяет вызов на `TestObjectMappers.internalMapper()`, но не упоминает, что `buildObjectMapper()` **возвращает `com.fasterxml.jackson.databind.ObjectMapper`**, а после миграции `DetectService` принимает **только** `tools.jackson.databind.ObjectMapper`.

**Дополнительная проблема:** План удаляет `buildJsonMapper` и `buildObjectMapper` в Step 6(f), но `buildObjectMapper` в `VideoVisualizationServiceTest` строится через `com.fasterxml.jackson.databind.json.JsonMapper.builder()` (Jackson 2). После замены всех вызовов на `TestObjectMappers.internalMapper()` метод становится бесполезным, но план не указывает удалить его явно. Dead code.

#### 3. `AiDescriptionAutoConfigurationTest` создаёт `com.fasterxml.jackson.databind.ObjectMapper`

**Файл:** `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt` строка 32:
```kotlin
@Bean
fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
```

После миграции `ClaudeResponseParser` принимает `tools.jackson.databind.ObjectMapper`. В тесте бин возвращает `com.fasterxml.jackson.databind.ObjectMapper` — **несовместимые типы**. Spring не сможет заинжектить этот бин в `ClaudeResponseParser`.

План Task 5 Step 7 говорит "заменить `ObjectMapper().registerKotlinModule()` на `TestObjectMappers.internalMapper()`" — но это невозможно без изменения возвращаемого типа `@Bean`.

**Рекомендация:** Переименовать бин и/или изменить возвращаемый тип на `tools.jackson.databind.ObjectMapper`. Без этого тест не скомпилируется.

#### 4. `AiDescriptionAutoConfigurationTest` — Spring context конфликт типов

Тест использует `ApplicationContextRunner` с user-provided `TestStubConfig`. После миграции:
1. `AiDescriptionAutoConfiguration` создаёт `ClaudeResponseParser` с `ObjectMapper` (Jackson 3)
2. `TestStubConfig` предоставляет бин `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2)
3. Два бина несовместимых типов — `ClaudeResponseParser` не получит правильный инжект

Фундаментальная проблема test design.

#### 5. План Task 5 Step 2b — неверный синтаксис Kotlin multi-pattern matching

```kotlin
is JsonProcessingException,
is JacksonException,
-> {
    DescriptionException.InvalidResponse(throwable)
}
```

Запятая после `JacksonException` перед `->` — потенциальная ошибка синтаксиса. Правильно:
```kotlin
is JsonProcessingException, is JacksonException -> {
    DescriptionException.InvalidResponse(throwable)
}
```

#### 6. План Tasks 2 и 3 — TDD "failing test" проверяется только на отсутствие файла

```
Expected: Compilation failure — `unresolved reference: TestObjectMappers`
```

Это проверяет только отсутствие файла. Намного надёжнее написать тест с конкретной assertion (например `assertThat(true).isFalse()`), который **гарантированно падает при запуске**, а не при компиляции.

### Concerns

#### 7. Несоответствие строк в Task 4 Step 4(е) для `DetectServiceTest`

План говорит удалить импорты `tools.jackson.databind.DeserializationFeature` и др., но эти импорты — **правильные** tools.jackson. План должен пояснить, что эти импорты станут unused после удаления `buildJsonMapper()`.

#### 8. `bundles.jackson` остаётся в `libs.versions.toml`

Дизайн говорит "Jackson 2 — только как транзитивная зависимость". Но `bundles.jackson` содержит `jackson-kotlin` (Jackson 2) и подключается явно через `implementation(libs.bundles.jackson)`. Это противоречит духу миграции.

**Рекомендация:** Удалить `jackson-kotlin` из `bundles.jackson`, оставить только `jackson-databind`/`jackson-yaml`.

#### 9. `@Primary` на `internalObjectMapper` — потенциальный конфликт с springdoc

В дизайне § 3.1 нет анализа того, что произойдёт, если springdoc попытается использовать наш `@Primary` бин вместо своего.

#### 10. План Task 6 — возможное дублирование `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`

Проверить, что после выноса в бин `jsonEncoder()`/`jsonDecoder()` не создают новые инстансы.

### Suggestions

11. Добавить верификацию `StatusControllerTest` в Task 4 Step 7
12. `WebFluxJacksonCodecConfigurerTest` проверяет только факт вызова, но не то, что используется правильный mapper. Добавить assertion на equality mapper'ов.
13. Добавить тест на десериализацию Kotlin data class в `JacksonConfigurationTest` — но как проверить, что это именно tools.jackson KotlinModule?

### Questions

- Q1: Почему `modules/ai-description/build.gradle.kts` содержит `implementation(libs.bundles.jackson)`? Если springdoc не нужен ai-description, зачем там jackson bundle?
- Q2: Какой механизм гарантирует, что `WebFluxConfigurer.configureHttpMessageCodecs` вызывается **после** auto-config CodecCustomizer beans?
- Q3: Есть ли в `libs.versions.toml` другие зависимости, которые конфликтуют с tools.jackson?

---

## ollama-kimi (kimi-k2.6:cloud, first run)

### Critical Issues

#### 1. `AiDescriptionAutoConfigurationTest` — `ObjectMapper().registerKotlinModule()` несовместимо с Jackson 3 после смены import

В `AiDescriptionAutoConfigurationTest.kt:32` объявлен `@Bean fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()`. План Task 5 Step 7 говорит «Same pattern as Step 3», но `registerKotlinModule()` — это extension-функция из `com.fasterxml.jackson.module.kotlin`, **не применимая** к Jackson 3 `ObjectMapper`. Нужно **явно** заменить всё выражение на `TestObjectMappers.internalMapper()`. Иначе компиляция упадёт.

#### 2. `ClaudeDescriptionAgentTest` inline-конструктор `ClaudeResponseParser` неявно требует смены типа

В `ClaudeDescriptionAgentTest.kt:59` создаётся `ClaudeResponseParser(ObjectMapper().registerKotlinModule())`. После миграции `ClaudeResponseParser` будет ожидать `tools.jackson.databind.ObjectMapper`. План Step 4(c) говорит заменить на `TestObjectMappers.internalMapper()`, но не упоминает, что нужно убедиться, что `TestObjectMappers` импортирован.

#### 3. `ClaudeDescriptionAgentIntegrationTest` (disabled, но компилируется) содержит Jackson 2 `ObjectMapper`

В `ClaudeDescriptionAgentIntegrationTest.kt:102` `val mapper = ObjectMapper().registerKotlinModule()`. Даже при `@Disabled` код компилируется. План Task 5 Step 5 («Same as Step 4») должен явно указать замену.

#### 4. `StatusControllerTest` KDoc станет архитектурно ложным после миграции

KDoc в `StatusControllerTest.kt:20-24` прямо утверждает:
> «...through Spring Boot 4's WebFlux Jackson codec stack (`tools.jackson`, NOT our `com.fasterxml.jackson`-based `JacksonConfiguration`...»

После миграции это утверждение станет stale. Документационный долг. Обновить KDoc или удалить устаревшую ссылку.

#### 5. `JacksonJsonEncoder`/`JacksonJsonDecoder` — конструктор принимает `JsonMapper.Builder`, а не `JsonMapper`

В текущем `WebClientConfiguration.kt:63-78` передаётся `JsonMapper.Builder` (без `.build()`). План Task 6 предлагает `.build()` и передать `JsonMapper`. Нужно убедиться, что Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` имеют конструктор, принимающий `ObjectMapper`/`JsonMapper`. **Необходимо проверить перед мерджем**.

#### 6. `WebFluxJacksonCodecConfigurer` — отсутствие proof, что он действительно override'ит auto-config

`StatusControllerTest` проверяет ISO-8601 wire-format, но default Jackson 3 mapper **тоже** сериализует `Instant`/`Duration` в ISO-8601. Если `WebFluxJacksonCodecConfigurer` случайно удалят, `StatusControllerTest` **не упадёт**. `WebFluxJacksonCodecConfigurerTest` — unit test с MockK, который проверяет только факт вызова. Нужен integration test, который проверяет behavior, отличающийся от default (например, `FAIL_ON_UNKNOWN_PROPERTIES=false` на inbound deserialization).

### Concerns

#### 7. `ClaudeExceptionMapperTest` — anonymous `JacksonException` может не скомпилироваться

```kotlin
val cause = object : tools.jackson.core.JacksonException("boom") {}
```
Если `JacksonException` имеет `protected` конструктор или abstract методы, anonymous class не скомпилируется. В Jackson 3 `JacksonException` — `public abstract class extends RuntimeException`, но это зависит от версии 3.0.4. Использовать concrete subclass (например, `tools.jackson.core.exc.StreamReadException`) или проверить перед мерджем.

#### 8. `JacksonConfigurationTest` не проверяет Spring bean — только direct call

Вызов `JacksonConfiguration().internalObjectMapper()` напрямую не доказывает, что Spring контекст создаёт bean типа `tools.jackson.databind.ObjectMapper` и помечает его `@Primary`. Рекомендуется добавить `ApplicationContextRunner` test.

#### 9. Spring Boot auto-config `JacksonCodecCustomizer` может уже использовать `@Primary ObjectMapper`

Если Spring Boot 4.0.6 `JacksonCodecCustomizer` инжектирует `@Primary ObjectMapper` напрямую, `WebFluxJacksonCodecConfigurer` становится избыточным. Если же строит свой через `Jackson2ObjectMapperBuilder`, то configurer — единственный способ повлиять на wire-format. Неясно, какой путь выбирает Spring Boot 4.0.6.

#### 10. `findAndAddModules()` — нет fallback'а если `KotlinModule` не подхватится

Если `jackson-module-kotlin` 3 не экспортирует `META-INF/services/tools.jackson.databind.Module`, Kotlin data classes не будут десериализоваться. Test на round-trip data class в `JacksonConfigurationTest` это поймает, но только в Task 4.

#### 11. `Task 4` atomic commit — корректно

`DetectService.kt:48` содержит `private val objectMapper: ObjectMapper`. Kotlin compiler выведет тип из импорта.

#### 12. `Task 5` atomic commit — корректно

#### 13. `TestObjectMappers` в ai-description не содержит `detectServerMapper()`

Это правильно, но стоит убедиться, что ни один тест в ai-description не использует `detectServerMapper()`.

### Suggestions

14. Добавить `@Order(Ordered.LOWEST_PRECEDENCE)` к `WebFluxJacksonCodecConfigurer`.
15. Добавить `ApplicationContextRunner` test для `JacksonConfiguration` bean type.
16. Обновить KDoc `StatusControllerTest`.
17. Добавить комментарий в `libs.versions.toml` про необходимость `jackson-kotlin-3`.
18. В `ClaudeExceptionMapperTest` использовать concrete Jackson 3 exception:
    ```kotlin
    val cause = tools.jackson.core.exc.StreamReadException(null, "boom")
    ```

### Questions

- Q1: Подтверждено ли, что Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` принимают `JsonMapper`? Конструктор `JacksonJsonEncoder(ObjectMapper)` существует?
- Q2: Spring Boot 4.0.6 `JacksonCodecCustomizer` — inject'ит ли он `@Primary ObjectMapper`?
- Q3: Конструктор `tools.jackson.core.JacksonException(String)` — public?
- Q4: Почему `JacksonConfigurationTest` вызывает `JacksonConfiguration().internalObjectMapper()` напрямую вместо `ApplicationContextRunner`?
- Q5: Нужен ли явный regression integration test для REST inbound `FAIL_ON_UNKNOWN_PROPERTIES=false`?

---

## ollama-deepseek (через fallback на kimi-k2.6:cloud)

### Critical Issues

#### 1. `StatusControllerTest` НЕ является regression guard для `WebFluxJacksonCodecConfigurer`

Дизайн (§4.3) и план (Task 7 Step 5) утверждают, что `StatusControllerTest` — «живое свидетельство», что configurer подключён. **Это неверно.** `StatusControllerTest` проверяет только ISO-8601 формат. В Spring Boot 4 / Jackson 3 **дефолтный** `ObjectMapper` тоже сериализует `Instant`/`Duration` в ISO-8601. Удаление `WebFluxJacksonCodecConfigurer` **не сломает** `StatusControllerTest`.

Нужен end-to-end тест, который проверяет **специфичную** настройку нашего mapper'а (например, `FAIL_ON_UNKNOWN_PROPERTIES=false`): отправить JSON с неизвестным полем на `/status` и убедиться, что сервер возвращает 200, а не 400.

#### 2. `findAndAddModules()` — ненадёжный способ подключить KotlinModule

Работает через `ServiceLoader`, но **не гарантировано** в зависимости от classpath / shading / Gradle exclude rules. Если `META-INF/services/tools.jackson.databind.Module` отсутствует в конкретной версии артефакта, тест упадёт, и в production Kotlin data classes не будут десериализовываться.

**Митигация:** явно добавить модуль:
```kotlin
.addModule(tools.jackson.module.kotlin.KotlinModule.Builder().build())
```

#### 3. `ClaudeExceptionMapper` `JacksonException` ветка — dead code (почти)

`ClaudeResponseParser.parse()` оборачивает `readTree()` в `try/catch (e: Exception)`, превращая любое Jackson 3 исключение в `DescriptionException.InvalidResponse(e)` **до** того, как оно долетит до `ClaudeExceptionMapper`. Ветка `is JacksonException` **никогда не выполнится**. Defensive programming ок, но дизайн не объясняет, **откуда** именно `JacksonException` может прийти.

#### 4. Недостаточная coverage production bean'а в `JacksonConfigurationTest`

Тест проверяет `Instant`, `Duration`, KotlinModule и unknown properties. Но `JacksonConfiguration` конфигурирует **три** фичи:
- `FAIL_ON_UNKNOWN_PROPERTIES = false`
- `WRITE_DATES_AS_TIMESTAMPS = false`
- `WRITE_DURATIONS_AS_TIMESTAMPS = false`

Тест проверяет только (1) косвенно и (2)-(3) через Instant/Duration. Нет отдельного теста на `FAIL_ON_UNKNOWN_PROPERTIES`.

#### 5. `JacksonConfiguration` KDoc ссылается на документ, который станет «resolved»

KDoc должен быть самодостаточным. Либо убрать ссылку на документ, либо добавить в KDoc краткое резюме dual-stack rationale, чтобы не нужно было ходить в git history.

### Concerns

#### 6. `TestObjectMappers` дублируется между модулями

Gradle имеет `java-test-fixtures` plugin, который позволяет публиковать тестовые утилиты. Стоит рассмотреть вместо копипасты.

#### 7. `DetectService` internal parser использует `internalObjectMapper` (camelCase) для detect-server error bodies

Дизайн §3.4 показывает, что `DetectService` использует `internalObjectMapper`. Текущий код в тестах (`buildObjectMapper`) использовал **SNAKE_CASE** для этого парсера. Дизайн не объясняет, почему раньше был SNAKE_CASE, и почему теперь camelCase безопасен. Поскольку `JsonNode.path("detail")` не зависит от naming strategy, это работает, но **неочевидно**.

#### 8. Порядок регистрации `WebFluxConfigurer`

План утверждает, что `configureHttpMessageCodecs` «вызывается после default registration». Это верно для `DelegatingWebFluxConfiguration`, но если другой `WebFluxConfigurer` тоже вызывает `jacksonJsonEncoder`, порядок определяется `@Order`. Дизайн не упоминает `@Order` для `WebFluxJacksonCodecConfigurer`.

#### 9. `springdoc-openapi` и `@Primary` конфликт (теоретический риск)

`JacksonAutoConfiguration` использует `@ConditionalOnMissingBean(ObjectMapper.class)`, где `ObjectMapper` — это `tools.jackson.databind.ObjectMapper`. Наш бин — `tools.jackson.databind.ObjectMapper`, так что auto-config отступит. Но если `JacksonAutoConfiguration` также создаёт `JsonMapper` bean (через `Jackson2ObjectMapperBuilder`), это может привести к **трём** mapper'ам в контексте.

#### 10. `WebFluxJacksonCodecConfigurerTest` — mock-only, нет интеграционной проверки

MockK test проверяет, что методы были вызваны. Но не проверяет, что `JacksonJsonEncoder` действительно использует переданный `ObjectMapper` (а не default), и что Spring WebFlux регистрирует этот encoder для `application/json`.

### Suggestions

1. Добавить end-to-end тест tolerance to unknown properties в `StatusControllerTest`
2. Явно регистрировать KotlinModule вместо `findAndAddModules()`
3. Рассмотреть Gradle `java-test-fixtures` для `TestObjectMappers`
4. Добавить `@Order` к `WebFluxJacksonCodecConfigurer`
5. Добавить комментарий в `DetectService`, объясняющий, что mapper используется только для raw `JsonNode` парсинга

### Questions

1. Проверено ли, что `tools.jackson.module:jackson-module-kotlin:3.0.4` содержит `META-INF/services/tools.jackson.databind.Module`?
2. Почему `DetectServiceTest` / `VideoVisualizationServiceTest` использовали SNAKE_CASE для `buildObjectMapper()`?
3. Как `@Primary internalObjectMapper()` взаимодействует с `JacksonAutoConfiguration` + `Jackson2ObjectMapperBuilder` в Spring Boot 4.0.6?
4. Есть ли другие места, где инжектируется `ObjectMapper` или используется `ObjectMapper().registerKotlinModule()`? Поиск показал 19 файлов — все покрыты планом?
