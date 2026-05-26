# Merged Design Review — Iteration 2

**Date:** 2026-05-26
**Topic:** Jackson 3 migration (issue #29)
**Design:** `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`
**Plan:** `docs/superpowers/plans/2026-05-26-jackson-3-migration.md`
**Prior iterations:** iter-1 (39 issues resolved)

## Agents participating

| Agent | Status | Output |
|---|---|---|
| codex-executor (gpt-5.5, xhigh) | ✅ complete | `/home/zinin/.claude/codex-interaction/2026-05-26-07-37-09-43637-design-review-jackson-3-migration-iter-2-codex/output.txt` |
| ccs-executor (glm) | ✅ complete | `/home/zinin/.claude/ccs-interaction/2026-05-26-07-37-19-43901-design-review-jackson-3-migration-iter-2-ccs-glm/output.txt` |
| ollama-executor (ollama-kimi) | ✅ complete | `/home/zinin/.claude/ollama-interaction/2026-05-26-07-37-23-44029-design-review-jackson-3-migration-iter-2-ollama-kimi/output.txt` |
| ollama-executor (ollama-minimax) | ✅ complete | `/home/zinin/.claude/ollama-interaction/2026-05-26-07-37-20-43945-design-review-jackson-3-migration-iter-2-ollama-minimax/output.txt` |
| ollama-executor (ollama-deepseek) | ✅ complete | `/home/zinin/.claude/ollama-interaction/2026-05-26-07-37-22-43989-design-review-jackson-3-migration-iter-2-ollama-deepseek/output.txt` |

All 5 agents completed successfully (vs. iter-1 where codex and ccs-glm aborted mid-execution).

---

## codex-executor (gpt-5.5, reasoning xhigh)

> Output was in English despite the prompt's "respond in Russian" — content is substantive but language wasn't honored.

### Critical Issues

1. **Jackson 3 date/time flags use the wrong enum.**
   In `tools.jackson.databind.SerializationFeature` 3.0.4 there is no `WRITE_DATES_AS_TIMESTAMPS` or `WRITE_DURATIONS_AS_TIMESTAMPS`. They moved to `tools.jackson.databind.cfg.DateTimeFeature`.
   Affects design and plan snippets in `JacksonConfiguration` and both `TestObjectMappers`. Current snippets will not compile. Use:
   ```kotlin
   import tools.jackson.databind.cfg.DateTimeFeature

   .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
   .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
   ```

2. **Gradle accessor `libs.jackson.kotlin.3` is invalid Kotlin.**
   Alias `jackson-kotlin-3` produces a problematic numeric accessor; the plan inserts:
   ```kotlin
   implementation(libs.jackson.kotlin.3)
   ```
   which is syntactically invalid. Rename the alias to something accessor-safe, e.g. `jackson-kotlin3` → `libs.jackson.kotlin3`, or `jackson-kotlin-v3` → `libs.jackson.kotlin.v3`.

3. **`detectServerObjectMapper` also needs KotlinModule discovery.**
   The detect-server WebClient decodes Kotlin data classes (`DetectResponse`, `JobCreatedResponse`, `FrameExtractionResponse`, `JobStatusResponse`). The planned `detectServerMapper()` / `detectServerObjectMapper()` uses SNAKE_CASE but does not call `.findAndAddModules()`. Without Jackson 3 Kotlin module registration, constructor-based Kotlin DTO deserialization is fragile and likely fails.
   Add `.findAndAddModules()` to both production and test detect-server mappers, and keep the SNAKE_CASE test.

4. **The proposed unknown-property regression test is not valid as written.**
   `/status` is GET-only, so `webTestClient.get().uri("/status")` does not exercise inbound JSON deserialization at all. The clean fix is a test-only POST controller in `StatusControllerTestConfig`, e.g. `/jackson-wire-test`, accepting a Kotlin data class request body with an unknown field.

5. **That same test will not prove `WebFluxJacksonCodecConfigurer` specifically.**
   Spring Boot 4.0.6 already has `CodecsAutoConfiguration.jacksonCodecCustomizer(JsonMapper)`, which wires any `JsonMapper` bean into WebFlux codecs. See Spring Boot source: `CodecsAutoConfiguration` and `WebFluxAutoConfiguration`.
   Therefore removing the custom `WebFluxJacksonCodecConfigurer` may not break the behavior test as long as `internalObjectMapper` remains. Either rely on Boot's `CodecCustomizer` path, or keep the custom configurer but stop claiming the integration test proves its presence.

### Concerns

1. **Custom `JsonMapper` bean bypasses Boot's builder customizers.**
   Defining `internalObjectMapper()` from `JsonMapper.builder()` disables Boot's auto-created `JsonMapper` and skips Boot customizers such as ProblemDetail mixins, `@JacksonComponent` modules, `@JacksonMixin`, and `spring.jackson.*` properties. Spring Boot docs explicitly describe this replacement behavior.
   Safer pattern:
   ```kotlin
   @Bean
   @Primary
   fun internalObjectMapper(builder: JsonMapper.Builder): JsonMapper =
       builder
           .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
           .configure(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, false)
           .configure(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
           .build()
   ```

2. **`ApplicationContextRunner` test overclaims.**
   Loading `JacksonAutoConfiguration` and checking `@Primary` does not prove WebFlux codec selection. It only proves bean resolution. If the plan keeps that test, adjust the comment; WebFlux proof belongs in a reactive web context / `WebTestClient` test.

3. **Audit grep is too narrow.**
   `ObjectMapper|registerKotlinModule` misses `JsonNode`, `JsonMapper`, `JacksonJsonEncoder`, `JsonProcessingException`, `@JsonFormat`, `@JsonDeserialize`, `@JsonProperty`, etc. Use a broader final audit:
   ```bash
   rg -n "com\.fasterxml\.jackson|tools\.jackson|JsonMapper|ObjectMapper|JsonNode|JsonProcessingException|JacksonException|JacksonJson|@Json" modules docker
   ```
   Then classify `JobStatus.@JsonProperty` as intentionally allowed.

### Suggestions

1. Prefer Boot's native `CodecCustomizer` path over a full `WebFluxConfigurer`, or document why the explicit configurer is intentionally redundant. Boot already uses `CodecCustomizer` for Jackson codecs.

2. If keeping the qualified mapper bean, `@Bean("detectServerObjectMapper")` is clearer than relying on method name plus `@Qualifier`, though the current approach can work.

3. Remove or soften line-number references after the plan is edited again. Most are currently close, but they will drift immediately after applying the DateTimeFeature and dependency-alias fixes.

### Questions

1. Do you want `internalObjectMapper` to intentionally ignore all Boot `JsonMapperBuilderCustomizer`s, or should it build from Boot's configured `JsonMapper.Builder`?

2. Is `WebFluxJacksonCodecConfigurer` required as an explicit architectural statement, or is "Boot auto-wires our `JsonMapper` bean into codecs" acceptable as the simpler design?

---

## ccs-executor (glm)

### Critical Issues

**CRITICAL-9: `StatusControllerTest` FAIL_ON_UNKNOWN_PROPERTIES regression guard — тест не тестирует то, что заявлено**

План Task 7 Step 5(b) предлагает:
```kotlin
webTestClient.get().uri("/status").exchange().expectStatus().isOk
```

Это **GET-запрос без body**. `FAIL_ON_UNKNOWN_PROPERTIES` влияет **только на inbound-десериализацию** (JSON → объект). GET-запрос не имеет тела, поэтому настройка **никогда не активируется**. Тест функционально идентичен существующему `GET status returns 200 with expected structure` и добавляет нулевую новую coverage.

Собственный комментарий плана признаёт: *"Default Spring Boot 4 tools.jackson mapper would also tolerate unknowns"*. Но даже если заменить GET на POST (что план упоминает как альтернативу, но не реализует), это **всё равно не сработает** как regression guard — Spring Boot 4 auto-config по умолчанию тоже ставит `FAIL_ON_UNKNOWN_PROPERTIES=false`.

**Суть проблемы:** ни одно из наших настроек (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_DATES_AS_TIMESTAMPS=false`, `WRITE_DURATIONS_AS_TIMESTAMPS=false`, `findAndAddModules()`) **не отличается** от Spring Boot 4 дефолтов. Поведенческий тест не может отличить наш configurer от auto-config. Реальный regression guard — `ApplicationContextRunner` тест из Task 4 Step 2 (проверка `@Primary` bean topology) + `WebFluxJacksonCodecConfigurerTest` (проверка что configurer регистрирует кодеки).

**Рекомендация:** убрать из Task 7 Step 5(b) неработающий FAIL_ON_UNKNOWN_PROPERTIES тест. Вместо него расширить комментарий в KDoc `StatusControllerTest` — указать, что ISO-8601 wire-format тест + `ApplicationContextRunner` topology тест совместно доказывают что наш mapper управляет REST-кодеком. Если в будущем появится настройка, отличающаяся от Spring Boot default — добавить специфичный behavioural тест тогда.

### Concerns

**CONCERN-20: `WebClientConfiguration` передаёт `JsonMapper.Builder` (не `JsonMapper`) в `JacksonJsonEncoder` — javap-верификация из iter-1 неполна**

Текущий рабочий код (`WebClientConfiguration.kt` строки 63-67):
```kotlin
fun jsonEncoder(): JacksonJsonEncoder =
    JacksonJsonEncoder(
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
    ) // <-- нет .build()! Передаётся JsonMapper.Builder
```

Iter-1 (CRITICAL-1) утверждает: *"verified via javap of spring-web-7.0.5.jar: `JacksonJsonEncoder` принимает только `JsonMapper`"*. Но текущий код передаёт `JsonMapper.Builder` и **компилируется**. Значит, либо:
- Существует перегруженный конструктор, принимающий `JsonMapper.Builder` (который javap check пропустил)
- Либо проверялся не тот класс/метод

Это не блокирует миграцию — план в Task 6 добавляет `.build()`, что корректно. Но **заявленное ограничение** *"только JsonMapper"* — неточно. Следует уточнить: `JacksonJsonEncoder` принимает как минимум `JsonMapper` и `JsonMapper.Builder`.

**CONCERN-21: Поведенческая эквивалентность нашего configurer'а и Spring Boot 4 auto-config defaults**

Все настройки нашего `internalObjectMapper`:

| Настройка | Наше значение | Spring Boot 4 default |
|---|---|---|
| `FAIL_ON_UNKNOWN_PROPERTIES` | `false` | `false` |
| `WRITE_DATES_AS_TIMESTAMPS` | `false` | `false` |
| `WRITE_DURATIONS_AS_TIMESTAMPS` | `false` | `false` |
| KotlinModule discovery | `findAndAddModules()` | аналогично через auto-config |

Если ни одна настройка не отличается, configurer **архитектурно ценен** (explicitness, future-proofing, документированное владение wire-format), но поведенческие тесты **не могут доказать его необходимость**. Дизайн § 3.2 уже частично признаёт это ("если auto-config делает то же — наш бин безвреден"), но план всё ещё пытается построить behavioral regression guard (Task 7 Step 5b) на свойстве, которое не дифференцирует.

**Рекомендация:** дополнить дизайн § 3.2 явным параграфом: *"Наши настройки совпадают с Spring Boot 4 defaults. Ценность configurer'а — архитектурная (explicitness), не поведенческая. Regression guard — bean topology test (`ApplicationContextRunner`) + unit test на configurer registration."*

**CONCERN-22: Plan Task 6 "before" code не отмечает отсутствие `.build()` как значимое**

План показывает "before" код как точную копию текущего (без `.build()`), а "after" добавляет `.build()`. Но не объясняет **почему** текущий код работает без `.build()`. Если реализатор увидит несоответствие с утверждением *"только JsonMapper"*, он может запутаться.

**Рекомендация:** добавить в Task 6 Step 1 контекстную заметку: *"Текущий код передаёт `JsonMapper.Builder` (без `.build()`) — это работает благодаря перегруженному конструктору `JacksonJsonEncoder`. Рефакторинг добавляет `.build()` для явности и единообразия с `JacksonConfiguration.internalObjectMapper()`."*

**CONCERN-23: `@Qualifier("detectServerObjectMapper")` на bean definition — избыточно**

Task 6 Step 1:
```kotlin
@Bean
@Qualifier("detectServerObjectMapper")
fun detectServerObjectMapper(): JsonMapper = ...
```

Имя метода = имя бина = `"detectServerObjectMapper"`. `@Qualifier` на определении бина не даёт эффекта — Spring и так свяжет по имени. `@Qualifier` нужен **только** на injection points (`jsonEncoder`, `jsonDecoder`). Наличие его на обоих концах вводит в заблуждение — создаёт впечатление, что qualifier value отличается от bean name.

### Suggestions

**SUGGESTION-6: Упростить Task 7 Step 5 — убрать неработающий FAIL_ON_UNKNOWN_PROPERTIES тест, оставить KDoc update**

Текущий Step 5 содержит:
1. (a) KDoc update — **правильное и ценное изменение**
2. (b) FAIL_ON_UNKNOWN_PROPERTIES тест — **неработающий, удалить**

Вместо (b) — добавить в KDoc явное объяснение regression strategy:
> "ISO-8601 wire-format assertions + `ApplicationContextRunner` topology test = совместно доказывают что наш `@Primary` mapper управляет кодеком. Behavioural equivalence с Spring Boot defaults задокументирована в `JacksonConfiguration` KDoc."

**SUGGESTION-7: Рассмотреть `CodecCustomizer` вместо `WebFluxConfigurer`**

Spring Boot предоставляет `org.springframework.boot.web.reactive.function.client` `CodecCustomizer` — узкоспециализированный интерфейс для настройки кодеков. Он:
- Уже вызывается Spring Boot auto-config в правильной точке
- Не требует `@Order` (управляется через `@AutoConfigureOrder` или `Ordered`)
- Идиоматичен для Spring Boot

Но `WebFluxConfigurer` с `@Order(LOWEST_PRECEDENCE)` тоже работает и даёт больший контроль. Текущий подход допустим, но `CodecCustomizer` был бы проще и более "Spring Boot way". Оставляю на усмотрение автора.

**SUGGESTION-8: Убрать `@Qualifier` с bean definition в Task 6**

```kotlin
// Было (избыточно):
@Bean @Qualifier("detectServerObjectMapper")
fun detectServerObjectMapper(): JsonMapper = ...

// Стало (чище):
@Bean
fun detectServerObjectMapper(): JsonMapper = ...
```

`@Qualifier` оставить только на injection points (`jsonEncoder`, `jsonDecoder`).

### Questions

**QUESTION-13:** Проверялся ли javap **все** перегруженные конструкторы `JacksonJsonEncoder`? Текущий код передаёт `JsonMapper.Builder` и компилируется — значит как минимум один конструктор принимает builder. Утверждение *"только JsonMapper"* из iter-1 CRITICAL-1 требует корректировки.

**QUESTION-14:** Line numbers — план ссылается на конкретные строки: "line 481" для `buildWebClient` в `DetectServiceTest`, "line 488" для `buildObjectMapper` в `VideoVisualizationServiceTest`. Агент-исследователь показал `buildWebClient` ближе к line ~473 в DetectServiceTest. Это мелкие расхождения, но при scripted execution они могут сбить. Рекомендация: перед execution прогнать `grep -n "fun buildWebClient\|fun buildJsonMapper\|fun buildObjectMapper"` и обновить line numbers.

---

## ollama-executor (ollama-kimi)

### Critical Issues

**CRITICAL-NEW-1: `StatusControllerTest` regression guard — stub code в Plan Task 7 Step 5(b) не реализует проверку inbound deserialization**

План предлагает добавить тест на `FAIL_ON_UNKNOWN_PROPERTIES=false` в `StatusControllerTest`, но stub code использует:
```kotlin
webTestClient.get().uri("/status").exchange().expectStatus().isOk
```
`/status` — **GET-only endpoint** (`StatusController.kt:30` — только `@GetMapping`). GET-запрос без body **не может** проверить inbound JSON deserialization с неизвестными свойствами. План правильно отмечает альтернативу («add a tiny `@TestConfiguration`-scoped controller»), но **сам stub code эту альтернативу не реализует**. Если разработчик скопирует stub буквально, regression guard будет false positive — тест пройдёт даже при удалении `WebFluxJacksonCodecConfigurer`, потому что GET без body не затрагивает JSON decoder.

**Как исправить:** заменить stub на полноценный пример — внутренний `@TestConfiguration` класс с POST echo endpoint'ом и `webTestClient.post().bodyValue(...)` с JSON, содержащим unknown property.

### Concerns

**CONCERN-NEW-1: `JacksonJsonEncoder`/`JacksonJsonDecoder` в Spring 7 принимают и `JsonMapper.Builder` — потеря Spring auto-customization**

Design § 3.2 и Plan Task 7 утверждают: «конструктор `JacksonJsonEncoder` принимает только `JsonMapper`». На самом деле Spring 7 предоставляет **две пары** конструкторов: `(JsonMapper)` и `(JsonMapper.Builder)`. При использовании `Builder`-конструктора Spring **автоматически** применяет `ProblemDetailJacksonMixin` и вызывает `MapperBuilder.findModules()`. При использовании pre-built `JsonMapper` — **не применяет**.

Текущий `WebClientConfiguration.kt` уже использует `Builder`-конструктор (именно поэтому там отсутствует `.build()` — это валидный код!). Task 6 и Task 7 переводят на pre-built `JsonMapper`, тихо убирая авто-кастомизацию Spring. Проект сейчас не использует `ProblemDetail` (`grep ProblemDetail` — 0 results), поэтому немедленного breakage нет. Но это поведенческое отличие, которое нужно задокументировать в KDoc `JacksonConfiguration` или в разделе «Out of scope».

**CONCERN-NEW-2: Audit grep в Task 8 Step 1a не покрывает bean name references**

Поиск `ObjectMapper|registerKotlinModule` найдёт use-sites по типу, но **не найдёт** ссылки на имя бина. Имя меняется с `objectMapper` на `internalObjectMapper`. В текущей кодовой базе `getBean("objectMapper")` / `@Qualifier("objectMapper")` отсутствуют (проверено), но audit step должен явно включать:
```bash
grep -rn '"objectMapper"\|@Qualifier("objectMapper")\|getBean("objectMapper")'
```
иначе скрытая зависимость по имени пройдёт мимо.

**CONCERN-NEW-3: `@Configuration` на `WebFluxJacksonCodecConfigurer` — избыточно**

Класс реализует `WebFluxConfigurer`, но **не определяет `@Bean` методов**. `@Configuration` вызывает CGLIB-проксирование без пользы. Достаточно `@Component` (или вообще без стереотипа, если package покрыт `@ComponentScan`). Не критично, но убирает лишний proxy layer и потенциальные edge cases с порядком обработки config-классов.

### Suggestions

**SUGGESTION-NEW-1: Добавить в Plan Task 7 Step 5(b) полный пример `@TestConfiguration` controller'а**

Вместо заглушки с `.get().uri("/status")` показать:
```kotlin
@TestConfiguration
class JacksonToleranceTestConfig {
    @Bean
    fun testController() = object {
        @PostMapping("/test/jackson-tolerance")
        fun post(@RequestBody body: Map<String, Any>) = "ok"
    }
}
```
и тест:
```kotlin
webTestClient.post()
    .uri("/test/jackson-tolerance")
    .bodyValue("""{"known":"x","unknown":"ignored"}""")
    .exchange()
    .expectStatus().isOk
```

**SUGGESTION-NEW-2: Задокументировать `JsonMapper.Builder` vs pre-built `JsonMapper` в Design § 3.2/§ 3.3**

Добавить параграф:
> «Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` также принимают `JsonMapper.Builder`, что позволяет Spring автоматически добавить `ProblemDetailJacksonMixin` и обнаружить модули. Мы сознательно используем pre-built `JsonMapper` для полного контроля над конфигурацией wire-format. Проект не использует `ProblemDetail`, поэтому отсутствие mixin не влияет на текущее поведение.»

**SUGGESTION-NEW-3: Расширить audit grep в Task 8 Step 1a**

Добавить поиск Jackson аннотаций (`@JsonProperty`, `@JsonIgnore`, `@JsonInclude`) и bean name literals (`"objectMapper"`). Это даст полную уверенность, что ничто не упущено.

### Questions

**QUESTION-NEW-1: Создаёт ли `JacksonAutoConfiguration` в Spring Boot 4.0.6 бин типа `JsonMapper` или `ObjectMapper`?**

API говорит, что auto-config имеет `@ConditionalOnClass(tools.jackson.databind.json.JsonMapper.class)`, но неясно, какой **тип** бина регистрируется в контексте — `JsonMapper` или его абстрактный родитель `ObjectMapper`. Если auto-config регистрирует `ObjectMapper`, то `ctx.getBean(JsonMapper::class.java)` в `ApplicationContextRunner` тесте увидит только наш `@Primary` бин, и тест будет проверять тривиальный случай. Если auto-config регистрирует `JsonMapper` — тест корректно валидирует `@Primary` disambiguation. Уточнить, пожалуйста, ожидаемое поведение теста при наличии auto-config бина типа `ObjectMapper`.

**QUESTION-NEW-2: Есть ли `@SpringBootTest` или `WebTestClient` вне `modules/core/src/test/`, которые неявно зависят от `ObjectMapper`?**

Например, модули `service` или `telegram` могут иметь slice-тесты (`@WebFluxTest`, `@SpringBootTest`), где `WebTestClient` или `TestRestTemplate` неявно используют Jackson codec. Текущий audit grep по `ObjectMapper` в `.kt` файлах их не найдёт, но runtime autowiring `tools.jackson.databind.ObjectMapper` вместо `com.fasterxml...ObjectMapper` может сломать тесты на этапе подъёма контекста. Был ли `./gradlew build` в CI на этой ветке после всех правок iter-1?

**QUESTION-NEW-3: Стоит ли `WebFluxJacksonCodecConfigurer` использовать `@Component` вместо `@Configuration`?**

Класс не определяет `@Bean` методов, поэтому `@Configuration` добавляет только CGLIB proxy overhead. Есть ли причина держать `@Configuration` (например, `@Order` на `@Configuration` ведёт себя иначе, чем на `@Component`, при обработке `WebFluxConfigurer` цепочки)? Если нет — замена на `@Component` упростит lifecycle.

---

## ollama-executor (ollama-minimax)

### Critical Issues

**[CRITICAL-NEW-1] `StatusControllerTest` FAIL_ON_UNKNOWN_PROPERTIES regression test — GET-запрос не тестирует десериализацию**

План Task 7 Step 5(b) добавляет regression test для `FAIL_ON_UNKNOWN_PROPERTIES=false`. Текущий KDoc в `StatusControllerTest.kt:20-24` всё ещё содержит старую формулировку про «com.fasterxml.jackson-based JacksonConfiguration», а не про `WebFluxJacksonCodecConfigurer`. Но главная проблема — план предлагает:

```kotlin
// План Task 7 Step 5(b), строки 1049-1053:
webTestClient.get()  // or .post(...).bodyValue(...)
    .uri("/status")
    .exchange()
```

`/status` — **GET-эндпоинт**. GET-запрос **не имеет тела** — ни один вариант (`get()` или `.post().bodyValue(...)`) не тестирует inbound JSON deserialization. Текущий код просто проверяет ISO-8601 (которая одинакова и для дефолтного Spring Boot mapper, и для нашего), и removing `WebFluxJacksonCodecConfigurer` не сломает этот тест — это уже было documented как CRITICAL-7 в iter-1, но исправление неполное.

План говорит (строка 1057): «If `/status` is read-only and cannot accept a JSON body, alternative: add a tiny `@TestConfiguration`-scoped controller in `StatusControllerTest`... then exercise it through the same `WebTestClient`». Но в итоговой редакции теста (строки 1040-1055) этого **нет** — там просто `.get().uri("/status").exchange()` без какой-либо проверки unknown property.

**Фикс:** перед Task 7 Step 5 убедиться, что regression test действительно посылает JSON body. Если `/status` не поддерживает POST — нужен отдельный контроллер внутри теста, как описано в плане, но это **не отражено** в итоговом коде шага.

### Concerns

**[CONCERN-NEW-1] `ClaudeExceptionMapper` multi-pattern branch `is JsonProcessingException, is JacksonException` — разные типы без общего предка**

Текущий `ClaudeExceptionMapper.kt:35-37` ловит только `JsonProcessingException` (Jackson 2). План Task 5 Step 2(b) добавляет `is JacksonException` (Jackson 3) в ту же ветку:

```kotlin
is JsonProcessingException, is JacksonException ->
    DescriptionException.InvalidResponse(throwable)
```

Проверил текущий код `ClaudeExceptionMapper.kt:35` — там только `is JsonProcessingException`. Проблема: в Kotlin's `when` выражении обе ветки должны иметь **общий супертип**, иначе type checking не сработает. В данном случае:
- `com.fasterxml.jackson.core.JsonProcessingException` — Jackson 2
- `tools.jackson.core.JacksonException` — Jackson 3

Это **два разных иерархии** без общего предка. При компиляции Kotlin не позволит объединить их в одну ветку `when` без общего супертипа. Это **не** вопрос Jackson — это вопрос Kotlin type system. branch с двумя несвязанными типами требует either sealed class / common interface, либо two separate `when` branches.

**Вопрос к автору:** планируется ли в Task 5 Step 2(b) изменить `when` на `if/else-if` вместо multi-pattern? Или предполагается создание локального type alias / wrapper? Текущая формулировка плана не учитывает этот technical constraint.

**[CONCERN-NEW-2] KDoc в `StatusControllerTest.kt:20-24` всё ещё ссылается на старую JacksonConfiguration**

Текущий код строк 20-24 содержит: «Spring Boot 4's WebFlux Jackson codec stack (`tools.jackson`, NOT our `com.fasterxml.jackson`-based `JacksonConfiguration`». Это **exactly** то, что план в Task 7 Step 5(a) обещает исправить. Однако план не указывает точно, что весь paragraph (строки 10-24) нужно переписать — а не только KDoc строку. После миграции `JacksonConfiguration` **управляет** codec через `WebFluxJacksonCodecConfigurer`, поэтому формулировка «NOT our com.fasterxml.jackson-based JacksonConfiguration» устарела и вводит в заблуждение.

**Вопрос:** план Task 7 Step 5(a) говорит заменить KDoc строк 20-24. Но полная замена подразумевает переписать весь comment block (строки 10-24) — или достаточно только обновить sentence про "NOT our..."?

**[CONCERN-NEW-3] Line number references в плане могут быть неточными**

План содержит конкретные line references:
- Task 4 Step 4(a): «Remove line 41: `import com.fasterxml.jackson.databind.ObjectMapper as FasterxmlObjectMapper`»
- Task 4 Step 4(d): «Replace line 481 to use TestObjectMappers»
- Task 5 Step 5: «line 102 `val mapper = ObjectMapper().registerKotlinModule()`»

После iter-1 изменений (многие CRITICAL автоисправлены) line numbers могли сместиться. Например, `DetectServiceTest.kt` сейчас содержит импорты `tools.jackson.*` (строки 28-30), alias `FasterxmlObjectMapper` (строка 41), и `buildObjectMapper()` вызов (строка 88). Если iter-1 делал какие-то изменения в этом файле, line numbers могли сдвинуться.

**Вопрос:** перед выполнением Task 4 Steps 4-6 — нужно ли перепроверить актуальные line numbers через IDE или grep, а не полагаться на plan?

### Suggestions

**[SUGGESTION-NEW-1] Добавить явный test для `/status` POST/PUT endpoint**

Проблема CRITICAL-NEW-1 имеет простое решение: добавить в `StatusControllerTest` test-контроллер внутри того же файла (через `@TestConfiguration` внутри companion object или отдельный inner class), который принимает POST с JSON body и возвращает его. Тогда regression test с unknown property действительно проверяет inbound deserialization. Это соответствует advice в плане (строка 1057), но не реализовано.

**[SUGGESTION-NEW-2] Task 8 Step 1a grep нужно расширить на telegram модуль**

Task 8 Step 1a проверяет `grep -rn "ObjectMapper\|registerKotlinModule" modules/` (включая telegram, service, model, etc.). Но после миграции может остаться usage в:
- Telegram module — возможно использует Jackson для Telegram Bot API responses
- Model module — MapStruct mappers могут генерировать Jackson-специфичный код
- Возможные integration test fixtures

Текущий план не включает telegram module в проверку, хотя модуль может содержать Jackson usage.

**[SUGGESTION-NEW-3] В Task 5 Step 2(b) добавить explicit Kotlin type-check workaround**

Для CONCERN-NEW-1: если `when` с multi-pattern типами не работает в Kotlin, альтернатива — использовать `if/else-if`:

```kotlin
is JsonProcessingException -> DescriptionException.InvalidResponse(throwable)
is tools.jackson.core.JacksonException -> DescriptionException.InvalidResponse(throwable)
```

Или сохранить `when` но с `Throwable` и pattern matching по типу:

```kotlin
when {
    throwable is JsonProcessingException || throwable is tools.jackson.core.JacksonException ->
        DescriptionException.InvalidResponse(throwable)
    ...
}
```

Это деталь реализации, но план должен предвидеть этот technical constraint.

### Questions

1. **По CRITICAL-NEW-1:** Регрессионный тест для `FAIL_ON_UNKNOWN_PROPERTIES` — планируется ли добавить отдельный test-контроллер внутри `StatusControllerTest` (как описано в абзаце про "alternative" на строке 1057), или это было признано ненужным?

2. **По CONCERN-NEW-1:** Как планируется обработать Kotlin type-check error при объединении `JsonProcessingException` (Jackson 2) и `JacksonException` (Jackson 3) в одну ветку `when`? Нужен ли explicit workaround в плане?

3. **По CONCERN-NEW-3:** Нужно ли перед Task 4 Steps 4-6 перепроверить line numbers в `DetectServiceTest.kt`, `DetectServiceCancelJobTest.kt`, `VideoVisualizationServiceTest.kt` через grep, или план достаточно точен для execution?

---

## ollama-executor (ollama-deepseek)

### Critical Issues

**CRITICAL-1: Регрессионный тест `FAIL_ON_UNKNOWN_PROPERTIES` в `StatusControllerTest` НЕ проверяет inbound-десериализацию**

Plan Task 7 Step 5(b) предлагает тест:

```kotlin
webTestClient.get()  // or .post(...).bodyValue(...)
    .uri("/status")
    .exchange()
    .expectStatus().isOk
```

`StatusController` имеет **только** `@GetMapping` (файл `StatusController.kt:30`). GET-запрос **не отправляет тело запроса** — `JacksonJsonDecoder` никогда не вызывается для этого запроса. Тест доказывает ровно ноль про `FAIL_ON_UNKNOWN_PROPERTIES` для декодера.

План сам признаёт эту неопределённость («if `/status` is read-only and cannot accept a JSON body, alternative: add a tiny `@TestConfiguration`...») но оставляет её неразрешённой. Альтернатива не специфицирована — нет конкретных шагов реализации, нет кода тестового контроллера.

**Без исправления:** тест проходит всегда, независимо от настройки `FAIL_ON_UNKNOWN_PROPERTIES`. Удаление `WebFluxJacksonCodecConfigurer` не ломает этот тест. Заявленная цель («regression guard на FAIL_ON_UNKNOWN_PROPERTIES через WebFlux codec») не достигается.

**Рекомендация:** Добавить `@WebFluxTest` с минимальным тестовым controller'ом:

```kotlin
@RestController
class EchoController {
    @PostMapping("/test-echo")
    suspend fun echo(@RequestBody body: Map<String, Any?>): Map<String, Any?> = body
}
```

И затем в тесте:
```kotlin
webTestClient.post()
    .uri("/test-echo")
    .bodyValue(mapOf("known" to "x", "unknown" to "ignored"))
    .exchange()
    .expectStatus().isOk
```

Это реально прогоняет JSON через `JacksonJsonDecoder`, зарегистрированный нашим configurer'ом.

**CRITICAL-2: FQN `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration` не верифицирован для Spring Boot 4.0.6**

Plan Task 4 Step 2 импортирует:
```kotlin
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
```

В Spring Boot 3.x этот класс был `org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration`. В Spring Boot 4.x пакеты авто-конфигураций реструктурированы в `org.springframework.boot.<module>.autoconfigure.*`, но:

- Точный FQN для `JacksonAutoConfiguration` в 4.0.6 **не подтверждён** ни в design, ни в plan
- В Spring Boot 4.x некоторые авто-конфигурации были переименованы или слиты с другими
- Если класс не существует по указанному пути — `ApplicationContextRunner` тест не скомпилируется, блокируя Task 4

**Рекомендация:** Перед началом реализации проверить:
```bash
jar tf ~/.gradle/caches/.../spring-boot-autoconfigure-4.0.6.jar | grep -i jacksonauto
```
Если класс не найден — найти актуальный FQN или заменить на `@ImportAutoConfiguration` с строковым именем класса.

### Concerns

**CONCERN-1: `WebFluxJacksonCodecConfigurerTest` не проверяет identity mapper'а**

Тест в Plan Task 7 Step 1 захватывает `encoderSlot`/`decoderSlot` и проверяет `.isNotNull`, но не верифицирует, что `JacksonJsonEncoder`/`JacksonJsonDecoder` были созданы именно с переданным mapper'ом. Баг, при котором `configureHttpMessageCodecs` создаёт новые default-mapper'ы, **пройдёт** этот тест.

Хотя CRITICAL-7 покрывает это через интеграционный тест, unit-тест теряет свою ценность как regression guard. Документировать это ограничение явно в KDoc теста.

**CONCERN-2: `ApplicationContextRunner` тест может тривиально проходить**

Если `JacksonAutoConfiguration` содержит `@ConditionalOnClass`/`@ConditionalOnBean`, не удовлетворённые в тестовом контексте `ApplicationContextRunner`, авто-конфигурация не зарегистрирует свой `JsonMapper` бин. Тогда `ctx.getBean(JsonMapper::class.java)` вернёт **единственный** бин (наш `@Primary`), и тест пройдёт, но **не докажет** сценарий disambiguation с двумя бинами, который он заявляет.

Стоит либо (а) явно задокументировать это ограничение, либо (б) добавить assert что `JacksonAutoConfiguration` действительно зарегистрировала свой бин (через `ctx.getBeansOfType(JsonMapper::class.java).size > 1`), пропуская тест если auto-config не активировалась.

**CONCERN-3: Plan Task 5 Step 4(d) вводит в заблуждение про смену declared type**

Plan говорит: «If the `ObjectMapper` parameter type appears in a constructor or property, switch its declared type to `tools.jackson.databind.ObjectMapper`». Но:

- `ClaudeResponseParser(objectMapper: ObjectMapper)` — параметр остаётся `ObjectMapper` (меняется только import)
- `val responseParser = ClaudeResponseParser(TestObjectMappers.internalMapper())` — Kotlin выводит `JsonMapper` из `internalMapper()` return type, явная аннотация типа не требуется
- Инструкция «switch its declared type» может запутать разработчика — неясно, ЧТО конкретно менять

Формулировку стоит уточнить: «replace the constructor argument with `TestObjectMappers.internalMapper()`; no declared-type change needed — Kotlin infers `JsonMapper`, which extends `ObjectMapper`».

### Suggestions

**SUGGESTION-1 (архитектура): Рассмотреть `CodecCustomizer` вместо `WebFluxConfigurer`**

Plan использует `WebFluxConfigurer.configureHttpMessageCodecs(ServerCodecConfigurer)`. Альтернатива — Spring Boot 4 `JacksonCodecCustomizer`, который позволяет кастомизировать только JSON-кодеки, не затрагивая весь `ServerCodecConfigurer`. Это даёт более узкий scope и меньший риск конфликта с другими configurer'ами.

Однако `JacksonCodecCustomizer` может отсутствовать в Spring Boot 4.0.6 или иметь другую сигнатуру. Это стоит проверить, но не блокирует текущий подход — `WebFluxConfigurer` с `@Order(LOWEST_PRECEDENCE)` корректен.

**SUGGESTION-2 (completeness): Grep-audit в Task 8 Step 1a — добавить поиск `com.fasterxml.jackson.databind`**

Текущий grep ищет `ObjectMapper|registerKotlinModule`. Стоит также искать `com.fasterxml.jackson.databind` — может найти custom serializers/deserializers (унаследованные от Jackson 2 API), которые grep по `ObjectMapper` пропустит. На практике в проекте таких нет, но для полноты audit'а полезно.

**SUGGESTION-3 (completeness): Проверить `spring-boot-jackson2` compat starter в зависимостях**

Design § 4.2 упоминает что `spring-boot-jackson2` compat-стартер «шипится Spring Boot 4 именно для springdoc». Стоит в Task 8 Step 1b (полный build) явно проверить наличие этого стартера в `runtimeClasspath`:

```bash
./gradlew :frigate-analyzer-core:dependencies --configuration runtimeClasspath | grep jackson2
```

Это подтвердит, что Jackson 2 действительно остаётся **только** транзитивно, как заявлено в цели.

**SUGGESTION-4 (minor): Несоответствие design § 4.1 и plan Task 7 Step 5**

Design § 4.1 в таблице указывает для `StatusControllerTest.kt` только «KDoc UPDATE». Но Plan Task 7 Step 5(b) добавляет ещё и **новый тестовый метод** (регрессия на `FAIL_ON_UNKNOWN_PROPERTIES`). Design-документ не обновлён. Стоит синхронизировать.

### Questions

Вопросов нет — все неясности покрыты критическими замечаниями и предложениями выше.

---

## Summary by reviewer

| Agent | Critical | Concerns | Suggestions | Questions |
|---|---|---|---|---|
| codex (gpt-5.5/xhigh) | 5 | 3 | 3 | 2 |
| ccs-glm | 1 | 4 | 3 | 2 |
| ollama-kimi | 1 | 3 | 3 | 3 |
| ollama-minimax | 1 | 3 | 3 | 3 |
| ollama-deepseek | 2 | 3 | 4 | 0 |
| **Total** | **10** | **16** | **16** | **10** |

## Strongest consensus (highest-priority)

1. **FAIL_ON_UNKNOWN_PROPERTIES regression test через `/status` GET не работает** — все 5 ревьюеров (codex Critical #4, ccs-glm CRITICAL-9, ollama-kimi CRITICAL-NEW-1, ollama-minimax CRITICAL-NEW-1, ollama-deepseek CRITICAL-1)
2. **`CodecCustomizer` вместо `WebFluxConfigurer`** — 3 ревьюера (codex, ccs-glm, deepseek)
3. **`JsonMapper.Builder` тоже работает в codec constructors** — 2 ревьюера (ccs-glm CONCERN-20, ollama-kimi CONCERN-NEW-1) — противоречит iter-1 CRITICAL-1 утверждению «только JsonMapper»
4. **Audit grep слишком узкий** — 4 ревьюера (codex Concern #3, ollama-kimi CONCERN-NEW-2/SUGGESTION-NEW-3, ollama-minimax SUGGESTION-NEW-2, ollama-deepseek SUGGESTION-2)
5. **Line numbers могут быть неточными** — 3 ревьюера (codex Suggestion #3, ccs-glm Q14, ollama-minimax CONCERN-NEW-3)

## Unique critical/blocking issues

- **codex Critical #1:** `WRITE_DATES_AS_TIMESTAMPS`/`WRITE_DURATIONS_AS_TIMESTAMPS` находятся в `tools.jackson.databind.cfg.DateTimeFeature`, не в `SerializationFeature` — текущие snippets не компилируются
- **codex Critical #2:** `libs.jackson.kotlin.3` — invalid Kotlin accessor (numeric trailing component) — не компилируется
- **codex Critical #3:** `detectServerObjectMapper` без `findAndAddModules()` — fragile/broken Kotlin DTO deserialization
- **deepseek CRITICAL-2:** FQN `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration` не верифицирован для Spring Boot 4.0.6
- **minimax CONCERN-NEW-1:** Kotlin multi-pattern `is A, is B` с типами из разных иерархий может не компилироваться
