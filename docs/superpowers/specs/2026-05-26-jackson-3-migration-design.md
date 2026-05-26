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
     * Primary tools.jackson (Jackson 3) JSON mapper used by:
     *  - WebFlux REST codec (inbound/outbound JSON for /status etc.) via [WebFluxJacksonCodecConfigurer]
     *  - DetectService (parses detect-server error-detail bodies as raw JsonNode)
     *  - ClaudeResponseParser (parses Claude AI agent JSON responses)
     *
     * Settings: camelCase (default), ISO-8601 для Instant/Duration,
     * FAIL_ON_UNKNOWN_PROPERTIES=false, KotlinModule auto-discovered via ServiceLoader.
     *
     * **Тип возврата — `JsonMapper`, не `ObjectMapper`.** `JsonMapper extends ObjectMapper`,
     * поэтому DI совместима с любыми параметрами `ObjectMapper` (DetectService, ClaudeResponseParser).
     * Spring 7 codec API (`JacksonJsonEncoder(JsonMapper)`, `JacksonJsonDecoder(JsonMapper)`)
     * требует именно `JsonMapper` — поэтому `WebFluxJacksonCodecConfigurer` получает корректный тип.
     *
     * **Dual-stack rationale (self-contained — не зависит от внешних docs):**
     * `tools.jackson` (Jackson 3) управляет всем нашим внутренним и REST wire-форматом JSON.
     * Legacy `com.fasterxml.jackson` остаётся **только** как транзитивная зависимость:
     *  - `springdoc-openapi-starter` 3.0.3 явно требует `com.fasterxml.jackson.module.kotlin.KotlinModule`
     *    (через `SpringDocJacksonKotlinModuleConfiguration`) для генерации OpenAPI spec.
     *  - Spring Boot 4 шипит `spring-boot-jackson2` compat-стартер именно для этого случая.
     * Наш `@Primary tools.jackson.databind.json.JsonMapper` НЕ конфликтует с springdoc:
     * springdoc инжектит `com.fasterxml.jackson.databind.ObjectMapper` (другой класс), Spring
     * не подменяет несовместимым типом.
     */
    @Bean
    @Primary
    fun internalObjectMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

Импорты — `tools.jackson.databind.DeserializationFeature`, `tools.jackson.databind.cfg.DateTimeFeature`, `tools.jackson.databind.json.JsonMapper`. **Важно:** в Jackson 3.0.4 фичи `WRITE_DATES_AS_TIMESTAMPS` и `WRITE_DURATIONS_AS_TIMESTAMPS` перенесены из `SerializationFeature` (Jackson 2 расположение) в `tools.jackson.databind.cfg.DateTimeFeature`. Использование `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` в Jackson 3 кодовой базе не компилируется. Метод `findAndAddModules()` подхватит `tools.jackson.module.kotlin` через ServiceLoader (артефакт `tools.jackson.module:jackson-module-kotlin:3.0.4` шипит `META-INF/services/tools.jackson.databind.JacksonModule`). Тест на round-trip Kotlin data class в `JacksonConfigurationTest` (см. § 4.3) — обязательный regression guard на случай если ServiceLoader-discovery сломается (например, при упаковке fat-jar с агрессивным `mergeServiceFiles`); тест мгновенно покажет проблему в CI до того как она дойдёт до runtime.

**Альтернатива (rejected):** явная регистрация `.addModule(KotlinModule.Builder().build())` была рассмотрена. Отвергнута: создаёт coupling с internal Jackson 3 builder API (молодой, может эволюционировать в минорных версиях), скрывает тот факт что подключение модулей в Jackson 3 — стандартизированный ServiceLoader-contract. Test guard выше — достаточная защита.

### 3.2. `WebFluxJacksonCodecConfigurer` (новый файл/бин)

```kotlin
@Configuration
@Order(Ordered.LOWEST_PRECEDENCE)
class WebFluxJacksonCodecConfigurer(
    private val internalObjectMapper: JsonMapper,
) : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(internalObjectMapper))
        configurer.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(internalObjectMapper))
    }
}
```

Имя класса `JacksonJsonEncoder`/`JacksonJsonDecoder` — `org.springframework.http.codec.json.JacksonJsonEncoder` (Spring 7, под капотом использует `tools.jackson`). Конструктор `JacksonJsonEncoder` принимает только `JsonMapper` (verified via `javap` для spring-web-7.0.5) — параметр должен быть типа `JsonMapper`, не `ObjectMapper`.

**`@Order(Ordered.LOWEST_PRECEDENCE)`** гарантирует, что наш configurer выполняется последним в цепочке `WebFluxConfigurer`'ов: даже если другой configurer (наш собственный в будущем или auto-config Spring Boot) зарегистрирует свой `jacksonJsonEncoder`, наша явная регистрация перезапишет его. Без `@Order` порядок определяется именами beans / порядком обнаружения — слишком хрупко.

Это **явная** регистрация: если бин удалят, дефолтная регистрация Spring Boot auto-config возьмёт верх, но wire-format регрессирует на дефолты — regression-тест поймает (см. § 4.3 про `StatusControllerTest` extension с `FAIL_ON_UNKNOWN_PROPERTIES`).

**Про взаимодействие с Spring Boot 4 auto-config:** мы намеренно НЕ углубляемся в анализ `JacksonAutoConfiguration` / `JacksonCodecCustomizer` source code для Spring Boot 4.0.6, потому что эта реализация меняется между minor releases. Вместо этого используем **behavioural verification**: расширенный `StatusControllerTest` (§ 4.3) проверяет `FAIL_ON_UNKNOWN_PROPERTIES=false` end-to-end. Если auto-config делает то же что и наш configurer — тест проходит обоими путями (наш бин безвреден). Если auto-config делает что-то другое — тест требует именно наш configurer. В обоих случаях `@Order(LOWEST_PRECEDENCE)` гарантирует что наша явная регистрация — последняя и побеждает.

Может быть в отдельном файле `WebFluxJacksonCodecConfigurer.kt` или внутри `JacksonConfiguration.kt`. Решение оставлено имплементации (предпочтительнее отдельный файл — single-responsibility).

### 3.3. `WebClientConfiguration` (уточнён)

Существующие inline-сборки `JsonMapper` внутри `jsonEncoder()`/`jsonDecoder()` вынести в отдельный qualified бин:

```kotlin
@Bean
fun detectServerObjectMapper(): JsonMapper =
    JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .findAndAddModules()
        .build()

@Bean
fun jsonEncoder(@Qualifier("detectServerObjectMapper") mapper: JsonMapper): JacksonJsonEncoder =
    JacksonJsonEncoder(mapper)

@Bean
fun jsonDecoder(@Qualifier("detectServerObjectMapper") mapper: JsonMapper): JacksonJsonDecoder =
    JacksonJsonDecoder(mapper)
```

**Заметка про `.findAndAddModules()`:** detect-server WebClient декодирует Kotlin data class'ы (`DetectResponse`, `JobCreatedResponse`, `FrameExtractionResponse`, `JobStatusResponse`); без KotlinModule конструкторная десериализация для required-параметров без default-значений ломается. `.findAndAddModules()` подхватит `tools.jackson.module.kotlin` через ServiceLoader (тот же артефакт `jackson-kotlin3`, что и для `internalObjectMapper`). Текущий код работает потому что использует `JacksonJsonEncoder(JsonMapper.Builder)`-overload — Spring сам вызывает `findModules()` через `MapperBuilder`-кастомизаторы. Переход на pre-built `JsonMapper` (`.build()`) этот auto-discovery отключает, поэтому `.findAndAddModules()` нужен явно.

**Заметка про `@Qualifier`:** `@Qualifier` ставится **только на injection points** (`jsonEncoder`/`jsonDecoder` параметры), а не на `@Bean`-определение. Имя метода `detectServerObjectMapper` = имя бина = qualifier value по умолчанию, поэтому второй `@Qualifier` на определении избыточен и вводит в заблуждение.

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

**Заметка про DetectService и camelCase:** до миграции тесты `DetectServiceTest`/`VideoVisualizationServiceTest` строили локальный `buildObjectMapper()` с `SNAKE_CASE` стратегией для конструктора `DetectService`. Этот выбор был historic accident — копипаста из соседнего `buildJsonMapper()` для detect-сервера. `DetectService` использует mapper **только** для `objectMapper.readTree(body).path("detail")` — доступ к свойству идёт через явное имя `"detail"` (case-sensitive по тексту JSON-ключа), naming strategy не релевантна. Переход на camelCase `internalObjectMapper` безопасен.

**Заметка про `ClaudeExceptionMapper.is JacksonException` ветку:** в текущей реализации `ClaudeResponseParser.parse()` оборачивает `objectMapper.readTree(...)` в `try { } catch (e: Exception)` и возвращает результат как `Result.failure(DescriptionException.InvalidResponse(e))` — `JacksonException` не доходит до `ClaudeExceptionMapper`. Ветка `is JacksonException` в mapper'е — **defensive code** на случай добавления новых call sites, где `internalObjectMapper` используется напрямую без локального try-catch (например, будущий парсер агрегированных Claude streams). Документируется явно в KDoc метода `map`.

## 4. Изменения по файлам

### 4.1. Main-код

| Файл | Изменение |
|---|---|
| `modules/core/.../config/JacksonConfiguration.kt` | Импорты `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`. Бин переименовать `objectMapper` → `internalObjectMapper`, добавить `@Primary`. **Тип возврата — `JsonMapper`** (Spring 7 codec API требует именно его; `JsonMapper extends ObjectMapper`, поэтому DI с другими консьюмерами не ломается). KDoc переписать с inline dual-stack rationale (см. § 3.1). |
| `modules/core/.../config/WebFluxJacksonCodecConfigurer.kt` | **НОВЫЙ ФАЙЛ.** Содержит `@Configuration @Order(Ordered.LOWEST_PRECEDENCE) class WebFluxJacksonCodecConfigurer(private val internalObjectMapper: JsonMapper) : WebFluxConfigurer` (см. § 3.2). |
| `modules/core/.../config/WebClientConfiguration.kt` | Вынести inline `JsonMapper` в `@Bean @Qualifier("detectServerObjectMapper")`. Обновить `jsonEncoder`/`jsonDecoder` чтобы принимать qualified бин (см. § 3.3). |
| `modules/core/.../service/DetectService.kt` | `import com.fasterxml.jackson.databind.ObjectMapper` → `import tools.jackson.databind.ObjectMapper`. Добавить inline-комментарий перед `private val objectMapper`: «used only for raw JsonNode parsing of detect-server error bodies; naming strategy irrelevant». Использование `objectMapper.readTree(body).path("detail").isTextual`/`asText()` — без изменений (API совместим). Тип параметра остаётся `ObjectMapper` — Spring заинжектит `@Primary JsonMapper` (он extends ObjectMapper). |
| `modules/ai-description/.../claude/ClaudeResponseParser.kt` | Импорты `com.fasterxml.jackson.databind.JsonNode`/`ObjectMapper` → `tools.jackson.databind.JsonNode`/`ObjectMapper`. Использование `objectMapper.readTree(jsonText)`, `node["short"]?.asText()` — без изменений. |
| `modules/ai-description/.../claude/ClaudeExceptionMapper.kt` | Расширить catch: оставить `is JsonProcessingException` (Jackson 2, для Claude SDK), добавить `is tools.jackson.core.JacksonException` (Jackson 3, defensive — см. § 3.4). Обе ветки маппят в одно и то же `DescriptionException.InvalidResponse(throwable)`. Kotlin multi-condition в одну строку: `is JsonProcessingException, is JacksonException -> DescriptionException.InvalidResponse(throwable)`. |
| `modules/core/src/test/.../controller/StatusControllerTest.kt` | **KDoc UPDATE.** Удалить устаревшую ссылку «tools.jackson, NOT our com.fasterxml.jackson-based JacksonConfiguration» — после миграции `JacksonConfiguration` сам управляет codec'ом. Новая формулировка: тест проверяет wire-format end-to-end через `JacksonJsonEncoder` зарегистрированный нашим `WebFluxJacksonCodecConfigurer`. |
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

**Про co-existence Jackson 2 и Jackson 3 в одном classpath:** namespace разный (`com.fasterxml.jackson.*` vs `tools.jackson.*`) — артефакты физически не конфликтуют на JVM уровне. Build verification (поэтапные `./gradlew build` после Tasks 1, 4, 5, 6, 7, 8) — primary способ убедиться что transitive resolution здоровое; реальный конфликт классов вызвал бы immediate compile/runtime failure. Полный formal audit `./gradlew :*:dependencies | grep jackson` — useful follow-up (вместе с CONCERN-3 bundles.jackson cleanup), не блокирует миграцию.

### 4.3. Тесты

**Новый файл `modules/core/src/test/.../testsupport/TestObjectMappers.kt`:**
```kotlin
object TestObjectMappers {
    /** Matches production @Primary internalObjectMapper from JacksonConfiguration. Returns JsonMapper to match production type. */
    fun internalMapper(): JsonMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
        .findAndAddModules()
        .build()

    /** Matches production detectServerObjectMapper from WebClientConfiguration. */
    fun detectServerMapper(): JsonMapper = JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .findAndAddModules()
        .build()
}
```

Аналогичный файл создаётся в `modules/ai-description/src/test/.../testsupport/TestObjectMappers.kt` (между модулями нет shared test-source — допустимо дублирование двух фабрик).

**Альтернатива (rejected):** Gradle `java-test-fixtures` plugin для shared test utilities. Отвергнута: дублирование 5 строк не оправдывает Gradle complexity (новый source set, новый classpath, потенциальные конфликты с MockK/JUnit5 setup). KDoc «Adding a setting in production? Add it here too» + JacksonConfigurationTest на production бине дают достаточную социальную/поведенческую защиту от drift. Если коллекция test utilities вырастет — рефакторинг под test-fixtures можно сделать отдельной задачей с понятным ROI.

**Существующие тесты — рефакторинг:**
- `DetectServiceTest.kt`, `DetectServiceCancelJobTest.kt`: удалить `as FasterxmlObjectMapper` alias, удалить локальные `buildObjectMapper()`/`buildJsonMapper()`, заменить на `TestObjectMappers.internalMapper()`/`detectServerMapper()`.
- `VideoVisualizationServiceTest.kt`: то же самое, плюс удалить локальный `buildObjectMapper()` на line 488. **Внимание:** `buildObjectMapper()` в этом файле возвращает `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2) — после замены вызовов на `TestObjectMappers.internalMapper()` (Jackson 3 `JsonMapper`) функция становится dead code и удаляется целиком.
- `ClaudeResponseParserTest.kt`, `ClaudeDescriptionAgentTest.kt`, `ClaudeDescriptionAgentIntegrationTest.kt` (даже `@Disabled` — Kotlin компилирует disabled-тесты!), `ClaudeExceptionMapperTest.kt`: удалить `import com.fasterxml.jackson.module.kotlin.registerKotlinModule`, заменить локальную `ObjectMapper().registerKotlinModule()` на `TestObjectMappers.internalMapper()`. Убедиться что `TestObjectMappers` импортирован в каждом файле (не полагаться на IDE auto-import).
- `AiDescriptionAutoConfigurationTest.kt`: **special case**. Содержит `@Bean fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()` (Jackson 2 тип возврата!). После миграции `ClaudeResponseParser` инжектится с типом `tools.jackson.databind.ObjectMapper` — Spring не найдёт совместимый бин если оставить Jackson 2 тип. Требуется **изменить и тип возврата бина**, и тело: `@Bean fun objectMapper(): tools.jackson.databind.json.JsonMapper = TestObjectMappers.internalMapper()`.
- `JacksonConfigurationTest.kt`: переписать — тип бина теперь `tools.jackson.databind.json.JsonMapper`. Тесты ISO-8601 для Instant/Duration сохранить. Добавить explicit тесты: (a) data class round-trip через KotlinModule (regression guard для ServiceLoader); (b) `FAIL_ON_UNKNOWN_PROPERTIES=false` — десериализовать JSON с unknown полем; (c) `ApplicationContextRunner`-test: загрузить `JacksonConfiguration` в spring-context, assert что бин типа `tools.jackson.databind.json.JsonMapper` зарегистрирован и помечен `@Primary`. KDoc обновить: теперь тест проверяет бин, который **и** управляет wire-format.

**Новый файл `WebFluxJacksonCodecConfigurerTest.kt`:**
- Unit-тест (без `@SpringBootTest`): создать инстанс `WebFluxJacksonCodecConfigurer` с тестовым mapper'ом, заспуфить `ServerCodecConfigurer` через MockK, вызвать `configureHttpMessageCodecs`, asserts: `defaultCodecs().jacksonJsonEncoder(...)` был вызван с `JacksonJsonEncoder` использующим переданный mapper.
- Это regression guard: если кто-то удалит configurer-бин, тест провалится; если кто-то поменяет тип encoder'а — тест провалится.

**`StatusControllerTest.kt` — расширение под integration regression guard:**
- Текущий тест проверяет только ISO-8601 формат. Это **недостаточно** как guard для нашего configurer'а: default Spring Boot 4 `tools.jackson` mapper тоже сериализует `Instant`/`Duration` в ISO-8601, поэтому удаление `WebFluxJacksonCodecConfigurer` НЕ сломает текущий тест.
- **Добавить новый тестовый случай:** POST/PUT JSON с unknown property на любой REST endpoint (либо `/status` если поддерживает body, либо новый `@WebFluxTest` с тестовым controller'ом). Assert: ответ 200 OK (НЕ 400 Bad Request) — это специфично для нашего `FAIL_ON_UNKNOWN_PROPERTIES=false`, default Spring Boot mapper бы вернул 400. Удаление `WebFluxJacksonCodecConfigurer` сломает этот тест.
- **KDoc UPDATE:** убрать устаревшее «through Spring Boot 4's WebFlux Jackson codec stack (tools.jackson, NOT our com.fasterxml.jackson-based JacksonConfiguration)». Новая формулировка: «тест проверяет wire-format end-to-end через codec'и, зарегистрированные `WebFluxJacksonCodecConfigurer` от нашего `@Primary internalObjectMapper`».

**`ClaudeExceptionMapperTest.kt`:** добавить тестовый случай на `tools.jackson.core.JacksonException` ветку. Использовать concrete subclass — `tools.jackson.core.exc.StreamReadException` — вместо anonymous `object : JacksonException("boom") {}`, чтобы избежать зависимости от protected/abstract конструктора в Jackson 3.0.4. Existing test cases на Jackson 2 `JsonProcessingException` сохранить.

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
- [ ] Тип бина `internalObjectMapper` — `JsonMapper` (не `ObjectMapper`); Spring 7 `JacksonJsonEncoder(JsonMapper)` строго типизирован
- [ ] `WebFluxJacksonCodecConfigurer` помечен `@Order(Ordered.LOWEST_PRECEDENCE)`
- [ ] `JobStatus.kt` и `model/build.gradle.kts` НЕ изменены
- [ ] `gradle/libs.versions.toml`: добавлен алиас `jackson-kotlin-3` с комментарием; `bundles.jackson` остался
- [ ] `modules/core/build.gradle.kts`, `modules/ai-description/build.gradle.kts`: добавлен `implementation(libs.jackson.kotlin.3)`
- [ ] Создан `TestObjectMappers.kt` в core и в ai-description test-source (return type `JsonMapper`)
- [ ] Все существующие тесты рефакторены на `TestObjectMappers` — удалены `as FasterxmlObjectMapper` aliases, локальные `buildObjectMapper`/`buildJsonMapper`/`registerKotlinModule`, и dead-code `buildObjectMapper()` в `VideoVisualizationServiceTest`
- [ ] `AiDescriptionAutoConfigurationTest.kt`: `@Bean` return type изменён на `tools.jackson.databind.json.JsonMapper`, тело — `TestObjectMappers.internalMapper()`
- [ ] `ClaudeDescriptionAgentIntegrationTest.kt:102` (даже под `@Disabled`) тоже мигрирован
- [ ] `JacksonConfigurationTest.kt` переписан: тип бина `tools.jackson.databind.json.JsonMapper`, тесты ISO-8601 сохранены, добавлены тесты: data class round-trip, FAIL_ON_UNKNOWN_PROPERTIES explicit, `ApplicationContextRunner` Spring-context test
- [ ] Создан `WebFluxJacksonCodecConfigurerTest.kt` — regression guard на связь mapper ↔ codec
- [ ] `StatusControllerTest.kt` расширен: добавлен test-case на JSON с unknown property (regression guard на FAIL_ON_UNKNOWN_PROPERTIES). KDoc обновлён — нет устаревших ссылок на «com.fasterxml-based JacksonConfiguration»
- [ ] `ClaudeExceptionMapperTest.kt` использует concrete `tools.jackson.core.exc.StreamReadException` вместо anonymous JacksonException
- [ ] План включает audit step: `grep -rn "ObjectMapper\|registerKotlinModule" --include="*.kt"` по проекту; все use sites зафиксированы как covered/out-of-scope
- [ ] `./gradlew build` зелёный, `./gradlew ktlintCheck` чистый
- [ ] `JacksonConfiguration` KDoc обновлён: inline dual-stack rationale (self-contained, не ссылается на ephemeral docs), explicit `JsonMapper`-type justification, springdoc-изоляция объяснена
- [ ] `DetectService` имеет inline-комментарий про raw `JsonNode` parsing (mapper нужен только для path-access)
- [ ] `docs/issues/2026-05-25-dual-jackson-stack.md` помечен как resolved
- [ ] GitHub issue #29 закрывается ссылкой на PR
- [ ] CHANGELOG/commit message: `refactor: govern REST wire-format via tools.jackson (closes #29)`

## 8. Out of scope (явно)

- Замена springdoc-openapi на Jackson-3-совместимую альтернативу — отдельная задача, требует upstream-релиза springdoc или альтернативной библиотеки
- Удаление `bundles.jackson` из явных deps `modules/core/build.gradle.kts` — отдельная follow-up задача после успешной миграции. Сейчас bundle подключён явно (`jackson-databind`, `jackson-jsr310`, `jackson-kotlin`, `jackson-yaml`); большая часть нужна транзитивно через springdoc + `spring-boot-jackson2` (YAML config loading). Cleanup требует поэтапной проверки что транзитивная связь действительно достаточна — рискованно объединять с текущей миграцией.
- Удаление `implementation(libs.bundles.jackson)` из `modules/ai-description/build.gradle.kts` — потенциально безопасное cleanup (springdoc там не используется), но требует отдельной проверки и тестов. Отложено follow-up'ом.
- Изменение SNAKE_CASE контракта detect-сервера — внешний API, не наш контроль
- Миграция Claude SDK на Jackson 3 — внешняя зависимость
