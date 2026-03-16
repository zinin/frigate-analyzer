---
paths: "modules/telegram/**/TelegramAutoConfiguration*"
---

# Long Polling Timeout Bug (ktgbotapi) — WORKAROUND ACTIVE

GitHub issue: https://github.com/InsanusMokrassar/ktgbotapi/issues/1027

## Status

**Workaround active in `TelegramAutoConfiguration`.** The `FilterKSLog` filter suppresses
ERROR-level `HttpRequestTimeoutException` from KSLog.

### Timeline

- **v30.0.2**: Bug discovered. Added `FilterKSLog` workaround.
- **v31.2.0**: Library fixed `LongPolling.kt` (`runCatchingLogging` → `runCatching` in polling loop).
  Workaround removed — but the fix was **partial** (see below).
- **v32.0.0**: ERROR logs returned. `FilterKSLog` workaround restored.

### Why the v31.2.0 fix was partial

The v31.2.0 fix changed `LongPolling.kt` to use `runCatching` instead of `runCatchingLogging`
in the polling loop. However, `DefaultKtorRequestsExecutor.execute()` still uses
`runCatchingLogging` which logs `HttpRequestTimeoutException` at ERROR **before** the exception
propagates back to the polling loop where `autoSkipTimeoutExceptions` would silently skip it.

The bug was always present in both v31.2.0 and v32.0.0 at the `DefaultKtorRequestsExecutor`
level (MicroUtils `runCatchingLogging` with default message `"Something web wrong"`).

## Root Cause

Two issues in the library:

1. **Race condition**: Per-request timeout in `AbstractRequestCallFactory.makeCall()` equals
   Telegram long polling timeout (both 30s). Network latency causes Ktor timeout to fire
   slightly before Telegram responds, triggering `HttpRequestTimeoutException` every ~30s.
2. **Premature logging**: `DefaultKtorRequestsExecutor.execute()` wraps the call in
   `runCatchingLogging` which logs at ERROR before the long polling handler can skip it.

## Upstream: Awaiting Response

Comment posted on 2026-03-16 with detailed analysis of the partial fix and suggested solutions:
https://github.com/InsanusMokrassar/ktgbotapi/issues/1027#issuecomment-4065937766

When the library fix lands, remove `FilterKSLog` workaround from `TelegramAutoConfiguration`.
