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

## Pending: Comment About Root Cause

v31.2.0 fixed issue 2 (logging), but issue 1 (race condition) is still present — exceptions are silently swallowed every ~30s. Consider posting this comment to the issue:

---

Thank you for fixing the premature error logging — upgrading to v31.2.0 resolved the noisy logs and I was able to remove my `FilterKSLog` workaround.

However, I believe the root cause is still present: in `AbstractRequestCallFactory.makeCall()`, the per-request `requestTimeoutMillis` is set to exactly `request.timeout * 1000` — the same value as the Telegram long polling `timeout`. Because of network latency and processing overhead, Ktor's HTTP timeout fires slightly before Telegram's server responds, creating a race condition that triggers `HttpRequestTimeoutException` on almost every idle polling cycle.

The current fix suppresses the log, but the exception itself still occurs and is silently swallowed. This means every ~30s an exception is thrown, caught, and discarded — which is unnecessary overhead and makes it harder to notice *real* timeout issues.

A more robust fix would be to add a small margin to the per-request timeout for `GetUpdates` requests, e.g.:

```kotlin
// Instead of:
timeout { requestTimeoutMillis = customTimeoutMillis }

// Something like:
timeout { requestTimeoutMillis = customTimeoutMillis + 5000 }
```

This way the Ktor timeout would only fire if Telegram genuinely fails to respond, rather than racing against normal long polling behavior.

Would you consider addressing this? Happy to submit a PR if helpful.
