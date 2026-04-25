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
    val anthropic: AnthropicSection,  // NEW — все поля с дефолтами ""
) {
    data class AnthropicSection(
        val authToken: String = "",
        val baseUrl: String = "",
        val modelOverride: String = "",  // переопределение модели для альтернативного провайдера
        val defaultOpusModel: String = "",
        val defaultSonnetModel: String = "",
        val defaultHaikuModel: String = "",
    )

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
```

> **Решение review (iter 1):** `init`-валидация удалена — ClaudeProperties остаётся "глупым" data class. Валидация перенесена в ClaudeAsyncClientFactory (активен только при enabled=true). Поля AnthropicSection имеют default-значения `= ""` для совместимости с Spring Boot binding и существующими тестами.

### 2. ClaudeAsyncClientFactory.kt — extended `buildEnvMap()` + validation

Validation added to `create()` (only called when `enabled=true`):

```kotlin
fun create(workTimeout: Duration): ClaudeAsyncClient {
    check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
        "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
        "when application.ai.description.enabled=true"
    }
    // ... rest of create()
}
```

`buildEnvMap()` updated to emit ANTHROPIC_* vars and conditionally emit oauthToken:

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
    if (ap.modelOverride.isNotBlank()) put("ANTHROPIC_MODEL", ap.modelOverride)
    if (ap.defaultOpusModel.isNotBlank()) put("ANTHROPIC_DEFAULT_OPUS_MODEL", ap.defaultOpusModel)
    if (ap.defaultSonnetModel.isNotBlank()) put("ANTHROPIC_DEFAULT_SONNET_MODEL", ap.defaultSonnetModel)
    if (ap.defaultHaikuModel.isNotBlank()) put("ANTHROPIC_DEFAULT_HAIKU_MODEL", ap.defaultHaikuModel)
}
```

> **Model precedence:** Если `anthropic.modelOverride` задан, `ANTHROPIC_MODEL` env var передаётся CLI. `ClaudeAsyncClientFactory.create()` также может опционально не передавать `CLIOptions.model()` когда modelOverride задан — чтобы CLI использовал модель из env var.

### 3. ClaudeDescriptionAgent.kt — update validation

Existing `check()` at line 49 updated to accept either token:

```kotlin
init {
    check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
        "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
        "when application.ai.description.enabled=true"
    }
    // ... rest unchanged
}
```

### 4. application.yaml — new block under `claude:`

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

### 5. .env.example — new section

```bash
# --- Optional Anthropic-compatible provider overrides ---
# When set, CLAUDE_CODE_OAUTH_TOKEN is not required.
# Allows routing Claude CLI requests through alternative providers
# (e.g., Alibaba DashScope, local proxy, etc.).
# ANTHROPIC_AUTH_TOKEN=
# ANTHROPIC_BASE_URL=https://example.com/apps/anthropic
# ANTHROPIC_MODEL=<model-name>
# ANTHROPIC_DEFAULT_OPUS_MODEL=<model-name>
# ANTHROPIC_DEFAULT_SONNET_MODEL=<model-name>
# ANTHROPIC_DEFAULT_HAIKU_MODEL=<model-name>
```

### 6. Tests — updated `ClaudeAsyncClientFactoryTest`

New test cases:
- `env map omits oauth token when blank` — `props(token = "", authToken = "dummy")` → только ANTHROPIC_AUTH_TOKEN (authToken нужен для прохождения валидации)
- `anthropic vars omitted when blank` — все поля пустые → ни одного ANTHROPIC_* ключа
- `anthropic vars included when set` — все 6 заполнены → все присутствуют
- `throws when no token configured` — оба токена пустые → `IllegalStateException` из `create()`
- `env map contains only expected keys when anthropic vars blank` — regression-test для проверки что пустые поля не протекают

### 7. Tests — update other test files

Four additional test files need `anthropic` parameter in `ClaudeProperties` constructors:
| Файл | Изменение |
|------|-----------|
| `ClaudeDescriptionAgentTest.kt` | Добавить `anthropic = ClaudeProperties.AnthropicSection()` |
| `ClaudeDescriptionAgentValidationTest.kt` | Добавить `anthropic`, обновить тесты на `oauthToken || authToken` |
| `ClaudeDescriptionAgentIntegrationTest.kt` | Добавить `anthropic = ClaudeProperties.AnthropicSection()` |
| `AiDescriptionAutoConfigurationTest.kt` | Добавить свойства `anthropic.*` в test property values |

## Validation Strategy

`check()` in `ClaudeAsyncClientFactory.create()` and `ClaudeDescriptionAgent.init()` — throws `IllegalStateException` when `enabled=true` and both tokens are blank. `ClaudeProperties` is a pure data class with no init validation, so it never blocks startup when `enabled=false`. Default values (`= ""`) on `AnthropicSection` fields ensure Spring Boot can bind config even without an explicit `anthropic:` block in YAML.

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

**Both tokens configured:** If both `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_AUTH_TOKEN` are set, both env vars are passed to the Claude CLI subprocess. The SDK's priority rules apply (typically CLI `--model` flag takes precedence over `ANTHROPIC_MODEL` env var).

## Files Modified

| File | Change |
|------|--------|
| `ClaudeProperties.kt` | `AnthropicSection` data class with default values |
| `ClaudeAsyncClientFactory.kt` | `buildEnvMap()` дополняется ANTHROPIC_* vars, `check()` валидация |
| `ClaudeDescriptionAgent.kt` | Обновлён `init check()` для поддержки обоих токенов |
| `application.yaml` | Блок `anthropic:` под `claude:` |
| `.env.example` | Секция Anthropic provider overrides |
| `ClaudeAsyncClientFactoryTest.kt` | Обновлённый helper, 5 новых тестов |
| `ClaudeDescriptionAgentTest.kt` | Добавить `anthropic` параметр |
| `ClaudeDescriptionAgentValidationTest.kt` | Обновить тесты валидации |
| `ClaudeDescriptionAgentIntegrationTest.kt` | Добавить `anthropic` параметр |
| `AiDescriptionAutoConfigurationTest.kt` | Добавить `anthropic.*` свойства |

## Breaking Changes

- `CLAUDE_CODE_OAUTH_TOKEN` теперь **опционален** — если задан `ANTHROPIC_AUTH_TOKEN`, OAuth-токен не требуется
- `buildEnvMap()` перестаёт передавать пустой `CLAUDE_CODE_OAUTH_TOKEN` в env (раньше передавался всегда). Если SDK зависит от presence пустого ключа — это изменение требует проверки
- Существующие конфигурации с `CLAUDE_CODE_OAUTH_TOKEN` без изменений работают как прежде

## Non-Goals

- Поддержка других провайдеров (OpenAI, etc.) — это потребует иного SDK
- Динамический switch провайдеров во runtime
- Валидация формата токена или URL
