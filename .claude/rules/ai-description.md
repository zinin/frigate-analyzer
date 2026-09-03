---
paths: "modules/ai-description/**,**/DescriptionEditJobRunner*,**/AiDescription*,**/RichNotificationRenderer*,**/DescriptionState*,**/DescriptionAuthAlertNotifier*"
---

# AI Description Module

Optional module that generates short and detailed natural-language descriptions of detections and
edits them into the Telegram notification. Two providers, selected by
`application.ai.description.provider`: `claude` (Claude Code CLI via `org.springaicommunity:claude-code-sdk`)
and `grok` (Grok Build CLI from xAI, headless via `ProcessBuilder`). Gated by
`application.ai.description.enabled` — when `false`, no agent bean is created, no CLI is required, and
the notification goes out with `DescriptionState.Absent`: no description blocks, no edit job.

Both CLIs are installed into the runtime container by `docker/deploy/Dockerfile` (`claude.ai/install.sh`
and `x.ai/cli/install.sh` pinned by `ARG GROK_VERSION`); local development needs the chosen binary on
`PATH` or an explicit `*_CLI_PATH`.

## Layers

| Layer | Component | Location | Purpose |
|-------|-----------|----------|---------|
| API | `DescriptionAgent` | `api/` | Single-method `suspend fun describe(request): DescriptionResult` |
| API | `DescriptionRequest` / `DescriptionResult` / `DescriptionException` | `api/` | Public DTOs; `DescriptionException` is provider-neutral: `Timeout`, `InvalidResponse`, `Transport`, `RateLimited`, `Unauthorized` |
| API | `DescriptionProviderAuthEvent` | `api/` | Spring event `LOST` / `RESTORED`, one per transition |
| API | `TempFileWriter` | `api/` | Filesystem abstraction for staging files (implemented in core) |
| Core | `DescriptionBackend` | `core/` | Provider SPI: one attempt, no semaphore, no retry |
| Core | `DefaultDescriptionAgent` | `core/` | Semaphore, queue/work timeouts, retry policy, auth state machine |
| Core | `ResultNormalizer` / `LanguageNames` / `JsonBlockExtractor` | `core/` | Blank-field check + `…` truncation; language names; JSON object cut out of free-form text |
| Claude | `ClaudeBackend` | `claude/` | stage jpg → prompt with `@/abs/path` → SDK → parse |
| Claude | `ClaudeImageStager`, `ClaudePromptBuilder`, `ClaudeInvoker`/`DefaultClaudeInvoker`, `ClaudeAsyncClientFactory`, `ClaudeResponseParser`, `ClaudeExceptionMapper` | `claude/` | Claude specifics; all gated on `provider=claude` |
| Grok | `GrokBackend` | `grok/` | prompt.json → process → `structuredOutput` |
| Grok | `GrokPromptBuilder`, `GrokPromptFileWriter` | `grok/` | Prompt text, ACP content blocks with inline base64 frames |
| Grok | `GrokCommandBuilder`, `GrokProcessRunner`/`DefaultGrokProcessRunner` | `grok/` | argv + isolated env; `ProcessBuilder` with cancellation-safe kill |
| Grok | `GrokOutputParser`, `GrokExceptionMapper` | `grok/` | JSON stdout, error envelope, classification |
| Grok | `GrokHomeGuard`, `GrokHomeSweeper` | `grok/` | shared/exclusive lock on `GROK_HOME`; hourly cleanup of `sessions/` and `logs/` |
| Config | `AiDescriptionAutoConfiguration` | `config/` | Registers properties; creates the agent `@Bean` when a `DescriptionBackend` exists |
| Config | `DescriptionProperties` / `ClaudeProperties` / `GrokProperties` | `config/` | `@ConfigurationProperties` for `application.ai.description.*`; both provider sections bind always |
| Config | `DescriptionAgentSanityChecker` | `config/` | WARN when `enabled=true` but no agent (unknown provider) |
| Limits | `DescriptionRateLimiter` | `ratelimit/` | Sliding-window throttle; `tryAcquire()` returns false when quota exceeded |

## Provider selection and retry

Backends are `@Component`s gated on `enabled=true` and `provider=<id>`. The agent is a `@Bean` in the
auto-configuration guarded by `@ConditionalOnBean(DescriptionBackend::class)`, so an unknown provider
yields no agent, a WARN from `DescriptionAgentSanityChecker`, and notifications without placeholders.
Consumers (`RecordingProcessingFacade`, telegram) use `ObjectProvider<DescriptionAgent>` and never see
the provider.

`DefaultDescriptionAgent` retries once on `InvalidResponse` (immediately) and once on `Transport`
(after 5 s), each only if enough of `timeout` is left (5 s and 10 s respectively). `Timeout`,
`RateLimited` and `Unauthorized` are not retried. Anything a backend throws that is not a
`DescriptionException` becomes `Transport`.

## Grok invocation

Per recording `GrokPromptFileWriter` writes `prompt.json` (suffix mandatory: any other extension is
read as plain text) with ACP content blocks: intro text, then `Frame N:` + `{"type":"image",
"mimeType":"image/jpeg","data":"<base64>"}` per frame in `frameIndex` order, then the rules.
`GrokCommandBuilder` runs:

```
grok --prompt-file <file> --json-schema '{…short,detailed…}' --output-format json -m <model>
     [--effort <effort>] --max-turns 1 --tools read_file --no-plan --no-subagents
     --disable-web-search --permission-mode bypassPermissions --no-auto-update
     --system-prompt-override "<constant>" --cwd <working-directory>
```

with `GROK_HOME=<home>`, `GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0` and
`GROK_CLAUDE_*_ENABLED=0` / `GROK_CURSOR_*_ENABLED=0`. The child env is not a copy of the JVM:
`ProcessBuilder` is cleared, then PATH/HOME/locale, host `GROK_*`/`XAI_*` (BYOK `env_key`), and the
command map. `--tools read_file` is an allowlist that disables default tool injection;
`--disallowed-tools read_file` then removes that one tool. Frames are inline. `--effort` is omitted
when blank so BYOK models without reasoning levels work.

**Models that do not support `--json-schema`.** Only xAI endpoints reliably apply the schema. BYOK
models from `config.toml` either ignore it (the object arrives in `text`, sometimes inside a
` ```json ` fence) or reject the request outright — a LiteLLM gateway answers `failed to parse
grammar`, DeepSeek `This response_format type is unavailable now`. Three things make the provider
model-agnostic:

- the prompt rules always ask for `{"short": …, "detailed": …}` as text, so an unconstrained model
  still answers in the expected shape;
- `GrokOutputParser` falls back to the JSON found in `text` (via `core/JsonBlockExtractor`, shared
  with `ClaudeResponseParser`) whenever `structuredOutput` is missing or partial, and reports which
  source was used through `GrokOutput.fromText`;
- on an error whose message matches `GrokExceptionMapper.isStructuredOutputUnsupported`
  (`response_format`, `json_schema`, `parse grammar`, …) `GrokBackend` re-runs the same prompt file
  once without `--json-schema` and keeps the flag off for the rest of the process lifetime
  (`@Volatile schemaSupported`; the model is fixed by properties, so probing again would just cost
  tokens). Both attempts share the agent's single `timeout`, so the very first description after
  startup may time out on a slow endpoint — the next one goes straight to the schema-less form.

`GrokBackend` logs `model` and `effort` at INFO once at startup, and per recording at DEBUG:
`model=…, effort=…, json-schema=on|off, frames=N` before the run and
`model=…, effort=…, fields=structuredOutput|text, input_tokens=…` after it.

Output classification (`GrokExceptionMapper`): `{"type":"error","message":…}` on stdout, regardless of
exit code → `Unauthorized` when the message mentions `not signed in`, `grok login`, `not authenticated`,
`unauthorized`, `invalid_grant`, `authentication failed`, `invalid api key`, or `refresh token`
together with invalid/expired/rejected/failed/revoked, or `401` with HTTP/status/API context;
`RateLimited` on `rate limit`, `too many requests`, or `429` with HTTP/status/API context; everything else `Transport`
with the stderr tail. Exit 0 with both description fields (from `structuredOutput` or from the text JSON) → result; exit 0 with `stopReason`
`max_tokens` / `refusal` / `max_turn_requests` or a partial object → `InvalidResponse`; `cancelled` →
`Transport`. Token usage and cost are logged at DEBUG.

**GROK_HOME hygiene.** Every headless run persists a session under `GROK_HOME/sessions/<cwd>/<id>/`
with the base64 frames, and `sessions/session_search.sqlite` grows ~9 KB per run without shrinking.
`GrokHomeSweeper` runs one minute after startup and then hourly on its own IO scope under
`GrokHomeGuard.exclusive`. If a grok run is in flight the sweep is skipped until the next hour,
so descriptions and the Spring scheduler are not blocked. It deletes everything under `sessions/`
plus the files in `logs/`. `auth.json` and `config.toml` are never touched. The app must be the only
user of that `GROK_HOME`; `grok login` creates no sessions.

**Credentials.** OAuth via `grok login --device-code` inside the container; the access token lives
6 hours and refreshes itself, the refresh token rotates, so `auth.json` must never be copied from
another machine. BYOK models are `[model.<name>]` entries in `GROK_HOME/config.toml` with their own
`api_key`/`env_key`; the app only passes `-m <name>`.

## Authorization alerts

`DefaultDescriptionAgent` keeps an `AtomicReference` of `HEALTHY`/`LOST`. The first `Unauthorized`
after a success (or startup) flips it and publishes `DescriptionProviderAuthEvent(LOST, detail,
recoveryHint)` with an ERROR log; the first success afterwards flips it back and publishes `RESTORED`.
`compareAndSet` guarantees one event per transition under concurrency. Calls are not short-circuited
while `LOST`: a failing `grok` exits fast and costs nothing, and the next success is what restores.

`DescriptionAuthAlertNotifier` (core, `application/`, gated on telegram and ai enabled) listens and
calls `TelegramNotificationService.sendOwnerMessage` with `ai.description.auth.lost` /
`ai.description.auth.restored` (args: provider, recovery hint), appending the provider's technical
detail trimmed to 300 characters. Rate-limiter slots are never refunded on failure.

## Integration with Telegram

When a notification is enqueued and AI description is enabled:

1. `TelegramNotificationSender` sends the rich message rendered with `DescriptionState.Pending` —
   the short and detailed placeholders (only if `DescriptionRateLimiter.tryAcquire()` succeeded).
2. `DescriptionEditJobRunner` (in `telegram/queue/`) launches a coroutine on `DescriptionEditScope`
   that awaits `DescriptionAgent.describe(...)`. One model call per recording fans out to one
   `EditTarget` per recipient.
3. **One** edit closes the flow per recipient: `EditChatMessageRichText` with the HTML re-rendered
   for `DescriptionState.Ready` (model text) or `DescriptionState.Failed` (the
   `ai.description.fallback.unavailable` line in the `<p>`, and **no** `<details>` at all — a
   spoiler labelled "detailed description" holding the same one-line apology promises detail and
   delivers none; no error text is exposed either way). The edit re-declares `reply_markup` too,
   from `QuickExportHandler.currentKeyboard` (per chat and per export state), and re-checks it once
   the edit has landed — see the Quick Export keyboard row in `telegram.md`.

The render state is an explicit type, `DescriptionState` (`Absent` / `Pending` / `Ready` / `Failed`
in `telegram/service/model/`), instead of the former "no formatter means the feature is off" flag.

**Media are re-declared on every edit.** Omitting the `media` array while the HTML still references
`tg://photo?id=…` fails with `400 RICH_MESSAGE_PHOTO_INVALID`, even though the ids are unchanged.
That is why `EditTarget` carries the frame `file_id`s and not just a message id — and why the sender
never edits by a photo-id list whose length differs from the number of frames sent: a short array
would strip frames off the delivered message. On such an answer it falls back to the full list another
recipient cached, and skips its own edit only when there is none.

`AiDescriptionTelegramGuard` (in telegram module) fails fast at startup when
`ai.description.enabled=true` is paired with `telegram.enabled=false` — the feature only makes
sense when there's a chat to edit.

## Rate Limiting

- `DescriptionRateLimiter` enforces a sliding window (`max` requests per `window`).
- Counter increments **when a slot is granted**; failed model calls do NOT refund the slot —
  this is intentional to keep cost predictable when the binary is misbehaving.
- When the limit is exceeded, the recording is sent with `DescriptionState.Absent` — no
  placeholders, no edit job, no model call.
- Disable with `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED=false`.

## Concurrency

- `APP_AI_DESCRIPTION_MAX_CONCURRENT` (default `2`) bounds simultaneous model calls — enforced
  inside `DefaultDescriptionAgent` via a `Semaphore`; for Grok that is also the number of `grok`
  processes alive at once.
- `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` (default `30s`) — max wait for a free slot before failing
  the describe call.
- `APP_AI_DESCRIPTION_TIMEOUT` (default `60s`) — per-call timeout including the agent's retries;
  on expiry the Grok process is killed.

## Configuration

All variables documented in `.claude/rules/configuration.md` under "AI Description". Key flags:

- `APP_AI_DESCRIPTION_ENABLED` — master gate
- `APP_AI_DESCRIPTION_PROVIDER` — `claude` or `grok`
- `APP_AI_DESCRIPTION_LANGUAGE` — `ru` or `en`
- `APP_AI_DESCRIPTION_SHORT_MAX` / `APP_AI_DESCRIPTION_DETAILED_MAX` — character caps for the
  short paragraph and the `<details>` body
- `APP_AI_DESCRIPTION_MAX_FRAMES` — frames forwarded to the model per recording
- `CLAUDE_MAX_BUFFER_SIZE` — max size of one JSON message from the Claude CLI (default 16MB). The CLI
  echoes every frame the model reads back as base64, so the SDK's 1 MiB default overflowed on
  ~800 KB frames; an oversized line is dropped with `Failed to process message (continuing)`
- `GROK_MODEL`, `GROK_EFFORT`, `GROK_HOME`, `GROK_WORKING_DIR`, `GROK_CLI_PATH`, `GROK_*_PROXY` — Grok
  section, see `configuration.md`

## Testing

Unit tests use fakes at the seams: `DescriptionBackend` for the agent, `ClaudeInvoker` for Claude,
`GrokProcessRunner` for Grok. `DefaultGrokProcessRunnerTest` runs a stub `grok` shell script
(POSIX only) and covers stdout/stderr capture, environment, and the kill on cancellation.
`AiDescriptionAutoConfigurationTest` covers `provider=claude`, `provider=grok` and an unknown provider.
