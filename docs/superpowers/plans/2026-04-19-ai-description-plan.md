# AI Description Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional AI-generated short + detailed descriptions of detection frames, delivered via Telegram with placeholder + edit flow and `expandable_blockquote` for the detailed part.

**Architecture:** New `ai-description` module with `DescriptionAgent` abstraction and first implementation via `spring-ai-community/claude-code-sdk` (native Claude Code CLI subprocess). Facade kicks off `describe()` asynchronously; Telegram sender posts placeholder-messages immediately, then edits them when description is ready. All behavior gated by `application.ai.description.enabled`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, `org.springaicommunity:claude-code-sdk:1.0.0`, Kotlin Coroutines, ktgbotapi 32.0.0, Jackson, MockK, `@TempDir`.

**Spec:** [`docs/superpowers/specs/2026-04-19-ai-description-design.md`](../specs/2026-04-19-ai-description-design.md). Always defer to the spec on unclear requirements.

**Working branch:** `feature/ai-description` (already checked out).

---

## Ground rules for every task

- **Build gate.** At end of every task, before committing, run the build via the `build` skill (dispatches `build-runner`). Do NOT run `./gradlew` directly in this session — project convention in `CLAUDE.md`. On ktlint errors: run `./gradlew ktlintFormat`, then rerun build.
- **Commits are small.** One task = one commit minimum. Use conventional-commit style: `feat(ai-description): ...`, `refactor(core): ...`, etc.
- **Don't modify unrelated files.** If a task introduces a change you didn't plan, stop and re-read the spec.
- **Files live at specific paths.** The Gradle project name includes the `frigate-analyzer-` prefix (e.g. `:frigate-analyzer-ai-description`) per `settings.gradle.kts`.

---

## Phase 1 — Foundation: module scaffold + libraries

### Task 1: Register the new module and SDK dependency

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `modules/ai-description/build.gradle.kts`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep` (empty placeholder so the directory exists in git). Удаляется в Task 2 после создания первых реальных файлов в `api/`.

- [ ] **Step 1: Add module to settings.gradle.kts**

Replace the `modules` list in `settings.gradle.kts`:

```kotlin
val modules = listOf("common", "model", "service", "core", "telegram", "ai-description")
```

- [ ] **Step 2: Add SDK version and library to libs.versions.toml**

In `gradle/libs.versions.toml`, under `[versions]` add:

```toml
spring-ai-claude-code-sdk = "1.0.0"
```

Under `[libraries]` (after the `# Telegram` block, before `# Testing`) add:

```toml
# AI agents
spring-ai-claude-code-sdk = { module = "org.springaicommunity:claude-code-sdk", version.ref = "spring-ai-claude-code-sdk" }
```

Под `[libraries]` убедись что `spring-boot-autoconfigure` уже включён (используется telegram-модулем) — понадобится для `@AutoConfiguration`.

- [ ] **Step 3: Create module build.gradle.kts**

Create `modules/ai-description/build.gradle.kts`:

```kotlin
plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.kotlin.logging)
    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.jackson)
    implementation(libs.spring.ai.claude.code.sdk)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 4: Create package placeholder**

Create `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep` (empty file). Будет удалён в Task 2 когда появится первый реальный файл в `api/`.

- [ ] **Step 5: Run build to confirm module is wired**

Use the `build` skill to dispatch `build-runner` with command `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL, no tests yet.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml modules/ai-description/
git commit -m "feat(ai-description): scaffold module and register claude-code-sdk dependency"
```

---

### Task 2: API contracts — DTOs, interface, exceptions, SPI

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionRequest.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionResult.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionException.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionAgent.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/TempFileWriter.kt` (SPI, см. design §5; реализуется в core, потребляется хелпером стагера)
- Delete: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep`

- [ ] **Step 1: Create DescriptionRequest.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

import java.util.UUID

data class DescriptionRequest(
    val recordingId: UUID,
    val frames: List<FrameImage>,
    val language: String,
    val shortMaxLength: Int,
    val detailedMaxLength: Int,
) {
    data class FrameImage(
        val frameIndex: Int,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameImage) return false
            return frameIndex == other.frameIndex && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * frameIndex + bytes.contentHashCode()
    }
}
```

- [ ] **Step 2: Create DescriptionResult.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

data class DescriptionResult(
    val short: String,
    val detailed: String,
)
```

- [ ] **Step 3: Create DescriptionException.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

sealed class DescriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout(cause: Throwable? = null) : DescriptionException("Description timed out", cause)

    class InvalidResponse(cause: Throwable? = null) : DescriptionException("Claude returned invalid JSON", cause)

    class Transport(cause: Throwable? = null) : DescriptionException("Claude transport error", cause)

    class RateLimited(cause: Throwable? = null) : DescriptionException("Claude rate-limited (429)", cause)
}
```

Иерархия покрывает все ошибочные исходы работающего агента. Случай "агент выключен" моделируется на уровне DI (facade получает `null` supplier, describe-job не запускается) — отдельного исключения не нужно.

- [ ] **Step 4: Create DescriptionAgent.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

interface DescriptionAgent {
    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
```

- [ ] **Step 4.5: Create TempFileWriter.kt (SPI)**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

import java.nio.file.Path

/**
 * SPI — реализуется в core-модуле адаптером над TempFileHelper.
 * Живёт в api/ пакете: это модульный контракт, не claude-специфика.
 * Будущие провайдеры (OpenAI/Gemini) используют ту же абстракцию.
 */
interface TempFileWriter {
    suspend fun createTempFile(prefix: String, suffix: String, content: ByteArray): Path
    suspend fun deleteFiles(files: List<Path>): Int
}
```

- [ ] **Step 4.6: Remove .gitkeep placeholder**

```bash
git rm modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep
```

- [ ] **Step 5: Run build**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/
git commit -m "feat(ai-description): add DescriptionAgent API surface"
```

---

### Task 3: Configuration properties

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/ClaudeProperties.kt`

- [ ] **Step 1: Create DescriptionProperties.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    // Без @NotBlank — при enabled=false provider может быть пустым в конфиге.
    // Валидация provider происходит в AiDescriptionAutoConfiguration:
    // если enabled=true и нет бина под provider — WARN.
    val provider: String,
    @field:Valid
    val common: CommonSection,
) {
    data class CommonSection(
        @field:Pattern(regexp = "ru|en")
        val language: String,
        @field:Min(50) @field:Max(500)
        val shortMaxLength: Int,
        @field:Min(200) @field:Max(3500)
        val detailedMaxLength: Int,
        @field:Min(1) @field:Max(50)
        val maxFrames: Int,
        val queueTimeout: Duration,
        val timeout: Duration,
        @field:Min(1) @field:Max(10)
        val maxConcurrent: Int,
    ) {
        init {
            require(queueTimeout.toMillis() > 0) { "queue-timeout must be positive" }
            require(timeout.toMillis() > 0) { "timeout must be positive" }
        }
    }
}
```

Поля:
- `queueTimeout` (default `30s` в YAML) — ожидание слота семафора.
- `timeout` (default `60s`) — describe + retry внутри semaphore permit.
- `maxFrames` (default `10`) — лимит кадров отправляемых в Claude.
- `language` — паттерн `ru|en` даёт startup-ошибку при опечатке.

- [ ] **Step 2: Create ClaudeProperties.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "application.ai.description.claude")
@Validated
data class ClaudeProperties(
    val oauthToken: String,
    @field:NotBlank
    val model: String,
    val cliPath: String,              // пусто = SDK ищет через `which claude`
    @field:NotBlank
    val workingDirectory: String,     // обязателен для SDK 1.0.0
    @field:Valid
    val proxy: ProxySection,
) {
    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
```

Ключевые изменения vs исходной редакции:
- `startupTimeout` **удалён** — `CLIOptions.builder()` в SDK 1.0.0 такого поля не имеет.
- `workingDirectory` добавлен и `@NotBlank` — `ClaudeClient.AsyncSpec#workingDirectory(Path)` обязателен, иначе SDK бросает `IllegalArgumentException("workingDirectory is required")`.
- `cliPath` остаётся; передаётся в `ClaudeClient.AsyncSpec#claudePath(String)` (**не** в `CLIOptions`).
- `ClaudeProperties` регистрируется через `AiDescriptionAutoConfiguration` (Task 1.5), который условен на `enabled=true` — при выключенной фиче валидация claude.* не блокирует старт.

- [ ] **Step 3: Run build**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/
git commit -m "feat(ai-description): add DescriptionProperties and ClaudeProperties"
```

---

### Task 3.5: AutoConfiguration — register module beans in Spring context

**Context:** `ai-description` модуль живёт в пакете `ru.zinin.frigate.analyzer.ai.description`, а Spring Boot main-class сканирует `ru.zinin.frigate.analyzer.core`. Без autoconfig `@Component`-бины модуля не попадут в context. Используем паттерн `telegram` модуля.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt`
- Create: `modules/ai-description/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Create AiDescriptionAutoConfiguration**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class)
open class AiDescriptionAutoConfiguration
```

**Класс без `@ConditionalOnProperty`** — `DescriptionProperties` регистрируется всегда (его безусловно инжектит `RecordingProcessingFacade`). Условность в `@ConditionalOnProperty("application.ai.description.enabled")` перенесена на сами `@Component`-бины модуля (`ClaudePromptBuilder`, `ClaudeDescriptionAgent` и т. д.) в Phase 2.

**`ClaudeProperties` валидируется всегда** — но при `enabled=false` в YAML дефолты для его полей (см. Task 13 YAML): `oauth-token=""`, `model="opus"`, `working-directory="${application.temp-folder}"` — валидные, запуск не ломают. Будущая поддержка `provider=openai` потребует дополнительного условия (или снятия `@NotBlank` с Claude-специфичных полей) — см. open issue в §11 design.

- [ ] **Step 1.5: Create DescriptionAgentSanityChecker**

Отдельный `@Component` для WARN'а, БЕЗ `@ConditionalOnProperty`:

```kotlin
// modules/ai-description/src/main/kotlin/.../config/DescriptionAgentSanityChecker.kt
package ru.zinin.frigate.analyzer.ai.description.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent

private val logger = KotlinLogging.logger {}

@Component
class DescriptionAgentSanityChecker(
    private val descriptionProperties: DescriptionProperties,
    private val agentProvider: ObjectProvider<DescriptionAgent>,
) {
    @PostConstruct
    fun warnIfProviderMissing() {
        if (!descriptionProperties.enabled) return
        if (agentProvider.getIfAvailable() == null) {
            logger.warn {
                "application.ai.description.enabled=true but no DescriptionAgent registered " +
                    "for provider='${descriptionProperties.provider}'; all describe-calls will fall back."
            }
        }
    }
}
```

Безусловный `@Component` — срабатывает и при `enabled=true, provider=foo` (когда claude-бинов нет, но autoconfig активен). При `enabled=false` метод тихо возвращается.

- [ ] **Step 2: Register auto-config via imports file**

Create `modules/ai-description/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with exactly one line:

```
ru.zinin.frigate.analyzer.ai.description.config.AiDescriptionAutoConfiguration
```

(Стандартный Spring Boot 3+ discovery; без пустой строки в конце — многие редакторы добавят её сами, это ок.)

- [ ] **Step 3: Remove @EnableConfigurationProperties-to-be from core application class**

**НЕ** добавляй `DescriptionProperties::class` / `ClaudeProperties::class` в `@EnableConfigurationProperties` main-класса. Всё поднимается через autoconfig.

- [ ] **Step 4: Write failing ApplicationContext test**

Create `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import io.mockk.mockk
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import kotlin.test.Test

class AiDescriptionAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))
            .withUserConfiguration(TempFileWriterStubConfig::class.java)

    @Configuration
    class TempFileWriterStubConfig {
        @Bean
        fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)
    }

    @Test
    fun `DescriptionProperties registered even when enabled=false`() {
        // Критично: facade инжектит DescriptionProperties безусловно — бин должен быть всегда.
        runner
            .withPropertyValues(
                "application.ai.description.enabled=false",
                "application.ai.description.provider=claude",
                "application.ai.description.common.language=en",
                "application.ai.description.common.short-max-length=200",
                "application.ai.description.common.detailed-max-length=1500",
                "application.ai.description.common.max-frames=10",
                "application.ai.description.common.queue-timeout=30s",
                "application.ai.description.common.timeout=60s",
                "application.ai.description.common.max-concurrent=2",
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.claude.model=opus",
                "application.ai.description.claude.working-directory=/tmp",
            )
            .run { ctx ->
                assert(ctx.getBeansOfType(DescriptionProperties::class.java).isNotEmpty()) {
                    "DescriptionProperties must be available when enabled=false (facade inject)"
                }
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty()) {
                    "DescriptionAgent must NOT be registered when enabled=false"
                }
            }
    }

    @Test
    fun `autoconfig activates beans when enabled=true, provider=claude`() {
        runner
            .withPropertyValues(
                "application.ai.description.enabled=true",
                "application.ai.description.provider=claude",
                "application.ai.description.common.language=en",
                "application.ai.description.common.short-max-length=200",
                "application.ai.description.common.detailed-max-length=1500",
                "application.ai.description.common.max-frames=10",
                "application.ai.description.common.queue-timeout=30s",
                "application.ai.description.common.timeout=60s",
                "application.ai.description.common.max-concurrent=2",
                "application.ai.description.claude.oauth-token=fake",
                "application.ai.description.claude.model=opus",
                "application.ai.description.claude.working-directory=/tmp",
            )
            .run { ctx ->
                // Agent может не пройти init-валидацию без правильного oauth-token в реальных условиях —
                // этот тест проверяет только что bean registered.
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isNotEmpty()) {
                    "DescriptionAgent should be registered"
                }
            }
    }
}
```

- [ ] **Step 5: Build (тест упадёт — DescriptionAgent пока не создан, это ожидаемо)**

Отметить в коммите — полный тест пройдёт после Task 9.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt \
        modules/ai-description/src/main/resources/META-INF \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt
git commit -m "feat(ai-description): add AutoConfiguration with module component-scan"
```

---

## Phase 2 — Claude components (TDD)

**Preflight check (обязательно выполнить до Task 4):** все TDD-сниппеты в этой Phase и последующих отталкиваются от ktgbotapi v32 / SDK 1.0.0 API. Перед написанием теста:
1. Убедись, что `MockK.coEvery` применяется только к `suspend`-методам. Для `ObjectProvider.getIfAvailable()`, `Builder.build()` и других non-suspend — использовать `every`.
2. Проверь реальную сигнатуру `RecordingDto` в `modules/model/.../dto/` перед конструированием в тесте.
3. Проверь паттерн мока в существующем `TelegramNotificationSenderTest` — ловят ли там `bot.execute(...)` или extension `bot.sendTextMessage(...)`. Использовать тот же паттерн.

### Task 4: ClaudePromptBuilder

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilder.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudePromptBuilderTest {
    private val builder = ClaudePromptBuilder()

    private fun request(language: String = "en") =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames =
                listOf(
                    DescriptionRequest.FrameImage(0, ByteArray(1)),
                    DescriptionRequest.FrameImage(1, ByteArray(1)),
                ),
            language = language,
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    private val paths =
        listOf(
            Path.of("/tmp/a/frame-0.jpg"),
            Path.of("/tmp/a/frame-1.jpg"),
        )

    @Test
    fun `includes language instruction for en`() {
        val prompt = builder.build(request("en"), paths)
        assertTrue(prompt.contains("in English"), "prompt must include English language hint")
    }

    @Test
    fun `includes language instruction for ru`() {
        val prompt = builder.build(request("ru"), paths)
        assertTrue(prompt.contains("in Russian"), "prompt must include Russian language hint")
    }

    @Test
    fun `includes numeric length limits`() {
        val prompt = builder.build(request(), paths)
        assertTrue(prompt.contains("150"), "prompt must include short length")
        assertTrue(prompt.contains("800"), "prompt must include detailed length")
    }

    @Test
    fun `includes file paths with at-prefix in frameIndex order`() {
        val prompt = builder.build(request(), paths)
        val idxFrame0 = prompt.indexOf("@/tmp/a/frame-0.jpg")
        val idxFrame1 = prompt.indexOf("@/tmp/a/frame-1.jpg")
        assertTrue(idxFrame0 > 0, "frame-0 path must be present with @-prefix")
        assertTrue(idxFrame1 > 0, "frame-1 path must be present with @-prefix")
        assertTrue(idxFrame0 < idxFrame1, "frame-0 must come before frame-1 in prompt")
    }

    @Test
    fun `sorts unordered frames by frameIndex before zip`() {
        // Важно: input frames в обратном порядке; stager возвращает пути отсортированно.
        // Builder должен выровнять порядок frames перед zip.
        val unorderedRequest = DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(
                DescriptionRequest.FrameImage(1, ByteArray(1)),
                DescriptionRequest.FrameImage(0, ByteArray(1)),
            ),
            language = "en",
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )
        val prompt = builder.build(unorderedRequest, paths)
        val idxFrame0 = prompt.indexOf("@/tmp/a/frame-0.jpg")
        val idxFrame1 = prompt.indexOf("@/tmp/a/frame-1.jpg")
        assertTrue(idxFrame0 > 0 && idxFrame1 > 0)
        assertTrue(idxFrame0 < idxFrame1, "builder must re-sort frames by frameIndex")
    }

    @Test
    fun `rejects unknown language code`() {
        assertFailsWith<IllegalStateException> {
            builder.build(request("de"), paths)
        }
    }

    @Test
    fun `requires JSON response with short and detailed keys`() {
        val prompt = builder.build(request(), paths)
        assertTrue(prompt.contains("\"short\""), "prompt must require \"short\" key")
        assertTrue(prompt.contains("\"detailed\""), "prompt must require \"detailed\" key")
        assertTrue(prompt.contains("JSON"), "prompt must mention JSON format")
    }

    @Test
    fun `is deterministic for same input`() {
        val r = request()
        assertTrue(builder.build(r, paths) == builder.build(r, paths))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudePromptBuilderTest`.
Expected: FAIL — `ClaudePromptBuilder` class not found.

- [ ] **Step 3: Implement ClaudePromptBuilder**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudePromptBuilder {
    fun build(
        request: DescriptionRequest,
        framePaths: List<Path>,
    ): String {
        require(framePaths.size == request.frames.size) {
            "framePaths size (${framePaths.size}) must match request.frames size (${request.frames.size})"
        }
        val languageName = languageNameFor(request.language)
        // Сначала сортируем frames по frameIndex, потом zip со stagedPaths.
        // stager уже возвращает пути в отсортированном порядке, но request.frames
        // приходит из ConcurrentHashMap.values() без гарантий порядка.
        val sortedFrames = request.frames.sortedBy { it.frameIndex }
        val sortedPairs = sortedFrames.zip(framePaths)

        val framesBlock =
            sortedPairs
                .joinToString("\n") { (frame, path) ->
                    "- Frame ${frame.frameIndex}: @${path.toAbsolutePath().normalize()}"
                }

        return buildString {
            appendLine(
                "You are analyzing surveillance camera frames captured during an object detection event.",
            )
            appendLine("Write both descriptions in $languageName.")
            appendLine()
            appendLine("Frames (in chronological order):")
            appendLine(framesBlock)
            appendLine()
            appendLine("Return ONLY this JSON object (no prose around it):")
            appendLine("""{"short": "...", "detailed": "..."}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- \"short\" must not exceed ${request.shortMaxLength} characters.")
            appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
            appendLine("- No markdown, no explanations — just the JSON object.")
        }
    }

    private fun languageNameFor(code: String): String =
        when (code.lowercase()) {
            "ru" -> "in Russian"
            "en" -> "in English"
            // @Pattern на property-уровне уже отсеивает неверные коды.
            // Если сюда пришло что-то другое — это баг конфига/валидации.
            else -> error("Unsupported language code: '$code' (expected 'ru' or 'en')")
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudePromptBuilderTest`.
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilder.kt modules/ai-description/src/test/kotlin/
git commit -m "feat(ai-description): add ClaudePromptBuilder"
```

---

### Task 5: ClaudeResponseParser

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParserTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeResponseParserTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val parser = ClaudeResponseParser(mapper)

    private fun parse(
        raw: String,
        shortMax: Int = 200,
        detailedMax: Int = 1500,
    ) = parser.parse(raw, shortMax, detailedMax)

    @Test
    fun `parses valid JSON`() {
        val result = parse("""{"short": "Two cars.", "detailed": "Two cars entering the yard."}""")
        assertEquals("Two cars.", result.short)
        assertEquals("Two cars entering the yard.", result.detailed)
    }

    @Test
    fun `throws InvalidResponse on non-JSON`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("not JSON at all") }
    }

    @Test
    fun `throws InvalidResponse on missing short key`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"detailed": "foo"}""") }
    }

    @Test
    fun `throws InvalidResponse on missing detailed key`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"short": "foo"}""") }
    }

    @Test
    fun `throws InvalidResponse on blank short value`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"short": "", "detailed": "foo"}""") }
    }

    @Test
    fun `extracts JSON embedded in prose`() {
        val raw = """Here is the analysis: {"short": "X", "detailed": "Y"} — that's it."""
        val result = parse(raw)
        assertEquals("X", result.short)
        assertEquals("Y", result.detailed)
    }

    @Test
    fun `truncates short longer than limit with ellipsis`() {
        val longShort = "a".repeat(250)
        val result = parse("""{"short": "$longShort", "detailed": "d"}""", shortMax = 200)
        assertEquals(200, result.short.length)
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `truncates detailed longer than limit with ellipsis`() {
        val longDetailed = "b".repeat(2000)
        val result = parse("""{"short": "s", "detailed": "$longDetailed"}""", detailedMax = 1500)
        assertEquals(1500, result.detailed.length)
        assertEquals("…", result.detailed.last().toString())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeResponseParserTest`.
Expected: FAIL — class not found.

- [ ] **Step 3: Implement ClaudeResponseParser**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeResponseParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(
        raw: String,
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): DescriptionResult {
        val jsonText = extractJsonBlock(raw)
        val node: JsonNode =
            try {
                objectMapper.readTree(jsonText)
            } catch (e: Exception) {
                logger.debug { "Claude response was not parseable as JSON: ${raw.take(200)}" }
                throw DescriptionException.InvalidResponse(e)
            }

        val short = node["short"]?.asText().orEmpty()
        val detailed = node["detailed"]?.asText().orEmpty()

        if (short.isBlank()) {
            throw DescriptionException.InvalidResponse(
                IllegalStateException("missing or blank 'short' field"),
            )
        }
        if (detailed.isBlank()) {
            throw DescriptionException.InvalidResponse(
                IllegalStateException("missing or blank 'detailed' field"),
            )
        }

        return DescriptionResult(
            short = truncate(short, shortMaxLength),
            detailed = truncate(detailed, detailedMaxLength),
        )
    }

    private fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0 until end) trimmed.substring(start, end + 1) else trimmed
    }

    private fun truncate(
        text: String,
        maxLength: Int,
    ): String =
        if (text.length <= maxLength) {
            text
        } else {
            text.substring(0, maxLength - 1) + "…"
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeResponseParserTest`.
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParserTest.kt
git commit -m "feat(ai-description): add ClaudeResponseParser"
```

---

### Task 6: ClaudeImageStager

**Context:** `ClaudeImageStager` обёртка над `TempFileWriter` SPI-интерфейсом (из `api/` пакета, Task 2 Step 4.5). Adapter реализуется в core-модуле (Task 13). В Task 6 пишем сам stager и unit-тесты с моком `TempFileWriter`.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStager.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStagerTest.kt`

`TempFileWriter` уже создан в Task 2 (api/ пакет) — здесь его только потребляем.

- [ ] **Step 1: (removed — `TempFileWriter` создан в Task 2)**

- [ ] **Step 2: Write failing tests**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeImageStagerTest {
    private val tempWriter = mockk<TempFileWriter>()
    private val stager = ClaudeImageStager(tempWriter)

    @Test
    fun `creates one temp file per frame in frameIndex order`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val request =
                DescriptionRequest(
                    recordingId = recordingId,
                    frames =
                        listOf(
                            DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)),
                            DescriptionRequest.FrameImage(0, byteArrayOf(3, 4)),
                            DescriptionRequest.FrameImage(1, byteArrayOf(5, 6)),
                        ),
                    language = "en",
                    shortMaxLength = 200,
                    detailedMaxLength = 1500,
                )

            val prefixes = mutableListOf<String>()
            val bytes = mutableListOf<ByteArray>()
            coEvery {
                tempWriter.createTempFile(capture(prefixes), any(), capture(bytes))
            } answers {
                Path.of("/tmp/${firstArg<String>()}-stub.jpg")
            }

            val paths = stager.stage(request)

            assertEquals(3, paths.size)
            // prefixes must be ordered by frameIndex ascending
            assertEquals(3, prefixes.size)
            assertEquals(listOf(0, 1, 2), prefixes.map { it.substringAfterLast("-frame-").toInt() })
        }

    @Test
    fun `cleanup delegates to deleteFiles with the same paths`() =
        runTest {
            val paths: List<Path> = listOf(Path.of("/tmp/a.jpg"), Path.of("/tmp/b.jpg"))
            val captured = slot<List<Path>>()
            coEvery { tempWriter.deleteFiles(capture(captured)) } returns paths.size

            stager.cleanup(paths)

            coVerify(exactly = 1) { tempWriter.deleteFiles(any()) }
            assertEquals(paths, captured.captured)
        }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeImageStagerTest`.
Expected: FAIL — class not found.

- [ ] **Step 4: Implement ClaudeImageStager**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeImageStager(
    private val tempWriter: TempFileWriter,
) {
    suspend fun stage(request: DescriptionRequest): List<Path> {
        val sorted = request.frames.sortedBy { it.frameIndex }
        val staged = mutableListOf<Path>()
        try {
            for (frame in sorted) {
                val prefix = "claude-${request.recordingId}-frame-${frame.frameIndex}"
                val path = tempWriter.createTempFile(prefix, ".jpg", frame.bytes)
                staged.add(path)
            }
            return staged
        } catch (e: Exception) {
            logger.warn(e) { "Failed to stage frames for ${request.recordingId}; cleaning up partial set" }
            // NonCancellable — stage может упасть при TimeoutCancellationException,
            // а suspend-вызов в отменённой корутине сразу бросит CancellationException.
            withContext(NonCancellable) { runCatching { tempWriter.deleteFiles(staged) } }
            throw e
        }
    }

    suspend fun cleanup(paths: List<Path>) {
        if (paths.isEmpty()) return
        // NonCancellable обязателен: cleanup() вызывается из finally в describe(),
        // куда выполнение часто попадает через TimeoutCancellationException.
        // Без этого suspend-вызов в отменённой корутине немедленно бросит
        // CancellationException, runCatching его проглотит, файлы останутся.
        withContext(NonCancellable) {
            runCatching { tempWriter.deleteFiles(paths) }
                .onFailure { logger.warn(it) { "Failed to delete staged Claude frames" } }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeImageStagerTest`.
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStager.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStagerTest.kt
git commit -m "feat(ai-description): add ClaudeImageStager consuming TempFileWriter SPI"
```

---

### Task 7: ClaudeAsyncClientFactory

**Context:** Строит `ClaudeAsyncClient` per call. По SDK 1.0.0 API:
- `workingDirectory(Path)` и `claudePath(String)` живут на `ClaudeClient.AsyncSpec`-builder'е (не в `CLIOptions`).
- `workingDirectory` — **обязательное** (SDK бросает `IllegalArgumentException`). Берём из `ClaudeProperties.workingDirectory` (default = `application.temp-folder`).
- `claudePath` — опциональное, пропускаем если `cliPath` пустой (тогда SDK полагается на `which claude`).
- `startupTimeout` в SDK **не существует** — поле убрано из `ClaudeProperties`.
- `timeout(Duration)` на `CLIOptions.builder()` управляет transport-таймаутом CLI (default 2 мин).
- SDK не автоматически пробрасывает HTTP_PROXY/HTTPS_PROXY в subprocess; передаём через `CLIOptions.env(Map)`.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt`

- [ ] **Step 1: Write failing tests for buildEnvMap**

We unit-test only the env-map construction (the one piece not requiring a real CLI). Actual client construction is covered indirectly by the integration test in Task 23.

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeAsyncClientFactoryTest {
    private fun factory(props: ClaudeProperties) = ClaudeAsyncClientFactory(props)

    private fun props(
        token: String = "token-1",
        model: String = "opus",
        http: String = "",
        https: String = "",
        noProxy: String = "",
    ) = ClaudeProperties(
        oauthToken = token,
        model = model,
        cliPath = "",
        workingDirectory = "/tmp/frigate-analyzer",
        proxy = ClaudeProperties.ProxySection(http, https, noProxy),
    )

    @Test
    fun `env map contains OAuth token`() {
        val env = factory(props()).buildEnvMap()
        assertEquals("token-1", env["CLAUDE_CODE_OAUTH_TOKEN"])
    }

    @Test
    fun `env map omits proxy vars when blank`() {
        val env = factory(props()).buildEnvMap()
        assertFalse(env.containsKey("HTTP_PROXY"))
        assertFalse(env.containsKey("HTTPS_PROXY"))
        assertFalse(env.containsKey("NO_PROXY"))
    }

    @Test
    fun `env map includes proxy vars when set`() {
        val env =
            factory(
                props(http = "http://proxy:80", https = "http://proxy:443", noProxy = "localhost"),
            ).buildEnvMap()
        assertEquals("http://proxy:80", env["HTTP_PROXY"])
        assertEquals("http://proxy:443", env["HTTPS_PROXY"])
        assertEquals("localhost", env["NO_PROXY"])
    }

    @Test
    fun `env map does not leak unrelated vars`() {
        val env = factory(props()).buildEnvMap()
        // should contain exactly the token, nothing more when proxy blank
        assertTrue(env.keys == setOf("CLAUDE_CODE_OAUTH_TOKEN"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeAsyncClientFactoryTest`.
Expected: FAIL — class not found.

- [ ] **Step 3: Implement ClaudeAsyncClientFactory**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient
import org.springaicommunity.claude.agent.sdk.ClaudeClient
import org.springaicommunity.claude.agent.sdk.transport.CLIOptions
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import java.nio.file.Path
import java.time.Duration

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeAsyncClientFactory(
    private val claudeProperties: ClaudeProperties,
) {
    fun create(workTimeout: Duration): ClaudeAsyncClient {
        val options =
            CLIOptions
                .builder()
                .model(claudeProperties.model)
                .timeout(workTimeout)
                .env(buildEnvMap())
                .build()

        // workingDirectory ОБЯЗАТЕЛЕН для SDK 1.0.0 (ClaudeClient.AsyncSpec#build бросает
        // IllegalArgumentException("workingDirectory is required")).
        // claudePath опционален — если задан, SDK использует его вместо поиска в PATH.
        val spec = ClaudeClient.async(options)
            .workingDirectory(Path.of(claudeProperties.workingDirectory))
        if (claudeProperties.cliPath.isNotBlank()) {
            spec.claudePath(claudeProperties.cliPath)
        }
        return spec.build()
    }

    internal fun buildEnvMap(): Map<String, String> =
        buildMap {
            put("CLAUDE_CODE_OAUTH_TOKEN", claudeProperties.oauthToken)
            if (claudeProperties.proxy.http.isNotBlank()) {
                put("HTTP_PROXY", claudeProperties.proxy.http)
            }
            if (claudeProperties.proxy.https.isNotBlank()) {
                put("HTTPS_PROXY", claudeProperties.proxy.https)
            }
            if (claudeProperties.proxy.noProxy.isNotBlank()) {
                put("NO_PROXY", claudeProperties.proxy.noProxy)
            }
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeAsyncClientFactoryTest`.
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt
git commit -m "feat(ai-description): add ClaudeAsyncClientFactory with env wiring"
```

---

### Task 8: Exception mapping helper

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class ClaudeExceptionMapperTest {
    private val mapper = ClaudeExceptionMapper()

    @Test
    fun `TransportException maps to Transport`() {
        val e = mapper.map(TransportException("socket closed"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `429 with http context maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("HTTP 429 rate limit exceeded"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `rate limit text maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("request was rate limited"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `bare 429 in unrelated text does NOT map to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("process exited with code 429 unknown"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `generic ClaudeSDKException maps to Transport`() {
        val e = mapper.map(ClaudeSDKException("process exited with code 1"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `JsonParseException maps to InvalidResponse`() {
        val e = mapper.map(JsonParseException(null, "bad json"))
        assertIs<DescriptionException.InvalidResponse>(e)
    }

    @Test
    fun `unknown Throwable maps to Transport`() {
        val e = mapper.map(IllegalStateException("oops"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `CancellationException is rethrown as-is (not wrapped)`() {
        val cancellation = CancellationException("cancelled by scope")
        val caught = assertFailsWith<CancellationException> { mapper.map(cancellation) }
        assert(caught === cancellation)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeExceptionMapperTest`.
Expected: FAIL — class not found.

- [ ] **Step 3: Implement ClaudeExceptionMapper**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.CancellationException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeExceptionMapper {
    /**
     * Маппит произвольный Throwable в иерархию DescriptionException.
     *
     * CRITICAL: CancellationException (в т.ч. TimeoutCancellationException) НЕ оборачиваем —
     * это сломает structured concurrency. Её должен поймать сам describe() на границе withTimeout.
     */
    fun map(throwable: Throwable): DescriptionException {
        if (throwable is CancellationException) throw throwable
        return when (throwable) {
            is DescriptionException -> throwable
            is JsonProcessingException -> DescriptionException.InvalidResponse(throwable)
            is TransportException -> DescriptionException.Transport(throwable)
            is ClaudeSDKException -> {
                if (isRateLimit(throwable)) {
                    DescriptionException.RateLimited(throwable)
                } else {
                    DescriptionException.Transport(throwable)
                }
            }
            else -> DescriptionException.Transport(throwable)
        }
    }

    private fun isRateLimit(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        // "rate limit" — однозначный hit.
        // "429" без контекста даст false positive (например, "code 429 offset"),
        // поэтому требуем либо HTTP/status-context, либо явного слова rate.
        if ("rate limit" in message) return true
        if (Regex("\\b429\\b").containsMatchIn(message) &&
            ("http" in message || "status" in message)) return true
        return false
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeExceptionMapperTest`.
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt
git commit -m "feat(ai-description): add ClaudeExceptionMapper"
```

---

## Phase 3 — Agent skeleton and retry logic

### Task 9: ClaudeDescriptionAgent — skeleton + startup validation

**Context:** `callClaudeOnce()` is sealed behind a `ClaudeInvoker` functional interface so `describe()` can be unit-tested without an actual CLI. Real invoker uses `ClaudeAsyncClient` from factory; tests inject a fake.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeInvoker.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentValidationTest.kt`

- [ ] **Step 1: Create ClaudeInvoker interface**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

/**
 * Seam over the SDK call — implemented in production by `DefaultClaudeInvoker`, replaced in tests
 * with a fake that returns canned responses or throws specific exceptions.
 */
fun interface ClaudeInvoker {
    suspend fun invoke(prompt: String): String
}
```

- [ ] **Step 2: Write failing validation test**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClaudeDescriptionAgentValidationTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val descriptionProps =
        DescriptionProperties(enabled = true, provider = "claude", common = common)

    private fun agent(token: String): ClaudeDescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = token,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                ),
            descriptionProperties = descriptionProps,
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects blank oauth token`() {
        assertFailsWith<IllegalStateException> { agent("") }
    }

    @Test
    fun `init rejects blank oauth token with whitespace`() {
        assertFailsWith<IllegalStateException> { agent("   ") }
    }

    @Test
    fun `init accepts non-blank oauth token`() {
        agent("token-xyz") // no exception
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentValidationTest`.
Expected: FAIL — class not found.

- [ ] **Step 4: Create ClaudeDescriptionAgent skeleton**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Semaphore
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

private val logger = KotlinLogging.logger {}

/**
 * Agent активен только при enabled=true AND provider=claude. Модуль helpers (promptBuilder,
 * responseParser, imageStager, ...) регистрируются @Component только при enabled=true, без условия
 * на provider — они переиспользуются будущими провайдерами.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val descriptionProperties: DescriptionProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)

    init {
        check(claudeProperties.oauthToken.isNotBlank()) {
            "CLAUDE_CODE_OAUTH_TOKEN must be set when application.ai.description.enabled=true"
        }
        // CLI detection зависит от cliPath: пустой → which claude; non-empty → проверяем executable напрямую.
        if (claudeProperties.cliPath.isBlank()) {
            if (!Query.isCliInstalled()) {
                logger.warn {
                    "Claude CLI not found in PATH (Query.isCliInstalled()==false); all description " +
                        "requests will return fallback. Check Dockerfile ENV PATH=... and claude install."
                }
            }
        } else {
            val cliFile = java.nio.file.Path.of(claudeProperties.cliPath)
            if (!java.nio.file.Files.isExecutable(cliFile)) {
                logger.warn {
                    "Explicit claude.cli-path='${claudeProperties.cliPath}' not found or not executable; " +
                        "all description requests will return fallback."
                }
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        TODO("implemented in Task 10")
    }
}
```

`ClaudeDescriptionAgent` делается `@Component`. Конструктор принимает `DescriptionProperties` целиком (не `CommonSection` напрямую), т.к. `@ConfigurationProperties` регистрирует только корневой класс, а nested `CommonSection` не автоматически bean'ом. Внутри берём `.common`.

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentValidationTest`.
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeInvoker.kt modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentValidationTest.kt
git commit -m "feat(ai-description): ClaudeDescriptionAgent skeleton with startup validation"
```

---

### Task 10: ClaudeDescriptionAgent.describe() — retry, timeout, cleanup

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeDescriptionAgentTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val descriptionProps =
        DescriptionProperties(enabled = true, provider = "claude", common = common)

    private val claudeProps =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            workingDirectory = "/tmp",
            proxy = ClaudeProperties.ProxySection("", "", ""),
        )

    private val promptBuilder = mockk<ClaudePromptBuilder>()
    private val responseParser = ClaudeResponseParser(ObjectMapper().registerKotlinModule())
    private val imageStager = mockk<ClaudeImageStager>()
    private val exceptionMapper = ClaudeExceptionMapper()

    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private val stagedPaths: List<Path> = listOf(Path.of("/tmp/f.jpg"))
    private val okJson = """{"short": "s", "detailed": "d"}"""

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        every { promptBuilder.build(any(), any()) } returns "prompt"  // not suspend
    }

    private fun build(invoker: ClaudeInvoker, customCommon: DescriptionProperties.CommonSection = common) =
        ClaudeDescriptionAgent(
            claudeProps,
            DescriptionProperties(enabled = true, provider = "claude", common = customCommon),
            promptBuilder,
            responseParser,
            imageStager,
            invoker,
            exceptionMapper,
        )

    @Test
    fun `happy path returns parsed result and cleans up`() =
        runTest {
            val agent = build { okJson }
            val result = agent.describe(request)
            assertEquals(DescriptionResult("s", "d"), result)
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `retries once on invalid JSON then succeeds`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    if (calls == 1) "not json" else okJson
                }
            val agent = build(invoker)
            agent.describe(request)
            assertEquals(2, calls)
        }

    @Test
    fun `fails with InvalidResponse after two invalid JSONs`() =
        runTest {
            val agent = build { "not json" }
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
        }

    @Test
    fun `retries once on Transport then succeeds (virtual time)`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    if (calls == 1) throw DescriptionException.Transport() else okJson
                }
            val agent = build(invoker)
            // delay(5s) между attempts идёт через testScheduler virtual clock;
            // runTest сам продвигает время, но если нужно вручную — advanceTimeBy(5_000).
            agent.describe(request)
            assertEquals(2, calls)
        }

    @Test
    fun `fails with Transport after two Transport errors`() =
        runTest {
            val agent = build { throw DescriptionException.Transport() }
            assertFailsWith<DescriptionException.Transport> { agent.describe(request) }
        }

    @Test
    fun `RateLimited does not retry`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    throw DescriptionException.RateLimited()
                }
            val agent = build(invoker)
            assertFailsWith<DescriptionException.RateLimited> { agent.describe(request) }
            assertEquals(1, calls)
        }

    @Test
    fun `cleanup runs even when describe throws`() =
        runTest {
            val agent = build { throw DescriptionException.RateLimited() }
            runCatching { agent.describe(request) }
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `work timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val shortTimeoutCommon = common.copy(timeout = Duration.ofMillis(500))
            val agent = build(
                ClaudeInvoker {
                    gate.await()  // никогда не resolved — форсируем timeout
                    okJson
                },
                customCommon = shortTimeoutCommon,
            )
            val job = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            val outcome = job.await()
            assertFailsWith<DescriptionException.Timeout> { outcome.getOrThrow() }
        }

    @Test
    fun `queue timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            // maxConcurrent=1, permit захвачен "бесконечной" задачей — второй запрос упадёт по queue-timeout.
            val busyCommon = common.copy(
                maxConcurrent = 1,
                queueTimeout = Duration.ofMillis(100),
                timeout = Duration.ofSeconds(60),
            )
            val blocker = CompletableDeferred<Unit>()
            val agent = build(
                ClaudeInvoker {
                    blocker.await()
                    okJson
                },
                customCommon = busyCommon,
            )
            val first = async { agent.describe(request) }   // захватит permit, будет ждать
            advanceTimeBy(1)
            val second = async { runCatching { agent.describe(request) } }
            advanceTimeBy(200)
            advanceUntilIdle()
            val outcome = second.await()
            assertFailsWith<DescriptionException.Timeout> { outcome.getOrThrow() }
            blocker.complete(Unit)
            first.await()   // cleanup
        }

    @Test
    fun `third call waits for semaphore permit with maxConcurrent=2`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            val agent = build(
                ClaudeInvoker {
                    val current = inFlight.incrementAndGet()
                    maxSeen.updateAndGet { kotlin.math.max(it, current) }
                    delay(100)
                    inFlight.decrementAndGet()
                    okJson
                }
            )
            coroutineScope {
                repeat(3) { launch { agent.describe(request) } }
            }
            assertTrue(maxSeen.get() <= 2)
        }
}
```

Импорты для concurrency-теста (добавить в шапку файла вместе с остальными):
```kotlin
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentTest`.
Expected: FAIL — `describe()` throws `NotImplementedError`.

- [ ] **Step 3: Replace the TODO body with full implementation**

Replace the body of `describe()` in `ClaudeDescriptionAgent.kt`. The full file becomes:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val descriptionProperties: DescriptionProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)

    init {
        check(claudeProperties.oauthToken.isNotBlank()) {
            "CLAUDE_CODE_OAUTH_TOKEN must be set when application.ai.description.enabled=true"
        }
        if (!Query.isCliInstalled()) {
            logger.warn {
                "Claude CLI not found in PATH; all description requests will return fallback."
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        // Acquire с queue-timeout-ом (если очередь забита, не ждём вечно).
        try {
            withTimeout(commonSection.queueTimeout.toMillis()) {
                semaphore.acquire()
            }
        } catch (e: TimeoutCancellationException) {
            throw DescriptionException.Timeout(cause = e)  // превышен queue-timeout
        }

        val callStart = System.nanoTime()
        try {
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    val stagedPaths = imageStager.stage(request)
                    try {
                        val prompt = promptBuilder.build(request, stagedPaths)
                        executeWithRetry(prompt, request)
                    } finally {
                        imageStager.cleanup(stagedPaths)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)  // превышен work-timeout
            }
        } finally {
            val elapsedMs = (System.nanoTime() - callStart) / 1_000_000
            logger.debug { "Claude describe completed in ${elapsedMs}ms for recording ${request.recordingId}" }
            semaphore.release()
        }
    }

    private suspend fun executeWithRetry(
        prompt: String,
        request: DescriptionRequest,
    ): DescriptionResult {
        val overallStart = TimeSource.Monotonic.markNow()
        val totalBudget = commonSection.timeout.toKotlinDuration()
        var jsonRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val raw =
                    try {
                        invoker.invoke(prompt)
                    } catch (e: Throwable) {
                        throw exceptionMapper.map(e)   // CancellationException rethrown, остальное -> DescriptionException
                    }
                return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
            } catch (e: DescriptionException.InvalidResponse) {
                if (jsonRetries >= 1) throw e
                jsonRetries++
                logger.warn(e) { "Invalid JSON from Claude, retrying (attempt ${jsonRetries + 1})" }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                val elapsed = overallStart.elapsedNow()
                val remaining = totalBudget - elapsed
                if (remaining <= 7.seconds) {  // 5s delay + minimum attempt headroom
                    logger.warn(e) { "Claude transport error but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Claude transport error, retrying in 5s (remaining budget=$remaining)" }
                delay(5.seconds)
            }
            // RateLimited пробрасывается без retry.
            // Timeout НЕ достигнет этого места — он генерируется на границе describe() после withTimeout.
        }
    }
}
```

**Ключевые изменения vs исходного плана:**
- Agent — `@Component` с двумя `@ConditionalOnProperty` (enabled + provider). Убрал `semaphore.withPermit` — вручную через `acquire`/`release` чтобы иметь отдельные timeout-ы на acquire и работу.
- Два timeout-а: `queueTimeout` (acquire), `timeout` (stage + retry loop).
- Оба `TimeoutCancellationException` явно ловятся и оборачиваются в `DescriptionException.Timeout` — Telegram-слой получает нормализованный тип.
- Retry-loop для Transport проверяет оставшийся бюджет перед `delay(5s)` — если `remaining <= 7s`, не уходит в delay, а пробрасывает текущий Transport с WARN "budget exhausted".
- `exceptionMapper.map(e)` — CancellationException rethrown as-is (см. Task 8).
- Добавлен DEBUG-лог latency (`System.nanoTime` around work).

- [ ] **Step 4: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentTest`.
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt
git commit -m "feat(ai-description): implement ClaudeDescriptionAgent.describe with retry and timeout"
```

---

### Task 11: DefaultClaudeInvoker (SDK call adapter)

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/DefaultClaudeInvoker.kt`

No test — this is a thin wiring layer covered end-to-end by the integration test in Task 23.

**Важные факты SDK 1.0.0 (проверено по sources jar):**
- `ClaudeAsyncClient` **не** `AutoCloseable` — `.use { }` не применим (interface не наследует Closeable).
- `close()` возвращает `Mono<Void>` (подписываем через `awaitSingleOrNull`).
- `connect()` возвращает `Mono<Void>`.
- `query(prompt).text()` возвращает `Mono<String>` — готовый ассистентский текст, без manual парсинга `AssistantMessage`/`TextBlock`. Это основной API.
- `queryAndReceive(prompt)` **deprecated since 1.0.0** (`forRemoval = true`). Не использовать.

- [ ] **Step 1: Implement adapter**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class DefaultClaudeInvoker(
    private val clientFactory: ClaudeAsyncClientFactory,
    descriptionProperties: DescriptionProperties,
) : ClaudeInvoker {
    // Инвокер зависит только от work-timeout — берём его из properties в конструкторе,
    // чтобы ловить конфигурационные ошибки на этапе DI, а не при первом вызове.
    private val workTimeout: java.time.Duration = descriptionProperties.common.timeout

    override suspend fun invoke(prompt: String): String {
        val client = clientFactory.create(workTimeout)
        try {
            client.connect().awaitSingleOrNull()  // Mono<Void>
            return client.query(prompt).text().awaitSingle()  // Mono<String>
        } finally {
            // ClaudeAsyncClient НЕ AutoCloseable — .use недопустим. Закрываем явно.
            client.close().awaitSingleOrNull()
        }
    }
}
```

Конструктор принимает `DescriptionProperties` (Spring бинит только root), но класс хранит только нужный `workTimeout: Duration` — остальная часть properties отбрасывается. Это защищает `invoke()` от неожиданных зависимостей при будущих изменениях `DescriptionProperties.common`.

- [ ] **Step 2: Build (no new tests)**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/DefaultClaudeInvoker.kt
git commit -m "feat(ai-description): add DefaultClaudeInvoker for real SDK calls"
```

---

### Task 12: ~~Spring wiring — ClaudeAgentConfig~~ (УДАЛЕНА)

Этот task был удалён по результатам внешнего ревью (итерация 1). Причина: design §5 требует `@Component` на `ClaudeDescriptionAgent`/`DefaultClaudeInvoker` с `@ConditionalOnProperty`, но план пытался делать их через `@Bean`-фабрики в `@Configuration`-классе, причём с двойным `@ConditionalOnProperty` (на класс + на каждый бин), что создаёт пустой конфиг-класс при `provider != claude`.

Решение: `ClaudeDescriptionAgent` (Task 9/10) и `DefaultClaudeInvoker` (Task 11) — `@Component` с `@ConditionalOnProperty(enabled=true AND provider=claude)`. Все helpers (`ClaudePromptBuilder`, `ClaudeResponseParser`, `ClaudeImageStager`, `ClaudeAsyncClientFactory`, `ClaudeExceptionMapper`) — `@Component` с `@ConditionalOnProperty(enabled=true)` (переиспользуются будущими провайдерами). `AiDescriptionAutoConfiguration` (Task 3.5) делает `@ComponentScan` этого пакета.

Ничего создавать/коммитить в рамках этого task — пропускаем.

---

## Phase 4 — Core integration

### Task 13: DescriptionScopeConfig + TempFileWriter adapter

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/DescriptionScopeConfig.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/TempFileWriterAdapter.kt`
- Modify: `modules/core/build.gradle.kts`

- [ ] **Step 1: Add ai-description dependency to core**

In `modules/core/build.gradle.kts`, under `dependencies {` block, add after `implementation(project(":frigate-analyzer-telegram"))`:

```kotlin
    implementation(project(":frigate-analyzer-ai-description"))
```

- [ ] **Step 2: Create TempFileWriterAdapter**

Create `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/TempFileWriterAdapter.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import java.nio.file.Path

@Configuration
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class TempFileWriterAdapter {
    @Bean
    fun tempFileWriter(tempFileHelper: TempFileHelper): TempFileWriter =
        object : TempFileWriter {
            override suspend fun createTempFile(
                prefix: String,
                suffix: String,
                content: ByteArray,
            ): Path = tempFileHelper.createTempFile(prefix, suffix, content)

            override suspend fun deleteFiles(files: List<Path>): Int = tempFileHelper.deleteFiles(files)
        }
}
```

Импорт — из `api/` пакета (был `claude/`, переехал в Task 2). Adapter условен по `enabled=true` — при выключенной фиче не создаётся.

- [ ] **Step 2.1: Add TempFileWriterAdapterTest**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/TempFileWriterAdapterTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TempFileWriterAdapterTest {
    private val tempFileHelper = mockk<TempFileHelper>()
    private val writer = TempFileWriterAdapter().tempFileWriter(tempFileHelper)

    @Test
    fun `createTempFile delegates to TempFileHelper`() = runTest {
        val expected = Path.of("/tmp/test.jpg")
        coEvery { tempFileHelper.createTempFile("pref", ".jpg", any()) } returns expected

        val result = writer.createTempFile("pref", ".jpg", ByteArray(1))

        assertEquals(expected, result)
        coVerify(exactly = 1) { tempFileHelper.createTempFile("pref", ".jpg", any()) }
    }

    @Test
    fun `deleteFiles delegates to TempFileHelper and returns count`() = runTest {
        val paths = listOf(Path.of("/tmp/a"), Path.of("/tmp/b"))
        coEvery { tempFileHelper.deleteFiles(paths) } returns 2

        val count = writer.deleteFiles(paths)

        assertEquals(2, count)
        coVerify(exactly = 1) { tempFileHelper.deleteFiles(paths) }
    }
}
```

Adapter — тонкий делегат, но без теста легко пропустить рассинхронизацию сигнатур при будущем рефакторинге `TempFileHelper`.

- [ ] **Step 3: Create DescriptionScopeConfig**

Create `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/DescriptionScopeConfig.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Scope for describe-jobs kicked off from RecordingProcessingFacade.
 *
 * SupervisorJob изолирует ошибки одной джобы от других. @PreDestroy отменяет pending jobs
 * на shutdown с коротким grace-окном.
 *
 * ВАЖНО: scope создаётся ВСЕГДА, без @ConditionalOnProperty. Facade инжектит его
 * как обязательный параметр; при enabled=false — agentProvider.getIfAvailable() == null,
 * scope просто простаивает (idle SupervisorJob дешёв). Убрав условность, мы избегаем
 * NoSuchBeanDefinitionException при старте с дефолтным enabled=false.
 */
@Component
open class DescriptionCoroutineScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Description coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L
    }
}
```

- [ ] **Step 4: ~~Register @ConfigurationProperties classes~~ (СНЯТО)**

Эта задача перенесена в `AiDescriptionAutoConfiguration` (Task 3.5). В `FrigateAnalyzerApplication.kt` НЕ добавляем `DescriptionProperties`/`ClaudeProperties` в `@EnableConfigurationProperties` — они регистрируются autoconfig'ом самого модуля.

- [ ] **Step 4.5: Move YAML section here (moved from Task 17)**

Ранее YAML-секция появлялась только в Task 17, что создавало интервал между Task 13 (код использует properties) и Task 17 (default-значения в yaml) — приложение не могло стартовать. Переносим YAML в эту задачу:

Open `modules/core/src/main/resources/application.yaml`. After the `application.telegram` block (ends with `port: ${TELEGRAM_PROXY_PORT:1080}`) and before `application.detection-filter:`, insert:

```yaml
  ai:
    description:
      enabled: ${APP_AI_DESCRIPTION_ENABLED:false}
      provider: ${APP_AI_DESCRIPTION_PROVIDER:claude}
      common:
        language: ${APP_AI_DESCRIPTION_LANGUAGE:en}
        short-max-length: ${APP_AI_DESCRIPTION_SHORT_MAX:200}
        detailed-max-length: ${APP_AI_DESCRIPTION_DETAILED_MAX:1500}
        max-frames: ${APP_AI_DESCRIPTION_MAX_FRAMES:10}
        queue-timeout: ${APP_AI_DESCRIPTION_QUEUE_TIMEOUT:30s}
        timeout: ${APP_AI_DESCRIPTION_TIMEOUT:60s}
        max-concurrent: ${APP_AI_DESCRIPTION_MAX_CONCURRENT:2}
      claude:
        oauth-token: ${CLAUDE_CODE_OAUTH_TOKEN:}
        model: ${CLAUDE_MODEL:opus}
        cli-path: ${CLAUDE_CLI_PATH:}
        working-directory: ${CLAUDE_WORKING_DIR:${application.temp-folder}}
        proxy:
          http: ${CLAUDE_HTTP_PROXY:}
          https: ${CLAUDE_HTTPS_PROXY:}
          no-proxy: ${CLAUDE_NO_PROXY:}
```

`working-directory` по умолчанию берёт `application.temp-folder` (уже существующий — `/tmp/frigate-analyzer/`) через Spring property reference. Это исключает необходимость отдельной env-переменной для типичного случая.

- [ ] **Step 5: Build**

Dispatch `build-runner` with `./gradlew build -x test`.
Expected: BUILD SUCCESSFUL (module compiles, wiring intact).

- [ ] **Step 6: Commit**

```bash
git add modules/core/build.gradle.kts \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/DescriptionScopeConfig.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/TempFileWriterAdapter.kt \
        modules/core/src/main/resources/application.yaml
git commit -m "feat(core): wire ai-description module with scope, temp-file adapter and yaml"
```

---

### Task 14: RecordingProcessingFacade — start describe-job via supplier

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt` (interface)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` (impl)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt` (noop — **обязательно**, иначе compile error)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`

**Ключевые архитектурные решения:**
1. **Supplier pattern** — facade передаёт `() -> Deferred<Result<DescriptionResult>>?`, а не готовый `Deferred`. Supplier вызывается Telegram-слоем только после того, как убедится что уведомление реально уйдёт (есть подписчики). Иначе AI-токены тратятся впустую. Supplier nullable — при `enabled=false` или пустых frames — `null`.
2. **10-frame limit** — `take(descriptionProperties.common.maxFrames)` после сортировки по `frameIndex`, согласовано с design §2.
3. **Empty-frames guard** — если после `take` пусто, supplier = null, describe не запускается.
4. **NoOp impl обязательно обновляется** в том же Task'е — иначе signature mismatch ломает build.
5. **TelegramNotificationService interface** с default = null (Kotlin override может опускать default, но параметр принимает — проверить).

- [ ] **Step 1: Add ai-description dependency to telegram module**

In `modules/telegram/build.gradle.kts`, under `dependencies {`, add:

```kotlin
    implementation(project(":frigate-analyzer-ai-description"))
```

- [ ] **Step 2: Widen the TelegramNotificationService interface**

Replace the content of `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramNotificationService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData

interface TelegramNotificationService {
    suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        /**
         * Supplier стартует describe-job LAZY — только после того как Telegram-слой
         * убедится что уведомление реально уйдёт хотя бы одному подписчику. Иначе
         * Claude будет тратить токены на записи без получателей.
         *
         * null — фича выключена или нет кадров (supplier сам вернёт null если решит не стартовать).
         */
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)? = null,
    )
}
```

- [ ] **Step 3: Update TelegramNotificationServiceImpl signature**

In `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`:

```kotlin
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)?,
    ) {
        // ...existing early-return logic (filter subscribers, check zones, etc.)...
        // СРАЗУ ПОСЛЕ фильтрации — если есть хотя бы один получатель:
        val descriptionHandle = descriptionSupplier?.invoke()  // LAZY start

        usersWithZones.forEach { (user, zones) ->
            val task = NotificationTask(
                // existing fields...
                descriptionHandle = descriptionHandle,  // shared Deferred между всеми получателями одного recording
            )
            // enqueue task
        }
    }
```

Add imports:
```kotlin
import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
```

**Ключевой момент:** `supplier.invoke()` вызывается ПОСЛЕ `usersWithZones`-фильтрации. Если zone-filter отсеял всех получателей и `usersWithZones.isEmpty()` → возвращаемся рано, supplier не вызывается, Claude не запускается. Все получатели одного recording разделяют один и тот же `Deferred` (так пишет design §6 — каждый target имеет свой placeholder, но Claude-результат один).

- [ ] **Step 3.1: Update NoOpTelegramNotificationService (ОБЯЗАТЕЛЬНО)**

Найти файл `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/NoOpTelegramNotificationService.kt` и обновить override:

```kotlin
import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
// ...
override suspend fun sendRecordingNotification(
    recording: RecordingDto,
    visualizedFrames: List<VisualizedFrameData>,
    descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>?)?,
) {
    // no-op, параметр игнорируется
}
```

**Без этого Step build упадёт** — Kotlin требует override сигнатуру parent'а, default-значения в override не допускаются.

- [ ] **Step 4: Update NotificationTask data class**

Replace content of `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import java.time.Instant
import java.util.UUID

data class NotificationTask(
    val id: UUID,
    val chatId: Long,
    val message: String,
    val visualizedFrames: List<VisualizedFrameData>,
    /** ID of the recording, used for callback data in inline export buttons. */
    val recordingId: UUID,
    val language: String? = null,
    /**
     * Shared Deferred между всеми получателями одного recording (один AI-запрос —
     * N edit'ов по получателям). Запускается в Impl.sendRecordingNotification
     * ПОСЛЕ фильтрации подписчиков, перед enqueue каждого task'а.
     * null — фича выключена / нет кадров / нет подписчиков.
     */
    val descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    val createdAt: Instant = Instant.now(),
)
```

- [ ] **Step 5: Write failing test for facade behavior**

Modify (create if missing) `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`. Add these focused tests (leave any existing content intact; if file doesn't exist, start with this full content):

```kotlin
package ru.zinin.frigate.analyzer.core.facade

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecordingProcessingFacadeTest {
    private val recordingEntityService = mockk<RecordingEntityService>()
    private val telegramNotificationService = mockk<TelegramNotificationService>(relaxed = true)
    private val frameVisualizationService = mockk<FrameVisualizationService>()

    private val recordingId = UUID.randomUUID()
    private val recording =
        RecordingDto(
            id = recordingId,
            filePath = "/path/to/rec.mp4",
            camId = "cam1",
            detectionsCount = 2,
            analyzedFramesCount = 3,
            analyzeTime = 10,
            recordTimestamp = Instant.now(),
            processTimestamp = Instant.now(),
        )

    init {
        coEvery { frameVisualizationService.visualizeFrames(any()) } returns emptyList<VisualizedFrameData>()
        coEvery { recordingEntityService.saveProcessingResult(any()) } returns Unit
        coEvery { recordingEntityService.getRecording(recordingId) } returns recording
    }

    private fun facade(
        agent: DescriptionAgent?,
        framesForRequest: List<FrameData> = listOf(FrameData(0, ByteArray(1))),
    ): Pair<RecordingProcessingFacade, SaveProcessingResultRequest> {
        val provider = mockk<ObjectProvider<DescriptionAgent>>()
        every { provider.getIfAvailable() } returns agent  // non-suspend
        // UnconfinedTestDispatcher — корутины исполняются сразу в runTest, supplier().await() не залипнет.
        val scope = DescriptionCoroutineScope(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()))
        val props =
            DescriptionProperties(
                enabled = agent != null,
                provider = "claude",
                common =
                    DescriptionProperties.CommonSection(
                        language = "en",
                        shortMaxLength = 200,
                        detailedMaxLength = 1500,
                        maxFrames = 10,
                        queueTimeout = java.time.Duration.ofSeconds(30),
                        timeout = java.time.Duration.ofSeconds(60),
                        maxConcurrent = 2,
                    ),
            )
        val facade = RecordingProcessingFacade(
            recordingEntityService = recordingEntityService,
            telegramNotificationService = telegramNotificationService,
            frameVisualizationService = frameVisualizationService,
            descriptionAgentProvider = provider,
            descriptionScope = scope,
            descriptionProperties = props,
        )
        return facade to SaveProcessingResultRequest(recordingId = recordingId, frames = framesForRequest)
    }

    private suspend fun captureSupplierDuring(
        block: suspend () -> Unit,
    ): (() -> Deferred<Result<DescriptionResult>>?)? {
        var captured: (() -> Deferred<Result<DescriptionResult>>?)? = null
        coEvery {
            telegramNotificationService.sendRecordingNotification(any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            captured = thirdArg() as? () -> Deferred<Result<DescriptionResult>>?
            Unit
        }
        block()
        return captured
    }

    @Test
    fun `agent disabled produces null supplier`() =
        runTest {
            val (f, req) = facade(agent = null)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNull(supplier)
        }

    @Test
    fun `agent enabled but empty frames produces null supplier`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            val (f, req) = facade(agent, framesForRequest = emptyList())
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNull(supplier)
        }

    @Test
    fun `agent enabled with frames produces non-null supplier that starts describe lazily`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            assertNotNull(supplier)
            // supplier ещё не вызван — describe не должен был запуститься
            coVerify(exactly = 0) { agent.describe(any()) }
            val handle = supplier!!.invoke()
            assertNotNull(handle)
            val outcome = handle!!.await()
            assertEquals(DescriptionResult("s", "d"), outcome.getOrNull())
        }

    @Test
    fun `exception in describe is captured in Result_failure, does not break facade`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers {
                delay(1)
                throw IllegalStateException("boom")
            }

            val (f, req) = facade(agent)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            val outcome = supplier!!.invoke()!!.await()
            assertEquals(true, outcome.isFailure)
        }

    @Test
    fun `frame limit 10 is applied when more frames present`() =
        runTest {
            val manyFrames = (0..49).map { FrameData(it, ByteArray(1)) }
            val agent = mockk<DescriptionAgent>()
            val captured = slot<DescriptionRequest>()
            coEvery { agent.describe(capture(captured)) } coAnswers { DescriptionResult("s", "d") }

            val (f, req) = facade(agent, framesForRequest = manyFrames)
            val supplier = captureSupplierDuring { f.processAndNotify(req) }
            supplier!!.invoke()!!.await()

            assertEquals(10, captured.captured.frames.size)
            assertEquals((0..9).toList(), captured.captured.frames.map { it.frameIndex })
        }
}
```

Импорты для теста (обновить):
```kotlin
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.test.StandardTestDispatcher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.model.dto.FrameData
```

- [ ] **Step 6: Run test to verify it fails**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-core:test --tests RecordingProcessingFacadeTest`.
Expected: FAIL — facade constructor does not match.

- [ ] **Step 7: Update RecordingProcessingFacade**

Replace the full content of `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.facade

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
import ru.zinin.frigate.analyzer.model.request.SaveProcessingResultRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class RecordingProcessingFacade(
    private val recordingEntityService: RecordingEntityService,
    private val telegramNotificationService: TelegramNotificationService,
    private val frameVisualizationService: FrameVisualizationService,
    private val descriptionAgentProvider: ObjectProvider<DescriptionAgent>,
    private val descriptionScope: DescriptionCoroutineScope,
    private val descriptionProperties: DescriptionProperties,
) {
    suspend fun processAndNotify(
        request: SaveProcessingResultRequest,
        failedFramesCount: Int = 0,
    ) {
        val recordingId = request.recordingId

        if (failedFramesCount > 0) {
            logger.warn {
                "Recording $recordingId has $failedFramesCount failed frames, " +
                    "skipping save (will retry automatically)"
            }
            return
        }

        val visualizedFrames = frameVisualizationService.visualizeFrames(request.frames)

        try {
            recordingEntityService.saveProcessingResult(request)
            val recording = recordingEntityService.getRecording(recordingId)
            if (recording != null) {
                // Supplier передаётся в Telegram-слой, вызывается после фильтрации получателей.
                val descriptionSupplier = buildDescriptionSupplier(recordingId, request)
                try {
                    telegramNotificationService.sendRecordingNotification(
                        recording,
                        visualizedFrames,
                        descriptionSupplier,
                    )
                } catch (e: CancellationException) {
                    throw e   // structured concurrency — не глотаем отмену
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send telegram notification for recording $recordingId" }
                }
            } else {
                logger.warn { "Recording $recordingId not found after saving, skipping notification" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to save processing result for recording $recordingId" }
            throw e
        }
    }

    /**
     * Возвращает supplier для lazy-старта describe-job. Supplier ↔ null — agent
     * отсутствует (enabled=false/provider mismatch) ИЛИ нет кадров. Сам supplier
     * внутри может вернуть null (ещё раз проверяет frames на момент вызова).
     */
    private fun buildDescriptionSupplier(
        recordingId: UUID,
        request: SaveProcessingResultRequest,
    ): (() -> Deferred<Result<DescriptionResult>>?)? {
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null

        val common = descriptionProperties.common
        val trimmedFrames = request.frames
            .sortedBy { it.frameIndex }
            .take(common.maxFrames)
            .map { DescriptionRequest.FrameImage(it.frameIndex, it.frameBytes) }

        if (trimmedFrames.isEmpty()) {
            logger.debug { "No frames for recording $recordingId; skipping describe-job" }
            return null
        }

        val descriptionRequest = DescriptionRequest(
            recordingId = recordingId,
            frames = trimmedFrames,
            language = common.language,
            shortMaxLength = common.shortMaxLength,
            detailedMaxLength = common.detailedMaxLength,
        )

        return {
            descriptionScope.async {
                runCatching { agent.describe(descriptionRequest) }
            }
        }
    }
}
```

**Изменения vs исходного плана:**
- `startDescribeJob` заменён на `buildDescriptionSupplier` — возвращает функцию, не готовый Deferred.
- Добавлены `CancellationException` rethrow в двух `catch (e: Exception)` блоках — structured concurrency не сломана.
- 10-frame limit через `.take(common.maxFrames)` после сортировки.
- Пустой список frames → supplier = null, describe не запускается.

- [ ] **Step 8: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-core:test --tests RecordingProcessingFacadeTest`.
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
git add modules/telegram/build.gradle.kts modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/ modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt
git commit -m "feat(core): kick off describe job from RecordingProcessingFacade"
```

---

## Phase 5 — Telegram integration

### Task 15: DescriptionMessageFormatter

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatter.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatterTest.kt`

- [ ] **Step 1: Add i18n keys**

Edit `modules/telegram/src/main/resources/messages_en.properties`, append:

```properties

# AI description
ai.description.placeholder.short=⏳ <i>AI is analyzing frames…</i>
ai.description.placeholder.detailed=⏳ <i>AI is preparing the detailed description…</i>
ai.description.fallback.unavailable=⚠ <i>Description unavailable</i>
```

Edit `modules/telegram/src/main/resources/messages_ru.properties`, append:

```properties

# AI description
ai.description.placeholder.short=⏳ <i>AI анализирует кадры…</i>
ai.description.placeholder.detailed=⏳ <i>AI готовит подробное описание…</i>
ai.description.fallback.unavailable=⚠ <i>Описание недоступно</i>
```

Ключ `notification.recording.export.prompt.with.description` из исходного плана **не добавляем** — он был пустым и не используется (дубликат от ревью, замечание устранено).

- [ ] **Step 2: Write failing test**

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptionMessageFormatterTest {
    private val resolver =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val formatter = DescriptionMessageFormatter(resolver)

    @Test
    fun `escapes HTML specials in short and detailed`() {
        val result = DescriptionResult(short = "A <b>car</b> & person", detailed = "Full <text>")
        val caption = formatter.captionSuccess(baseText = "base", result = result, language = "en")
        assertTrue(caption.contains("&lt;b&gt;car&lt;/b&gt; &amp; person"))
        assertTrue(!caption.contains("<b>car</b>"))
    }

    @Test
    fun `escapes HTML in baseText too (camId or filePath may contain specials)`() {
        val result = DescriptionResult(short = "s", detailed = "d")
        val caption = formatter.captionSuccess(
            baseText = "Zone <Entrance> & gate",
            result = result,
            language = "en",
        )
        assertTrue(caption.contains("Zone &lt;Entrance&gt; &amp; gate"))
        assertTrue(!caption.contains("<Entrance>"))
    }

    @Test
    fun `expandableBlockquoteSuccess escapes HTML in detailed`() {
        val result = DescriptionResult("s", "a <b> & <c>")
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertEquals("<blockquote expandable>a &lt;b&gt; &amp; &lt;c&gt;</blockquote>", block)
    }

    @Test
    fun `placeholderShort returns HTML-safe language-specific string`() {
        val pl = formatter.placeholderShort("ru")
        assertTrue(pl.contains("AI анализирует"))
    }

    @Test
    fun `expandableBlockquoteSuccess wraps detailed in blockquote`() {
        val result = DescriptionResult(short = "s", detailed = "Detailed text")
        val block = formatter.expandableBlockquoteSuccess(result, "en")
        assertEquals("<blockquote expandable>Detailed text</blockquote>", block)
    }

    @Test
    fun `expandableBlockquoteFallback uses localized unavailable text`() {
        val block = formatter.expandableBlockquoteFallback("ru")
        assertTrue(block.startsWith("<blockquote expandable>"))
        assertTrue(block.endsWith("</blockquote>"))
        assertTrue(block.contains("Описание недоступно"))
    }

    @Test
    fun `captionSuccess appends short under base with blank line`() {
        val result = DescriptionResult(short = "two cars", detailed = "ignored")
        val caption = formatter.captionSuccess(baseText = "base text", result = result, language = "en")
        assertEquals("base text\n\ntwo cars", caption)
    }

    @Test
    fun `captionFallback appends fallback text under base`() {
        val caption = formatter.captionFallback(baseText = "base", language = "en")
        assertTrue(caption.startsWith("base\n\n"))
        assertTrue(caption.contains("Description unavailable"))
    }

    @Test
    fun `captionInitialPlaceholder appends placeholder under base`() {
        val caption = formatter.captionInitialPlaceholder(baseText = "base", language = "en")
        assertTrue(caption.startsWith("base\n\n"))
        assertTrue(caption.contains("analyzing"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests DescriptionMessageFormatterTest`.
Expected: FAIL — class not found.

- [ ] **Step 4: Implement DescriptionMessageFormatter**

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionMessageFormatter(
    private val msg: MessageResolver,
) {
    fun captionInitialPlaceholder(
        baseText: String,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${msg.get(KEY_PLACEHOLDER_SHORT, language)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun captionSuccess(
        baseText: String,
        result: DescriptionResult,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${htmlEscape(result.short)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun captionFallback(
        baseText: String,
        language: String,
        maxLength: Int = MAX_CAPTION_LENGTH,
    ): String {
        val suffix = "\n\n${msg.get(KEY_FALLBACK, language)}"
        return "${escapeAndTrim(baseText, maxLength - suffix.length)}$suffix"
    }

    fun placeholderShort(language: String): String = msg.get(KEY_PLACEHOLDER_SHORT, language)

    fun placeholderDetailedExpandable(language: String): String =
        "<blockquote expandable>${msg.get(KEY_PLACEHOLDER_DETAILED, language)}</blockquote>"

    fun expandableBlockquoteSuccess(
        result: DescriptionResult,
        language: String,
    ): String = "<blockquote expandable>${htmlEscape(result.detailed)}</blockquote>"

    fun expandableBlockquoteFallback(language: String): String =
        "<blockquote expandable>${msg.get(KEY_FALLBACK, language)}</blockquote>"

    /**
     * Возвращает длину HTML-overhead для caption при включённом описании.
     * Используется sender'ом для бюджета truncation (см. design §6 "Caption 1024-лимит").
     */
    fun captionPlaceholderOverhead(language: String): Int =
        "\n\n".length + msg.get(KEY_PLACEHOLDER_SHORT, language).length

    /**
     * HTML-escape для Telegram HTML parse mode. Экранируем `<`, `>`, `&`.
     * `"` и `'` в text content экранировать НЕ требуется (Telegram HTML это не требует,
     * это нужно только для attribute values, которых мы не формируем).
     */
    private fun htmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * HTML-aware truncation: escape(text) → если укладывается в budget, вернуть;
     * иначе обрезать, не разрывая HTML-entity. Обязательно вызывать на `baseText`
     * внутри формирования caption — обычный `substring` после escape может разорвать
     * `&amp;`/`&lt;`/`&gt;` и превратить Telegram HTML в битый.
     */
    private fun escapeAndTrim(text: String, budget: Int): String {
        if (budget <= 0) return ""
        val escaped = htmlEscape(text)
        if (escaped.length <= budget) return escaped
        var cutoff = budget - 1  // резерв под ellipsis
        val lastAmp = escaped.lastIndexOf('&', startIndex = (cutoff - 1).coerceAtLeast(0))
        if (lastAmp >= 0) {
            val entityEnd = escaped.indexOf(';', startIndex = lastAmp)
            if (entityEnd < 0 || entityEnd >= cutoff) {
                cutoff = lastAmp
            }
        }
        return escaped.substring(0, cutoff.coerceAtLeast(0)) + "…"
    }

    companion object {
        private const val MAX_CAPTION_LENGTH = 1024
        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests DescriptionMessageFormatterTest`.
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/resources/messages_en.properties modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatter.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatterTest.kt
git commit -m "feat(telegram): add DescriptionMessageFormatter and i18n keys"
```

---

### Task 16: TelegramNotificationSender — placeholder + edit flow

**Context:** Это ключевой рефактор. HTML parse mode активируется **только** когда `descriptionHandle != null`. При null — старый flow без изменений.

**Ключевые решения (результат итерации 1 ревью):**
- **`DescriptionEditJobRunner` — `@Component`** с собственным `CoroutineScope` + `@PreDestroy shutdown()` (не inline default-параметр). Паттерн по аналогии с `ExportCoroutineScope`.
- **ktgbotapi v32 корректный API** (проверено по sources библиотеки):
  - Используем suspend-extensions `bot.editMessageText(contentMessage, ...)` / `bot.editMessageCaption(contentMessage, ...)` из `dev.inmo.tgbotapi.extensions.api.edit.*` — совпадает с паттерном в `ExportExecutor.kt`. **НЕ** используем raw-классы `EditChatMessageText/Caption`.
  - `sendTextMessage(..., replyParameters = ReplyParameters(chatId, messageId), ...)` — **нет** `replyToMessageId`.
  - `bot.sendMediaGroup(...)` возвращает **один** `ContentMessage<MediaGroupContent<MediaGroupPartContent>>`, не `List`. Берём `group.messageId` напрямую.
  - `HTMLParseMode` импорт: `dev.inmo.tgbotapi.types.message.HTMLParseMode`.
  - `ContentMessage.messageId` — тип `MessageId` (value class). `MessageIdentifier` — deprecated typealias; не кастуем.
- **HTML-budget truncation** — `toCaption` вызывается на plain text ДО добавления HTML placeholder'а; бюджет уменьшается на `captionPlaceholderOverhead(lang)`.
- **Independent try/catch** — caption-edit и details-edit в `editOne` обёрнуты в отдельные `try/catch`, чтобы failure одного не блокировал второй.
- **MessageIsNotModifiedException** и `MessageToEditNotFoundException` из `dev.inmo.tgbotapi.bot.exceptions` ловятся специфично и логируются как DEBUG (ожидаемое поведение).
- **CancellationException** в каждом `catch (e: Exception)` внутри runner rethrow'ится (structured concurrency).

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditScope.kt` (собственный `@Component` scope для runner'а)
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt`

- [ ] **Step 1: Write failing tests (add to existing file)**

Append these test cases to `TelegramNotificationSenderTest.kt`:

```kotlin
    // NB: тесты sender'а проверяют WIRING (что метод вызван с правильными аргументами).
    // Финальная сборка ktgbotapi вызовов тестируется в интеграционном тесте.
    // Мокаем suspend-extensions через MockKStatic — существующий TelegramNotificationSenderTest
    // уже использует этот паттерн, продолжаем его же.

    @Test
    fun `disabled path with null descriptionHandle preserves current single-photo behavior`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames))

            // Ни один edit вызов не случился — потому что descriptionHandle=null.
            coVerify(exactly = 0) { anyConstructed<EditTarget>() }
        }

    @Test
    fun `single photo with description handle sends placeholder then edits on success`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            val photoMsg = mockk<ContentMessage<PhotoContent>>()
            every { photoMsg.messageId } returns MessageId(42L)
            val textMsg = mockk<ContentMessage<TextContent>>()
            every { textMsg.messageId } returns MessageId(43L)

            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery {
                bot.sendTextMessage(
                    chatId = any(),
                    text = any<String>(),
                    parseMode = any(),
                    linkPreviewOptions = any(),
                    threadId = any(),
                    directMessageThreadId = any(),
                    businessConnectionId = any(),
                    disableNotification = any(),
                    protectContent = any(),
                    allowPaidBroadcast = any(),
                    effectId = any(),
                    suggestedPostParameters = any(),
                    replyParameters = any(),
                    replyMarkup = any(),
                )
            } returns textMsg

            // editMessageCaption / editMessageText — suspend extensions; мокаем аналогично как в ExportExecutorTest.
            coEvery {
                bot.editMessageCaption(
                    chatId = any(), messageId = any<MessageId>(),
                    text = any<String>(), parseMode = any(), replyMarkup = any(),
                )
            } returns mockk(relaxed = true)
            coEvery {
                bot.editMessageText(
                    chatId = any(), messageId = any<MessageId>(),
                    text = any<String>(), parseMode = any(), replyMarkup = any(),
                )
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()   // тестовый DescriptionEditJobRunner возвращает последний job

            coVerify { bot.editMessageCaption(any(), any<MessageId>(), any<String>(), any(), any()) }
            coVerify { bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any()) }
        }

    @Test
    fun `single photo with description handle uses fallback on failure`() =
        runTest {
            val frames = listOf(VisualizedFrameData(0, ByteArray(1), 1))
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.failure(RuntimeException("boom")))

            val photoMsg = mockk<ContentMessage<PhotoContent>> {
                every { messageId } returns MessageId(42L)
            }
            val textMsg = mockk<ContentMessage<TextContent>> {
                every { messageId } returns MessageId(43L)
            }
            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery {
                bot.sendTextMessage(any(), any<String>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns textMsg

            val captionTextSlot = slot<String>()
            val detailsTextSlot = slot<String>()
            coEvery {
                bot.editMessageCaption(any(), any<MessageId>(), capture(captionTextSlot), any(), any())
            } returns mockk(relaxed = true)
            coEvery {
                bot.editMessageText(any(), any<MessageId>(), capture(detailsTextSlot), any(), any())
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()

            assertTrue(captionTextSlot.captured.contains("unavailable", ignoreCase = true))
            assertTrue(detailsTextSlot.captured.contains("unavailable", ignoreCase = true))
        }

    @Test
    fun `media group with description handle sends albums and single edit on success`() =
        runTest {
            val frames = (0..2).map { VisualizedFrameData(it, ByteArray(1), 1) }
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            // sendMediaGroup возвращает ОДИН ContentMessage<MediaGroupContent<...>> (value class messageId).
            val groupMsg = mockk<ContentMessage<MediaGroupContent<MediaGroupPartContent>>> {
                every { messageId } returns MessageId(50L)
            }
            coEvery { bot.sendMediaGroup(any(), any<List<TelegramMediaPhoto>>()) } returns groupMsg

            val textMsg = mockk<ContentMessage<TextContent>> {
                every { messageId } returns MessageId(51L)
            }
            coEvery {
                bot.sendTextMessage(any(), any<String>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns textMsg
            coEvery {
                bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any())
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()

            // Для media group только editMessageText; editMessageCaption НЕ вызывается.
            coVerify(exactly = 1) { bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any()) }
            coVerify(exactly = 0) { bot.editMessageCaption(any(), any<MessageId>(), any<String>(), any(), any()) }
        }
```

Обнови `createTask` и сетап:

```kotlin
    // Instead of passing raw runner instance, используем ObjectProvider — как в production.
    private val formatterProvider = mockk<ObjectProvider<DescriptionMessageFormatter>>()
    private val runnerProvider = mockk<ObjectProvider<DescriptionEditJobRunner>>()
    private val formatter = mockk<DescriptionMessageFormatter>(relaxed = true)

    // helper-runner для тестов — выставляет синхронный scope и отдаёт Job через getEditJob()
    private val runner = TestDescriptionEditJobRunner(bot, formatter)

    init {
        every { formatterProvider.getIfAvailable() } returns formatter
        every { runnerProvider.getIfAvailable() } returns runner
        every { formatter.captionPlaceholderOverhead(any()) } returns 0
        // Никаких захардкоженных возвратов для captionSuccess/Fallback/etc — пусть реальный htmlEscape работает:
        // but relaxed mock is OK since тесты не проверяют экранирование (это отдельный formatter-test).
    }

    private fun createTask(
        frames: List<VisualizedFrameData> = emptyList(),
        descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    ) = NotificationTask(
        id = UUID.randomUUID(),
        chatId = 12345L,
        message = "Test notification",
        visualizedFrames = frames,
        recordingId = recordingId,
        language = "ru",
        descriptionHandle = descriptionHandle,
    )
```

Add imports at the top of the test file:

```kotlin
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.MediaGroupContent
import dev.inmo.tgbotapi.types.media.MediaGroupPartContent
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter
```

**Реализация `TestDescriptionEditJobRunner`** (положить в тот же test-файл, перед классом `TelegramNotificationSenderTest`, либо в отдельный helper):

```kotlin
/**
 * Test double для DescriptionEditJobRunner. Использует UnconfinedTestDispatcher —
 * корутины исполняются сразу, `getEditJob()?.join()` возвращается моментально.
 * Паттерн скопирован с существующего TestExportExecutor (см. ExportExecutorTest.kt).
 */
private class TestDescriptionEditJobRunner(
    bot: TelegramBot,
    formatter: DescriptionMessageFormatter,
) : DescriptionEditJobRunner(
    bot = bot,
    formatter = formatter,
    scope = DescriptionEditScope.forTest(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
) {
    @Volatile
    private var lastJob: Job? = null

    override fun launchEditJob(
        targets: List<EditTarget>,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job {
        val job = super.launchEditJob(targets, handleOutcome)
        lastJob = job
        return job
    }

    fun getEditJob(): Job? = lastJob
}
```

Для поддержки test-scope в `DescriptionEditScope` добавлен фабричный метод `companion object { fun forTest(scope: CoroutineScope) = DescriptionEditScope(scope) }` — см. Step 3a. Альтернатива — сделать `DescriptionEditScope` `open` и подкласс с override-нутым `delegate`.

- [ ] **Step 2: Run test — expect failures**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: FAIL — new cases missing implementation.

- [ ] **Step 3a: Create DescriptionEditScope**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditScope.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Managed scope для edit-job'ов описаний в Telegram-слое. Аналог ExportCoroutineScope.
 *
 * Условен на enabled=true (без описаний edit-job'ы не запускаются вообще).
 * Отделён от DescriptionCoroutineScope (describe живёт в core, edit — в telegram).
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
open class DescriptionEditScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Description edit coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L
    }
}
```

- [ ] **Step 3b: Create DescriptionEditJobRunner**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

data class EditTarget(
    val chatId: ChatIdentifier,
    val captionMessageId: MessageId?,
    val detailsMessageId: MessageId,
    val baseText: String,               // raw text — formatter сам сделает escape+trim
    val captionBudget: Int,             // budget под итоговый HTML-caption (1024 − short overhead)
    val exportKeyboard: InlineKeyboardMarkup,
    val language: String,
    val isMediaGroup: Boolean,
)

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionEditJobRunner(
    private val bot: TelegramBot,
    private val formatter: DescriptionMessageFormatter,
    private val scope: DescriptionEditScope,
) {
    fun launchEditJob(
        targets: List<EditTarget>,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job =
        scope.launch {
            val outcome = handleOutcome()
            targets.forEach { target ->
                editOne(target, outcome)
            }
        }

    private suspend fun editOne(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        if (target.isMediaGroup) {
            editMediaGroup(target, outcome)
        } else {
            // Два независимых try/catch: если caption edit упал, details всё равно обновится.
            editSinglePhotoCaption(target, outcome)
            editSinglePhotoDetails(target, outcome)
        }
    }

    private suspend fun editMediaGroup(target: EditTarget, outcome: Result<DescriptionResult>) {
        // Для media group: caption в фото не трогаем, только reply-text. Бюджет здесь не caption-лимит (1024),
        // а Telegram text-message лимит (4096) — short+expandable детально умещаются. Используем captionBudget
        // как прокси "разумного размера" для верхнего куска, expandable идёт следом без сжатия.
        val newText = outcome.fold(
            onSuccess = { result ->
                val short = formatter.captionSuccess(target.baseText, result, target.language, target.captionBudget)
                "$short\n\n${formatter.expandableBlockquoteSuccess(result, target.language)}"
            },
            onFailure = {
                val short = formatter.captionFallback(target.baseText, target.language, target.captionBudget)
                "$short\n\n${formatter.expandableBlockquoteFallback(target.language)}"
            },
        )
        runEdit("media group details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = newText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoCaption(target: EditTarget, outcome: Result<DescriptionResult>) {
        val captionText = outcome.fold(
            onSuccess = { formatter.captionSuccess(target.baseText, it, target.language, target.captionBudget) },
            onFailure = { formatter.captionFallback(target.baseText, target.language, target.captionBudget) },
        )
        runEdit("single-photo caption", target) {
            bot.editMessageCaption(
                chatId = target.chatId,
                messageId = target.captionMessageId!!,
                text = captionText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoDetails(target: EditTarget, outcome: Result<DescriptionResult>) {
        val detailsText = outcome.fold(
            onSuccess = { formatter.expandableBlockquoteSuccess(it, target.language) },
            onFailure = { formatter.expandableBlockquoteFallback(target.language) },
        )
        runEdit("single-photo details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = detailsText,
                parseMode = HTMLParseMode,
            )
        }
    }

    private suspend fun runEdit(
        label: String,
        target: EditTarget,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e  // structured concurrency — не глотаем отмену
        } catch (e: MessageIsNotModifiedException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message is not modified" }
        } catch (e: MessageToEditNotFoundException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message not found" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to edit $label for chat=${target.chatId}; continuing" }
        }
    }
}
```

- [ ] **Step 4: Update TelegramNotificationSender**

Replace the full content of `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.send.media.SendPhoto
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Deferred
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.DescriptionEditJobRunner
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val msg: MessageResolver,
    // При enabled=false у description beanов нет — используем ObjectProvider чтобы sender
    // компилировался и работал без описаний.
    private val descriptionFormatter: ObjectProvider<DescriptionMessageFormatter>,
    private val editJobRunner: ObjectProvider<DescriptionEditJobRunner>,
) {
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val frames = task.visualizedFrames
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val formatter = descriptionFormatter.getIfAvailable()
        val withDescription = task.descriptionHandle != null && formatter != null

        // HTML-budget: formatter'у даём raw text + budget; он сам сделает HTML-aware escape+truncate
        // (см. DescriptionMessageFormatter.escapeAndTrim). Это гарантирует что финальный HTML-caption
        // укладывается в 1024 символа даже при наличии &<> в camId/filePath.
        val parseMode = if (withDescription) HTMLParseMode else null
        val captionInitial =
            if (withDescription) formatter!!.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
            else task.message.toCaption(MAX_CAPTION_LENGTH)

        // Для edit case: передаём raw text, formatter сам ужмёт под оставшийся бюджет после short-хвоста.
        // SHORT_MAX_LENGTH — pessimistic worst-case (@Max(500) из CommonSection).
        val editBaseBudget = if (withDescription) MAX_CAPTION_LENGTH - SHORT_MAX_LENGTH - "\n\n".length else MAX_CAPTION_LENGTH

        val targets = mutableListOf<EditTarget>()

        when {
            frames.isEmpty() -> {
                // Нет кадров — не прикрепляем placeholder/edit (некуда reply'ить).
                RetryHelper.retryIndefinitely("Send text message", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = captionInitial,
                        parseMode = parseMode,
                        replyMarkup = exportKeyboard,
                    )
                }
            }

            frames.size == 1 -> {
                val frame = frames.first()
                val photoMsg =
                    RetryHelper.retryIndefinitely("Send photo message", task.chatId) {
                        bot.execute(
                            SendPhoto(
                                chatId = chatIdObj,
                                photo = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                text = captionInitial,
                                parseMode = parseMode,
                                replyMarkup = exportKeyboard,
                            ),
                        )
                    }
                if (withDescription) {
                    val detailsMsg =
                        RetryHelper.retryIndefinitely("Send details placeholder", task.chatId) {
                            bot.sendTextMessage(
                                chatId = chatIdObj,
                                text = formatter!!.placeholderDetailedExpandable(lang),
                                parseMode = HTMLParseMode,
                                replyParameters = ReplyParameters(chatIdObj, photoMsg.messageId),
                            )
                        }
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = photoMsg.messageId,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = task.message,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = false,
                        ),
                    )
                }
            }

            else -> {
                var firstAlbumMessageId: MessageId? = null
                frames.chunked(MAX_MEDIA_GROUP_SIZE).forEachIndexed { chunkIndex, chunk ->
                    val group =
                        RetryHelper.retryIndefinitely("Send media group", task.chatId) {
                            val media =
                                chunk.mapIndexed { idx, frame ->
                                    TelegramMediaPhoto(
                                        file = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                        // first frame carries caption; formatter сам escape'ит если withDescription
                                        text =
                                            if (chunkIndex == 0 && idx == 0) {
                                                if (withDescription) {
                                                    formatter!!.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
                                                } else {
                                                    task.message.toCaption(MAX_CAPTION_LENGTH)
                                                }
                                            } else null,
                                    )
                                }
                            @Suppress("OPT_IN_USAGE")
                            bot.sendMediaGroup(chatIdObj, media)
                        }
                    if (chunkIndex == 0) {
                        // sendMediaGroup возвращает ContentMessage (не List) — messageId это id первого сообщения альбома.
                        firstAlbumMessageId = group.messageId
                    }
                }
                val albumBaseText = msg.get("notification.recording.export.prompt", lang)
                val promptInitial =
                    if (withDescription) {
                        albumBaseText +
                            "\n\n" +
                            formatter!!.placeholderShort(lang) +
                            "\n\n" +
                            formatter.placeholderDetailedExpandable(lang)
                    } else {
                        albumBaseText
                    }
                val detailsMsg =
                    RetryHelper.retryIndefinitely("Send export button", task.chatId) {
                        bot.sendTextMessage(
                            chatId = chatIdObj,
                            text = promptInitial,
                            parseMode = if (withDescription) HTMLParseMode else null,
                            replyParameters = firstAlbumMessageId?.let { ReplyParameters(chatIdObj, it) },
                            replyMarkup = exportKeyboard,
                        )
                    }
                if (withDescription) {
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = null,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = albumBaseText,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = true,
                        ),
                    )
                }
            }
        }

        if (withDescription && targets.isNotEmpty()) {
            val runner = editJobRunner.getIfAvailable()
            if (runner != null) {
                runner.launchEditJob(targets) { task.descriptionHandle!!.await() }
            }
        }
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024
        // Pessimistic worst-case: @Max(500) из DescriptionProperties.CommonSection.
        // Используем upper bound для edit budget — гарантирует 1024 даже при shortMaxLength=500.
        // При реальном дефолте 200 caption space останется на ~300 символов недоиспользованным —
        // приемлемый компромисс между простотой (без проброса реального значения в sender) и безопасностью.
        private const val SHORT_MAX_LENGTH = 500
    }

    private fun String.toCaption(maxLength: Int): String {
        // Plain-text truncation (без HTML parseMode). Для HTML-сценариев используется
        // DescriptionMessageFormatter.escapeAndTrim внутри formatter-методов.
        if (length <= maxLength) return this
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
```

**Критические изменения vs исходного плана:**
- `descriptionFormatter` и `editJobRunner` — `ObjectProvider` вместо прямого инжекта, т.к. при `enabled=false` их бинов нет, но sender существует (условен только на `telegram.enabled`).
- `MessageIdentifier` → `MessageId` (cast `as MessageIdentifier` удалён — typealias deprecated).
- `HTMLParseMode` импорт из `dev.inmo.tgbotapi.types.message`.
- `replyToMessageId` → `replyParameters = ReplyParameters(chatId, msgId)` — ktgbotapi v32 API.
- `sendMediaGroup(...)` возвращает **один** `ContentMessage<MediaGroupContent<...>>`, `group.messageId` — id первого сообщения альбома. `.firstOrNull()?.messageId` убрано.
- HTML-aware truncation перенесён в `DescriptionMessageFormatter.escapeAndTrim` (escape-first, не разрывает entity). Sender передаёт raw baseText + budget — formatter сам escape'ит и трим'ит.
- `EditTarget.baseCaption` → `baseText` + `captionBudget` — передаём сырой текст с бюджетом, а не pre-trimmed.
- Импорты `kotlinx.coroutines.Deferred`, `ObjectProvider`, `DescriptionResult`, `DescriptionEditJobRunner` добавлены в шапку файла.

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: PASS (all existing + 4 new).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt
git commit -m "feat(telegram): add placeholder+edit flow for AI descriptions in notification sender"
```

---

## Phase 6 — ~~YAML configuration~~ (ПЕРЕНЕСЕНО В TASK 13)

### Task 17: ~~application.yaml — AI description section~~ (УДАЛЕНА)

YAML-секция добавляется в Task 13 Step 4.5 (перенесено по результатам итерации 1 ревью — иначе между Task 13 и Task 17 приложение не могло стартовать из-за отсутствия default-значений).

---

## Phase 7 — Docker

### Task 18: Dockerfile — native Claude CLI install

**Files:**
- Modify: `docker/deploy/Dockerfile`

- [ ] **Step 1: Add Claude CLI installation**

Edit `docker/deploy/Dockerfile`. **Не добавляй отдельный `RUN apk add`** — существующую `RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu` нужно расширить **в той же строке**, чтобы не дублировать apk-слой:

```dockerfile
# Runtime deps (ffmpeg/fontconfig existing + bash/libgcc/libstdc++/ripgrep for Claude CLI per Anthropic docs)
RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep
```

Затем, после `USER appuser` line near the end, add (before `EXPOSE 8080`):

```dockerfile
# Install Claude Code CLI under appuser (~/.local/bin)
RUN curl -fsSL https://claude.ai/install.sh | bash
# Disable built-in ripgrep on musl + disable auto-updater (deterministic image)
RUN mkdir -p /home/appuser/.claude \
 && printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json
```

Then, still as root, insert **before** the `USER appuser` line a `USER root` block (if not already root) so the subsequent `USER appuser` + native install runs under appuser context. The final ordering should be:

1. `RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep`  (as root — существующий, расширенный)
2. (existing) setup of directories, group, user
3. existing COPY --from=builder lines
4. existing AOT cache build (as root) — keep
5. existing `COPY docker/deploy/docker-entrypoint.sh` + `chmod +x` + `chown`
6. `USER appuser`
7. NEW: `RUN curl -fsSL https://claude.ai/install.sh | bash`
8. NEW: `RUN mkdir -p /home/appuser/.claude && printf ...`
9. `USER root` + `ENV PATH="/home/appuser/.local/bin:${PATH}"` + `USER appuser`
10. `EXPOSE 8080`
11. `ENTRYPOINT ...`

Concrete final Dockerfile block for reference — **copy exactly** over the current runtime-stage below the FROM line:

```dockerfile
FROM azul/zulu-openjdk-alpine:25
WORKDIR /application

RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep

RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer

COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

RUN java --enable-native-access=ALL-UNNAMED \
         -XX:AOTCacheOutput=application.aot \
         -Dspring.context.exit=onRefresh \
         -jar application.jar || true

COPY docker/deploy/docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh && \
    chown -R appuser:appgroup /application

USER appuser
RUN curl -fsSL https://claude.ai/install.sh | bash && \
    mkdir -p /home/appuser/.claude && \
    printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json

USER root
ENV PATH="/home/appuser/.local/bin:${PATH}"
USER appuser

EXPOSE 8080

ENTRYPOINT ["/application/docker-entrypoint.sh"]
```

- [ ] **Step 2: Build the image locally (manual verification)**

Run from the repo root:

```bash
./gradlew :frigate-analyzer-core:bootJar
docker build -f docker/deploy/Dockerfile -t frigate-analyzer:ai-test .
# --entrypoint обязательно — образ имеет ENTRYPOINT=docker-entrypoint.sh,
# просто аргументы "claude --version" уйдут в Java-приложение и не проверят CLI.
docker run --rm --entrypoint claude frigate-analyzer:ai-test --version
```

Expected: version string prints, no errors.

If `claude --version` fails with missing shared libraries, re-read Anthropic Alpine docs and adjust `apk add` list.

- [ ] **Step 3: Commit**

```bash
git add docker/deploy/Dockerfile
git commit -m "chore(docker): install Claude Code CLI natively in image"
```

---

### Task 19: docker-entrypoint.sh — warn block

**Files:**
- Modify: `docker/deploy/docker-entrypoint.sh`

- [ ] **Step 1: Add WARN block**

Replace the content of `docker/deploy/docker-entrypoint.sh` with:

```bash
#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but CLAUDE_CODE_OAUTH_TOKEN is empty; AI descriptions will return fallback." >&2
    elif [ -n "${CLAUDE_CLI_PATH:-}" ]; then
        # Explicit path: проверяем его, а не PATH — иначе WARN будет ложным при кастомном location.
        if [ -x "${CLAUDE_CLI_PATH}" ]; then
            echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
        else
            echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
        fi
    elif ! command -v claude >/dev/null 2>&1; then
        echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); AI descriptions will return fallback." >&2
    else
        echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
    fi
fi

exec java "$@" -jar application.jar
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/docker-entrypoint.sh
git commit -m "chore(docker): warn on misconfigured AI description in entrypoint"
```

---

### Task 20: .env.example update

**Files:**
- Modify: `docker/deploy/.env.example`

- [ ] **Step 1: Append AI description section**

Append to `docker/deploy/.env.example`:

```bash

# --- AI descriptions (optional) ---
# Enables Claude-generated short + detailed descriptions of detection frames.
# APP_AI_DESCRIPTION_ENABLED=true
# APP_AI_DESCRIPTION_PROVIDER=claude
# APP_AI_DESCRIPTION_LANGUAGE=en             # ru | en (validated at startup via @Pattern)
# APP_AI_DESCRIPTION_SHORT_MAX=200
# APP_AI_DESCRIPTION_DETAILED_MAX=1500
# APP_AI_DESCRIPTION_MAX_FRAMES=10           # top-N frames by frameIndex sent to Claude
# APP_AI_DESCRIPTION_QUEUE_TIMEOUT=30s       # waiting for a semaphore permit
# APP_AI_DESCRIPTION_TIMEOUT=60s             # actual describe + retry
# APP_AI_DESCRIPTION_MAX_CONCURRENT=2

# --- Claude-specific (when provider=claude) ---
# Obtain the token ONCE on the host: `claude setup-token`,
# copy the value here. Long-lived OAuth token (works against your Claude subscription).
# CLAUDE_CODE_OAUTH_TOKEN=
# CLAUDE_MODEL=opus                          # opus | sonnet | haiku (alias)
# CLAUDE_CLI_PATH=                           # empty = SDK resolves via `which claude`
# CLAUDE_WORKING_DIR=                        # empty = uses application.temp-folder (required for SDK)

# --- Optional proxy for Claude API calls ---
# CLAUDE_HTTP_PROXY=http://proxy:8080
# CLAUDE_HTTPS_PROXY=http://proxy:8080
# CLAUDE_NO_PROXY=localhost,127.0.0.1
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/.env.example
git commit -m "docs(docker): document AI description env variables in .env.example"
```

---

## Phase 8 — Final build + code review

### Task 21: Full project build + ktlint

**Files:** none

- [ ] **Step 1: Run ktlint format**

Dispatch `build-runner` with `./gradlew ktlintFormat`.
Expected: BUILD SUCCESSFUL. Any formatting fixes auto-applied.

- [ ] **Step 2: Run full build with tests**

Dispatch `build-runner` with `./gradlew build`.
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: If any files changed during ktlintFormat, commit**

```bash
git status --short
# If there are unstaged changes from ktlint:
git add -u
git commit -m "style: apply ktlint formatting"
```

---

### Task 22: Superpowers code-reviewer pass

**Files:** none (review-only)

- [ ] **Step 1: Run superpowers code-reviewer**

In the current session, dispatch `Agent` with `subagent_type: superpowers:code-reviewer` and this prompt:

> Review the ai-description feature implementation on branch `feature/ai-description`. The design doc is at `docs/superpowers/specs/2026-04-19-ai-description-design.md`. Compare the implementation to the spec. Focus on:
>
> 1. Does the code match the 8 sections of the spec (modules, config, API, data flow, errors, Docker, tests, changes)?
> 2. Any places where `// TODO`, placeholder text, or unfinished code slipped in?
> 3. Any thread-safety / coroutine lifecycle issues around `descriptionScope`, `DescriptionEditJobRunner.scope`, semaphore, or `Deferred<Result<…>>.await()`?
> 4. Any Telegram edit-path regression risk (`message is not modified`, lost messageId, absence of `parseMode` when HTML is used)?
> 5. Test coverage: are the four sender scenarios present (single-success/fail, group-success, disabled-regression)? Facade 3 scenarios?
>
> Report findings in priority order.

- [ ] **Step 2: Fix any critical issues reported**

For each critical finding from the reviewer: create a commit fixing it. Minor nits can be addressed in a separate pass or noted for a follow-up PR.

- [ ] **Step 3: Re-run build**

Dispatch `build-runner` with `./gradlew build`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix(ai-description): address code-reviewer findings"
```

---

### Task 23: (Optional) Integration test with stub CLI

**Files:**
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt`

This test is opt-in via env var `INTEGRATION_CLAUDE=stub`, so normal CI is unaffected.

- [ ] **Step 1: Write integration test**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

@EnabledIfEnvironmentVariable(named = "INTEGRATION_CLAUDE", matches = "stub")
class ClaudeDescriptionAgentIntegrationTest {
    @Test
    fun `end-to-end prompt to parsed result with stub CLI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // 1. Create stub that echoes stream-json with our canned JSON inside AssistantMessage text
        val stubClaude = tempDir.resolve("claude")
        stubClaude.writeText(
            """#!/bin/sh
cat <<'JSON'
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"{\"short\": \"stub-s\", \"detailed\": \"stub-d\"}"}]}}
{"type":"result","subtype":"success","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"result":"ok","session_id":"test","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0}}
JSON
""",
        )
        Files.setPosixFilePermissions(
            stubClaude,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val claudeProps =
            ClaudeProperties(
                oauthToken = "fake",
                model = "opus",
                cliPath = stubClaude.absolutePathString(),
                workingDirectory = tempDir.absolutePathString(),
                proxy = ClaudeProperties.ProxySection("", "", ""),
            )
        val common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                maxFrames = 10,
                queueTimeout = Duration.ofSeconds(30),
                timeout = Duration.ofSeconds(30),
                maxConcurrent = 1,
            )

        val factory = ClaudeAsyncClientFactory(claudeProps)
        val invoker = DefaultClaudeInvoker(
            factory,
            DescriptionProperties(enabled = true, provider = "claude", common = common),
        )

        val mapper = ObjectMapper().registerKotlinModule()
        val stager =
            ClaudeImageStager(
                object : ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter {
                    override suspend fun createTempFile(
                        prefix: String,
                        suffix: String,
                        content: ByteArray,
                    ): Path {
                        val p = tempDir.resolve("$prefix$suffix")
                        p.parent.createDirectories()
                        Files.write(p, content)
                        return p
                    }

                    override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
                },
            )

        val agent =
            ClaudeDescriptionAgent(
                claudeProperties = claudeProps,
                descriptionProperties = DescriptionProperties(
                    enabled = true,
                    provider = "claude",
                    common = common,
                ),
                promptBuilder = ClaudePromptBuilder(),
                responseParser = ClaudeResponseParser(mapper),
                imageStager = stager,
                invoker = invoker,
                exceptionMapper = ClaudeExceptionMapper(),
            )

        val request =
            DescriptionRequest(
                recordingId = UUID.randomUUID(),
                frames = listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1))),
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
            )

        val result = agent.describe(request)
        assertEquals("stub-s", result.short)
        assertEquals("stub-d", result.detailed)
    }
}
```

- [ ] **Step 2: Run the test explicitly**

```bash
INTEGRATION_CLAUDE=stub ./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentIntegrationTest
```

Expected: PASS (if SDK wire-format of stub matches the real SDK — adjust stub JSON as needed to match `MessageParser` expectations).

**Note:** If this integration test proves fragile against SDK internals, treat it as optional — it's a nice-to-have, not a gate.

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt
git commit -m "test(ai-description): add opt-in integration test with stub CLI"
```

---

### Task 24: Clean up design/plan docs before PR

Per user CLAUDE.md: design and plan docs must not appear in the final PR diff.

**Files:**
- Delete: `docs/superpowers/specs/2026-04-19-ai-description-design.md`
- Delete: `docs/superpowers/plans/2026-04-19-ai-description-plan.md`

- [ ] **Step 1: Remove docs and commit**

```bash
git rm docs/superpowers/specs/2026-04-19-ai-description-design.md
git rm docs/superpowers/plans/2026-04-19-ai-description-plan.md
git commit -m "chore: remove brainstorming docs before PR"
```

Docs stay available in branch history via `git log feature/ai-description` if needed.

---

## Post-plan: verification checklist

Before opening the PR:

- [ ] `./gradlew build` is green (via build-runner).
- [ ] `./gradlew ktlintCheck` is green.
- [ ] Manual test: build docker image, set `CLAUDE_CODE_OAUTH_TOKEN`, trigger a test recording, observe placeholder → edit in Telegram.
- [ ] Manual test: disable feature (`APP_AI_DESCRIPTION_ENABLED=false` — default), confirm notification flow unchanged AND application starts without NoSuchBeanDefinitionException.
- [ ] Manual test: set `APP_AI_DESCRIPTION_ENABLED=true` BUT `APP_AI_DESCRIPTION_PROVIDER=foo` (не зарегистрирован); startup должен выдать WARN "no agent registered" и не ломать приложение.
- [ ] Manual test: camera с именем, содержащим `<` или `&` — Telegram уведомление должно успешно уходить и отображаться (проверяет HTML-escape baseText).
- [ ] Manual test: recording с >10 кадрами — в Claude уходит ровно 10.
- [ ] PR description references the spec sections (reviewers will want it).
