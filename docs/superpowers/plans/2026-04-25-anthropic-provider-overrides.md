# Anthropic Provider Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable routing Claude CLI requests through alternative Anthropic-compatible providers (e.g., Alibaba DashScope) via ANTHROPIC_* environment variables.

**Architecture:** Add an `AnthropicSection` data class to `ClaudeProperties` with 6 configurable env var fields (all with default `""`). Validation moved to `ClaudeAsyncClientFactory.create()` and `ClaudeDescriptionAgent.init()` — both check `oauthToken || authToken` when `enabled=true`. `buildEnvMap()` is extended to emit ANTHROPIC_* vars. `AnthropicSection.model` renamed to `modelOverride` to avoid confusion with `ClaudeProperties.model`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, JUnit/Kotlin test

---

### Files Map

| File | Responsibility |
|------|----------------|
| `ClaudeProperties.kt` | New `AnthropicSection` data class with defaults |
| `ClaudeAsyncClientFactory.kt` | Extended `buildEnvMap()`, `check()` validation in `create()` |
| `ClaudeDescriptionAgent.kt` | Updated `init check()` for both tokens |
| `application.yaml` | New `anthropic:` YAML block under `claude:` |
| `.env.example` | New commented section for ANTHROPIC_* vars |
| `ClaudeAsyncClientFactoryTest.kt` | Updated helper + 5 test cases |
| `ClaudeDescriptionAgentTest.kt` | Add `anthropic` parameter to constructor |
| `ClaudeDescriptionAgentValidationTest.kt` | Update validation tests |
| `ClaudeDescriptionAgentIntegrationTest.kt` | Add `anthropic` parameter |
| `AiDescriptionAutoConfigurationTest.kt` | Add `anthropic.*` properties |

---

### Task 1: ClaudeProperties — Add AnthropicSection with Defaults

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/ClaudeProperties.kt`

- [ ] **Step 1: Replace the file content**

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
    val cliPath: String, // пусто = SDK ищет через `which claude`
    @field:NotBlank
    val workingDirectory: String, // обязателен для SDK 1.0.0
    @field:Valid
    val proxy: ProxySection,
    @field:Valid
    val anthropic: AnthropicSection,
) {
    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )

    data class AnthropicSection(
        val authToken: String = "",
        val baseUrl: String = "",
        val modelOverride: String = "",
        val defaultOpusModel: String = "",
        val defaultSonnetModel: String = "",
        val defaultHaikuModel: String = "",
    )
}
```

> No init validation here — validation is in ClaudeAsyncClientFactory and ClaudeDescriptionAgent.

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/ClaudeProperties.kt
git commit -m "feat: add AnthropicSection to ClaudeProperties with defaults"
```

---

### Task 2: ClaudeAsyncClientFactory — Extend buildEnvMap() + Add Validation

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`

- [ ] **Step 1: Add validation to `create()` method**

Insert `check()` after the `workTimeout: Duration` parameter and before building options:

```kotlin
    fun create(workTimeout: Duration): ClaudeAsyncClient {
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
            "when application.ai.description.enabled=true"
        }
        val options = ...
```

- [ ] **Step 2: Update `buildEnvMap()` method**

Replace the existing `buildEnvMap()` (lines 39-51) with:

```kotlin
    internal fun buildEnvMap(): Map<String, String> =
        buildMap {
            if (claudeProperties.oauthToken.isNotBlank()) {
                put("CLAUDE_CODE_OAUTH_TOKEN", claudeProperties.oauthToken)
            }
            val proxy = claudeProperties.proxy
            if (proxy.http.isNotBlank()) {
                put("HTTP_PROXY", proxy.http)
            }
            if (proxy.https.isNotBlank()) {
                put("HTTPS_PROXY", proxy.https)
            }
            if (proxy.noProxy.isNotBlank()) {
                put("NO_PROXY", proxy.noProxy)
            }
            val ap = claudeProperties.anthropic
            if (ap.authToken.isNotBlank()) {
                put("ANTHROPIC_AUTH_TOKEN", ap.authToken)
            }
            if (ap.baseUrl.isNotBlank()) {
                put("ANTHROPIC_BASE_URL", ap.baseUrl)
            }
            if (ap.modelOverride.isNotBlank()) {
                put("ANTHROPIC_MODEL", ap.modelOverride)
            }
            if (ap.defaultOpusModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_OPUS_MODEL", ap.defaultOpusModel)
            }
            if (ap.defaultSonnetModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_SONNET_MODEL", ap.defaultSonnetModel)
            }
            if (ap.defaultHaikuModel.isNotBlank()) {
                put("ANTHROPIC_DEFAULT_HAIKU_MODEL", ap.defaultHaikuModel)
            }
        }
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt
git commit -m "feat: add ANTHROPIC_* env vars to buildEnvMap()"
```

---

### Task 3: ClaudeDescriptionAgent — Update Validation

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt`

- [ ] **Step 1: Update `init` block at line 48-51**

Replace:
```kotlin
    init {
        check(claudeProperties.oauthToken.isNotBlank()) {
            "CLAUDE_CODE_OAUTH_TOKEN must be set when application.ai.description.enabled=true"
        }
```

With:
```kotlin
    init {
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
            "when application.ai.description.enabled=true"
        }
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt
git commit -m "feat: accept ANTHROPIC_AUTH_TOKEN as alternative to OAuth token"
```

---

### Task 4: application.yaml — Add anthropic Block

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`

- [ ] **Step 1: Insert anthropic block**

Add the `anthropic:` block after `working-directory:` and before `proxy:` (lines 74-75):

```yaml
        claude:
          oauth-token: ${CLAUDE_CODE_OAUTH_TOKEN:}
          model: ${CLAUDE_MODEL:opus}
          cli-path: ${CLAUDE_CLI_PATH:}
          working-directory: ${CLAUDE_WORKING_DIR:${application.temp-folder}}
          anthropic:
            auth-token: ${ANTHROPIC_AUTH_TOKEN:}
            base-url: ${ANTHROPIC_BASE_URL:}
            model-override: ${ANTHROPIC_MODEL:}
            default-opus-model: ${ANTHROPIC_DEFAULT_OPUS_MODEL:}
            default-sonnet-model: ${ANTHROPIC_DEFAULT_SONNET_MODEL:}
            default-haiku-model: ${ANTHROPIC_DEFAULT_HAIKU_MODEL:}
          proxy:
            http: ${CLAUDE_HTTP_PROXY:}
            https: ${CLAUDE_HTTPS_PROXY:}
            no-proxy: ${CLAUDE_NO_PROXY:}
```

- [ ] **Step 2: Commit**

```bash
git add modules/core/src/main/resources/application.yaml
git commit -m "feat: add anthropic env var config block to application.yaml"
```

---

### Task 4: .env.example — Add Anthropic Section

**Files:**
- Modify: `docker/deploy/.env.example`

- [ ] **Step 1: Insert after the Claude section** (after line 54 `# CLAUDE_WORKING_DIR=`)

```bash
# --- Optional Anthropic-compatible provider overrides ---
# When set, CLAUDE_CODE_OAUTH_TOKEN is not required.
# Allows routing Claude CLI requests through alternative providers
# (e.g., Alibaba DashScope, local proxy, etc.).
# At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN is required.
# ANTHROPIC_AUTH_TOKEN=
# ANTHROPIC_BASE_URL=https://example.com/apps/anthropic
# ANTHROPIC_MODEL=<model-name>
# ANTHROPIC_DEFAULT_OPUS_MODEL=<model-name>
# ANTHROPIC_DEFAULT_SONNET_MODEL=<model-name>
# ANTHROPIC_DEFAULT_HAIKU_MODEL=<model-name>
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/.env.example
git commit -m "docs: add ANTHROPIC_* env vars to .env.example"
```

---

### Task 5: ClaudeDescriptionAgent — Add anthropic parameter

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt:49-55`

- [ ] **Step 1: Add `anthropic` to `claudeProps` constructor**

```kotlin
    private val claudeProps =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            workingDirectory = "/tmp",
            proxy = ClaudeProperties.ProxySection("", "", ""),
            anthropic = ClaudeProperties.AnthropicSection(),
        )
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt
git commit -m "test: add anthropic parameter to ClaudeDescriptionAgentTest"
```

---

### Task 6: ClaudeDescriptionAgentValidationTest — Update Validation Tests

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentValidationTest.kt`

- [ ] **Step 1: Replace the test file**

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

    private fun agent(
        oauthToken: String = "token",
        authToken: String = "",
    ): ClaudeDescriptionAgent =
        ClaudeDescriptionAgent(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = oauthToken,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                    anthropic = ClaudeProperties.AnthropicSection(authToken = authToken),
                ),
            descriptionProperties = descriptionProps,
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects when both tokens blank`() {
        assertFailsWith<IllegalStateException> { agent(oauthToken = "", authToken = "") }
    }

    @Test
    fun `init rejects when both tokens whitespace`() {
        assertFailsWith<IllegalStateException> { agent(oauthToken = "   ", authToken = "   ") }
    }

    @Test
    fun `init accepts oauth token only`() {
        agent(oauthToken = "token-xyz") // no exception
    }

    @Test
    fun `init accepts anthropic auth token only`() {
        agent(oauthToken = "", authToken = "sk-sp-xxx") // no exception
    }

    @Test
    fun `init accepts both tokens`() {
        agent(oauthToken = "token-xyz", authToken = "sk-sp-xxx") // no exception
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentValidationTest.kt
git commit -m "test: update validation tests for oauthToken || authToken"
```

---

### Task 7: ClaudeDescriptionAgentIntegrationTest — Add anthropic

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt:79-85`

- [ ] **Step 1: Add `anthropic` to `claudeProps` constructor**

```kotlin
        val claudeProps =
            ClaudeProperties(
                oauthToken = "fake",
                model = "opus",
                cliPath = stubClaude.absolutePathString(),
                workingDirectory = tempDir.absolutePathString(),
                proxy = ClaudeProperties.ProxySection("", "", ""),
                anthropic = ClaudeProperties.AnthropicSection(),
            )
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt
git commit -m "test: add anthropic parameter to ClaudeDescriptionAgentIntegrationTest"
```

---

### Task 8: AiDescriptionAutoConfigurationTest — Add anthropic Properties

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`

- [ ] **Step 1: Add anthropic properties to all 3 test methods**

Add these lines to the `withPropertyValues()` call in each test:
```kotlin
"application.ai.description.claude.anthropic.auth-token=",
"application.ai.description.claude.anthropic.base-url=",
"application.ai.description.claude.anthropic.model-override=",
"application.ai.description.claude.anthropic.default-opus-model=",
"application.ai.description.claude.anthropic.default-sonnet-model=",
"application.ai.description.claude.anthropic.default-haiku-model=",
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt
git commit -m "test: add anthropic properties to AiDescriptionAutoConfigurationTest"
```

---

### Task 9: Tests — Update ClaudeAsyncClientFactoryTest

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt`

- [ ] **Step 1: Replace the entire test file**

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
        authToken: String = "",
        baseUrl: String = "",
        modelOverride: String = "",
        defaultOpusModel: String = "",
        defaultSonnetModel: String = "",
        defaultHaikuModel: String = "",
    ) = ClaudeProperties(
        oauthToken = token,
        model = model,
        cliPath = "",
        workingDirectory = "/tmp/frigate-analyzer",
        proxy = ClaudeProperties.ProxySection(http, https, noProxy),
        anthropic = ClaudeProperties.AnthropicSection(
            authToken = authToken,
            baseUrl = baseUrl,
            modelOverride = modelOverride,
            defaultOpusModel = defaultOpusModel,
            defaultSonnetModel = defaultSonnetModel,
            defaultHaikuModel = defaultHaikuModel,
        ),
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
    fun `env map omits oauth token when blank`() {
        val env = factory(props(token = "", authToken = "dummy")).buildEnvMap()
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `env map contains only expected keys when anthropic vars blank`() {
        val env = factory(props()).buildEnvMap()
        assertTrue(env.keys == setOf("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `anthropic vars omitted when blank`() {
        val env = factory(props()).buildEnvMap()
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
        assertFalse(env.containsKey("ANTHROPIC_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_OPUS_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_SONNET_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_DEFAULT_HAIKU_MODEL"))
    }

    @Test
    fun `anthropic vars included when set`() {
        val env =
            factory(
                props(
                    authToken = "sk-sp-xxx",
                    baseUrl = "https://example.com/apps/anthropic",
                    modelOverride = "qwen3.5-plus",
                    defaultOpusModel = "qwen3.5-plus",
                    defaultSonnetModel = "qwen3.5-plus",
                    defaultHaikuModel = "qwen3.5-plus",
                ),
            ).buildEnvMap()
        assertEquals("sk-sp-xxx", env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://example.com/apps/anthropic", env["ANTHROPIC_BASE_URL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("qwen3.5-plus", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
    }
}
```

> Note: `throws when no token configured` test moved to `ClaudeDescriptionAgentValidationTest` — validation is now in the agents, not in the properties data class.

- [ ] **Step 2: Run tests to verify**

```bash
./gradlew :ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.claude.ClaudeAsyncClientFactoryTest"
```

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt
git commit -m "test: update ClaudeAsyncClientFactoryTest for anthropic support"
```

---

## Spec Coverage Checklist

| Spec Requirement | Task |
|-----------------|------|
| AnthropicSection data class with defaults | Task 1 |
| No init validation in ClaudeProperties | Task 1 |
| buildEnvMap() emits ANTHROPIC_* vars (non-blank only) | Task 2 |
| buildEnvMap() omits oauthToken when blank | Task 2, Task 9 |
| ClaudeAsyncClientFactory validation in create() | Task 2 |
| ClaudeDescriptionAgent init check updated | Task 3 |
| application.yaml anthropic block | Task 4 |
| .env.example Anthropic section | Task 4 |
| ClaudeDescriptionAgentTest anthropic param | Task 5 |
| ClaudeDescriptionAgentValidationTest updated | Task 6 |
| ClaudeDescriptionAgentIntegrationTest anthropic | Task 7 |
| AiDescriptionAutoConfigurationTest anthropic props | Task 8 |
| Test: env map only expected keys when blank | Task 9 |
| Test: anthropic vars omitted when blank | Task 9 |
| Test: anthropic vars included when set | Task 9 |
| Test: oauth token omitted when blank | Task 9 |

## Self-Review

- **Placeholder scan:** None found — all steps have complete code
- **Type consistency:** `AnthropicSection` field names match: Task 1 (`modelOverride`), Task 4 (YAML `model-override`), Task 9 (test `modelOverride`)
- **Default values:** All 6 AnthropicSection fields have `= ""` defaults, ensuring Spring Boot can bind without explicit YAML block
- **Validation moved:** `ClaudeProperties` is pure data class; validation lives in `ClaudeAsyncClientFactory.create()` and `ClaudeDescriptionAgent.init()` — both only active when `enabled=true`
- **Existing test compatibility:** Test files updated in Tasks 5-8 with new `anthropic` parameter using `AnthropicSection()` default constructor
- **Validation tests:** Moved from ClaudeAsyncClientFactory to ClaudeDescriptionAgentValidationTest (Task 6) — covers oauth-only, auth-only, both, both blank scenarios
