# Jackson 3 migration: govern REST wire-format via tools.jackson

**Дата:** 2026-05-26
**Issue:** [#29 — Dual Jackson stack](https://github.com/zinin/frigate-analyzer/issues/29)
**Branch:** `refactor/jackson-3-migration`
**Связанные документы:**
- `docs/issues/2026-05-25-dual-jackson-stack.md` — исходное описание проблемы
- `docs/superpowers/specs/2026-05-25-status-telegram-design.md` § REST — где gap впервые проявился

## 1. Контекст и цель

Сейчас в проекте сосуществуют две Jackson-стека: `com.fasterxml.jackson.*` (Jackson 2, legacy) и `tools.jackson.*` (Jackson 3, новый). `JacksonConfiguration.objectMapper()` объявляет Jackson 2 бин, но Spring Boot 4 WebFlux использует `tools.jackson` для REST codec'ов независимо. Итог: **наш конфиг не управляет wire-format REST endpoint'ов** — ISO-8601 контракт `/status` обеспечивается *дефолтами* `tools.jackson`, а не нашим явным выбором. `JacksonConfigurationTest` тестирует бин в изоляции и ничего не доказывает про реальный wire.

**Цель работы:** закрыть архитектурный gap «конфиг лжёт». После миграции `JacksonConfiguration` действительно управляет wire-format REST endpoint'ов и используется внутренними парсерами. Jackson 2 остаётся **только** как транзитивная зависимость третьих сторон (springdoc-openapi, Spring Boot YAML config loading) — это документируется и изолируется.

**Что НЕ цель работы:**
- Полное удаление Jackson 2 из classpath — невозможно, пока springdoc-openapi 3.0.3 не поддерживает Jackson 3 (`SpringDocJacksonKotlinModuleConfiguration` явно требует `com.fasterxml.jackson.module.kotlin.KotlinModule`)
- Замена springdoc или удаление Swagger UI — отдельная задача
- Изменение wire-format `/status` или контракта detect-сервера — обратная совместимость сохраняется

## 2. Ключевые исследовательские выводы

Подтверждено перед началом работы:

| Вопрос | Ответ |
|---|---|
| `tools.jackson.module:jackson-module-kotlin:3.0.4` существует? | **Да**, управляется BOM Spring Boot 4 (`tools.jackson:jackson-bom:3.0.4`). Содержит `tools.jackson.module.kotlin.registerKotlinModule` и `jacksonObjectMapper()`. Не на classpath по умолчанию — нужно явное объявление. |
| JSR-310 (java.time) в Jackson 3 | **Встроен** в `tools.jackson.databind` 3.x (PR #5032). Отдельный модуль не нужен. |
| `@JsonProperty` annotation в Jackson 3 | Осталась в `com.fasterxml.jackson.annotation` (BOM пинит `jackson-annotations:2.20`). Jackson 3 databind её распознаёт. `JobStatus.kt` и любые DTO с `@JsonProperty` **не требуют изменений**. |
| `JsonProcessingException` в Jackson 3 | Заменён на `tools.jackson.core.JacksonException` (теперь `RuntimeException`). `instanceof JsonProcessingException` на Jackson-3-исключениях даёт `false`. |
| springdoc-openapi 3.0.3 + Spring Boot 4 | Требует Jackson 2 (`com.fasterxml.jackson.module:jackson-module-kotlin`). Spring Boot 4 шипит `spring-boot-jackson2` compat-стартер специально для этого случая. |
| Backward-compat аннотаций Jackson 2 → 3 | Аннотации `com.fasterxml.jackson.annotation.*` работают под Jackson 3. Custom serializers/`ObjectMapper` subclasses — НЕ совместимы (package сменился). |

В main-коде ObjectMapper инжектится только в три файла (`JacksonConfiguration` — бин, `DetectService`, `ClaudeResponseParser`), что делает миграцию малорискованной.

## 3. Целевая архитектура: бин-топология

Два ролевых бина с явным разделением ответственности.

### 3.1. `JacksonConfiguration` (переписан на `tools.jackson`)

```kotlin
@Configuration
class JacksonConfiguration {
    /**
     * Primary tools.jackson (Jackson 3) ObjectMapper used by:
     *  - WebFlux REST codec (inbound/outbound JSON for /status etc.) via [WebFluxJacksonCodecConfigurer]
     *  - DetectService (parses detect-server error-detail bodies)
     *  - ClaudeResponseParser (parses Claude AI agent JSON responses)
     *
     * Settings: camelCase (default), ISO-8601 для Instant/Duration,
     * FAIL_ON_UNKNOWN_PROPERTIES=false, KotlinModule auto-discovered.
     *
     * NOTE: a legacy com.fasterxml.jackson.databind.ObjectMapper bean MAY still be created
     * by springdoc-openapi-starter's auto-configuration (SpringDocJacksonKotlinModuleConfiguration).
     * That bean is OUT OF SCOPE — springdoc owns it for OpenAPI spec generation. See README on
     * dual-stack rationale.
     */
    @Bean
    @Primary
    fun internalObjectMapper(): ObjectMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

Импорты — все `tools.jackson.databind.*`. Метод `findAndAddModules()` подхватит `tools.jackson.module.kotlin` (если артефакт явно объявлен в gradle dependency).

### 3.2. `WebFluxJacksonCodecConfigurer` (новый файл/бин)

```kotlin
@Configuration
class WebFluxJacksonCodecConfigurer(
    private val internalObjectMapper: ObjectMapper,
) : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(internalObjectMapper))
        configurer.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(internalObjectMapper))
    }
}
```

Имя класса `JacksonJsonEncoder`/`JacksonJsonDecoder` — `org.springframework.http.codec.json.JacksonJsonEncoder` (Spring 7, под капотом использует `tools.jackson`). Это **явная** регистрация: если бин удалят, дефолтная регистрация Spring Boot auto-config возьмёт верх, но wire-format регрессирует на дефолты — regression-тест поймает.

Может быть в отдельном файле `WebFluxJacksonCodecConfigurer.kt` или внутри `JacksonConfiguration.kt`. Решение оставлено имплементации (предпочтительнее отдельный файл — single-responsibility).

### 3.3. `WebClientConfiguration` (уточнён)

Существующие inline-сборки `JsonMapper` внутри `jsonEncoder()`/`jsonDecoder()` вынести в отдельный qualified бин:

```kotlin
@Bean
@Qualifier("detectServerObjectMapper")
fun detectServerObjectMapper(): JsonMapper =
    JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

@Bean
fun jsonEncoder(@Qualifier("detectServerObjectMapper") mapper: JsonMapper): JacksonJsonEncoder =
    JacksonJsonEncoder(mapper)

@Bean
fun jsonDecoder(@Qualifier("detectServerObjectMapper") mapper: JsonMapper): JacksonJsonDecoder =
    JacksonJsonDecoder(mapper)
```

`webClient(...)` бин — без изменений. WebClient к detect-серверу продолжает использовать SNAKE_CASE через свои локальные encoder/decoder, не затрагивая `@Primary internalObjectMapper`.

### 3.4. Поток данных wire-format

```
inbound HTTP /status (camelCase JSON, ISO-8601)
   → JacksonJsonDecoder (из internalObjectMapper, наш WebFluxConfigurer)
   → StatusController
   → JacksonJsonEncoder (из internalObjectMapper, наш WebFluxConfigurer)
   → outbound HTTP response (camelCase JSON, ISO-8601)

internal: DetectService.extractFramesRemote (error-detail parse)
   → internalObjectMapper.readTree(body).path("detail")

internal: ClaudeResponseParser.parse (Claude JSON response)
   → internalObjectMapper.readTree(jsonText)

outbound: WebClient к detect-серверу (snake_case JSON)
   → JacksonJsonEncoder/Decoder (из detectServerObjectMapper, SNAKE_CASE)
```

## 4. Изменения по файлам

### 4.1. Main-код

| Файл | Изменение |
|---|---|
| `modules/core/.../config/JacksonConfiguration.kt` | Импорты `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`. Бин переименовать `objectMapper` → `internalObjectMapper`, добавить `@Primary`. KDoc переписать (см. § 3.1). |
| `modules/core/.../config/WebFluxJacksonCodecConfigurer.kt` | **НОВЫЙ ФАЙЛ.** Содержит `@Configuration class WebFluxJacksonCodecConfigurer : WebFluxConfigurer` (см. § 3.2). |
| `modules/core/.../config/WebClientConfiguration.kt` | Вынести inline `JsonMapper` в `@Bean @Qualifier("detectServerObjectMapper")`. Обновить `jsonEncoder`/`jsonDecoder` чтобы принимать qualified бин (см. § 3.3). |
| `modules/core/.../service/DetectService.kt` | `import com.fasterxml.jackson.databind.ObjectMapper` → `import tools.jackson.databind.ObjectMapper`. Использование `objectMapper.readTree(body).path("detail").isTextual`/`asText()` — без изменений (API совместим). |
| `modules/ai-description/.../claude/ClaudeResponseParser.kt` | Импорты `com.fasterxml.jackson.databind.JsonNode`/`ObjectMapper` → `tools.jackson.databind.JsonNode`/`ObjectMapper`. Использование `objectMapper.readTree(jsonText)`, `node["short"]?.asText()` — без изменений. |
| `modules/ai-description/.../claude/ClaudeExceptionMapper.kt` | Расширить catch: оставить `is JsonProcessingException` (Jackson 2, для Claude SDK), добавить `is tools.jackson.core.JacksonException` (Jackson 3). Обе ветки маппят в одно и то же `DescriptionException.InvalidResponse(throwable)`. Можно объединить в Kotlin multi-condition: `is JsonProcessingException, is JacksonException ->`. |
| `modules/model/.../response/JobStatus.kt` | **БЕЗ ИЗМЕНЕНИЙ.** `@JsonProperty` остаётся в `com.fasterxml.jackson.annotation` — это поддерживается обеими стеками. |

### 4.2. Build/dependencies

| Файл | Изменение |
|---|---|
| `gradle/libs.versions.toml` | Добавить алиас: `jackson-kotlin-3 = { module = "tools.jackson.module:jackson-module-kotlin" }`. Версия управляется BOM Spring Boot 4. `bundles.jackson` остаётся как есть. |
| `modules/core/build.gradle.kts` | Добавить `implementation(libs.jackson.kotlin.3)` после существующего `implementation(libs.bundles.jackson)`. Bundle оставить (нужен транзитивно). |
| `modules/ai-description/build.gradle.kts` | Аналогично: добавить `implementation(libs.jackson.kotlin.3)`. |
| `modules/model/build.gradle.kts` | **БЕЗ ИЗМЕНЕНИЙ.** `jackson.annotations` остаётся. |

**Обоснование «bundles.jackson остаётся»:** удаление преждевременно. Jackson 2 нужен транзитивно для:
- springdoc-openapi (явно)
- Spring Boot YAML config loading (`spring-boot-jackson2`)
- Возможных других транзитивов
Cделать «удалить bundle» можно отдельной follow-up задачей после успешной миграции.

### 4.3. Тесты

**Новый файл `modules/core/src/test/.../testsupport/TestObjectMappers.kt`:**
```kotlin
object TestObjectMappers {
    /** Matches production @Primary internalObjectMapper from JacksonConfiguration. */
    fun internalMapper(): ObjectMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
        .findAndAddModules()
        .build()

    /** Matches production detectServerObjectMapper from WebClientConfiguration. */
    fun detectServerMapper(): JsonMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()
}
```

Аналогичный файл создаётся в `modules/ai-description/src/test/.../testsupport/TestObjectMappers.kt` (между модулями нет shared test-source — допустимо дублирование двух фабрик).

**Существующие тесты — рефакторинг:**
- `DetectServiceTest.kt`, `DetectServiceCancelJobTest.kt`: удалить `as FasterxmlObjectMapper` alias, удалить локальные `buildObjectMapper()`/`buildJsonMapper()`, заменить на `TestObjectMappers.internalMapper()`/`detectServerMapper()`.
- `VideoVisualizationServiceTest.kt`: то же самое, плюс удалить локальный `buildObjectMapper()` на line 488.
- `ClaudeResponseParserTest.kt`, `ClaudeDescriptionAgent*Test.kt`, `AiDescriptionAutoConfigurationTest.kt`, `ClaudeExceptionMapperTest.kt`: удалить `import com.fasterxml.jackson.module.kotlin.registerKotlinModule`, заменить локальную `ObjectMapper().registerKotlinModule()` на `TestObjectMappers.internalMapper()`.
- `JacksonConfigurationTest.kt`: переписать — тип бина теперь `tools.jackson.databind.ObjectMapper`. Тесты ISO-8601 для Instant/Duration сохранить (поведение не должно меняться). KDoc обновить: теперь тест проверяет бин, который **и** управляет wire-format.

**Новый файл `WebFluxJacksonCodecConfigurerTest.kt`:**
- Unit-тест (без `@SpringBootTest`): создать инстанс `WebFluxJacksonCodecConfigurer` с тестовым mapper'ом, заспуфить `ServerCodecConfigurer` через MockK, вызвать `configureHttpMessageCodecs`, asserts: `defaultCodecs().jacksonJsonEncoder(...)` был вызван с `JacksonJsonEncoder` использующим переданный mapper.
- Это regression guard: если кто-то удалит configurer-бин, тест провалится; если кто-то поменяет тип encoder'а — тест провалится.

**`StatusControllerTest.kt`:** БЕЗ ИЗМЕНЕНИЙ. Продолжает доказывать ISO-8601 wire-format end-to-end через WebTestClient. После миграции этот тест — живое свидетельство, что наш WebFluxConfigurer действительно подключён.

## 5. Риски и митигация

| Риск | Митигация |
|---|---|
| `tools.jackson` API отличается от `com.fasterxml` для `readTree`/`JsonNode` | Используем только common подмножество (`readTree(String)`, `path(String)`, `isTextual`, `asText()`, `get(String)`, `["key"]`) — есть в обоих, идентичные сигнатуры. Существующие тесты `DetectServiceTest`/`ClaudeResponseParserTest` покрывают парсинг. |
| `tools.jackson.module.kotlin` не подхватится через `findAndAddModules()` | Явно объявить `implementation(libs.jackson.kotlin.3)` в core/ai-description. Добавить в `JacksonConfigurationTest` тест на десериализацию Kotlin data class. |
| `WebFluxConfigurer` не подменит дефолтный codec | `configureHttpMessageCodecs` в Spring WebFlux вызывается после default registration — наши явные регистрации перезапишут default для `application/json`. `WebFluxJacksonCodecConfigurerTest` + `StatusControllerTest` это проверят. |
| Claude SDK пробрасывает Jackson 2 `JsonProcessingException` | `ClaudeExceptionMapper` оставляет ветку Jackson 2 И добавляет Jackson 3. Обе → `DescriptionException.InvalidResponse`. |
| springdoc-openapi пытается заинжектить наш `internalObjectMapper` | springdoc требует `com.fasterxml.jackson.databind.ObjectMapper` (legacy тип) — Spring контейнер не подменит несовместимым типом. springdoc создаст свой бин через `SpringDocJacksonKotlinModuleConfiguration`. |
| Регрессия wire-format `/status` | `StatusControllerTest` (уже существует) проверяет ISO-8601 формат end-to-end через WebTestClient. Должен продолжать проходить без изменений. |

## 6. Rollback plan

- Вся миграция — одна PR в одной feature-ветке `refactor/jackson-3-migration`.
- Если что-то ломается после merge → `git revert <merge-commit>` откатывает всё.
- Мягкий частичный rollback (если падает только wire-format): удалить `WebFluxJacksonCodecConfigurer` бин — wire-format вернётся к auto-config дефолтам `tools.jackson` (то же поведение, что сейчас). Остальная миграция (internal parsers на tools.jackson) остаётся работать.

## 7. Definition of Done

- [ ] Все 5 main-файлов изменены согласно § 4.1 (`JacksonConfiguration`, `WebFluxJacksonCodecConfigurer` new, `WebClientConfiguration`, `DetectService`, `ClaudeResponseParser`, `ClaudeExceptionMapper`)
- [ ] `JobStatus.kt` и `model/build.gradle.kts` НЕ изменены
- [ ] `gradle/libs.versions.toml`: добавлен алиас `jackson-kotlin-3`; `bundles.jackson` остался
- [ ] `modules/core/build.gradle.kts`, `modules/ai-description/build.gradle.kts`: добавлен `implementation(libs.jackson.kotlin.3)`
- [ ] Создан `TestObjectMappers.kt` в core и в ai-description test-source
- [ ] Все существующие тесты рефакторены на `TestObjectMappers` — удалены `as FasterxmlObjectMapper` aliases и локальные `buildObjectMapper`/`buildJsonMapper`/`registerKotlinModule`
- [ ] `JacksonConfigurationTest.kt` переписан под `tools.jackson` тип бина, тесты ISO-8601 сохранены
- [ ] Создан `WebFluxJacksonCodecConfigurerTest.kt` — regression guard на связь mapper ↔ codec
- [ ] `StatusControllerTest.kt` проходит без изменений (wire-format end-to-end)
- [ ] `./gradlew build` зелёный, `./gradlew ktlintCheck` чистый
- [ ] `JacksonConfiguration` KDoc обновлён: больше нет «BEWARE: bean does NOT control wire format»
- [ ] `docs/issues/2026-05-25-dual-jackson-stack.md` помечен как resolved (или удалён, или обновлён ссылкой на PR)
- [ ] GitHub issue #29 закрывается ссылкой на PR
- [ ] CHANGELOG/commit message: `refactor: govern REST wire-format via tools.jackson (closes #29)`

## 8. Out of scope (явно)

- Замена springdoc-openapi на Jackson-3-совместимую альтернативу — отдельная задача, требует upstream-релиза springdoc или альтернативной библиотеки
- Удаление `bundles.jackson` из явных deps — отдельная follow-up задача после успешной миграции (нужно убедиться, что Spring Boot YAML/springdoc не сломаются)
- Изменение SNAKE_CASE контракта detect-сервера — внешний API, не наш контроль
- Миграция Claude SDK на Jackson 3 — внешняя зависимость
