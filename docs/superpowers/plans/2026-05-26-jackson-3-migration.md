# Jackson 3 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate internal Jackson usage from `com.fasterxml` (Jackson 2) to `tools.jackson` (Jackson 3) and explicitly wire the @Primary `JsonMapper` into the WebFlux REST codec, so `JacksonConfiguration` truly governs wire-format (closes #29).

**Architecture:** Two role-based `JsonMapper` beans — `@Primary internalObjectMapper` (tools.jackson `JsonMapper`, camelCase, ISO-8601; used by REST codec + DetectService + ClaudeResponseParser) and `@Qualifier("detectServerObjectMapper")` (tools.jackson `JsonMapper`, SNAKE_CASE; used only by outbound WebClient to detect-server). New `WebFluxJacksonCodecConfigurer` (with `@Order(Ordered.LOWEST_PRECEDENCE)`) explicitly registers Jackson codecs built from the @Primary mapper. Jackson 2 remains as transitive only (springdoc-openapi, Spring Boot YAML config).

**Critical type note:** Spring 7 `JacksonJsonEncoder`/`JacksonJsonDecoder` constructors accept **only** `tools.jackson.databind.json.JsonMapper`, not `ObjectMapper`. Therefore the `internalObjectMapper` bean returns `JsonMapper`. Since `JsonMapper extends ObjectMapper`, DI into consumers declaring `ObjectMapper` parameter (DetectService, ClaudeResponseParser) still works transparently.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.0.6, WebFlux, tools.jackson 3.0.4, JUnit5, MockK 1.14.9

**Spec:** `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`

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

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `modules/core/build.gradle.kts`
- Modify: `modules/ai-description/build.gradle.kts`

- [ ] **Step 1: Add alias to `gradle/libs.versions.toml`**

After the existing `jackson-yaml` line (~line 55), insert:

```toml
# tools.jackson (Jackson 3) Kotlin module — required for findAndAddModules() to discover KotlinModule
# via ServiceLoader (META-INF/services/tools.jackson.databind.JacksonModule). Without explicit
# declaration the module is not on classpath in Spring Boot 4.0.6 default deps.
jackson-kotlin-3 = { module = "tools.jackson.module:jackson-module-kotlin" }
```

- [ ] **Step 2: Add dep to `modules/core/build.gradle.kts`**

After the existing line `implementation(libs.bundles.jackson)` (~line 56), insert:

```kotlin
    implementation(libs.jackson.kotlin.3)
```

- [ ] **Step 3: Add dep to `modules/ai-description/build.gradle.kts`**

After the existing line `implementation(libs.bundles.jackson)` (~line 15), insert:

```kotlin
    implementation(libs.jackson.kotlin.3)
```

- [ ] **Step 4: Verify dependency resolution**

Run: `./gradlew :frigate-analyzer-core:dependencies --configuration runtimeClasspath | grep -E 'tools.jackson.module|jackson-module-kotlin'`

Expected: line containing `tools.jackson.module:jackson-module-kotlin:3.0.4` (or whatever version BOM resolves to).

- [ ] **Step 5: Verify build still compiles**

Run: `./gradlew build -x test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml modules/core/build.gradle.kts modules/ai-description/build.gradle.kts
git commit -m "build: add tools.jackson.module:jackson-module-kotlin (Jackson 3)"
```

---

## Task 2: Create `TestObjectMappers` utility in core module

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappers.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappersTest.kt`

- [ ] **Step 1: Write the self-test FIRST (TDD)**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappersTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TestObjectMappersTest {
    @Test
    fun `internalMapper serialises Instant as ISO-8601 string`() {
        val json = TestObjectMappers.internalMapper().writeValueAsString(Instant.parse("2026-05-26T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-05-26T10:00:00Z\"")
    }

    @Test
    fun `internalMapper deserialises unknown properties without failing`() {
        data class Foo(val known: String)
        val parsed = TestObjectMappers.internalMapper()
            .readValue("""{"known":"x","unknown":"ignored"}""", Foo::class.java)
        assertThat(parsed.known).isEqualTo("x")
    }

    @Test
    fun `detectServerMapper applies SNAKE_CASE naming strategy`() {
        data class Foo(val someField: String)
        val parsed = TestObjectMappers.detectServerMapper()
            .readValue("""{"some_field":"value"}""", Foo::class.java)
        assertThat(parsed.someField).isEqualTo("value")
    }
}
```

- [ ] **Step 2: Run test to verify it fails (file doesn't exist)**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappersTest'`

Expected: Compilation failure — `unresolved reference: TestObjectMappers`.

- [ ] **Step 3: Implement TestObjectMappers**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappers.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.testsupport

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Test-side factories matching production JsonMapper beans configured in
 * [ru.zinin.frigate.analyzer.core.config.JacksonConfiguration] and
 * [ru.zinin.frigate.analyzer.core.config.WebClientConfiguration].
 *
 * Use these so tests stay aligned with production wire-format and parser configuration.
 * Adding a setting in production? Add it here too.
 *
 * Return type is `JsonMapper` to match production (Spring 7 codec API requires JsonMapper).
 * `JsonMapper extends ObjectMapper`, so callers that accept `ObjectMapper` still work.
 */
object TestObjectMappers {
    /** Matches production `@Primary internalObjectMapper`. */
    fun internalMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()

    /** Matches production `detectServerObjectMapper` (SNAKE_CASE for detect-server contract). */
    fun detectServerMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappersTest'`

Expected: 3 tests pass.

- [ ] **Step 5: Run ktlint**

Run: `./gradlew ktlintFormat`

Then verify clean: `./gradlew ktlintCheck`

Expected: BUILD SUCCESSFUL, no ktlint issues.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappers.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/TestObjectMappersTest.kt
git commit -m "test(core): add TestObjectMappers shared factories"
```

---

## Task 3: Create `TestObjectMappers` utility in ai-description module

**Files:**
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappers.kt`
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappersTest.kt`

- [ ] **Step 1: Write the self-test FIRST**

Create `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappersTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestObjectMappersTest {
    @Test
    fun `internalMapper deserialises Kotlin data class via KotlinModule`() {
        data class Foo(val name: String)
        val parsed = TestObjectMappers.internalMapper()
            .readValue("""{"name":"x"}""", Foo::class.java)
        assertThat(parsed.name).isEqualTo("x")
    }

    @Test
    fun `internalMapper tolerates unknown properties`() {
        data class Foo(val known: String)
        val parsed = TestObjectMappers.internalMapper()
            .readValue("""{"known":"x","unknown":"ignored"}""", Foo::class.java)
        assertThat(parsed.known).isEqualTo("x")
    }
}
```

- [ ] **Step 2: Run test to verify it fails (file doesn't exist)**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappersTest'`

Expected: Compilation failure.

- [ ] **Step 3: Implement TestObjectMappers**

Create `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappers.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.testsupport

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Test-side factory matching the production `@Primary internalObjectMapper` bean from the
 * core module's `JacksonConfiguration`. Duplicated here because Gradle modules don't share
 * test sources; keep the body in sync with the core copy.
 *
 * Return type is `JsonMapper` to match production. `JsonMapper extends ObjectMapper`, so
 * `ClaudeResponseParser`'s `ObjectMapper` parameter is satisfied transparently.
 */
object TestObjectMappers {
    fun internalMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappersTest'`

Expected: 2 tests pass.

- [ ] **Step 5: ktlint**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`

Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappers.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/testsupport/TestObjectMappersTest.kt
git commit -m "test(ai-description): add TestObjectMappers shared factory"
```

---

## Task 4: Migrate core module Jackson stack to tools.jackson (atomic)

This task is intentionally **atomic** — `JacksonConfiguration`, `DetectService`, and all three core tests must change together. Splitting would break the build mid-task because `DetectService` injects the bean whose type is changing.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfigurationTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

- [ ] **Step 1: Rewrite `JacksonConfiguration.kt`**

Replace the entire file with:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Primary tools.jackson (Jackson 3) JSON mapper used by:
 *  - WebFlux REST inbound/outbound JSON codec (wired in [WebFluxJacksonCodecConfigurer])
 *  - [ru.zinin.frigate.analyzer.core.service.DetectService] (parses detect-server error bodies as raw JsonNode)
 *  - [ru.zinin.frigate.analyzer.ai.description.claude.ClaudeResponseParser] (parses Claude responses)
 *
 * Settings:
 *  - camelCase (default property naming)
 *  - ISO-8601 strings for `Instant`/`Duration` (no numeric timestamps)
 *  - tolerant deserialization (unknown properties ignored)
 *  - `findAndAddModules()` picks up `tools.jackson.module.kotlin` from classpath via ServiceLoader
 *
 * **Return type is `JsonMapper`, not `ObjectMapper`.** Spring 7's `JacksonJsonEncoder`/
 * `JacksonJsonDecoder` constructors require `JsonMapper` (verified via javap of spring-web-7.0.5).
 * `JsonMapper extends ObjectMapper`, so DI into consumers declaring `ObjectMapper` (DetectService,
 * ClaudeResponseParser) still works.
 *
 * **Dual-stack rationale (self-contained):**
 * `tools.jackson` governs all internal and REST wire-format JSON. Legacy `com.fasterxml.jackson`
 * is retained ONLY as a transitive dependency of:
 *  - `springdoc-openapi-starter` 3.0.3 (requires `com.fasterxml.jackson.module.kotlin.KotlinModule`
 *    via its own `SpringDocJacksonKotlinModuleConfiguration` for OpenAPI spec generation).
 *  - `spring-boot-jackson2` compat starter (Spring Boot 4 ships this exactly for the springdoc case).
 *
 * The `@Primary` annotation scopes only within `tools.jackson.databind.*` classes; springdoc
 * injects `com.fasterxml.jackson.databind.ObjectMapper` (a different class), so there is no
 * type collision. Spring will never substitute incompatible types.
 */
@Configuration
class JacksonConfiguration {
    @Bean
    @Primary
    fun internalObjectMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

- [ ] **Step 2: Rewrite `JacksonConfigurationTest.kt`**

Replace the entire file with:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * Verifies the `@Primary internalObjectMapper` bean configured in [JacksonConfiguration].
 *
 * End-to-end wire-format coverage for REST endpoints lives in
 * [ru.zinin.frigate.analyzer.core.controller.StatusControllerTest]; this test exercises the
 * bean in isolation (settings + KotlinModule discovery + Spring-context registration).
 */
class JacksonConfigurationTest {
    private val mapper = JacksonConfiguration().internalObjectMapper()

    @Test
    fun `Instant serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Instant.parse("2026-04-25T10:00:00Z"))
        assertThat(json).isEqualTo("\"2026-04-25T10:00:00Z\"")
    }

    @Test
    fun `Duration serialised as ISO-8601 string`() {
        val json = mapper.writeValueAsString(Duration.ofMinutes(7))
        assertThat(json).isEqualTo("\"PT7M\"")
    }

    @Test
    fun `KotlinModule is auto-discovered — data class round-trips`() {
        // Regression guard for findAndAddModules() ServiceLoader discovery — fails if
        // jackson-module-kotlin-3 artifact is missing or its META-INF/services file is not packaged.
        data class Foo(val name: String, val count: Int)
        val original = Foo("x", 42)
        val json = mapper.writeValueAsString(original)
        val parsed = mapper.readValue(json, Foo::class.java)
        assertThat(parsed).isEqualTo(original)
    }

    @Test
    fun `FAIL_ON_UNKNOWN_PROPERTIES is disabled — unknown JSON fields tolerated`() {
        // Explicit guard on the most behaviour-changing feature configured in production.
        data class Foo(val known: String)
        val parsed = mapper.readValue("""{"known":"x","unknown":"ignored"}""", Foo::class.java)
        assertThat(parsed.known).isEqualTo("x")
    }

    @Test
    fun `Spring context registers internalObjectMapper as Primary JsonMapper bean`() {
        // Verifies the bean is actually visible to Spring (not just constructible by hand)
        // and has the @Primary marker so codec/parser DI picks it deterministically.
        ApplicationContextRunner()
            .withUserConfiguration(JacksonConfiguration::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(JsonMapper::class.java)
                val bean = ctx.getBean(JsonMapper::class.java)
                assertThat(bean).isInstanceOf(JsonMapper::class.java)
                // BeanDefinition primary flag check
                val bd = ctx.beanFactory.getBeanDefinition("internalObjectMapper")
                assertThat(bd.isPrimary).isTrue()
            }
    }
}
```

- [ ] **Step 3: Update `DetectService.kt` import + add explanatory comment**

Edit `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt`. Change line 3:

From:
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
```

To:
```kotlin
import tools.jackson.databind.ObjectMapper
```

Add inline comment immediately above `private val objectMapper: ObjectMapper` in the constructor:

```kotlin
    // Used only for raw JsonNode parsing of detect-server error-detail bodies via
    // objectMapper.readTree(body).path("detail"). Property access is case-sensitive on the JSON
    // text "detail" — PropertyNamingStrategy is irrelevant here. Spring injects the @Primary
    // JsonMapper bean (which extends ObjectMapper).
    private val objectMapper: ObjectMapper,
```

No other changes in this file — the `readTree(...).path("detail").isTextual`/`asText()` API is identical between Jackson 2 and Jackson 3.

- [ ] **Step 4: Update `DetectServiceTest.kt`**

Edit `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt`:

(a) Remove the `as FasterxmlObjectMapper` alias import (line 41):
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper as FasterxmlObjectMapper
```

(b) Add an import for the new test utility:
```kotlin
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
```

(c) Replace the call site (line 88):

From:
```kotlin
detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
```

To:
```kotlin
detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, TestObjectMappers.internalMapper())
```

(d) Replace the WebClient builder helper at line 481 to use TestObjectMappers:

From:
```kotlin
    private fun buildWebClient(): WebClient {
        val mapper = buildJsonMapper()
        ...
    }

    private fun buildJsonMapper(): JsonMapper = ...

    private fun buildObjectMapper(): FasterxmlObjectMapper { ... }
```

To (delete `buildJsonMapper` and `buildObjectMapper` entirely, simplify `buildWebClient`):
```kotlin
    private fun buildWebClient(): WebClient {
        val mapper = TestObjectMappers.detectServerMapper()
        val strategies =
            ExchangeStrategies
                .builder()
                .codecs { codecs ->
                    codecs.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(mapper))
                    codecs.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(mapper))
                }.build()
        return WebClient.builder().exchangeStrategies(strategies).build()
    }
```

(e) Remove the now-unused imports near the top (these were correct `tools.jackson` imports needed by the soon-to-be-deleted `buildJsonMapper()` helper — after Step (d) they become unused, IDE "Optimize Imports" will pick them up):
```kotlin
import tools.jackson.databind.DeserializationFeature        // remove (unused after buildJsonMapper deleted)
import tools.jackson.databind.PropertyNamingStrategies      // remove (unused after buildJsonMapper deleted)
import tools.jackson.databind.json.JsonMapper               // remove (unused after buildJsonMapper deleted)
```

- [ ] **Step 5: Update `DetectServiceCancelJobTest.kt`**

Edit `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt`:

(a) Remove the alias import (line 49):
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper as FasterxmlObjectMapper
```

(b) Add:
```kotlin
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
```

(c) Replace line 93-94 (jsonDecoder/jsonEncoder in webClient setup):

From:
```kotlin
                            it.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(buildJsonMapper()))
                            it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(buildJsonMapper()))
```

To:
```kotlin
                            it.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(TestObjectMappers.detectServerMapper()))
                            it.defaultCodecs().jacksonJsonEncoder(JacksonJsonEncoder(TestObjectMappers.detectServerMapper()))
```

(d) Replace line 108:

From:
```kotlin
        service = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, buildObjectMapper())
```

To:
```kotlin
        service = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper, TestObjectMappers.internalMapper())
```

(e) Delete the `buildJsonMapper` (line 232) and `buildObjectMapper` (line 239) helper methods entirely.

(f) Remove the now-unused imports near the top:
```kotlin
import tools.jackson.databind.DeserializationFeature        // remove
import tools.jackson.databind.PropertyNamingStrategies      // remove
import tools.jackson.databind.json.JsonMapper               // remove
```

- [ ] **Step 6: Update `VideoVisualizationServiceTest.kt`**

Edit `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`:

**Context warning:** the existing `buildObjectMapper()` helper in this file returns `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2). After this step, all 4 call sites use `TestObjectMappers.internalMapper()` (Jackson 3 `JsonMapper`) — `buildObjectMapper()` becomes dead code and is deleted entirely in (e). Same for `buildJsonMapper()`.

(a) Remove line 3:
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
```

(b) Add:
```kotlin
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
```

(c) Replace all four `buildObjectMapper()` call sites (lines 93, 210, 279, 447) with `TestObjectMappers.internalMapper()`:

```kotlin
DetectService(webClient, ..., TestObjectMappers.internalMapper())
```

(d) Replace line 468 `buildJsonMapper()` call with `TestObjectMappers.detectServerMapper()`.

(e) Delete the `buildJsonMapper` (line 481) and `buildObjectMapper` (line 488) helper methods entirely — they are now dead code.

(f) Remove the now-unused tools.jackson imports if no other use:
```kotlin
import tools.jackson.databind.DeserializationFeature        // remove if unused
import tools.jackson.databind.PropertyNamingStrategies      // remove if unused
import tools.jackson.databind.json.JsonMapper               // remove if unused
```

- [ ] **Step 7: Run all core tests**

Run: `./gradlew :frigate-analyzer-core:test`

Expected: BUILD SUCCESSFUL. All tests pass — including the existing `StatusControllerTest` (which proves wire-format end-to-end is still correct after migration).

If any test fails — STOP and read the failure carefully. Most likely cause: type mismatch between bean (`tools.jackson.databind.ObjectMapper`) and consumer (still importing `com.fasterxml`).

- [ ] **Step 8: ktlint**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`

Expected: clean.

- [ ] **Step 9: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfigurationTest.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceCancelJobTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt
git commit -m "refactor(core): migrate JacksonConfiguration and DetectService to tools.jackson (Jackson 3)"
```

---

## Task 5: Migrate ai-description module to tools.jackson (atomic)

Same atomic-task rationale as Task 4 — `ClaudeResponseParser` injects ObjectMapper, so its tests and `ClaudeExceptionMapper` must move together.

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt`
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParserTest.kt`
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt`
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt`
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt`
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`

- [ ] **Step 1: Update `ClaudeResponseParser.kt`**

Edit lines 3-4. Replace:
```kotlin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
```
with:
```kotlin
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
```

No other changes — `readTree()`, `node["key"]`, `?.asText()` APIs are identical.

- [ ] **Step 2: Update `ClaudeExceptionMapper.kt`**

Edit `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt`:

(a) Add import after line 3:
```kotlin
import tools.jackson.core.JacksonException
```

(b) Replace the `is JsonProcessingException ->` branch in `map()` with a multi-pattern branch:

From (lines 35-37):
```kotlin
            is JsonProcessingException -> {
                DescriptionException.InvalidResponse(throwable)
            }
```
To:
```kotlin
            is JsonProcessingException, is JacksonException ->
                DescriptionException.InvalidResponse(throwable)
```

Keep the `import com.fasterxml.jackson.core.JsonProcessingException` line — Claude SDK and other transitive libs may still emit Jackson 2 exceptions.

Add KDoc above the `map` method explaining the `is JacksonException` branch is defensive code: `ClaudeResponseParser.parse()` currently wraps `readTree(...)` in `try/catch (e: Exception)`, so `JacksonException` doesn't reach this mapper today. The branch exists for future call sites that may use `internalObjectMapper` directly without local try-catch.

- [ ] **Step 3: Update `ClaudeResponseParserTest.kt`**

Edit `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParserTest.kt`:

(a) Remove lines 3-4:
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
```

(b) Add:
```kotlin
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
```

(c) Replace line 11:

From:
```kotlin
    private val mapper = ObjectMapper().registerKotlinModule()
```
To:
```kotlin
    private val mapper = TestObjectMappers.internalMapper()
```

- [ ] **Step 4: Update `ClaudeDescriptionAgentTest.kt`**

Same pattern as Step 3:

(a) Remove imports of `com.fasterxml.jackson.databind.ObjectMapper` and `com.fasterxml.jackson.module.kotlin.registerKotlinModule`.

(b) Explicitly add (do NOT rely on IDE auto-import in scripted runs): `import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers`.

(c) Replace `ObjectMapper().registerKotlinModule()` (wherever it appears, including line 59 `ClaudeResponseParser(ObjectMapper().registerKotlinModule())` → `ClaudeResponseParser(TestObjectMappers.internalMapper())`) with `TestObjectMappers.internalMapper()`.

(d) If the `ObjectMapper` parameter type appears in a constructor or property, switch its declared type to `tools.jackson.databind.ObjectMapper` (add the import).

- [ ] **Step 5: Update `ClaudeDescriptionAgentIntegrationTest.kt`**

Same as Step 4. **Important:** this file is annotated `@Disabled`, but Kotlin compiles disabled test bodies — leaving `ObjectMapper().registerKotlinModule()` at line 102 will cause compilation failure after migration. Explicitly replace `val mapper = ObjectMapper().registerKotlinModule()` (line 102) with `val mapper = TestObjectMappers.internalMapper()` and add the import as in Step 4(b).

- [ ] **Step 6: Update `ClaudeExceptionMapperTest.kt`**

Edit `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt`:

(a) Replace `import com.fasterxml.jackson.core.JsonParseException` (line 3) with imports for whatever Jackson 2 exception types are used; verify they still exist (`JsonParseException` is still in Jackson 2 — keep this import as-is since the test exercises Jackson-2 SDK exceptions for the existing `JsonProcessingException` branch).

(b) **ADD a new test case** verifying the Jackson 3 branch. Use a concrete subclass (`StreamReadException`) instead of an anonymous `object : JacksonException("boom") {}` — Jackson 3.0.4's `JacksonException` constructor visibility is not guaranteed across point releases, and `StreamReadException` is a stable public subclass representing the most common Jackson 3 exception thrown by `readTree`:

```kotlin
    @Test
    fun `map wraps tools_jackson JacksonException as InvalidResponse`() {
        val mapper = ClaudeExceptionMapper()
        val cause = tools.jackson.core.exc.StreamReadException(null, "boom")
        val result = mapper.map(cause)
        assertThat(result).isInstanceOf(DescriptionException.InvalidResponse::class.java)
        assertThat(result.cause).isSameAs(cause)
    }
```

(c) Verify the existing `JsonProcessingException` test case still passes.

- [ ] **Step 7: Update `AiDescriptionAutoConfigurationTest.kt`**

**This is NOT the same pattern as Step 3** — the file declares an `@Bean` whose return type must also change.

(a) Replace line 32:

From:
```kotlin
@Bean
fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
```
To:
```kotlin
@Bean
fun objectMapper(): tools.jackson.databind.json.JsonMapper = TestObjectMappers.internalMapper()
```

(b) Add the import:
```kotlin
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
```

(c) Remove imports of `com.fasterxml.jackson.databind.ObjectMapper` and `com.fasterxml.jackson.module.kotlin.registerKotlinModule` (they are now unused).

**Why this is different:** with the Jackson 2 `ObjectMapper` return type, Spring would register a `com.fasterxml.jackson.databind.ObjectMapper` bean — but post-migration `ClaudeResponseParser` is wired with `tools.jackson.databind.ObjectMapper`, an incompatible type. Spring cannot find a matching bean, autowiring fails. Changing both the body and the return type fixes both compile-time and DI resolution.

- [ ] **Step 8: Run all ai-description tests**

Run: `./gradlew :frigate-analyzer-ai-description:test`

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 9: ktlint**

Run: `./gradlew ktlintFormat && ./gradlew ktlintCheck`

Expected: clean.

- [ ] **Step 10: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParserTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt
git commit -m "refactor(ai-description): migrate ClaudeResponseParser to tools.jackson (Jackson 3)"
```

---

## Task 6: Refactor `WebClientConfiguration` to qualified `detectServerObjectMapper` bean

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt`

- [ ] **Step 1: Refactor bean wiring**

Edit `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt`. Add import for `Qualifier`:

```kotlin
import org.springframework.beans.factory.annotation.Qualifier
```

Then replace the inline `JsonMapper.builder()...` calls inside `jsonEncoder()` and `jsonDecoder()` with a separate qualified bean.

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
     */
    @Bean
    @Qualifier("detectServerObjectMapper")
    fun detectServerObjectMapper(): JsonMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
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
    }
}
```

- [ ] **Step 2: Run test to verify it fails (file doesn't exist)**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.WebFluxJacksonCodecConfigurerTest'`

Expected: Compilation failure — `unresolved reference: WebFluxJacksonCodecConfigurer`.

- [ ] **Step 3: Implement the configurer**

Create `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebFluxJacksonCodecConfigurer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.web.reactive.config.WebFluxConfigurer
import tools.jackson.databind.json.JsonMapper

/**
 * Wires the project's `@Primary internalObjectMapper` (configured in [JacksonConfiguration])
 * into WebFlux's REST JSON codec, so the inbound/outbound wire-format for REST endpoints
 * (e.g. `/status`) is governed by **our** mapper and not Spring Boot 4 auto-configuration
 * defaults.
 *
 * Without this configurer the wire-format works by coincidence — Spring Boot 4 auto-config
 * defaults happen to match our requirements (ISO-8601, camelCase). With this configurer the
 * relationship is **explicit**: regression guards in `WebFluxJacksonCodecConfigurerTest` and
 * the extended `StatusControllerTest` (unknown-property tolerance) fail if this wiring is
 * removed.
 *
 * `@Order(Ordered.LOWEST_PRECEDENCE)` ensures this configurer runs last in the
 * `WebFluxConfigurer` chain — even if another configurer (now or in the future) also calls
 * `jacksonJsonEncoder`, our explicit registration takes effect.
 *
 * Constructor parameter is `JsonMapper` (not `ObjectMapper`) because Spring 7's
 * `JacksonJsonEncoder`/`JacksonJsonDecoder` constructors accept only `JsonMapper`. `JsonMapper`
 * extends `ObjectMapper` so DI from `@Primary JsonMapper` satisfies both shapes.
 */
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.WebFluxJacksonCodecConfigurerTest'`

Expected: 1 test passes.

- [ ] **Step 5: Update `StatusControllerTest` KDoc and add FAIL_ON_UNKNOWN_PROPERTIES regression test**

Edit `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt`:

(a) Replace the obsolete KDoc at lines 20-24 (which claims wire-format goes through "tools.jackson, NOT our com.fasterxml.jackson-based JacksonConfiguration"). New KDoc:

```kotlin
/**
 * End-to-end wire-format check for /status: verifies ISO-8601 timestamps + tolerance to
 * unknown JSON properties. After the Jackson 3 migration (issue #29) these go through codecs
 * registered by [WebFluxJacksonCodecConfigurer] from our `@Primary internalObjectMapper` —
 * removing the configurer (or the @Primary marker) breaks the unknown-property tolerance test.
 */
```

(b) Add a new test method that POSTs (or otherwise sends body, depending on existing endpoint shape — if `/status` is GET-only, add a small `@WebFluxTest` with a stub controller accepting a body) JSON containing an unknown property and asserts 200 OK:

```kotlin
@Test
fun `inbound JSON with unknown properties is tolerated — regression guard for FAIL_ON_UNKNOWN_PROPERTIES`() {
    // Default Spring Boot 4 tools.jackson mapper would also tolerate unknowns — but if our
    // WebFluxJacksonCodecConfigurer is removed and SOMEONE in the future flips the default,
    // this test will fail. The combination of (a) extra field accepted (b) ISO-8601 dates is
    // specifically what JacksonConfiguration governs.
    webTestClient.get()  // or .post(...).bodyValue(...)
        .uri("/status")
        .exchange()
        .expectStatus().isOk
    // Sanity: assert response shape unchanged (ISO-8601 in `startedAt` field etc.)
}
```

If `/status` is read-only and cannot accept a JSON body, alternative: add a tiny `@TestConfiguration`-scoped controller in `StatusControllerTest` that echoes a POST body, then exercise it through the same `WebTestClient`.

- [ ] **Step 6: Run full core test suite (verify StatusControllerTest still passes — proves the configurer governs end-to-end wire-format)**

Run: `./gradlew :frigate-analyzer-core:test`

Expected: BUILD SUCCESSFUL. `StatusControllerTest` continues to assert ISO-8601 timestamps in JSON wire-format AND now asserts unknown-property tolerance — both governed by our configurer.

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

Run:
```bash
grep -rn "ObjectMapper\|registerKotlinModule" \
    --include="*.kt" --include="*.java" \
    modules/ | grep -v build/ | grep -v ".gradle/"
```

Cross-check every result against the plan's covered files:
- `JacksonConfiguration.kt`, `WebFluxJacksonCodecConfigurer.kt`, `WebClientConfiguration.kt`, `DetectService.kt`, `ClaudeResponseParser.kt`, `ClaudeExceptionMapper.kt`
- All tests under modules/core/src/test/ and modules/ai-description/src/test/ touched by Tasks 4 and 5
- `TestObjectMappers.kt` (×2)

If grep returns a use site NOT in the above list, STOP and add it explicitly to the plan before continuing. Common omissions to look for: MapStruct mapper interfaces, Liquibase changelog Java/Kotlin classes, telegram module helpers.

Expected universe: ~19 files (per design § 4.1 and existing grep before migration). Verify the count.

- [ ] **Step 1b: Full build with all tests**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL on all modules. If anything fails, STOP and investigate — this is the integration check that all module-level migrations compose correctly.

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
- [ ] `StatusControllerTest` — end-to-end wire-format (ISO-8601 dates in JSON response) unchanged
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
