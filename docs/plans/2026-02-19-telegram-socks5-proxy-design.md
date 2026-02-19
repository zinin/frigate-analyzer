# Telegram SOCKS5 Proxy Support

## Problem

Telegram Bot API requests may be blocked by ISP. Need optional SOCKS5 proxy support for the Telegram bot client.

## Decision

Switch Telegram module from default Ktor Java engine to OkHttp engine unconditionally. When proxy is configured, pass `java.net.Proxy(SOCKS)` to the engine. No authentication — only host:port.

## Changes

### 1. Dependency

`modules/telegram/build.gradle.kts`:
```kotlin
implementation("io.ktor:ktor-client-okhttp")
```

### 2. Configuration

`TelegramProperties` — add optional `proxy: ProxyProperties?`:
```kotlin
data class ProxyProperties(
    val host: String,
    val port: Int = 1080,
)
```

Environment variables: `TELEGRAM_PROXY_HOST`, `TELEGRAM_PROXY_PORT` (default 1080).

`application.yaml`:
```yaml
application:
  telegram:
    proxy:
      host: ${TELEGRAM_PROXY_HOST:}
      port: ${TELEGRAM_PROXY_PORT:1080}
```

When `TELEGRAM_PROXY_HOST` is empty, `proxy` remains `null` — no proxy used.

### 3. Bot Creation

`TelegramAutoConfiguration.telegramBot()` — use `telegramBot(token, clientConfig)` overload:

```kotlin
return telegramBot(properties.botToken) {
    engine {
        proxy?.let {
            this.proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(it.host, it.port))
        }
    }
}
```

Log proxy usage at startup: `"Telegram bot using SOCKS5 proxy: host:port"`.

## Context

- ktgbotapi v30.0.2 default JVM engine: `io.ktor.client.engine.java.Java` (no SOCKS5 support)
- Ktor OkHttp engine: supports both HTTP and SOCKS5 proxy
- `telegramBot()` overloads accept `HttpClient` or `HttpClientConfig` lambda
- No SOCKS5 authentication (would require global `java.net.Authenticator`)
