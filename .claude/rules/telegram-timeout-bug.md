---
paths: "modules/telegram/**/TelegramAutoConfiguration*"
---

# Long Polling Timeout Bug (ktgbotapi)

GitHub issue: https://github.com/InsanusMokrassar/ktgbotapi/issues/1027

## Problem

`buildBehaviourWithLongPolling` logs `HttpRequestTimeoutException` at ERROR level every ~30 seconds
when no updates arrive, even though `autoSkipTimeoutExceptions = true` handles it correctly.

```
[ERROR] KTgBot - Something web wrong
io.ktor.client.plugins.HttpRequestTimeoutException: Request timeout has expired [.../getUpdates, request_timeout=30000 ms]
```

## Root Cause (two issues in the library)

### 1. Per-request timeout equals polling timeout

`AbstractRequestCallFactory.makeCall()` sets per-request timeout for `GetUpdates`:

```kotlin
request.timeout?.times(1000L)?.let { customTimeoutMillis ->
    timeout { requestTimeoutMillis = customTimeoutMillis }  // 30 * 1000 = 30000
}
```

Ktor `requestTimeout` always equals Telegram long polling `timeout` (both 30s).
Due to network latency, Ktor times out slightly before Telegram responds -> race condition.

**Consequence:** configuring global `HttpTimeout` on the Ktor client is useless — per-request override
in `AbstractRequestCallFactory` always takes precedence for `GetUpdates` requests.

### 2. ERROR logged before skip check

In `LongPolling.kt`, the polling loop uses `runCatchingLogging(logger = Log)`:

```kotlin
runCatchingLogging(logger = Log) {           // logs ALL exceptions at ERROR ("Something web wrong")
    execute(getUpdatesRequestCreator(...))
}.onFailure { e ->
    if (isHttpRequestTimeoutException && autoSkipTimeoutExceptions) {
        return@onFailure                     // skip happens AFTER logging
    }
}
```

`runCatchingLogging` from `micro_utils` calls `logger.e(throwable)` before `.onFailure` runs.

## Current Workaround

In `TelegramAutoConfiguration.init`, the global `DefaultKTgBotAPIKSLog` is wrapped with `FilterKSLog`
that filters out `HttpRequestTimeoutException` (and `CommonBotException` wrapping it).

**When the issue is fixed upstream:** remove `suppressLongPollingTimeoutErrors()` from
`TelegramAutoConfiguration` and update the ktgbotapi dependency.
