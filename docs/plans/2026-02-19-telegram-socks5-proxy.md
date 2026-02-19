# Telegram SOCKS5 Proxy Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add optional SOCKS5 proxy support for the Telegram bot to bypass ISP blocking.

**Architecture:** Switch Ktor engine from Java to OkHttp unconditionally in telegram module. When `TELEGRAM_PROXY_HOST` env var is set, configure SOCKS5 proxy on the OkHttp client. No authentication.

**Tech Stack:** Ktor OkHttp engine, Spring Boot ConfigurationProperties, ktgbotapi v30.0.2

---

### Task 1: Add ktor-client-okhttp dependency

**Files:**
- Modify: `modules/telegram/build.gradle.kts:18`

**Step 1: Add dependency**

After the existing `dev.inmo:tgbotapi` line (line 18), add:

```kotlin
    implementation("io.ktor:ktor-client-okhttp")
```

Version is managed by Ktor BOM pulled transitively by ktgbotapi.

**Step 2: Commit**

```bash
git add modules/telegram/build.gradle.kts
git commit -m "feat: add ktor-client-okhttp dependency for SOCKS5 proxy support"
```

---

### Task 2: Add ProxyProperties and update TelegramProperties

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/TelegramProperties.kt`

**Step 1: Add ProxyProperties data class and proxy field**

Add `ProxyProperties` data class in the same file (before `TelegramProperties`):

```kotlin
data class ProxyProperties(
    val host: String = "",
    val port: Int = 1080,
)
```

Add `proxy` field to `TelegramProperties`:

```kotlin
val proxy: ProxyProperties? = null,
```

Full file should be:

```kotlin
package ru.zinin.frigate.analyzer.telegram.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

data class ProxyProperties(
    val host: String = "",
    val port: Int = 1080,
)

@ConfigurationProperties(prefix = "application.telegram")
@Validated
data class TelegramProperties(
    val enabled: Boolean = false,
    @field:NotBlank(message = "Telegram bot token must not be blank")
    val botToken: String,
    @field:NotBlank(message = "Telegram owner username must not be blank")
    val owner: String,
    val unauthorizedMessage: String = "Доступ запрещен. Вы не авторизованы для использования этого бота.",
    @field:Min(1)
    val queueCapacity: Int = 100,
    val sendVideoTimeout: Duration = Duration.ofMinutes(3),
    val proxy: ProxyProperties? = null,
)
```

**Step 2: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/TelegramProperties.kt
git commit -m "feat: add ProxyProperties for optional SOCKS5 proxy configuration"
```

---

### Task 3: Add proxy config to application.yaml

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml:45`

**Step 1: Add proxy section under telegram**

After `send-video-timeout` line (line 45), add:

```yaml
    proxy:
      host: ${TELEGRAM_PROXY_HOST:}
      port: ${TELEGRAM_PROXY_PORT:1080}
```

**Step 2: Commit**

```bash
git add modules/core/src/main/resources/application.yaml
git commit -m "feat: add TELEGRAM_PROXY_HOST/PORT env vars to application.yaml"
```

---

### Task 4: Switch TelegramAutoConfiguration to OkHttp engine with optional proxy

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/TelegramAutoConfiguration.kt:29`

**Step 1: Update telegramBot bean**

Replace line 29:
```kotlin
    fun telegramBot(properties: TelegramProperties): TelegramBot = telegramBot(properties.botToken)
```

With:
```kotlin
    fun telegramBot(properties: TelegramProperties): TelegramBot {
        val proxyConfig = properties.proxy?.takeIf { it.host.isNotBlank() }

        if (proxyConfig != null) {
            logger.info { "Telegram bot using SOCKS5 proxy: ${proxyConfig.host}:${proxyConfig.port}" }
        }

        return telegramBot(properties.botToken) {
            engine {
                if (proxyConfig != null) {
                    proxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress(proxyConfig.host, proxyConfig.port),
                    )
                }
            }
        }
    }
```

The `engine {}` block without an explicit engine factory uses the first engine on classpath. Since we added `ktor-client-okhttp`, OkHttp will be selected. The `telegramBot(token, clientConfig)` overload from `dev.inmo.tgbotapi.extensions.api.BotExtensions` passes the lambda to `HttpClient(clientConfig)`.

**Step 2: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/config/TelegramAutoConfiguration.kt
git commit -m "feat: switch Telegram bot to OkHttp engine with optional SOCKS5 proxy"
```

---

### Task 5: Build and verify

**Step 1: Run ktlintFormat**

```bash
./gradlew ktlintFormat
```

Fix any formatting issues reported.

**Step 2: Run full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all 86 tests pass.

**Step 3: If build fails, fix issues and re-run**

Common issues:
- Import conflicts: ensure `java.net.Proxy` and `java.net.InetSocketAddress` are imported (or use FQN as shown above)
- ktlint: long lines, trailing commas

**Step 4: Commit any formatting fixes**

```bash
git add -A
git commit -m "style: ktlint formatting"
```

---

### Task 6: Update configuration docs

**Files:**
- Modify: `.claude/rules/configuration.md`
- Modify: `.claude/rules/telegram.md`

**Step 1: Add proxy env vars to configuration.md**

Add to the Telegram section reference in `configuration.md` (or note it's in telegram.md).

**Step 2: Add proxy section to telegram.md**

Add to the Configuration table in `.claude/rules/telegram.md`:

| Variable | Default | Purpose |
|----------|---------|---------|
| `TELEGRAM_PROXY_HOST` | (empty) | SOCKS5 proxy host. Empty = no proxy |
| `TELEGRAM_PROXY_PORT` | 1080 | SOCKS5 proxy port |

**Step 3: Commit**

```bash
git add .claude/rules/configuration.md .claude/rules/telegram.md
git commit -m "docs: add SOCKS5 proxy configuration to rules docs"
```
