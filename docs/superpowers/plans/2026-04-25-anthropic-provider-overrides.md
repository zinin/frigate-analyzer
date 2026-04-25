# Anthropic Provider Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable routing Claude CLI requests through alternative Anthropic-compatible providers (e.g., Alibaba DashScope) via ANTHROPIC_* environment variables.

**Architecture:** Add an `AnthropicSection` data class to `ClaudeProperties` with 6 configurable env var fields. `ClaudeAsyncClientFactory.buildEnvMap()` is extended to emit ANTHROPIC_* vars. Startup validation ensures at least one auth method (OAuth or ANTHROPIC_AUTH_TOKEN) is configured.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, JUnit/Kotlin test

---

### Files Map

| File | Responsibility |
|------|----------------|
| `ClaudeProperties.kt` | New `AnthropicSection` data class + `init` validation |
| `ClaudeAsyncClientFactory.kt` | Extended `buildEnvMap()` to emit ANTHROPIC_* vars |
| `application.yaml` | New `anthropic:` YAML block under `claude:` |
| `.env.example` | New commented section for ANTHROPIC_* vars |
| `ClaudeAsyncClientFactoryTest.kt` | Updated helper + 4 new test cases |

---

### Task 1: ClaudeProperties — Add AnthropicSection + Validation

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
    init {
        require(oauthToken.isNotBlank() || anthropic.authToken.isNotBlank()) {
            "At least one of 'application.ai.description.claude.oauth-token' or " +
            "'application.ai.description.claude.anthropic.auth-token' must be set"
        }
    }

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )

    data class AnthropicSection(
        val authToken: String,
        val baseUrl: String,
        val model: String,
        val defaultOpusModel: String,
        val defaultSonnetModel: String,
        val defaultHaikuModel: String,
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/ClaudeProperties.kt
git commit -m "feat: add AnthropicSection to ClaudeProperties with validation"
```

---

### Task 2: ClaudeAsyncClientFactory — Extend buildEnvMap()

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`

- [ ] **Step 1: Update `buildEnvMap()` method**

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
            if (ap.model.isNotBlank()) {
                put("ANTHROPIC_MODEL", ap.model)
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

### Task 3: application.yaml — Add anthropic Block

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
            model: ${ANTHROPIC_MODEL:}
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
# ANTHROPIC_AUTH_TOKEN=
# ANTHROPIC_BASE_URL=https://example.com/apps/anthropic
# ANTHROPIC_MODEL=qwen3.5-plus
# ANTHROPIC_DEFAULT_OPUS_MODEL=qwen3.5-plus
# ANTHROPIC_DEFAULT_SONNET_MODEL=qwen3.5-plus
# ANTHROPIC_DEFAULT_HAIKU_MODEL=qwen3.5-plus
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/.env.example
git commit -m "docs: add ANTHROPIC_* env vars to .env.example"
```

---

### Task 5: Tests — Update Helper + Add 4 New Cases

**Files:**
- Modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt`

- [ ] **Step 1: Replace the entire test file**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
        anthropicModel: String = "",
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
            model = anthropicModel,
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
        val env = factory(props(token = "")).buildEnvMap()
        assertFalse(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
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
                    anthropicModel = "qwen3.5-plus",
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

    @Test
    fun `throws when no token configured`() {
        assertFailsWith<IllegalArgumentException> {
            props(token = "", authToken = "")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify**

```bash
./gradlew :ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.claude.ClaudeAsyncClientFactoryTest"
```

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt
git commit -m "test: add anthropic env var tests + oauth-blank + no-token validation"
```

---

## Spec Coverage Checklist

| Spec Requirement | Task |
|-----------------|------|
| AnthropicSection data class (6 fields) | Task 1 |
| Init validation (require at least one token) | Task 1 |
| buildEnvMap() emits ANTHROPIC_* vars (non-blank only) | Task 2 |
| buildEnvMap() omits oauthToken when blank | Task 2, Task 5 |
| application.yaml anthropic block | Task 3 |
| .env.example Anthropic section | Task 4 |
| Test: anthropic vars omitted when blank | Task 5 |
| Test: anthropic vars included when set | Task 5 |
| Test: oauth token omitted when blank | Task 5 |
| Test: throws when no token configured | Task 5 |

## Self-Review

- **Placeholder scan:** None found — all steps have complete code
- **Type consistency:** `AnthropicSection` field names match between Task 1 (data class), Task 3 (YAML), Task 4 (env), Task 5 (test helper)
- **Existing test compatibility:** The 3 existing tests (`OAuth token`, `proxy vars blank`, `proxy vars set`) still compile because the `props()` helper defaults `anthropic` to all-blank strings, and validation passes since `oauthToken="token-1"` is non-blank by default
- **Validation test correctness:** `assertFailsWith<IllegalArgumentException> { props(token = "", authToken = "") }` — when both tokens are blank, the `init` block throws before `props()` returns, so the test correctly catches it
