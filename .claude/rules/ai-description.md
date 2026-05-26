---
paths: "modules/ai-description/**,**/DescriptionEditJobRunner*,**/AiDescription*,**/DescriptionMessageFormatter*"
---

# AI Description Module

Optional module that generates short and detailed natural-language descriptions of detections by
invoking the Claude Code CLI via `org.springaicommunity:claude-code-sdk`. Gated by
`application.ai.description.enabled` — when `false`, no bean is created, no Claude binary is
required, and the notification flow runs unchanged (no caption suffix, no edit job).

The `claude` CLI binary is installed into the runtime container by `docker/deploy/Dockerfile`
(`curl -fsSL https://claude.ai/install.sh | bash`); local development must have `claude` on `PATH`.

## Layers

| Layer | Component | Location | Purpose |
|-------|-----------|----------|---------|
| API | `DescriptionAgent` | `api/` | Single-method `suspend fun describe(request): DescriptionResult` |
| API | `DescriptionRequest` / `DescriptionResult` / `DescriptionException` | `api/` | Public DTOs |
| API | `TempFileWriter` | `api/` | Filesystem abstraction for staging images |
| Claude impl | `ClaudeDescriptionAgent` | `claude/` | Orchestrates stage → prompt → invoke → parse |
| Claude impl | `ClaudeImageStager` | `claude/` | Writes frame bytes to temp files for `claude` CLI |
| Claude impl | `ClaudePromptBuilder` | `claude/` | Builds prompt with language/format/max-length rules |
| Claude impl | `ClaudeInvoker` / `DefaultClaudeInvoker` | `claude/` | Wraps `ClaudeAsyncClient` from spring-ai-claude-code-sdk |
| Claude impl | `ClaudeAsyncClientFactory` | `claude/` | Builds the async client (concurrency, timeout, binary path) |
| Claude impl | `ClaudeResponseParser` | `claude/` | Parses JSON `{short, detailed}` reply, applies max-length truncation |
| Claude impl | `ClaudeExceptionMapper` | `claude/` | Maps SDK exceptions → `DescriptionException` taxonomy |
| Config | `AiDescriptionAutoConfiguration` | `config/` | Spring auto-config gated by `enabled=true` + `provider=claude` |
| Config | `DescriptionProperties` / `ClaudeProperties` | `config/` | `@ConfigurationProperties` for `application.ai.description.*` |
| Config | `DescriptionAgentSanityChecker` | `config/` | Startup smoke-test (invokes `claude --version` or similar) |
| Limits | `DescriptionRateLimiter` | `ratelimit/` | Sliding-window throttle; `tryAcquire()` returns false when quota exceeded |

## Integration with Telegram

When a notification is enqueued and AI description is enabled:

1. `TelegramNotificationSender` sends notification with placeholder caption + placeholder reply
   message (only if `DescriptionRateLimiter.tryAcquire()` succeeded).
2. `DescriptionEditJobRunner` (in `telegram/queue/`) launches a coroutine on `DescriptionEditScope`
   that awaits `DescriptionAgent.describe(...)`.
3. On success: `editMessageCaption` + `editMessageText` (HTMLParseMode) replace placeholders with
   formatted text built by `DescriptionMessageFormatter`.
4. On failure: caught and logged; placeholders are best-effort rewritten back to the base text
   (no error message exposed to the user).

`AiDescriptionTelegramGuard` (in telegram module) fails fast at startup when
`ai.description.enabled=true` is paired with `telegram.enabled=false` — the feature only makes
sense when there's a chat to edit.

## Rate Limiting

- `DescriptionRateLimiter` enforces a sliding window (`max` requests per `window`).
- Counter increments **when a slot is granted**; failed Claude calls do NOT refund the slot —
  this is intentional to keep cost predictable when the binary is misbehaving.
- When the limit is exceeded, the recording is sent to Telegram as a plain notification — no
  placeholder caption, no reply message, no edit job, no Claude call.
- Disable with `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED=false`.

## Concurrency

- `APP_AI_DESCRIPTION_MAX_CONCURRENT` (default `2`) bounds simultaneous Claude calls — enforced
  inside `ClaudeAsyncClientFactory` via a `Semaphore`.
- `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` (default `30s`) — max wait for a free slot before failing
  the describe call.
- `APP_AI_DESCRIPTION_TIMEOUT` (default `60s`) — per-call timeout (including any internal
  retries by the SDK).

## Configuration

All variables documented in `.claude/rules/configuration.md` under "AI Description". Key flags:

- `APP_AI_DESCRIPTION_ENABLED` — master gate
- `APP_AI_DESCRIPTION_PROVIDER` — currently only `claude`
- `APP_AI_DESCRIPTION_LANGUAGE` — `ru` or `en`
- `APP_AI_DESCRIPTION_SHORT_MAX` / `APP_AI_DESCRIPTION_DETAILED_MAX` — caption / blockquote
  character caps
- `APP_AI_DESCRIPTION_MAX_FRAMES` — frames forwarded to the model per recording
