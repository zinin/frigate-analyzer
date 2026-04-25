# Design: ANTHROPIC Environment Variable Overrides for Alternative Providers

## Goal

Enable using Anthropic-compatible API providers (e.g., Alibaba DashScope) instead of the official Claude API by passing `ANTHROPIC_*` environment variables through the Claude CLI SDK.

## Context

`ClaudeAsyncClientFactory.buildEnvMap()` currently only sets:
- `CLAUDE_CODE_OAUTH_TOKEN` (OAuth token for official Claude CLI)
- `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (proxy passthrough)

Users want to route Claude CLI requests through alternative providers. The SDK supports this via `ANTHROPIC_*` env vars (base URL, auth token, model aliases).

## Changes

### 1. ClaudeProperties.kt — new `AnthropicSection`

```kotlin
data class ClaudeProperties(
    val oauthToken: String,
    @field:NotBlank
    val model: String,
    val cliPath: String,
    @field:NotBlank
    val workingDirectory: String,
    @field:Valid
    val proxy: ProxySection,
    @field:Valid
    val anthropic: AnthropicSection,  // NEW
) {
    init {
        require(oauthToken.isNotBlank() || anthropic.authToken.isNotBlank()) {
            "At least one of 'application.ai.description.claude.oauth-token' or " +
            "'application.ai.description.claude.anthropic.auth-token' must be set"
        }
    }

    data class AnthropicSection(
        val authToken: String,
        val baseUrl: String,
        val model: String,
        val defaultOpusModel: String,
        val defaultSonnetModel: String,
        val defaultHaikuModel: String,
    )

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
```

### 2. ClaudeAsyncClientFactory.kt — extended `buildEnvMap()`

```kotlin
internal fun buildEnvMap(): Map<String, String> = buildMap {
    if (claudeProperties.oauthToken.isNotBlank()) {
        put("CLAUDE_CODE_OAUTH_TOKEN", claudeProperties.oauthToken)
    }
    // proxy vars (existing, unchanged)
    val proxy = claudeProperties.proxy
    if (proxy.http.isNotBlank()) put("HTTP_PROXY", proxy.http)
    if (proxy.https.isNotBlank()) put("HTTPS_PROXY", proxy.https)
    if (proxy.noProxy.isNotBlank()) put("NO_PROXY", proxy.noProxy)
    // anthropic overrides (NEW — only non-blank)
    val ap = claudeProperties.anthropic
    if (ap.authToken.isNotBlank()) put("ANTHROPIC_AUTH_TOKEN", ap.authToken)
    if (ap.baseUrl.isNotBlank()) put("ANTHROPIC_BASE_URL", ap.baseUrl)
    if (ap.model.isNotBlank()) put("ANTHROPIC_MODEL", ap.model)
    if (ap.defaultOpusModel.isNotBlank()) put("ANTHROPIC_DEFAULT_OPUS_MODEL", ap.defaultOpusModel)
    if (ap.defaultSonnetModel.isNotBlank()) put("ANTHROPIC_DEFAULT_SONNET_MODEL", ap.defaultSonnetModel)
    if (ap.defaultHaikuModel.isNotBlank()) put("ANTHROPIC_DEFAULT_HAIKU_MODEL", ap.defaultHaikuModel)
}
```

### 3. application.yaml — new block under `claude:`

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

### 4. .env.example — new section

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

### 5. Tests — updated `ClaudeAsyncClientFactoryTest`

New test cases:
- `env map omits oauth token when blank` — oauthToken пустой, authToken задан → только ANTHROPIC_AUTH_TOKEN
- `anthropic vars omitted when blank` — все поля пустые → ни одного ANTHROPIC_* ключа
- `anthropic vars included when set` — все 6 заполнены → все присутствуют
- `throws when no token configured` — оба токена пустые → `IllegalArgumentException`

## Validation Strategy

`require()` in `ClaudeProperties.init` — throws `IllegalArgumentException` at bean creation time if both tokens are blank. Spring Boot will surface this as a startup failure with a clear message.

## Usage Examples

**Official Claude (unchanged):**
```env
CLAUDE_CODE_OAUTH_TOKEN=oxa-...
```

**DashScope alternative provider:**
```env
ANTHROPIC_AUTH_TOKEN=sk-sp-...
ANTHROPIC_BASE_URL=https://coding-intl.dashscope.aliyuncs.com/apps/anthropic
ANTHROPIC_MODEL=qwen3.5-plus
ANTHROPIC_DEFAULT_OPUS_MODEL=qwen3.5-plus
ANTHROPIC_DEFAULT_SONNET_MODEL=qwen3.5-plus
ANTHROPIC_DEFAULT_HAIKU_MODEL=qwen3.5-plus
```

## Files Modified

| File | Change |
|------|--------|
| `ClaudeProperties.kt` | `AnthropicSection` data class + `init` validation |
| `ClaudeAsyncClientFactory.kt` | `buildEnvMap()` дополняется ANTHROPIC_* vars |
| `application.yaml` | Блок `anthropic:` под `claude:` |
| `.env.example` | Секция Anthropic provider overrides |
| `ClaudeAsyncClientFactoryTest.kt` | 4 новых теста |

## Non-Goals

- Поддержка других провайдеров (OpenAI, etc.) — это потребует иного SDK
- Динамический switch провайдеров во runtime
- Валидация формата токена или URL
