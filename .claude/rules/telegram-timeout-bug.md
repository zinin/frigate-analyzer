---
paths: "modules/telegram/**/TelegramAutoConfiguration*"
---

# Long Polling Timeout Bug (ktgbotapi) — RESOLVED

GitHub issue: https://github.com/InsanusMokrassar/ktgbotapi/issues/1027

## Status

**Fixed in ktgbotapi v31.2.0.** The library replaced `runCatchingLogging` with `runCatching`,
moving logging after the `autoSkipTimeoutExceptions` check. The workaround (`FilterKSLog` in
`TelegramAutoConfiguration`) was removed when upgrading to v31.2.0.

## Original Problem

`buildBehaviourWithLongPolling` logged `HttpRequestTimeoutException` at ERROR level every ~30 seconds
when no updates arrived, even though `autoSkipTimeoutExceptions = true` handled it correctly.

### Root Cause

1. Per-request timeout in `AbstractRequestCallFactory.makeCall()` equalled Telegram long polling timeout (both 30s), causing race conditions with network latency.
2. `runCatchingLogging` logged ALL exceptions at ERROR before `.onFailure` could skip timeout exceptions.
