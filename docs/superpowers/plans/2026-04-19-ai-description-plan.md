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
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep` (empty placeholder so the directory exists in git)

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

Create `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/.gitkeep` (empty file).

- [ ] **Step 5: Run build to confirm module is wired**

Use the `build` skill to dispatch `build-runner` with command `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL, no tests yet.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml modules/ai-description/
git commit -m "feat(ai-description): scaffold module and register claude-code-sdk dependency"
```

---

### Task 2: API contracts — DTOs, interface, exceptions

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionRequest.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionResult.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionException.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionAgent.kt`

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

    class Disabled : DescriptionException("Description agent is disabled")
}
```

- [ ] **Step 4: Create DescriptionAgent.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

interface DescriptionAgent {
    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
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
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    @field:NotBlank
    val provider: String,
    @field:Valid
    val common: CommonSection,
) {
    data class CommonSection(
        @field:NotBlank
        val language: String,
        @field:Min(50) @field:Max(500)
        val shortMaxLength: Int,
        @field:Min(200) @field:Max(3500)
        val detailedMaxLength: Int,
        val timeout: Duration,
        @field:Min(1) @field:Max(10)
        val maxConcurrent: Int,
    )
}
```

- [ ] **Step 2: Create ClaudeProperties.kt**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.description.claude")
@Validated
data class ClaudeProperties(
    val oauthToken: String,
    val model: String,
    val cliPath: String,
    val startupTimeout: Duration,
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

- [ ] **Step 3: Run build**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/
git commit -m "feat(ai-description): add DescriptionProperties and ClaudeProperties"
```

---

## Phase 2 — Claude components (TDD)

### Task 4: ClaudePromptBuilder

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilder.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
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
            Paths.get("/tmp/a/frame-0.jpg"),
            Paths.get("/tmp/a/frame-1.jpg"),
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

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path

@Component
class ClaudePromptBuilder {
    fun build(
        request: DescriptionRequest,
        framePaths: List<Path>,
    ): String {
        require(framePaths.size == request.frames.size) {
            "framePaths size (${framePaths.size}) must match request.frames size (${request.frames.size})"
        }
        val languageName = languageNameFor(request.language)
        val sortedPairs =
            request.frames
                .zip(framePaths)
                .sortedBy { it.first.frameIndex }

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
            else -> "in English"
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
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

private val logger = KotlinLogging.logger {}

@Component
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

**Context:** `ClaudeImageStager` wraps `TempFileHelper` (which lives in `modules/core`). To keep `ai-description` self-contained, the stager accepts a narrow functional interface injected from `core`. Task 12 (DescriptionScopeConfig) will also provide the adapter bean. For now we write the stager against the `TempFileWriter` interface defined below.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/TempFileWriter.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStager.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStagerTest.kt`

- [ ] **Step 1: Create TempFileWriter interface**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import java.nio.file.Path

/**
 * Minimal contract the image stager needs — implemented by an adapter over `TempFileHelper`
 * in the `core` module. Keeps this module free of dependencies on `core`/`model`.
 */
interface TempFileWriter {
    suspend fun createTempFile(
        prefix: String,
        suffix: String,
        content: ByteArray,
    ): Path

    suspend fun deleteFiles(files: List<Path>): Int
}
```

- [ ] **Step 2: Write failing tests**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path
import java.nio.file.Paths
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
                Paths.get("/tmp/${firstArg<String>()}-stub.jpg")
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
            val paths: List<Path> = listOf(Paths.get("/tmp/a.jpg"), Paths.get("/tmp/b.jpg"))
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
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
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
            runCatching { tempWriter.deleteFiles(staged) }
            throw e
        }
    }

    suspend fun cleanup(paths: List<Path>) {
        if (paths.isEmpty()) return
        runCatching { tempWriter.deleteFiles(paths) }
            .onFailure { logger.warn(it) { "Failed to delete staged Claude frames" } }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeImageStagerTest`.
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/TempFileWriter.kt modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStager.kt modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeImageStagerTest.kt
git commit -m "feat(ai-description): add ClaudeImageStager with TempFileWriter abstraction"
```

---

### Task 7: ClaudeAsyncClientFactory

**Context:** Builds `ClaudeAsyncClient` per call with all env variables (OAuth token + proxy) passed through `CLIOptions.env(...)`. SDK whitelists only HOME/PATH/LANG/etc. for auto-inheritance, so proxy and auth must be injected explicitly.

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt`

- [ ] **Step 1: Write failing tests for buildEnvMap**

We unit-test only the env-map construction (the one piece not requiring a real CLI). Actual client construction is covered indirectly by the integration test in Task 23.

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import java.time.Duration
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
        startupTimeout = Duration.ofSeconds(10),
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
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import java.nio.file.Paths
import java.time.Duration

@Component
class ClaudeAsyncClientFactory(
    private val claudeProperties: ClaudeProperties,
) {
    fun create(totalTimeout: Duration): ClaudeAsyncClient {
        val options =
            CLIOptions
                .builder()
                .model(claudeProperties.model)
                .timeout(totalTimeout)
                .env(buildEnvMap())
                .build()

        val builder = ClaudeClient.async(options)
        if (claudeProperties.cliPath.isNotBlank()) {
            builder.workingDirectory(Paths.get(claudeProperties.cliPath))
        }
        return builder.build()
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
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertIs

class ClaudeExceptionMapperTest {
    private val mapper = ClaudeExceptionMapper()

    @Test
    fun `TransportException maps to Transport`() {
        val e = mapper.map(TransportException("socket closed"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `429 in message maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("HTTP 429 rate limit exceeded"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `rate limit text maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("request was rate limited"))
        assertIs<DescriptionException.RateLimited>(e)
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeExceptionMapperTest`.
Expected: FAIL — class not found.

- [ ] **Step 3: Implement ClaudeExceptionMapper**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonProcessingException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException

@Component
class ClaudeExceptionMapper {
    fun map(throwable: Throwable): DescriptionException =
        when (throwable) {
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

    private fun isRateLimit(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return "429" in message || "rate limit" in message
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
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private fun agent(token: String): ClaudeDescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = token,
                    model = "opus",
                    cliPath = "",
                    startupTimeout = Duration.ofSeconds(10),
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                ),
            commonSection = common,
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
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

private val logger = KotlinLogging.logger {}

class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val commonSection: DescriptionProperties.CommonSection,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionAgent {
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
        TODO("implemented in Task 10")
    }
}
```

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
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val claudeProps =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            startupTimeout = Duration.ofSeconds(10),
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

    private val stagedPaths: List<Path> = listOf(Paths.get("/tmp/f.jpg"))
    private val okJson = """{"short": "s", "detailed": "d"}"""

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        coEvery { promptBuilder.build(any(), any()) } returns "prompt"
    }

    private fun build(invoker: ClaudeInvoker) =
        ClaudeDescriptionAgent(
            claudeProps,
            common,
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
    fun `retries once on Transport then succeeds`() =
        runTest {
            var calls = 0
            val invoker =
                ClaudeInvoker {
                    calls++
                    if (calls == 1) throw DescriptionException.Transport() else okJson
                }
            val agent = build(invoker)
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
    fun `timeout triggers cancellation`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val shortTimeoutCommon = common.copy(timeout = Duration.ofMillis(500))
            val agent =
                ClaudeDescriptionAgent(
                    claudeProps,
                    shortTimeoutCommon,
                    promptBuilder,
                    responseParser,
                    imageStager,
                    ClaudeInvoker {
                        gate.await()
                        okJson
                    },
                    exceptionMapper,
                )
            val job = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            val outcome = job.await()
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { outcome.getOrThrow() }
        }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentTest`.
Expected: FAIL — `describe()` throws `NotImplementedError`.

- [ ] **Step 3: Replace the TODO body with full implementation**

Replace the body of `describe()` in `ClaudeDescriptionAgent.kt`. The full file becomes:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.springaicommunity.claude.agent.sdk.Query
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val commonSection: DescriptionProperties.CommonSection,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionAgent {
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

    override suspend fun describe(request: DescriptionRequest): DescriptionResult =
        semaphore.withPermit {
            withTimeout(commonSection.timeout.toMillis()) {
                val stagedPaths = imageStager.stage(request)
                try {
                    val prompt = promptBuilder.build(request, stagedPaths)
                    executeWithRetry(prompt, request)
                } finally {
                    imageStager.cleanup(stagedPaths)
                }
            }
        }

    private suspend fun executeWithRetry(
        prompt: String,
        request: DescriptionRequest,
    ): DescriptionResult {
        var jsonRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val raw =
                    try {
                        invoker.invoke(prompt)
                    } catch (e: Throwable) {
                        throw exceptionMapper.map(e)
                    }
                return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
            } catch (e: DescriptionException.InvalidResponse) {
                if (jsonRetries >= 1) throw e
                jsonRetries++
                logger.warn(e) { "Invalid JSON from Claude, retrying (attempt ${jsonRetries + 1})" }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                logger.warn(e) { "Claude transport error, retrying in 5s" }
                delay(5.seconds)
            }
            // RateLimited, Disabled, Timeout pass through without retry
        }
    }
}
```

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

- [ ] **Step 1: Implement adapter**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import kotlinx.coroutines.reactor.awaitSingle
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

class DefaultClaudeInvoker(
    private val clientFactory: ClaudeAsyncClientFactory,
    private val commonSection: DescriptionProperties.CommonSection,
) : ClaudeInvoker {
    override suspend fun invoke(prompt: String): String =
        clientFactory.create(commonSection.timeout).use { client ->
            client.connect()
            client.queryAndReceive(prompt).collectList().map { messages ->
                messages
                    .asSequence()
                    .filterIsInstance<org.springaicommunity.claude.agent.sdk.types.AssistantMessage>()
                    .flatMap { it.content.asSequence() }
                    .filterIsInstance<org.springaicommunity.claude.agent.sdk.types.TextBlock>()
                    .joinToString("") { it.text }
            }.awaitSingle()
        }
}
```

- [ ] **Step 2: Build (no new tests)**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/DefaultClaudeInvoker.kt
git commit -m "feat(ai-description): add DefaultClaudeInvoker for real SDK calls"
```

---

### Task 12: Spring wiring — ClaudeAgentConfig

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAgentConfig.kt`

- [ ] **Step 1: Implement config**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

@Configuration
@ConditionalOnProperty(
    prefix = "application.ai.description",
    name = ["enabled"],
    havingValue = "true",
)
class ClaudeAgentConfig {
    @Bean
    @ConditionalOnProperty(
        prefix = "application.ai.description",
        name = ["provider"],
        havingValue = "claude",
    )
    fun claudeInvoker(
        clientFactory: ClaudeAsyncClientFactory,
        descriptionProperties: DescriptionProperties,
    ): ClaudeInvoker = DefaultClaudeInvoker(clientFactory, descriptionProperties.common)

    @Bean
    @ConditionalOnProperty(
        prefix = "application.ai.description",
        name = ["provider"],
        havingValue = "claude",
    )
    fun claudeDescriptionAgent(
        claudeProperties: ClaudeProperties,
        descriptionProperties: DescriptionProperties,
        promptBuilder: ClaudePromptBuilder,
        responseParser: ClaudeResponseParser,
        imageStager: ClaudeImageStager,
        invoker: ClaudeInvoker,
        exceptionMapper: ClaudeExceptionMapper,
    ): DescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties = claudeProperties,
            commonSection = descriptionProperties.common,
            promptBuilder = promptBuilder,
            responseParser = responseParser,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )
}
```

- [ ] **Step 2: Build**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-ai-description:build`.
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAgentConfig.kt
git commit -m "feat(ai-description): wire ClaudeAgentConfig with conditional beans"
```

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

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.claude.TempFileWriter
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import java.nio.file.Path

@Configuration
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Scope for describe-jobs kicked off from RecordingProcessingFacade.
 *
 * SupervisorJob means a failure in one description does not cancel others.
 * @PreDestroy cancels pending jobs on shutdown with a short grace window so JVM exit
 * is not stalled by a hung CLI subprocess.
 */
@Component
@ConditionalOnProperty(
    prefix = "application.ai.description",
    name = ["enabled"],
    havingValue = "true",
)
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

- [ ] **Step 4: Register @ConfigurationProperties classes**

In `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt`, add these two imports:

```kotlin
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
```

And add them to `@EnableConfigurationProperties(...)`:

```kotlin
@EnableConfigurationProperties(
    ApplicationProperties::class,
    DetectionFilterProperties::class,
    DetectProperties::class,
    PipelineProperties::class,
    LocalVisualizationProperties::class,
    RecordsWatcherProperties::class,
    DescriptionProperties::class,
    ClaudeProperties::class,
)
```

- [ ] **Step 5: Build**

Dispatch `build-runner` with `./gradlew build -x test`.
Expected: BUILD SUCCESSFUL (module compiles, wiring intact).

- [ ] **Step 6: Commit**

```bash
git add modules/core/build.gradle.kts modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/DescriptionScopeConfig.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/TempFileWriterAdapter.kt modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt
git commit -m "feat(core): wire ai-description module with scope and temp-file adapter"
```

---

### Task 14: RecordingProcessingFacade — start describe-job

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`

**Context:** `TelegramNotificationService.sendRecordingNotification()` will grow a new parameter in Task 16. Since the interface is in `telegram` module and we haven't updated it yet, this task does two things: (a) updates the interface signature in `telegram` to accept a nullable `Deferred<Result<DescriptionResult>>`, and (b) updates the facade to supply it. Tests for sender stay as is for now (they pass `null`), the sender implementation change follows in Task 17.

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
        descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    )
}
```

- [ ] **Step 3: Update TelegramNotificationServiceImpl signature**

In `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`, change the `override fun sendRecordingNotification(...)` signature to:

```kotlin
    override suspend fun sendRecordingNotification(
        recording: RecordingDto,
        visualizedFrames: List<VisualizedFrameData>,
        descriptionHandle: Deferred<Result<DescriptionResult>>?,
    ) {
```

Add imports at the top of the file:

```kotlin
import kotlinx.coroutines.Deferred
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
```

Also update `val task = NotificationTask(...)` inside the `usersWithZones.forEach { ... }` block — add `descriptionHandle = descriptionHandle,` as the last field. (The field will be introduced in NotificationTask in Step 4.)

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
    val descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    val createdAt: Instant = Instant.now(),
)
```

- [ ] **Step 5: Write failing test for facade behavior**

Modify (create if missing) `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`. Add these focused tests (leave any existing content intact; if file doesn't exist, start with this full content):

```kotlin
package ru.zinin.frigate.analyzer.core.facade

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.core.config.DescriptionCoroutineScope
import ru.zinin.frigate.analyzer.core.service.FrameVisualizationService
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

    private fun facade(agent: DescriptionAgent?): RecordingProcessingFacade {
        val provider = mockk<ObjectProvider<DescriptionAgent>>()
        coEvery { provider.getIfAvailable() } returns agent
        val scope = DescriptionCoroutineScope(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        val props =
            DescriptionProperties(
                enabled = agent != null,
                provider = "claude",
                common =
                    DescriptionProperties.CommonSection(
                        language = "en",
                        shortMaxLength = 200,
                        detailedMaxLength = 1500,
                        timeout = java.time.Duration.ofSeconds(60),
                        maxConcurrent = 2,
                    ),
            )
        return RecordingProcessingFacade(
            recordingEntityService = recordingEntityService,
            telegramNotificationService = telegramNotificationService,
            frameVisualizationService = frameVisualizationService,
            descriptionAgentProvider = provider,
            descriptionScope = scope,
            descriptionProperties = props,
        )
    }

    private val request = SaveProcessingResultRequest(recordingId = recordingId, frames = emptyList())

    private suspend fun captureHandleDuring(block: suspend () -> Unit): Deferred<Result<DescriptionResult>>? {
        var captured: Deferred<Result<DescriptionResult>>? = null
        coEvery {
            telegramNotificationService.sendRecordingNotification(any(), any(), any())
        } answers {
            captured = thirdArg()
            Unit
        }
        block()
        return captured
    }

    @Test
    fun `agent disabled produces null descriptionHandle`() =
        runTest {
            val captured = captureHandleDuring { facade(agent = null).processAndNotify(request) }
            assertNull(captured)
        }

    @Test
    fun `agent enabled produces non-null descriptionHandle`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers { DescriptionResult("s", "d") }

            val captured = captureHandleDuring { facade(agent).processAndNotify(request) }
            assertNotNull(captured)
        }

    @Test
    fun `exception in describe is captured in Result failure, does not break facade`() =
        runTest {
            val agent = mockk<DescriptionAgent>()
            coEvery { agent.describe(any()) } coAnswers {
                delay(1)
                throw IllegalStateException("boom")
            }

            val captured = captureHandleDuring { facade(agent).processAndNotify(request) }
            val outcome = captured!!.await()
            assertEquals(true, outcome.isFailure)
        }
}
```

- [ ] **Step 6: Run test to verify it fails**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-core:test --tests RecordingProcessingFacadeTest`.
Expected: FAIL — facade constructor does not match.

- [ ] **Step 7: Update RecordingProcessingFacade**

Replace the full content of `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.facade

import io.github.oshai.kotlinlogging.KotlinLogging
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
                val descriptionHandle = startDescribeJob(recordingId, request)
                try {
                    telegramNotificationService.sendRecordingNotification(
                        recording,
                        visualizedFrames,
                        descriptionHandle,
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to send telegram notification for recording $recordingId" }
                }
            } else {
                logger.warn { "Recording $recordingId not found after saving, skipping notification" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save processing result for recording $recordingId" }
            throw e
        }
    }

    private fun startDescribeJob(
        recordingId: java.util.UUID,
        request: SaveProcessingResultRequest,
    ): Deferred<Result<DescriptionResult>>? {
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null
        val common = descriptionProperties.common

        val frameImages =
            request.frames.map {
                DescriptionRequest.FrameImage(it.frameIndex, it.frameBytes)
            }
        val descriptionRequest =
            DescriptionRequest(
                recordingId = recordingId,
                frames = frameImages,
                language = common.language,
                shortMaxLength = common.shortMaxLength,
                detailedMaxLength = common.detailedMaxLength,
            )
        return descriptionScope.async {
            runCatching { agent.describe(descriptionRequest) }
        }
    }
}
```

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
notification.recording.export.prompt.with.description=
```

Edit `modules/telegram/src/main/resources/messages_ru.properties`, append:

```properties

# AI description
ai.description.placeholder.short=⏳ <i>AI анализирует кадры…</i>
ai.description.placeholder.detailed=⏳ <i>AI готовит подробное описание…</i>
ai.description.fallback.unavailable=⚠ <i>Описание недоступно</i>
```

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

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

@Component
class DescriptionMessageFormatter(
    private val msg: MessageResolver,
) {
    fun captionInitialPlaceholder(
        baseText: String,
        language: String,
    ): String = "$baseText\n\n${msg.get(KEY_PLACEHOLDER_SHORT, language)}"

    fun captionSuccess(
        baseText: String,
        result: DescriptionResult,
        language: String,
    ): String = "$baseText\n\n${htmlEscape(result.short)}"

    fun captionFallback(
        baseText: String,
        language: String,
    ): String = "$baseText\n\n${msg.get(KEY_FALLBACK, language)}"

    fun placeholderShort(language: String): String = msg.get(KEY_PLACEHOLDER_SHORT, language)

    fun placeholderDetailedExpandable(language: String): String =
        "<blockquote expandable>${msg.get(KEY_PLACEHOLDER_DETAILED, language)}</blockquote>"

    fun expandableBlockquoteSuccess(
        result: DescriptionResult,
        language: String,
    ): String = "<blockquote expandable>${htmlEscape(result.detailed)}</blockquote>"

    fun expandableBlockquoteFallback(language: String): String =
        "<blockquote expandable>${msg.get(KEY_FALLBACK, language)}</blockquote>"

    private fun htmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    companion object {
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

**Context:** This is the core refactor. We switch to HTML parse mode *only when* `descriptionHandle != null`. When it's null, old behavior is preserved exactly. We also add a sender-owned coroutine scope for the edit-job.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt`

- [ ] **Step 1: Write failing tests (add to existing file)**

Append these test cases to `TelegramNotificationSenderTest.kt`:

```kotlin
    @Test
    fun `disabled path with null descriptionHandle preserves current single-photo behavior`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames))

            coVerify(exactly = 0) { bot.sendTextMessage(any(), any<String>(), any()) }
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
            every { photoMsg.chat.id } returns ChatId(RawChatId(12345L))
            val textMsg = mockk<ContentMessage<TextContent>>()
            every { textMsg.messageId } returns MessageId(43L)
            every { textMsg.chat.id } returns ChatId(RawChatId(12345L))

            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery { bot.sendTextMessage(any(), any<String>(), any()) } returns textMsg
            coEvery { bot.execute(any<EditMessageCaption>()) } returns mockk(relaxed = true)
            coEvery { bot.execute(any<EditMessageText>()) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))

            coVerify { bot.execute(any<EditMessageCaption>()) }
            coVerify { bot.execute(any<EditMessageText>()) }
        }

    @Test
    fun `single photo with description handle uses fallback on failure`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.failure(RuntimeException("boom")))

            val photoMsg = mockk<ContentMessage<PhotoContent>>()
            every { photoMsg.messageId } returns MessageId(42L)
            every { photoMsg.chat.id } returns ChatId(RawChatId(12345L))
            val textMsg = mockk<ContentMessage<TextContent>>()
            every { textMsg.messageId } returns MessageId(43L)
            every { textMsg.chat.id } returns ChatId(RawChatId(12345L))

            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery { bot.sendTextMessage(any(), any<String>(), any()) } returns textMsg

            val editCaptionSlot = slot<EditMessageCaption>()
            coEvery { bot.execute(capture(editCaptionSlot)) } returns mockk(relaxed = true)
            val editTextSlot = slot<EditMessageText>()
            coEvery { bot.execute(capture(editTextSlot)) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))

            assertTrue(editCaptionSlot.captured.text!!.contains("unavailable", ignoreCase = true))
            assertTrue(editTextSlot.captured.text.contains("unavailable", ignoreCase = true))
        }

    @Test
    fun `media group with description handle sends albums and single edit on success`() =
        runTest {
            val frames =
                (0..2).map {
                    VisualizedFrameData(frameIndex = it, visualizedBytes = ByteArray(1), detectionsCount = 1)
                }
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            coEvery { bot.sendMediaGroup(any(), any<List<TelegramMediaPhoto>>()) } returns
                listOf(mockk(relaxed = true) {
                    every { messageId } returns MessageId(50L)
                    every { chat.id } returns ChatId(RawChatId(12345L))
                })
            val textMsg = mockk<ContentMessage<TextContent>>()
            every { textMsg.messageId } returns MessageId(51L)
            every { textMsg.chat.id } returns ChatId(RawChatId(12345L))
            coEvery { bot.sendTextMessage(any(), any<String>(), any()) } returns textMsg
            coEvery { bot.execute(any<EditMessageText>()) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))

            coVerify(exactly = 1) { bot.execute(any<EditMessageText>()) }
            coVerify(exactly = 0) { bot.execute(any<EditMessageCaption>()) }
        }
```

Also update `createTask` to accept the new parameter:

```kotlin
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
import dev.inmo.tgbotapi.requests.edit.caption.EditMessageCaption
import dev.inmo.tgbotapi.requests.edit.text.EditMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.types.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
```

- [ ] **Step 2: Run test — expect failures**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: FAIL — new cases missing implementation.

- [ ] **Step 3: Create DescriptionEditJobRunner**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.edit.caption.EditMessageCaption
import dev.inmo.tgbotapi.requests.edit.text.EditMessageText
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.ParseMode.HTMLParseMode
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

data class EditTarget(
    val chatId: ChatIdentifier,
    val captionMessageId: MessageIdentifier?,
    val detailsMessageId: MessageIdentifier,
    val baseCaption: String,
    val baseTextForAlbum: String?,
    val exportKeyboard: InlineKeyboardMarkup,
    val language: String,
    val isMediaGroup: Boolean,
)

class DescriptionEditJobRunner(
    private val bot: TelegramBot,
    private val formatter: DescriptionMessageFormatter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
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
        try {
            if (target.isMediaGroup) {
                val newText =
                    outcome.fold(
                        onSuccess = { result ->
                            val short = formatter.captionSuccess(target.baseTextForAlbum!!, result, target.language)
                            "$short\n\n${formatter.expandableBlockquoteSuccess(result, target.language)}"
                        },
                        onFailure = {
                            val short = formatter.captionFallback(target.baseTextForAlbum!!, target.language)
                            "$short\n\n${formatter.expandableBlockquoteFallback(target.language)}"
                        },
                    )
                bot.execute(
                    EditMessageText(
                        chatId = target.chatId,
                        messageId = target.detailsMessageId,
                        text = newText,
                        parseMode = HTMLParseMode,
                        replyMarkup = target.exportKeyboard,
                    ),
                )
            } else {
                val captionText =
                    outcome.fold(
                        onSuccess = { formatter.captionSuccess(target.baseCaption, it, target.language) },
                        onFailure = { formatter.captionFallback(target.baseCaption, target.language) },
                    )
                val detailsText =
                    outcome.fold(
                        onSuccess = { formatter.expandableBlockquoteSuccess(it, target.language) },
                        onFailure = { formatter.expandableBlockquoteFallback(target.language) },
                    )
                bot.execute(
                    EditMessageCaption(
                        chatId = target.chatId,
                        messageId = target.captionMessageId!!,
                        text = captionText,
                        parseMode = HTMLParseMode,
                        replyMarkup = target.exportKeyboard,
                    ),
                )
                bot.execute(
                    EditMessageText(
                        chatId = target.chatId,
                        messageId = target.detailsMessageId,
                        text = detailsText,
                        parseMode = HTMLParseMode,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to edit message for chat=${target.chatId}; ignoring" }
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
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.ParseMode.HTMLParseMode
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val msg: MessageResolver,
    private val descriptionFormatter: DescriptionMessageFormatter,
    private val editJobRunner: DescriptionEditJobRunner = DescriptionEditJobRunner(bot, descriptionFormatter),
) {
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val frames = task.visualizedFrames
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val withDescription = task.descriptionHandle != null

        val captionBase = task.message.toCaption(MAX_CAPTION_LENGTH)
        val captionInitial =
            if (withDescription) {
                descriptionFormatter.captionInitialPlaceholder(captionBase, lang).toCaption(MAX_CAPTION_LENGTH)
            } else {
                captionBase
            }
        val parseMode = if (withDescription) HTMLParseMode else null

        val targets = mutableListOf<EditTarget>()

        when {
            frames.isEmpty() -> {
                RetryHelper.retryIndefinitely("Send text message", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = captionInitial,
                        parseMode = parseMode,
                        replyMarkup = exportKeyboard,
                    )
                }
                // no placeholders/edits for frame-less messages — nothing to attach to
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
                                text = descriptionFormatter.placeholderDetailedExpandable(lang),
                                parseMode = HTMLParseMode,
                                replyToMessageId = photoMsg.messageId,
                            )
                        }
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = photoMsg.messageId as MessageIdentifier,
                            detailsMessageId = detailsMsg.messageId as MessageIdentifier,
                            baseCaption = captionBase,
                            baseTextForAlbum = null,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = false,
                        ),
                    )
                }
            }

            else -> {
                var firstAlbumMessageId: MessageIdentifier? = null
                frames.chunked(MAX_MEDIA_GROUP_SIZE).forEachIndexed { chunkIndex, chunk ->
                    val group =
                        RetryHelper.retryIndefinitely("Send media group", task.chatId) {
                            val media =
                                chunk.mapIndexed { idx, frame ->
                                    TelegramMediaPhoto(
                                        file = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                        text = if (chunkIndex == 0 && idx == 0) captionBase else null,
                                    )
                                }
                            @Suppress("OPT_IN_USAGE")
                            bot.sendMediaGroup(chatIdObj, media)
                        }
                    if (chunkIndex == 0) {
                        firstAlbumMessageId = group.firstOrNull()?.messageId as? MessageIdentifier
                    }
                }
                val albumBaseText = msg.get("notification.recording.export.prompt", lang)
                val promptInitial =
                    if (withDescription) {
                        albumBaseText +
                            "\n\n" +
                            descriptionFormatter.placeholderShort(lang) +
                            "\n\n" +
                            descriptionFormatter.placeholderDetailedExpandable(lang)
                    } else {
                        albumBaseText
                    }
                val detailsMsg =
                    RetryHelper.retryIndefinitely("Send export button", task.chatId) {
                        bot.sendTextMessage(
                            chatId = chatIdObj,
                            text = promptInitial,
                            parseMode = if (withDescription) HTMLParseMode else null,
                            replyToMessageId = firstAlbumMessageId,
                            replyMarkup = exportKeyboard,
                        )
                    }
                if (withDescription) {
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = null,
                            detailsMessageId = detailsMsg.messageId as MessageIdentifier,
                            baseCaption = captionBase,
                            baseTextForAlbum = albumBaseText,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = true,
                        ),
                    )
                }
            }
        }

        task.descriptionHandle?.let { handle ->
            if (targets.isEmpty()) return@let
            editJobRunner.launchEditJob(targets) { handle.await() }
        }
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024
    }

    private fun String.toCaption(maxLength: Int): String {
        if (length <= maxLength) return this
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: PASS (all existing + 4 new).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt
git commit -m "feat(telegram): add placeholder+edit flow for AI descriptions in notification sender"
```

---

## Phase 6 — YAML configuration

### Task 17: application.yaml — AI description section

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`

- [ ] **Step 1: Add the section**

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
        timeout: ${APP_AI_DESCRIPTION_TIMEOUT:60s}
        max-concurrent: ${APP_AI_DESCRIPTION_MAX_CONCURRENT:2}
      claude:
        oauth-token: ${CLAUDE_CODE_OAUTH_TOKEN:}
        model: ${CLAUDE_MODEL:opus}
        cli-path: ${CLAUDE_CLI_PATH:}
        startup-timeout: ${CLAUDE_STARTUP_TIMEOUT:10s}
        proxy:
          http: ${CLAUDE_HTTP_PROXY:}
          https: ${CLAUDE_HTTPS_PROXY:}
          no-proxy: ${CLAUDE_NO_PROXY:}
```

- [ ] **Step 2: Verify full build still compiles**

Dispatch `build-runner` with `./gradlew build -x test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/main/resources/application.yaml
git commit -m "feat(core): add application.ai.description config section"
```

---

## Phase 7 — Docker

### Task 18: Dockerfile — native Claude CLI install

**Files:**
- Modify: `docker/deploy/Dockerfile`

- [ ] **Step 1: Add Claude CLI installation**

Edit `docker/deploy/Dockerfile`. After the line `RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu` and before `RUN mkdir -p /tmp/frigate-analyzer ...`, insert:

```dockerfile
# Claude Code CLI (native, no Node.js): musl+ripgrep required per Anthropic docs
RUN apk add --no-cache bash libgcc libstdc++ ripgrep
```

Then, after `USER appuser` line near the end, add (before `EXPOSE 8080`):

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

1. `RUN apk add --no-cache bash libgcc libstdc++ ripgrep`  (as root)
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
docker run --rm frigate-analyzer:ai-test claude --version
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
    elif ! command -v claude >/dev/null 2>&1; then
        echo "WARN: claude CLI not found in PATH; AI descriptions will return fallback." >&2
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
# APP_AI_DESCRIPTION_LANGUAGE=en             # ru | en
# APP_AI_DESCRIPTION_SHORT_MAX=200
# APP_AI_DESCRIPTION_DETAILED_MAX=1500
# APP_AI_DESCRIPTION_TIMEOUT=60s
# APP_AI_DESCRIPTION_MAX_CONCURRENT=2

# --- Claude-specific (when provider=claude) ---
# Obtain the token ONCE on the host: `claude setup-token`,
# copy the value here. Long-lived OAuth token (works against your Claude subscription).
# CLAUDE_CODE_OAUTH_TOKEN=
# CLAUDE_MODEL=opus                          # opus | sonnet | haiku (alias)
# CLAUDE_CLI_PATH=                           # empty = SDK uses PATH
# CLAUDE_STARTUP_TIMEOUT=10s

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
                cliPath = "",
                startupTimeout = Duration.ofSeconds(10),
                proxy = ClaudeProperties.ProxySection("", "", ""),
            )
        val common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                timeout = Duration.ofSeconds(30),
                maxConcurrent = 1,
            )

        // point PATH at the stub first so claude command resolves to it
        val originalPath = System.getenv("PATH")
        val newPath = "${tempDir.absolutePathString()}${java.io.File.pathSeparator}$originalPath"
        // cannot change process env in-place; pass stub via cliPath
        val factory = ClaudeAsyncClientFactory(claudeProps.copy(cliPath = stubClaude.absolutePathString()))
        val invoker = DefaultClaudeInvoker(factory, common)

        val mapper = ObjectMapper().registerKotlinModule()
        val stager =
            ClaudeImageStager(
                object : TempFileWriter {
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
                commonSection = common,
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
- [ ] Manual test: disable feature (`APP_AI_DESCRIPTION_ENABLED=false`), confirm notification flow unchanged.
- [ ] PR description references the spec sections (reviewers will want it).
