# Jackson 3 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate internal Jackson usage from `com.fasterxml` (Jackson 2) to `tools.jackson` (Jackson 3) and explicitly wire the @Primary `JsonMapper` into the WebFlux REST codec, so `JacksonConfiguration` truly governs wire-format (closes #29).

**Architecture:** Two role-based `JsonMapper` beans — `@Primary internalObjectMapper` (tools.jackson `JsonMapper`, camelCase, ISO-8601; used by REST codec + DetectService + ClaudeResponseParser) and `@Qualifier("detectServerObjectMapper")` (tools.jackson `JsonMapper`, SNAKE_CASE; used only by outbound WebClient to detect-server). New `WebFluxJacksonCodecConfigurer` (with `@Order(Ordered.LOWEST_PRECEDENCE)`) explicitly registers Jackson codecs built from the @Primary mapper. Jackson 2 remains as transitive only (springdoc-openapi, Spring Boot YAML config).

**Critical type note:** Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` constructors accept **only** `tools.jackson.databind.json.JsonMapper`, not `ObjectMapper`. Therefore the `internalObjectMapper` bean returns `JsonMapper`. Since `JsonMapper extends ObjectMapper`, DI into consumers declaring `ObjectMapper` parameter (DetectService, ClaudeResponseParser) still works transparently.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.0.6, WebFlux, tools.jackson 3.0.4, JUnit5, MockK 1.14.9

**Spec:** `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`

**Про line numbers в плане:** ссылки вида «line 88» / «line 481» — snapshot на момент написания плана. После применения auto-fix'ов или промежуточных commit'ов номера сдвигаются. **Перед каждым edit'ом в Tasks 4/5** запускать `grep -n "<уникальный текст рядом с целью>" <file>` для подтверждения актуальной позиции. Цели для поиска (use as `grep -n` queries):
- `DetectServiceTest.kt`: `"ObjectMapper as FasterxmlObjectMapper"`, `"fun buildObjectMapper"`, `"fun buildJsonMapper"`, `"fun buildWebClient"`, `"DetectService(webClient, loadBalancer"`
- `DetectServiceCancelJobTest.kt`: те же patterns + `"jacksonJsonDecoder(JacksonJsonDecoder(buildJsonMapper"`
- `VideoVisualizationServiceTest.kt`: `"import com.fasterxml.jackson.databind.ObjectMapper"`, `"fun buildObjectMapper"`, `"fun buildJsonMapper"`
- `ClaudeDescriptionAgentIntegrationTest.kt`: `"val mapper = ObjectMapper().registerKotlinModule"`
- `AiDescriptionAutoConfigurationTest.kt`: `"fun objectMapper(): ObjectMapper"`

Pattern-based pointers устойчивее к line-number drift, чем абсолютные позиции.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | Add `jackson-kotlin-3` alias |
| `modules/core/build.gradle.kts` | Modify | Add `jackson-kotlin-3` dep |
| `modules/ai-description/build.gradle.kts` | Modify | Add `jackson-kotlin-3` dep |
| `modules/core/.../config/JacksonConfiguration.kt` | Modify | Rewrite on tools.jackson; `@Primary internalObjectMapper` bean |
| `modules/core/.../config/WebFluxJacksonCodecConfigurer.kt` | Create | New WebFluxConfigurer wiring @Primary mapper into REST codec |
| `modules/core/.../config/WebClientConfiguration.kt` | Modify | Extract qualified `detectServerObjectMapper` bean |
| `modules/core/.../service/DetectService.kt` | Modify | Switch `ObjectMapper` import to tools.jackson |
| `modules/ai-description/.../claude/ClaudeResponseParser.kt` | Modify | Switch `ObjectMapper`/`JsonNode` imports to tools.jackson |
| `modules/ai-description/.../claude/ClaudeExceptionMapper.kt` | Modify | Catch both Jackson 2 and Jackson 3 exceptions |
| `modules/core/src/test/.../testsupport/TestObjectMappers.kt` | Create | Shared factories matching production mappers |
| `modules/ai-description/src/test/.../testsupport/TestObjectMappers.kt` | Create | Same, for ai-description module |
| `modules/core/.../config/JacksonConfigurationTest.kt` | Modify | Adjust to tools.jackson + add KotlinModule verification |
| `modules/core/.../config/WebFluxJacksonCodecConfigurerTest.kt` | Create | Regression guard: configurer wires codec from our mapper |
| `modules/core/.../service/DetectServiceTest.kt` | Modify | Use TestObjectMappers, drop `FasterxmlObjectMapper` alias |
| `modules/core/.../service/DetectServiceCancelJobTest.kt` | Modify | Same |
| `modules/core/.../service/VideoVisualizationServiceTest.kt` | Modify | Same |
| `modules/ai-description/.../claude/ClaudeResponseParserTest.kt` | Modify | Use TestObjectMappers |
| `modules/ai-description/.../claude/ClaudeDescriptionAgentTest.kt` | Modify | Same |
| `modules/ai-description/.../claude/ClaudeDescriptionAgentIntegrationTest.kt` | Modify | Same |
| `modules/ai-description/.../claude/ClaudeExceptionMapperTest.kt` | Modify | Same |
| `modules/ai-description/.../config/AiDescriptionAutoConfigurationTest.kt` | Modify | Same |
| `docs/issues/2026-05-25-dual-jackson-stack.md` | Modify | Mark resolved with link to PR |

---

## Task 1: Add Jackson 3 Kotlin module dependency

✅ Done — see commit `31c0701`.

---

## Task 2: Create `TestObjectMappers` utility in core module

✅ Done — see commit `9299943`.

---

## Task 3: Create `TestObjectMappers` utility in ai-description module

✅ Done — see commit `4750607`.

---

## Task 4: Migrate core module Jackson stack to tools.jackson (atomic)

✅ Done — see commit `6baf287`.

---

## Task 5: Migrate ai-description module to tools.jackson (atomic)

✅ Done — see commit `87fd8d5`.

---

## Task 6: Refactor `WebClientConfiguration` to qualified `detectServerObjectMapper` bean

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt`

- [ ] **Step 1: Refactor bean wiring**

Edit `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt`. Add import for `Qualifier`:

```kotlin
import org.springframework.beans.factory.annotation.Qualifier
```

Then replace the inline `JsonMapper.builder()...` calls inside `jsonEncoder()` and `jsonDecoder()` with a separate `@Bean`-method.

**Context — почему текущий код без `.build()` компилируется:** Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` имеют перегруженные конструкторы — принимают **и** `JsonMapper`, **и** `JsonMapper.Builder`. Текущий код передаёт `JsonMapper.Builder` (без `.build()`) и работает через `JacksonJsonEncoder(JsonMapper.Builder)`-overload. При этом Spring auto-вызывает `MapperBuilder.findModules()` и применяет `JsonMapperBuilderCustomizer`-ы (ProblemDetail mixin и пр.). Переход на pre-built `JsonMapper.build()` — **осознанный trade-off:** теряем auto-customization, выигрываем явный контроль над wire-format. Проект не использует `ProblemDetail` (`grep ProblemDetail` = 0 hits), поэтому отсутствие mixin не влияет на текущее поведение.

Replace the block (lines 62-78):

```kotlin
    @Bean
    fun jsonEncoder(): JacksonJsonEncoder =
        JacksonJsonEncoder(
            JsonMapper
                .builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
        )

    @Bean
    fun jsonDecoder(): JacksonJsonDecoder =
        JacksonJsonDecoder(
            JsonMapper
                .builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
        )
```

With:

```kotlin
    /**
     * SNAKE_CASE mapper used **only** for outbound JSON to the detect-server (whose API uses
     * snake_case). Separate from the project's `@Primary` `internalObjectMapper` (camelCase),
     * which governs our own REST wire-format.
     *
     * `.findAndAddModules()` — обязателен: detect-server decoder парсит Kotlin data class'ы
     * (`DetectResponse`, `JobCreatedResponse`, `FrameExtractionResponse`, `JobStatusResponse`).
     * Без `KotlinModule` constructor-based десериализация для required-параметров ломается.
     * Текущий `Builder`-overload справлялся за счёт Spring auto-вызова `findModules()`; при
     * переходе на pre-built `.build()` мы должны явно вызвать `.findAndAddModules()`.
     */
    @Bean
    fun detectServerObjectMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndAddModules()
            .build()

    @Bean
    fun jsonEncoder(
        @Qualifier("detectServerObjectMapper") mapper: JsonMapper,
    ): JacksonJsonEncoder = JacksonJsonEncoder(mapper)

    @Bean
    fun jsonDecoder(
        @Qualifier("detectServerObjectMapper") mapper: JsonMapper,
    ): JacksonJsonDecoder = JacksonJsonDecoder(mapper)
```

**`@Qualifier` placement note:** аннотация `@Qualifier` ставится **только на injection points** (`jsonEncoder`/`jsonDecoder` параметры), **не** на `@Bean`-определение. Имя метода `detectServerObjectMapper` = имя бина по умолчанию, поэтому `@Qualifier` на самом `@Bean` избыточен и вводит в заблуждение (создаёт впечатление, что qualifier value отличается от bean name).

- [ ] **Step 2: Verify single mapper instance per WebClient**

After the refactor, confirm that `jsonEncoder()` and `jsonDecoder()` accept the `detectServerObjectMapper` bean via parameter injection and do NOT construct fresh `JsonMapper.builder()...build()` instances inside their bodies. The bean is constructed once; both encoder and decoder reuse the same instance.

Quick verification: `grep -A2 "fun jsonEncoder\|fun jsonDecoder" modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt` — bodies should only contain `JacksonJsonEncoder(mapper)` / `JacksonJsonDecoder(mapper)`.

- [ ] **Step 3: Run all core tests**

Run: `./gradlew :frigate-analyzer-core:test`

Expected: BUILD SUCCESSFUL. The refactor preserves behaviour — `JacksonJsonEncoder(mapper)` accepts a built `JsonMapper`; tests that go through the WebClient should pass unchanged.

- [ ] **Step 4: ktlint**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`

Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt
git commit -m "refactor(core): extract qualified detectServerObjectMapper bean from WebClientConfiguration"
```

---

## Task 7: Create `WebFluxJacksonCodecConfigurer` (TDD)

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurer.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurerTest.kt`

- [ ] **Step 1: Write the failing test FIRST**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers

class WebFluxJacksonCodecConfigurerTest {
    @Test
    fun `configureHttpMessageCodecs registers Jackson encoder and decoder built from our mapper`() {
        val mapper = TestObjectMappers.internalMapper()
        val configurer = WebFluxJacksonCodecConfigurer(mapper)

        val serverCodecConfigurer = mockk<ServerCodecConfigurer>(relaxed = true)
        val defaultCodecs = mockk<ServerCodecConfigurer.ServerDefaultCodecs>(relaxed = true)
        every { serverCodecConfigurer.defaultCodecs() } returns defaultCodecs

        val encoderSlot = slot<JacksonJsonEncoder>()
        val decoderSlot = slot<JacksonJsonDecoder>()
        every { defaultCodecs.jacksonJsonEncoder(capture(encoderSlot)) } just Runs
        every { defaultCodecs.jacksonJsonDecoder(capture(decoderSlot)) } just Runs

        configurer.configureHttpMessageCodecs(serverCodecConfigurer)

        verify(exactly = 1) { defaultCodecs.jacksonJsonEncoder(any()) }
        verify(exactly = 1) { defaultCodecs.jacksonJsonDecoder(any()) }
        assertThat(encoderSlot.captured).isNotNull
        assertThat(decoderSlot.captured).isNotNull

        // IDENTITY GUARD: убедиться что codec'ы построены ИМЕННО на нашем mapper'е, а не
        // на дефолтных. Без этого тест прошёл бы при баге, где configureHttpMessageCodecs
        // создаёт новые JsonMapper.builder().build() вместо использования параметра.
        // Достаём mapper через reflection из private поля JacksonJsonEncoder.mapper (Spring 7
        // не предоставляет publicного getter'а).
        val encoderMapperField = encoderSlot.captured.javaClass.superclass
            .getDeclaredField("mapper").apply { isAccessible = true }
        assertThat(encoderMapperField.get(encoderSlot.captured)).isSameAs(mapper)

        val decoderMapperField = decoderSlot.captured.javaClass.superclass
            .getDeclaredField("mapper").apply { isAccessible = true }
        assertThat(decoderMapperField.get(decoderSlot.captured)).isSameAs(mapper)
    }
}
```

**Reflection note:** Spring 7 `AbstractJacksonHttpMessageWriter`/`Reader` хранит `mapper` в protected/private поле без публичного getter'а; identity check через reflection — единственный способ доказать что codec построен на нашем bean'е, а не на дефолтном. Если в будущем Spring добавит публичный `getMapper()` — заменить на прямой вызов. Альтернатива (десериализовать тестовый JSON через encoder/decoder и убедиться что наши настройки применились) — слишком хрупка и пересекается с интеграционным тестом.

- [ ] **Step 2: Run test to verify it fails (file doesn't exist)**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.WebFluxJacksonCodecConfigurerTest'`

Expected: Compilation failure — `unresolved reference: WebFluxJacksonCodecConfigurer`.

- [ ] **Step 3: Implement the configurer**

Create `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.config.WebFluxConfigurer
import tools.jackson.databind.json.JsonMapper

/**
 * Wires the project's `@Primary internalObjectMapper` (configured in [JacksonConfiguration])
 * into WebFlux's REST JSON codec.
 *
 * **Honest narrative про duplication:** Spring Boot 4's `CodecsAutoConfiguration.jacksonCodecCustomizer`
 * автоматически wire'ит любой `JsonMapper` бин (включая наш `@Primary`) в WebFlux codec через
 * `CodecCustomizer`. Наш configurer **функционально дублирует** эту логику. Поведение совпадает,
 * поэтому behavioral test (запрос → отвечает с ISO-8601 / толерантно к unknown props) пройдёт
 * обоими путями — это НЕ regression guard на конкретно наш configurer.
 *
 * **Ценность configurer'а — архитектурная, не поведенческая:**
 *  - Explicit ownership statement (closes #29 требует «config truly governs wire-format»).
 *  - Belt-and-suspenders: `@Order(Ordered.LOWEST_PRECEDENCE)` страхует если Boot's auto-config
 *    изменит wiring в minor-release.
 *  - Документирует ownership wire-format в одном явном файле.
 *
 * **Regression guard** — `WebFluxJacksonCodecConfigurerTest` (unit, reflection identity check —
 * codec'ы построены на нашем mapper'е) + `JacksonConfigurationTest.ApplicationContextRunner`
 * (bean topology с auto-config).
 *
 * **`@Component` вместо `@Configuration`:** класс не объявляет `@Bean`-методов, поэтому
 * `@Configuration` (CGLIB-proxy + full config scanning) — overhead без выигрыша.
 *
 * **Constructor parameter `JsonMapper`** (не `ObjectMapper`): Spring 7 codec API принимает
 * `JsonMapper` или `JsonMapper.Builder`; мы передаём pre-built `JsonMapper` для явного
 * контроля над wire-format (теряя Boot's `JsonMapperBuilderCustomizer`-ы — осознанный
 * trade-off, см. [JacksonConfiguration] KDoc).
 */
@Component
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.WebFluxJacksonCodecConfigurerTest'`

Expected: 1 test passes.

- [ ] **Step 5: Update `StatusControllerTest` file-level comment (без нового behavioral test)**

Edit `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt`:

(a) Цель — file-level **line-comments** (`//`) в позиции lines 10-24 (перед `@AutoConfigureWebTestClient`). **Не KDoc** (`/** */`) — оригинал в этом файле использует line-comment стиль, сохраняем для consistency.

**Перед заменой** прогнать `grep -n "tools.jackson, NOT our" modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt` чтобы подтвердить актуальную позицию устаревшего фрагмента (после master merge от PR #31 lines в этой части файла не сдвинулись, но pattern-based проверка надёжнее).

Целевой фрагмент для замены (текущий текст файла lines ~20-24):

```kotlin
// The wire-format test below is the only assertion that actually verifies the
// `/status` JSON contract end-to-end through Spring Boot 4's WebFlux Jackson codec
// stack (`tools.jackson`, NOT our `com.fasterxml.jackson`-based `JacksonConfiguration`
// — see KDoc on that class for the architectural caveat). `JacksonConfigurationTest`
// only proves the standalone mapper bean works; it does NOT prove WebFlux uses it.
```

Заменить на:

```kotlin
// The wire-format test below is an end-to-end sanity check для `/status`:
// ISO-8601 timestamps + ожидаемая структура JSON-ответа. После Jackson 3 migration
// (issue #29) запрос идёт через codecs, зарегистрированные `WebFluxJacksonCodecConfigurer`
// от нашего `@Primary internalObjectMapper`.
//
// Важно: этот тест НЕ доказывает что наш `WebFluxJacksonCodecConfigurer` фактически
// управляет codec'ом. Spring Boot 4 `CodecsAutoConfiguration.jacksonCodecCustomizer`
// автоматически wire'ит `@Primary JsonMapper` бин в WebFlux codec — все наши настройки
// совпадают с Boot 4 defaults, поэтому удаление нашего configurer'а не сломает ни этот
// тест, ни поведение `/status`. Это honest sanity check, не regression guard.
//
// Реальный regression guard configurer'а:
//  - `WebFluxJacksonCodecConfigurerTest` — unit test с reflection identity check:
//    codec'ы построены ИМЕННО на нашем mapper'е.
//  - `JacksonConfigurationTest` — `ApplicationContextRunner` с auto-config: `@Primary`
//    disambiguation в bean topology.
//
// См. design § 3.2 «Builder vs pre-built — осознанный trade-off» и «Про взаимодействие
// с Spring Boot 4 auto-config — honest narrative» для полного объяснения.
```

Lines 10-18 (`Note: IntegrationTestBase spins up Docker Compose ...`) и `StatusControllerTestConfig injects a mocked SignalLossMonitorTask ...` — **оставить без изменений**: они объясняют test setup, ортогональны Jackson scope.

**Контекст про master merge (PR #31, 2026-05-26):** test body (`jsonPath` assertions, lines ~50-53) теперь содержит две дополнительные проверки `$.recordings.success.isNumber` и `$.recordings.errors.isNumber` — это работа PR #31 и **не задевает** ни Jackson migration scope, ни целевой comment-блок выше. Замена comment'а сверху не пересекается с этими assertions.

(b) **НЕ добавлять** FAIL_ON_UNKNOWN_PROPERTIES regression test. Reason (consensus всех 5 ревьюеров iter-2):
- `/status` GET-only — `JacksonJsonDecoder` не вызывается на GET без body, behavioral assertion невозможна
- Даже с `@TestConfiguration` POST echo controller'ом — Spring Boot 4 default тоже `FAIL_ON_UNKNOWN_PROPERTIES=false` (verified), поэтому тест проходит обоими путями и не дифференцирует наш configurer
- Все наши настройки совпадают с Boot defaults → behavioral regression guard на configurer принципиально невозможен (см. design § 3.2 honest narrative)

Real regression coverage достигнута через NEW-17 fix (codec identity reflection в `WebFluxJacksonCodecConfigurerTest`) + NEW-14 fix (ApplicationContextRunner strengthening в `JacksonConfigurationTest`).

- [ ] **Step 6: Run full core test suite (verify StatusControllerTest still passes — sanity check end-to-end wire-format)**

Run: `./gradlew :frigate-analyzer-core:test`

Expected: BUILD SUCCESSFUL. `StatusControllerTest` continues to assert ISO-8601 timestamps + JSON структуру `/status` (включая поля `$.recordings.success`/`$.recordings.errors` добавленные PR #31 в master). Test проходит через WebFlux codec; см. (a) — это sanity check, не regression guard на наш configurer.

- [ ] **Step 7: ktlint**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`

Expected: clean.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurer.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurerTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt
git commit -m "feat(core): explicitly wire @Primary JsonMapper into WebFlux REST codec"
```

---

## Task 8: Full-project verification and documentation cleanup

**Files:**
- Modify: `docs/issues/2026-05-25-dual-jackson-stack.md`

- [ ] **Step 1a: Audit all Jackson use sites (safety net before final build)**

Запустить расширенный grep (узкое `ObjectMapper|registerKotlinModule` пропускает множество кейсов — Jackson аннотации, bean-name literals, типы из `tools.jackson.*`, custom serializer'ы):

```bash
# Тип- и сигнатуро-ориентированный audit
grep -rEn "com\.fasterxml\.jackson|tools\.jackson|JsonMapper|ObjectMapper|JsonNode|JsonProcessingException|JacksonException|JacksonJson|@Json[A-Z]" \
    --include="*.kt" --include="*.java" \
    modules/ docker/ | grep -v build/ | grep -v ".gradle/"

# Bean-name literal references (на случай скрытой зависимости по имени)
grep -rEn '"objectMapper"|@Qualifier\("objectMapper"\)|getBean\("objectMapper"\)' \
    --include="*.kt" --include="*.java" \
    modules/ | grep -v build/

# Compat starter sanity: убедиться что Jackson 2 остаётся ТОЛЬКО транзитивно
./gradlew :frigate-analyzer-core:dependencies --configuration runtimeClasspath \
    | grep -E "spring-boot-jackson2|com.fasterxml.jackson"
```

Cross-check every result against the plan's covered files:
- `JacksonConfiguration.kt`, `WebFluxJacksonCodecConfigurer.kt`, `WebClientConfiguration.kt`, `DetectService.kt`, `ClaudeResponseParser.kt`, `ClaudeExceptionMapper.kt`
- All tests under `modules/core/src/test/` and `modules/ai-description/src/test/` touched by Tasks 4 and 5
- `TestObjectMappers.kt` (×2)
- **`JobStatus.kt` `@JsonProperty`** — намеренно остаётся на Jackson 2 (`com.fasterxml.jackson.annotation`), pinned BOM 2.20, работает с обоими stacks. Классифицировать как «intentionally allowed».

**Модули для явной проверки (audit grep охватывает `modules/`, но reviewer'ы iter-2 настаивали на explicit mention):**
- `modules/telegram/` — может использовать Jackson для Bot API responses через ktgbotapi или собственные helpers
- `modules/service/` — MapStruct mapper'ы могут генерировать Jackson-зависимый код
- `modules/model/` — DTO с `@JsonProperty` (JobStatus.kt известно)
- `modules/common/`, `modules/ai-description/` — все Jackson use-sites
- `docker/liquibase/` — JSON-changelog'и (хотя они идут через Liquibase parser, не наш mapper)

If grep returns a use site NOT in the above list, STOP and add it explicitly to the plan before continuing. Common omissions to look for: MapStruct mapper interfaces, Liquibase changelog Java/Kotlin classes, telegram module helpers, slice/integration tests с неявным `WebTestClient` (codec wiring через autowiring `ObjectMapper`).

Expected universe: ~19 files (per design § 4.1 and existing grep before migration). Verify the count.

- [ ] **Step 1b: Full build with all tests**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL on all modules. If anything fails, STOP and investigate — this is the integration check that all module-level migrations compose correctly.

Дополнительно после успешного build'а — verify dual-stack topology:
```bash
./gradlew :frigate-analyzer-core:dependencies --configuration runtimeClasspath \
    | grep -E "spring-boot-jackson|com.fasterxml.jackson"
```

Ожидается:
- `spring-boot-jackson-4.0.x` (Jackson 3 — наш primary, autoconfig'и для `JsonMapper`)
- `spring-boot-jackson2-4.0.x` (Jackson 2 compat starter — нужен для springdoc-openapi YAML config loading)
- `com.fasterxml.jackson.*-2.x` присутствует **только** через transitive dependencies springdoc-openapi и `spring-boot-jackson2`. Подтверждает что миграция достигла цели: Jackson 2 — только транзитивно.

- [ ] **Step 2: Mark dual-stack issue resolved**

Edit `docs/issues/2026-05-25-dual-jackson-stack.md`. Prepend at the top (after the title):

```markdown
> **STATUS: RESOLVED** (2026-05-26) — closed by PR for #29. Internal Jackson usage migrated to `tools.jackson` (Jackson 3). `JacksonConfiguration` now explicitly governs WebFlux REST wire-format via `WebFluxJacksonCodecConfigurer`. Jackson 2 remains as transitive dependency of springdoc-openapi and Spring Boot YAML config loading only — documented in `JacksonConfiguration.kt` KDoc.
```

- [ ] **Step 3: Commit doc update**

```bash
git add docs/issues/2026-05-25-dual-jackson-stack.md
git commit -m "docs: mark dual-jackson-stack issue as resolved"
```

- [ ] **Step 4: Remove plan and spec from feature branch before PR (per global CLAUDE.md)**

```bash
git rm docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md \
       docs/superpowers/plans/2026-05-26-jackson-3-migration.md
git commit -m "chore: remove planning docs before PR"
```

The documents stay accessible in branch git history (`git log refactor/jackson-3-migration -- docs/superpowers/`).

- [ ] **Step 5: Push and open PR**

```bash
git push -u origin refactor/jackson-3-migration

gh pr create --title "refactor: govern REST wire-format via tools.jackson (closes #29)" --body "$(cat <<'EOF'
## Summary
- Migrate `JacksonConfiguration`, `DetectService`, `ClaudeResponseParser` from legacy `com.fasterxml.jackson` (Jackson 2) to `tools.jackson` (Jackson 3)
- Add `WebFluxJacksonCodecConfigurer` to **explicitly** wire our `@Primary internalObjectMapper` into WebFlux REST codec — config now truly governs wire-format
- Extract qualified `detectServerObjectMapper` bean (SNAKE_CASE for detect-server outbound) from `WebClientConfiguration`
- Add `TestObjectMappers` shared utility in core and ai-description test sources

Closes #29.

## Why
Before: `JacksonConfiguration` bean (Jackson 2) was orphaned from WebFlux REST codec — Spring Boot 4 used its own `tools.jackson` defaults for `/status` and other REST endpoints. The ISO-8601 wire-format worked by coincidence, not by our explicit configuration.

After: One `@Primary tools.jackson.ObjectMapper` bean is used by REST inbound/outbound codec (via `WebFluxJacksonCodecConfigurer`), `DetectService` (parse detect-server error bodies), and `ClaudeResponseParser` (parse Claude AI responses). Jackson 2 remains as a transitive of springdoc-openapi and Spring Boot YAML config only — documented in `JacksonConfiguration.kt` KDoc.

## Test plan
- [ ] `./gradlew build` green
- [ ] `JacksonConfigurationTest` — bean settings + KotlinModule discovery
- [ ] `WebFluxJacksonCodecConfigurerTest` — regression guard: configurer registers encoder/decoder
- [ ] `StatusControllerTest` — end-to-end wire-format (ISO-8601 dates + JSON структура `/status` включая `recordings.success`/`errors` поля от master PR #31) проходит через мигрированный codec; file-comment обновлён (honest narrative — sanity check, не regression guard)
- [ ] All existing `DetectServiceTest`, `DetectServiceCancelJobTest`, `VideoVisualizationServiceTest`, `ClaudeResponseParserTest`, etc. pass
EOF
)"
```

---

## Self-Review (post-write, before execution)

**Spec coverage check** (each spec § → task that implements it):

| Spec § | Coverage |
|---|---|
| § 3.1 `JacksonConfiguration` rewrite | Task 4 Step 1 |
| § 3.2 `WebFluxJacksonCodecConfigurer` | Task 7 Step 3 |
| § 3.3 `WebClientConfiguration` qualified bean | Task 6 Step 1 |
| § 4.1 `DetectService` import | Task 4 Step 3 |
| § 4.1 `ClaudeResponseParser` import | Task 5 Step 1 |
| § 4.1 `ClaudeExceptionMapper` catch both | Task 5 Step 2 |
| § 4.1 `JobStatus.kt` no change | (explicitly NOT in plan — correct) |
| § 4.2 `libs.versions.toml` alias | Task 1 Step 1 |
| § 4.2 `core/build.gradle.kts` dep | Task 1 Step 2 |
| § 4.2 `ai-description/build.gradle.kts` dep | Task 1 Step 3 |
| § 4.2 `model/build.gradle.kts` no change | (explicitly NOT in plan — correct) |
| § 4.3 `TestObjectMappers` (core) | Task 2 Step 3 |
| § 4.3 `TestObjectMappers` (ai-description) | Task 3 Step 3 |
| § 4.3 existing test rewrites | Task 4 Steps 4-6, Task 5 Steps 3-7 |
| § 4.3 `JacksonConfigurationTest` rewrite | Task 4 Step 2 (incl. new KotlinModule test) |
| § 4.3 `WebFluxJacksonCodecConfigurerTest` | Task 7 Steps 1-4 |
| § 4.3 `StatusControllerTest` unchanged | (verified passing in Task 4 Step 7 and Task 7 Step 5) |
| § 7 DoD: KDoc updates | Task 4 Step 1 (new KDoc in JacksonConfiguration) |
| § 7 DoD: docs/issues marked resolved | Task 8 Step 2 |
| § 7 DoD: GitHub issue close + PR | Task 8 Step 5 (`closes #29` in PR body) |

All spec items are covered.

**Placeholder scan:** No "TODO", "TBD", "implement later", "similar to Task N", "add appropriate error handling" anywhere. Every code step contains the actual code.

**Type consistency:**
- `internalObjectMapper` — same name everywhere (bean, KDoc references, test method name `TestObjectMappers.internalMapper()`).
- `detectServerObjectMapper` — same name in `@Qualifier`, bean method, `TestObjectMappers.detectServerMapper()` (test factory uses shorter name `detectServerMapper` — consistent across both Task 2 and references in Tasks 4-6).
- `WebFluxJacksonCodecConfigurer` — same class name in Task 7 production code, test, KDoc references in Tasks 4 and 8.

Plan is internally consistent.
