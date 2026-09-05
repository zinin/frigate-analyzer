---
paths: "modules/ai-description/**,**/handler/aisettings/**,**/judge/**,**/Verdicts*,**/DescriptionEditJobRunner*,**/AiDescription*,**/RichNotificationRenderer*,**/DescriptionState*,**/DescriptionAuthAlertNotifier*"
---

# AI Description Module

Optional module that generates short and detailed natural-language descriptions of detections and
edits them into the Telegram notification, and (when enabled separately) runs the LLM notification
judge over the same preset catalog. Two providers: `claude` (Claude Code CLI via
`org.springaicommunity:claude-code-sdk`) and `grok` (Grok Build CLI from xAI, headless via
`ProcessBuilder`).

A deployment declares **presets** — named `provider` + `model` + `effort` triples — under
`application.ai.description.presets`, and the bot owner switches the active one from `/ai` at
runtime. The provider is therefore a property of a preset, not of the deployment: a catalog may mix
claude and grok presets, and one backend instance is built per preset.

Gated by `application.ai.description.enabled` — when `false`, none of the feature beans exist, no CLI
is required, and the notification goes out with `DescriptionState.Absent`: no description blocks, no
edit job. The same happens when nothing is declared at all (empty `presets` map and no known legacy
`provider`): the beans are conditional on a declaration, not only on the flag.

Both CLIs are installed into the runtime container by `docker/deploy/Dockerfile` (`claude.ai/install.sh`
and `x.ai/cli/install.sh` pinned by `ARG GROK_VERSION`); local development needs the chosen binary on
`PATH` or an explicit `*_CLI_PATH`.

## Layers

| Layer | Component | Location | Purpose |
|-------|-----------|----------|---------|
| API | `DescriptionAgent` | `api/` | Single-method `suspend fun describe(request): DescriptionResult` |
| API | `JudgeAgent` | `api/` | Single-method `suspend fun judge(request): JudgeOutcome` |
| API | `DescriptionRequest` / `DescriptionResult` / `DescriptionException` | `api/` | Public DTOs; `DescriptionException` is provider-neutral: `Timeout`, `InvalidResponse`, `Transport`, `RateLimited`, `Unauthorized` |
| API | `JudgeRequest` / `JudgeOutcome` / `JudgeVerdict` | `api/` | Judge DTOs; `JudgeVerdict.Reason` is the five model reasons (`NEW_EVENT`, `CHANGED_SITUATION`, `FALSE_POSITIVE`, `STATIC_OBJECT`, `DUPLICATE`) |
| API | `DescriptionProviderAuthEvent` | `api/` | Spring event `LOST` / `RESTORED`, one per transition, keyed by `authScopeId` |
| API | `DescriptionPreset` / `DescriptionPresets` / `UnavailableReason` | `api/` | One preset as its consumers see it (`id`, `provider`, `model`, `effectiveModel`, `effort`, `authScopeId`, `unavailableReason`, `slowEffort`) and the read-only catalog `all()` in declaration order |
| API | `ActiveDescriptionPreset` / `ActiveJudgePreset` | `api/` | `storedId()` (the owner's choice) and `effective()` (what the next call will use) — two methods, so `/ai` can tell them apart |
| API | `DescriptionRuntimeSettings` | `api/` | Seam for "which preset is active" + "are descriptions on": `sourceName`, `activePresetId`/`setActivePresetId`, `descriptionsEnabled`/`setDescriptionsEnabled` |
| API | `JudgeRuntimeSettings` | `api/` | Same seam for the judge: `activePresetId`/`setActivePresetId`, `judgeEnabled`/`setJudgeEnabled` (absent = `true`) |
| API | `ProviderAuthStates` | `api/` | `byScope(): Map<String, Health>` (`UNKNOWN`/`HEALTHY`/`LOST`) for the `/ai` screen |
| API | `TempFileWriter` | `api/` | Filesystem abstraction for staging files (implemented in core) |
| Core | `VisionBackend` | `core/` | Provider SPI: one attempt, no semaphore, no retry; returns `VisionResponse` (primary text plus the provider's fallback representation, when it has one); takes the call budget (`complete(request, timeout)`) so a provider with its own timeout machinery sizes it from the calling task, not from the description settings; carries `providerId`, `authScopeId`, `authRecoveryHint` |
| Core | `VisionBackendFactory` | `core/` | Provider SPI for the catalog: `availability()`, `effectiveModel(preset)`, `authScopeId(preset)`, `create(preset)` |
| Core | `VisionRequest` / `VisionInstructions` | `core/` | One vision call: frames plus `systemPrompt` / `preamble` / `epilogue` / optional `jsonSchema`; the provider inserts frames between preamble and epilogue |
| Core | `VisionCallExecutor` | `core/` | Preset resolution, semaphore, queue/work timeouts, retry policy, frame downscale; hands each outcome to the tracker. Two beans (`descriptionVisionCallExecutor`, `judgeVisionCallExecutor`) with independent semaphores |
| Core | `DescriptionTask` / `JudgeTask` | `core/` | Build `VisionInstructions` for descriptions and for the judge |
| Core | `DescriptionResponseParser` / `JudgeResponseParser` | `core/` | Parse the raw model text into `DescriptionResult` / `JudgeVerdict` |
| Core | `DefaultDescriptionAgent` | `core/` | Thin `DescriptionAgent`: `DescriptionTask` → `VisionCallExecutor` → `DescriptionResponseParser` |
| Core | `DefaultJudgeAgent` | `core/` | Thin `JudgeAgent`: `JudgeTask` → `VisionCallExecutor` → `JudgeResponseParser` |
| Core | `JudgePresetResolver` | `core/` | `ActiveJudgePreset` over a second `ActivePresetResolver` (label `judge`); not itself the resolver bean |
| Core | `DescriptionPresetCatalogBuilder` | `core/` | Pure (no Spring) build of the catalog from declarations + factories; returns `Catalog` / `NoPresets` / `NoneUsable` |
| Core | `DescriptionPresetCatalog` | `core/` | Immutable `id → (view, backend)` plus `fallbackId`; implements `DescriptionPresets` |
| Core | `ActivePresetResolver` | `core/` | Resolves the active preset per call, fail-open; implements `ActiveDescriptionPreset` |
| Core | `InMemoryDescriptionRuntimeSettings` / `InMemoryJudgeRuntimeSettings` | `core/` | Defaults used only when `core` registers no `*RuntimeSettings`; the choice dies with the process |
| Core | `ProviderAuthTracker` | `core/` | Auth state machine per credential scope; publishes the events; implements `ProviderAuthStates` |
| Core | `logSignature()` (`PresetLogFormat.kt`) | `core/` | One `provider/model/effort` form for both INFO lines about presets |
| Core | `ResultNormalizer` / `LanguageNames` / `JsonBlockExtractor` | `core/` | Blank-field check + `…` truncation; language names; JSON object cut out of free-form text |
| Core | `FrameDownscaler` | `core/` | Optional resize to `max-image-side` (ImageIO, bilinear, JPEG q0.85), once per request in `VisionCallExecutor`; an unreadable frame is passed through with a WARN |
| Claude | `ClaudeBackend` | `claude/` | stage jpg → prompt with `@/abs/path` → SDK → parse |
| Claude | `ClaudeBackendFactory` | `claude/` | Token check, CLI WARN, `ANTHROPIC_MODEL` displacement, `authScopeId=claude` |
| Claude | `ClaudeImageStager`, `ClaudePromptBuilder`, `ClaudeInvoker`/`DefaultClaudeInvoker`, `ClaudeAsyncClientFactory`, `ClaudeResponseParser`, `ClaudeExceptionMapper` | `claude/` | Claude specifics; `@Component`s gated on `enabled=true` only |
| Grok | `GrokBackend` | `grok/` | prompt.json → process → `structuredOutput` |
| Grok | `GrokBackendFactory` | `grok/` | Creates `GROK_HOME`/cwd, WARNs on a missing CLI or `auth.json`, `authScopeId=grok:<model>` |
| Grok | `GrokPromptBuilder`, `GrokPromptFileWriter` | `grok/` | Prompt text, ACP content blocks with inline base64 frames |
| Grok | `GrokCommandBuilder`, `GrokProcessRunner`/`DefaultGrokProcessRunner` | `grok/` | argv + isolated env; `ProcessBuilder` with stdout/stderr redirected to temp files and a cancellation-safe kill |
| Grok | `GrokOutputParser`, `GrokExceptionMapper` | `grok/` | JSON stdout, error envelope, classification |
| Grok | `GrokHomeGuard`, `GrokHomeSweeper` | `grok/` | shared/exclusive lock on `GROK_HOME`; hourly cleanup of `sessions/` and `logs/`, skipped when no declared preset uses grok |
| Config | `AiDescriptionAutoConfiguration` | `config/` | Registers properties; nested `PresetBeans` holds description beans; nested `JudgeBeans` holds judge beans under `application.ai.judge.enabled=true` plus the catalog |
| Config | `DescriptionProperties` / `ClaudeProperties` / `GrokProperties` | `config/` | `@ConfigurationProperties` for `application.ai.description.*`; both provider sections bind always |
| Config | `JudgeProperties` | `config/` | `@ConfigurationProperties` for `application.ai.judge.*` (sibling of `description`, not nested in it); binds even when the judge is off |
| Config | `DescriptionPresetDeclarations` / `DescriptionPresetsDeclaredCondition` | `config/` | The single answer to "is anything declared?" — a `Binder` read of the `presets` map plus the normalized legacy `provider` |
| Config | `DescriptionAgentSanityChecker` | `config/` | WARN when `enabled=true` but no agent — nothing declared, or an unknown legacy provider |
| Config | `JudgeAgentSanityChecker` | `config/` | WARN when `application.ai.judge.enabled=true` but no `JudgeAgent` — catalog not built |
| Limits | `SlidingWindowRateLimiter` | `ratelimit/` | Domain-agnostic sliding window; two named subclasses do not share a counter |
| Limits | `DescriptionRateLimiter` | `ratelimit/` | Description throttle (`AI description`); default 30 / 1h |
| Limits | `JudgeRateLimiter` | `ratelimit/` | Judge throttle (`AI judge`); default 200 / 1h; miss → send unjudged (`FAILOVER` / `RATE_LIMITED`) |

## Presets, catalog and resolution

**Declaration.** `application.ai.description.presets` is a map `id → {provider, model, effort}`; the
id must match `[a-z0-9][a-z0-9-]{0,31}` (it travels in `callback_data`, 64 bytes for everything).
`effort` is grok-only and must be empty or one of `low|medium|high|xhigh|max`; a non-empty `effort`
on a claude preset fails startup. `default-preset` names the preset that is active until the owner
picks one; it must exist in a non-empty map, and with an **empty** map it is only a WARN — the
documented migration is "set it in `.env` first, declare the map in yaml afterwards".

**Legacy path.** An empty map plus a known `application.ai.description.provider` synthesizes exactly
one preset: the id is the provider name, `model`/`effort` come from that provider's own section
(`claude.model`, `grok.model`, `grok.effort`). The value is normalized with `trim().lowercase()`,
because the old `@ConditionalOnProperty(havingValue = "claude")` matched case-insensitively and
`APP_AI_DESCRIPTION_PROVIDER=CLAUDE` deployments must keep working. With a non-empty map the
variable is ignored and logged as a WARN, so a typo in a variable that stopped mattering stays
visible.

**Bean wiring.** All feature beans live in `AiDescriptionAutoConfiguration.PresetBeans`, one nested
`@Configuration` under two conditions: `enabled=true` and `DescriptionPresetsDeclaredCondition`
(anything declared at all). They are plain constructor dependencies, so `@Bean` method order does not
matter — deliberately not `@ConditionalOnBean` between siblings, whose ordering Spring Boot does not
guarantee. Nothing declared → no catalog, no agent, a WARN from `DescriptionAgentSanityChecker`, and
notifications without placeholders. Consumers (`RecordingProcessingFacade`, telegram) use
`ObjectProvider<DescriptionAgent>` and never see the provider.

**Factories.** `VisionBackendFactory` (`@Component`, gated on `enabled=true` only) replaces the
old per-provider backend beans; one backend is created per preset. **Factory constructors are
strictly passive** — no filesystem, no `PATH`, no logging. All environment inspection lives in
`availability()` behind `by lazy`, and `DescriptionPresetCatalogBuilder` asks only the providers that
appear in a declared preset: `GROK_HOME` is set by docker-compose unconditionally, so a claude-only
deployment must not create or inspect the grok volume. `GrokHomeSweeper` takes an
`ObjectProvider<DescriptionPresets>` for the same reason and stays silent when no preset uses grok.

**Unavailability.** `UnavailableReason` has exactly three variants: `NoToken`,
`HomeUnwritable(path)`, `NoFactory(provider)`. A missing CLI is deliberately **not** one of them — it
is a WARN from the factory, because losing the binary of an optional feature must not stop camera
monitoring; those calls fall back instead. `HomeUnwritable` is likewise narrower than its name: it is
raised only when `Files.createDirectories` throws, so an **existing** `GROK_HOME` that is merely not
writable by uid 1000 — the case the README's `chown` instruction is about — produces a WARN, keeps the
preset selectable and fails later, at call time, when `grok` cannot refresh its token. An unavailable
preset stays in the catalog (listed and marked in `/ai`) with `backend == null` and cannot be
selected. Startup fails only when *every*
declared preset is unusable (`Result.NoneUsable` → `error(...)` in the auto-configuration).

**The catalog.** `DescriptionPresetCatalogBuilder.build(presets, defaultPreset, factories, timeout)`
is pure and Spring-free, returning `Catalog | NoPresets | NoneUsable(message)`. It preserves
declaration order, picks `fallbackId` (the `default-preset` when usable, otherwise the first usable
preset — a non-blank `default-preset` that lost gets a WARN naming the substitution) and logs the
catalog with values, e.g.
`Description presets: grok-fast (grok/grok-4.6/low), claude-opus (claude/opus); default 'grok-fast'`.
It also emits two kinds of WARN: **one aggregated line** listing every preset whose declared model is
displaced by provider configuration (`ANTHROPIC_MODEL`) — the builder is the only place that sees all
presets at once — and **one line per preset** whose `effort` is `xhigh`/`max` while `common.timeout` is
below 120 s — `preset 'grok-deep': effort=xhigh with timeout=60s leaves no retry budget; consider
APP_AI_DESCRIPTION_TIMEOUT=120s`. That second predicate also fills `DescriptionPreset.slowEffort`, so
the log and the 🐢 mark in `/ai` can never disagree.

**Resolution.** `ActivePresetResolver` reads `DescriptionRuntimeSettings.activePresetId()` on every
call — cheap, because the `app_settings` implementation caches per process — and is fail-open in both
directions: a failed read and a read exceeding its own 5 s bound both yield the fallback preset plus a
`warnOnce` line, never an exception (`VisionCallExecutor` calls the resolver outside both of its `withTimeout`
blocks, so an unbounded read would hang the whole call). A stored id that is unknown or unavailable
also falls back, with its own warning. Resolution — never startup, since naming the source means a
suspend R2DBC read that must not happen during context refresh — logs one INFO line:
`Active description preset 'grok-deep' (grok/grok-4.6/xhigh) from app_settings, overriding
default-preset='grok-fast'`, or `… from default-preset`. It is written **whenever that line differs
from the last one written**, not once per process: the owner switches presets in `/ai` without a
restart and the settings write logs only the id, at DEBUG, so a log that reported the first
resolution only would stop answering "which model is running" exactly when the question is asked.
Change-detection keeps it quiet in steady state, covers a change nobody clicked (a fallback taking
over when the stored preset becomes unavailable) and compares the whole line, so a changed *source*
under an unchanged preset is reported too. The accepted cost: a switch appears at the next resolution
— the following description call, or the `/ai` screen — not at the moment of the click. A resolution
whose read failed logs only the warning: the source is genuinely unknown then, and "from
default-preset" would claim the owner's choice was consulted. The source string comes from
`DescriptionRuntimeSettings.sourceName`, which is abstract precisely so a new implementation cannot
forget to name itself.

**Per call.** `VisionCallExecutor` resolves the preset once per `execute`, **before** acquiring
the semaphore permit: retries must stay on the preset the first attempt used, and a settings read
must neither hold a permit nor eat the model's budget. The accepted cost is that a call which waited
in the queue runs the preset that was active when it was enqueued — a window bounded by
`queueTimeout`. Descriptions and the judge each have their own executor (and therefore their own
semaphore and timeouts).

`VisionCallExecutor` retries once on `InvalidResponse` (immediately) and once on `Transport`
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
     [--effort <effort>] --max-turns 1 --tools read_file --disallowed-tools read_file
     --no-plan --no-subagents --disable-web-search --permission-mode bypassPermissions
     --no-auto-update --system-prompt-override "<constant>" --cwd <working-directory>
```

with `GROK_HOME=<home>`, `GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0` and
`GROK_CLAUDE_*_ENABLED=0` / `GROK_CURSOR_*_ENABLED=0`. The child env is not a copy of the JVM:
`ProcessBuilder` is cleared, then PATH/HOME/locale, host `GROK_*`/`XAI_*` (BYOK `env_key`), the names listed in
`GROK_PASS_THROUGH_ENV` for BYOK keys outside those prefixes, and the command map. `--tools read_file` is an allowlist that disables default tool injection;
`--disallowed-tools read_file` then removes that one tool. Frames are inline. `--effort` is omitted
when blank so BYOK models without reasoning levels work.

**What the isolation does not cover, and why.** `grok inspect` reports six compatibility cells per
foreign harness. The five that actually scan the filesystem — `skills`, `rules`, `agents`, `mcps`,
`hooks` for claude and cursor — all read `OFF (env)` under `ISOLATION_ENV`. The sixth, `sessions`,
stays `on (default)` for claude, cursor **and** codex, and `[compat.codex]` exposes no other cell.
That is deliberate: in 1.0.13 session cells are "staged and inert until a foreign-session scanner
consumes them" and additionally need a `resume-claude`/`resume-codex`/`resume-cursor` skill before
they do any filesystem I/O, while Codex's remaining cells are "reserved and currently inert — they
do not enable `.codex` discovery" (the CLI's own `docs/user-guide/05-configuration.md`). Adding
`GROK_*_SESSIONS_ENABLED=0` or `GROK_CODEX_*_ENABLED=0` would change the `inspect` output and
nothing else. Re-check with `grok inspect` when `ARG GROK_VERSION` is raised — a later release may
ship the scanner that makes those cells real.

**Models that do not support `--json-schema`.** Only xAI endpoints reliably apply the schema. BYOK
models from `config.toml` either ignore it (the object arrives in `text`, sometimes inside a
` ```json ` fence) or reject the request outright — a LiteLLM gateway answers `failed to parse
grammar`, DeepSeek `This response_format type is unavailable now`. Three things make the provider
model-agnostic:

- the prompt rules always ask for `{"short": …, "detailed": …}` as text, so an unconstrained model
  still answers in the expected shape;
- `GrokOutputParser` keeps both representations of one answer — `structuredOutput` as
  `GrokOutput.payload` and the response text as `GrokOutput.fallback` (dropped when it repeats the
  structured object verbatim) — and `GrokBackend` hands both to the executor in `VisionResponse`.
  The task parses the primary payload; only if it rejects it as `InvalidResponse` does
  `VisionCallExecutor` parse the fallback, inside the same attempt. That is what covers an endpoint
  which applies the schema only partially while the text already carries the complete object:
  without it the paid answer would be thrown away, the call repeated, and a second partial reply
  would leave the recording with no description or no verdict. `GrokOutput.fromText` still reports
  which source the primary payload came from (the text JSON is read via `core/JsonBlockExtractor`,
  shared with `ClaudeResponseParser`);
- on an error whose message matches `GrokExceptionMapper.isStructuredOutputUnsupported`
  (`response_format`, `json_schema`, `parse grammar`, …) `GrokBackend` re-runs the same prompt file
  once without `--json-schema` and keeps the flag off for the rest of the process lifetime
  (`@Volatile schemaSupported`; the model is fixed by the *preset*, which is why the flag is a field of
  the backend instance — one per grok preset — and not a shared one: a second preset on another model
  must not inherit this refusal). Both attempts share the agent's single `timeout`, so the very first description after
  startup may time out on a slow endpoint — the next one goes straight to the schema-less form.

`GrokBackend` logs per recording at DEBUG: `model=…, effort=…, json-schema=on|off, frames=N` before
the run and `model=…, effort=…, fields=structuredOutput|text, input_tokens=…` after it. It has no
startup line of its own — one instance exists per grok preset now; the values are named at INFO by the
catalog line once at startup and by the resolver's active-preset line on every change (see "Presets,
catalog and resolution").

Output classification (`GrokExceptionMapper`): `{"type":"error","message":…}` on stdout, regardless of
exit code → `Unauthorized` when the message mentions `not signed in`, `grok login`, `not authenticated`,
`unauthorized`, `invalid_grant`, `authentication failed`, `invalid api key`, or `refresh token`
together with invalid/expired/rejected/failed/revoked, or `401` with HTTP/status/API context;
`RateLimited` on `rate limit`, `too many requests`, or `429` with HTTP/status/API context; everything else `Transport`
with the stderr tail. Exit 0 with both description fields (from `structuredOutput` or from the text JSON) → result; exit 0 with `stopReason`
`max_tokens` / `refusal` / `max_turn_requests` or a partial object → `InvalidResponse`; `cancelled` →
`Transport`. Token usage and cost are logged at DEBUG.

**Output goes to files, not pipes.** `grok` can leave a descendant that inherited the pipe ends and
keeps them open after `grok` itself exits; a blocking `InputStream.read` is interrupted by neither
coroutine cancellation nor `close()` on the stream, so a pipe reader would keep a `Dispatchers.IO`
thread and two descriptors alive for as long as that descendant lives, and the already-paid-for
response with it. `DefaultGrokProcessRunner` therefore redirects stdout and stderr to temp files
through `TempFileWriter` and reads them after `onExit()`: stdout whole (up to `STDOUT_MAX_BYTES`,
above which it is `Transport`), stderr as its last `STDERR_TAIL_BYTES`. The files are deleted under
`NonCancellable`, and a descendant still holding them changes nothing.

**GROK_HOME hygiene.** Every headless run persists a session under `GROK_HOME/sessions/<cwd>/<id>/`
with the base64 frames, and `sessions/session_search.sqlite` grows ~9 KB per run without shrinking.
`GrokHomeSweeper` runs one minute after startup and then hourly on its own IO scope under
`GrokHomeGuard.exclusive`. If a grok run is in flight the sweep is skipped until the next hour,
so descriptions and the Spring scheduler are not blocked. It deletes everything under `sessions/`
plus the files in `logs/`. `auth.json` and `config.toml` are never touched. The app must be the only
user of that `GROK_HOME`; `grok login` creates no sessions. The sweep is skipped entirely when no
declared preset uses grok — `GROK_HOME` is set and mounted on every deployment, so otherwise a
claude-only deployment would hourly empty a directory the operator may be using by hand.

**Credentials.** OAuth via `grok login --device-code` inside the container; the access token lives
6 hours and refreshes itself, the refresh token rotates, so `auth.json` must never be copied from
another machine. BYOK models are `[model.<name>]` entries in `GROK_HOME/config.toml` with their own
`api_key`/`env_key`; the app only passes `-m <name>`.

## Authorization alerts

**Authorization is tracked per credential scope, not per provider and not per preset.** The scope id
comes from the factory: `claude` for claude (one token covers every model) and `grok:<model>` for
grok, because two grok presets on one model share `auth.json` while a BYOK model uses its own key
from `config.toml` — its success says nothing about the xAI session, and its failure says nothing
about the other presets. `VisionBackend.authScopeId` carries the same string the catalog put
into `DescriptionPreset.authScopeId`.

`ProviderAuthTracker` holds one state per scope (`UNKNOWN` → `HEALTHY`/`LOST`). The first
`Unauthorized` after a success or startup flips that scope and publishes
`DescriptionProviderAuthEvent(authScopeId, LOST, detail, recoveryHint)` with an ERROR log; the first
success afterwards flips it back and publishes `RESTORED`. Transition and publication happen under a
per-scope lock, so events reach the owner in order without a slow listener on one scope delaying
another; a listener that throws rolls the state back, or the transition would never be reported
again. Calls are not short-circuited while `LOST`: a failing `grok` exits fast and costs nothing, and
the next success is what restores. `UNKNOWN` behaves like `HEALTHY` for transitions and differs only
in what `/ai` draws.

`DescriptionAuthAlertNotifier` (core, `application/`, gated on telegram and ai enabled) listens and
calls `TelegramNotificationService.sendOwnerMessage` with `ai.description.auth.lost` /
`ai.description.auth.restored` (args: **the scope id**, recovery hint), appending the provider's
technical detail trimmed to 300 characters. Rate-limiter slots are never refunded on failure.

## Runtime settings and the `/ai` dialog

`DescriptionRuntimeSettings` is the seam between this module (which knows nothing about a database)
and storage. `AppSettingsDescriptionRuntimeSettings` (core, `application/`, gated on
`ai.description.enabled=true`) implements it over `app_settings` with two keys —
`ai.description.preset.active` (string, the owner's preset id) and `ai.description.enabled`
(boolean, absent means `true`). `InMemoryDescriptionRuntimeSettings` is the fallback registered by
`@ConditionalOnMissingBean` and loses the choice on restart; both log one INFO line at construction
naming themselves, so a deployment that silently fell back to in-memory is visible in the log.

`/ai` (`telegram/bot/handler/aisettings/`, `AiSettingsCommandHandler`, owner-only, `ownerOnly = true`
so it stays out of everyone's command menu and `/help`) shows two blocks when the judge beans exist,
one when they do not. The description block: the on/off state, the active preset as
`id (provider / effectiveModel / effort)`, one line per **distinct credential scope** with its auth
icon, and one keyboard button per preset (✅ effective, ⚠️ unavailable, 🐢 slow effort). The judge
block (only if `JudgeRuntimeSettings` is present): the same catalog again with a ⚖️ prefix on each
button, plus enable/disable. A stored preset that is not the effective one adds a mismatch line, so
the owner can see that their choice is overridden instead of wondering why the ✅ moved.
`AiSettingsViewStateFactory` builds the state through `ObjectProvider`s and reads everything
fail-open — the screen is opened precisely when something is broken.

`AiSettingsCallbackHandler` dispatches `aip:*` (`aip:close`, `aip:on`, `aip:off`, `aip:set:<id>`,
and when the judge is on `aip:j:on`, `aip:j:off`, `aip:j:set:<id>`) and
splits into `classify` (pure: payload, catalog, role — no I/O) and `apply` (the write). **The
callback is answered before the write**, because callback handlers are serialized per user by the
default `markerFactory`, so waiting on a slow database would delay the owner's *next* tap too. Every
outcome that needs alert text (not owner, unknown id, unavailable preset) is decided from the catalog
and the role alone; outcomes that write carry no text and are confirmed by the re-render that follows
the write. `FrigateAnalyzerBot.handleAiSettingsCallback` holds that order — answer, write, re-render
(the re-render rebuilds the state from scratch, so two open copies of the screen converge) — and
keeps the registration itself thin, so what decides the reply stays unit-testable. Payloads are
explicit (`aip:on` / `aip:off` / `aip:j:on` / `aip:j:off`, never a toggle), so a repeated tap is
idempotent. i18n keys are `ai.settings.*` in `messages_ru.properties` / `messages_en.properties`:
`title`, `state`, `state.on`, `state.off`, `active`, `active.none`, `active.mismatch`,
`mismatch.kept`, `mismatch.recheck`, `reason.noToken`, `reason.homeUnwritable`, `reason.noFactory`,
`reason.gone`, `reason.unknown`, `auth.healthy`, `auth.lost`, `auth.unknown`, `auth.unavailable`,
`auth.note`, `slow.note`, `button.enable`, `button.disable`, `button.close`, `alert.unavailable`,
and the judge block `ai.settings.judge.title`, `state`, `active`, `active.none`, `mismatch`,
`button.enable`, `button.disable`.
The mismatch line takes its consequence sentence as `{3}`, picked by the same `when` that picks the
reason: `mismatch.kept` ("applies again once the preset becomes available") for a stored preset that
is unavailable or gone, `mismatch.recheck` for `reason.unknown`, where the stored preset *is* present
and usable and a "wait for it" tail would be false. A `reason.*` value therefore never carries a
consequence of its own — the same keys are read by the auth line and the refusal alert.

**The runtime switch is read twice** in `RecordingProcessingFacade`, both times fail-open to `true`:
once while building the description supplier (off → no supplier, so the notification goes out with
`DescriptionState.Absent` — no placeholders, no rate-limiter slot) and once inside the supplier just
before `describe`, because the recipient filter and the rate limiter sit between the two and the
button is pressed when something is going wrong *now*. The second check cannot return "no
description": the placeholder message is already sent and only a completed `Deferred` can replace it,
so it completes with `Result.failure`, which renders as `DescriptionState.Failed` — the localized
"description unavailable" line. **Known rough edge:** switching descriptions off mid-flight therefore
produces that apology text rather than a clean message with no description block; recorded for
triage, not fixed.

## Judge

Optional third gate between the object tracker and Telegram. A fast, cheap model looks at the
annotated frames and a JSON context assembled from the database and answers `PUBLISH` or `SUPPRESS`.
The verdict is **not** shown to recipients and does not change the notification text. There is no
feedback button and no agentic access to the database: context is a fixed dump (`JudgeContextBuilder`),
and the model's `wanted` field is stored and ignored.

Gated by `application.ai.judge.enabled=true` **and** the description catalog
(`APP_AI_DESCRIPTION_ENABLED=true` plus declared presets). `AiJudgeGuard` (core) fails startup if
the judge is on while descriptions are off. The catalog key stays `application.ai.description.presets`;
`application.ai.judge` is a **sibling** of `application.ai.description`, not nested in it. The judge
picks its own active preset from that shared catalog (`APP_AI_JUDGE_DEFAULT_PRESET`, empty = the
descriptions default). `JudgeAgentSanityChecker` WARNs when the flag is on but no `JudgeAgent` bean
exists.

When the bean exists, `RecordingProcessingFacade` hands a `JudgeCandidate` to
`NotificationJudgeService.submit` and returns; the pipeline consumer does not wait for the model.
`JudgeCoroutineScope` (IO + `SupervisorJob`, `@PreDestroy` cancel) runs the work. Without the bean
the facade sends as today. Candidates of one camera are serialized on a per-camera mutex; cameras
run in parallel. A queue deeper than 20 on one camera logs a WARN.

Frames sent to the model are the first `APP_AI_JUDGE_MAX_FRAMES` visualized frames (the same ranking
as Telegram) re-ordered by `frameIndex`. Local times in the prompt use `APP_AI_JUDGE_ZONE`; empty =
the owner's `/timezone`, then the JVM zone (UTC in the container).

### Five steps (`NotificationJudgeService.judgeLocked`)

1. **Runtime switch.** `JudgeRuntimeSettings.judgeEnabled()`, fail-open to on, 5 s bound. Off →
   record `BYPASS` / `PUBLISH` / `JUDGE_OFF` and send.
2. **Snooze.** In-memory `SnoozeRegistry.covers` (window by absolute distance from the anchor, so a
   newest-first backlog still matches; a new class or a higher count of a covered class breaks it).
   Hit → record `SNOOZE` / `SUPPRESS` / `SNOOZED` and return without sending or calling the model.
3. **Context.** `JudgeContextBuilder` assembles JSON (recording, frames, clustered objects with
   static score, tracker reason, active tracks, recent verdicts, last published, camera notes).
   Failure → `FAILOVER` / `PUBLISH` / `CONTEXT_ERROR` and send.
4. **Rate limit.** `JudgeRateLimiter.tryAcquire()`. Miss → `FAILOVER` / `PUBLISH` / `RATE_LIMITED`
   and send unjudged. The slot is not refunded on a later model failure.
5. **Model.** `JudgeAgent.judge`. Success → `JUDGE` plus the model's decision/reason; `SnoozeRegistry.set`
   runs **only on this branch** (`FAILOVER` / `BYPASS` / `SNOOZE` do not arm or extend snooze).
   `PUBLISH` sends, `SUPPRESS` does not. Agent failure → `FAILOVER` / `PUBLISH` with the reason
   below, and send.

A failed write to `notification_verdicts` is logged at ERROR; the decision (send or not) is applied
anyway.

**Cancellation (shutdown).** A candidate cancelled *before* the fan-out started is recorded as
`FAILOVER` / `TRANSPORT` and sent under `NonCancellable` before the cancellation is rethrown — the
facade has already marked the recording processed, so nothing would retry it. The fan-out itself is
indivisible: `send()` arms its `handedOver` flag and then calls `sendRecordingNotification` under
`NonCancellable`, so a cancellation arriving mid-send neither truncates it nor triggers a second
one. Both halves matter. Without `NonCancellable` a shutdown could land while the call is still
suspended *before* its first `TelegramNotificationQueue.enqueue` (it reads the subscribers from the
database first), and the armed flag would then skip the fallback send and lose the recording
outright. Without the flag the fallback would repeat a fan-out that already reached the queue:
recipients would get a duplicate message and the recording a second verdict row, counted twice in
`/status` and listed twice in `/verdicts`.

**`/status` snapshot.** `snapshotSnoozes()` returns only snoozes still active on the wall clock. The
registry itself is never pruned by the clock: `covers` measures the window from the *recording*
timestamp, so a newest-first backlog still matches entries whose `until` has passed in wall-clock
time.

### Snooze

`SnoozeRegistry` is process memory only (restart clears it). `set(camId, anchor, minutes, classes)`
with `minutes == 0` or empty classes removes the camera's snooze — unless the update is older than
the camera's current anchor, in which case it changes nothing at all. The backlog is drained
newest-first (`findUnprocessedForUpdate` orders by `file_creation_timestamp DESC`), so a verdict on
an older recording would otherwise drag the window backwards, or clear it, and leave the live
duplicates it was armed against uncovered. `minutes` is capped by
`APP_AI_JUDGE_MAX_SNOOZE` in `JudgeResponseParser`. Coverage is class-and-count: a person walking
through a yard that already has a parked car does not stay silent if `person` was not in `covered`,
and a second person (`person:2` vs `person:1`) breaks it.

### Fail-open

Any judge failure sends the notification as today. Reasons by exception:

| Exception | `VerdictReason` |
|-----------|-----------------|
| `DescriptionException.Timeout` | `TIMEOUT` |
| `DescriptionException.RateLimited` | `RATE_LIMITED` |
| `DescriptionException.Unauthorized` | `UNAUTHORIZED` |
| `DescriptionException.InvalidResponse` | `INVALID_RESPONSE` |
| anything else (including a missing `JudgeAgent`) | `TRANSPORT` |
| context builder | `CONTEXT_ERROR` |
| local rate limiter | `RATE_LIMITED` |

### Reasons by stage

| `VerdictStage` | `verdict` | `reason` | Sends? | Calls the model? |
|----------------|-----------|----------|--------|------------------|
| `JUDGE` | `PUBLISH` | `NEW_EVENT`, `CHANGED_SITUATION` | yes | yes |
| `JUDGE` | `SUPPRESS` | `FALSE_POSITIVE`, `STATIC_OBJECT`, `DUPLICATE` | no | yes |
| `SNOOZE` | `SUPPRESS` | `SNOOZED` | no | no |
| `BYPASS` | `PUBLISH` | `JUDGE_OFF` | yes | no |
| `FAILOVER` | `PUBLISH` | `TIMEOUT`, `RATE_LIMITED`, `UNAUTHORIZED`, `INVALID_RESPONSE`, `TRANSPORT`, `CONTEXT_ERROR` | yes | maybe (failed) |

### `/ai` and `app_settings`

The judge block is omitted when there is no `JudgeRuntimeSettings` bean (`APP_AI_JUDGE_ENABLED=false`,
or the description catalog was not built).
Callbacks `aip:j:on` / `aip:j:off` / `aip:j:set:<id>` write:

| Key | Type | Absent means |
|-----|------|--------------|
| `ai.judge.preset.active` | string, a preset id | `APP_AI_JUDGE_DEFAULT_PRESET`, else the descriptions fallback |
| `ai.judge.enabled` | boolean | `true` — the runtime off-switch; `APP_AI_JUDGE_ENABLED=false` still wins, the beans do not exist then |

`AppSettingsJudgeRuntimeSettings` (core) is the production implementation; `InMemoryJudgeRuntimeSettings`
is `@ConditionalOnMissingBean`. Same per-process cache as descriptions — see `database.md`.

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

Two independent `SlidingWindowRateLimiter` instances; they do not share a counter.

- `DescriptionRateLimiter` (`AI description`) — default **30** requests per `window` (was 10).
  When the limit is exceeded, the recording is sent with `DescriptionState.Absent` — no
  placeholders, no edit job, no model call. Disable with
  `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED=false`.
- `JudgeRateLimiter` (`AI judge`) — default **200** requests per `window`. When the limit is
  exceeded, the candidate is sent unjudged (`FAILOVER` / `RATE_LIMITED`). Disable with
  `APP_AI_JUDGE_RATE_LIMIT_ENABLED=false`.
- Counter increments **when a slot is granted**; failed model calls do NOT refund the slot —
  this is intentional to keep cost predictable when the binary is misbehaving.

## Concurrency

- `APP_AI_DESCRIPTION_MAX_CONCURRENT` (default `2`) bounds simultaneous description calls —
  enforced inside `descriptionVisionCallExecutor` via a `Semaphore`; for Grok that is also the
  number of `grok` processes alive at once for descriptions.
- `APP_AI_JUDGE_MAX_CONCURRENT` (default `2`) is a **separate** semaphore on
  `judgeVisionCallExecutor`. Descriptions and the judge do not steal each other's slots.
- `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` / `APP_AI_JUDGE_QUEUE_TIMEOUT` (default `30s`) — max wait for
  a free slot before failing the call.
- `APP_AI_DESCRIPTION_TIMEOUT` (default `60s`) — per-call timeout including the executor's retries;
  on expiry the Grok process is killed. Set it for the slowest **declared** preset: `grok-4.6` at
  `effort=xhigh` runs ~48 s, leaving no room for either retry, and the startup WARN plus the 🐢 mark
  in `/ai` both point at 120 s. Two such calls also hold both slots of `maxConcurrent=2` for those
  ~48 s, so a third recording gives up on `queueTimeout`. That is a *failure*, not a silent omission:
  the wait raises `DescriptionException.Timeout`, the facade's supplier folds it into `Result.failure`
  and `DescriptionEditJobRunner` renders `DescriptionState.Failed` — the notification carries
  "⚠ Описание недоступно" where the description would have been. The judge's own timeout
  (`APP_AI_JUDGE_TIMEOUT`, default `60s`) is sized for a fast preset; on expiry the candidate is
  sent unjudged (`FAILOVER` / `TIMEOUT`).

## Configuration

All variables documented in `.claude/rules/configuration.md` under "AI Description" and "AI Judge". Key flags:

- `APP_AI_DESCRIPTION_ENABLED` — master gate
- `application.ai.description.presets` — the preset map; yaml only, no environment variable
  (`application-docker.yaml` in a deployment, empty in `modules/core/src/main/resources/application.yaml`)
- `APP_AI_DESCRIPTION_DEFAULT_PRESET` — `default-preset`; decides only until the owner's first pick
- `APP_AI_DESCRIPTION_PROVIDER` — legacy single-preset path, used only while the preset map is empty
- `APP_AI_DESCRIPTION_LANGUAGE` — `ru` or `en`
- `APP_AI_DESCRIPTION_SHORT_MAX` / `APP_AI_DESCRIPTION_DETAILED_MAX` — character caps for the
  short paragraph and the `<details>` body
- `APP_AI_DESCRIPTION_MAX_FRAMES` — frames forwarded to the model per recording
- `CLAUDE_MAX_BUFFER_SIZE` — max size of one JSON message from the Claude CLI (default 16MB). The CLI
  echoes every frame the model reads back as base64, so the SDK's 1 MiB default overflowed on
  ~800 KB frames; an oversized line is dropped with `Failed to process message (continuing)`
- `GROK_MODEL`, `GROK_EFFORT`, `GROK_HOME`, `GROK_WORKING_DIR`, `GROK_CLI_PATH`, `GROK_*_PROXY` — Grok
  section, see `configuration.md`
- `APP_AI_JUDGE_ENABLED` — master gate for the notification judge (requires descriptions on)
- `APP_AI_JUDGE_DEFAULT_PRESET` — judge preset until the owner picks one in `/ai`

## Testing

Unit tests use fakes at the seams: `VisionBackend` for the executor, `VisionBackendFactory`
for the catalog builder, `DescriptionRuntimeSettings` / `JudgeRuntimeSettings` for the resolvers, `ClaudeInvoker` for Claude,
`GrokProcessRunner` for Grok. `DefaultGrokProcessRunnerTest` runs a stub `grok` shell script
(POSIX only) and covers stdout/stderr capture, environment, and the kill on cancellation.
`DescriptionPresetCatalogBuilderTest` pins declaration order, the fallback choice, typed
unavailability, one backend per preset, the slow-effort WARN/mark and the startup catalog line
(`the startup line names every preset with its values and the default`, an exact-string assertion);
`ActivePresetResolverTest` pins fail-open resolution, the bounded read, the one-line-per-problem
logging and the active-preset line — `a switched preset is logged again` and `an unchanged preset is
logged once however often it resolves`. Both assert on the log text, so both INFO lines about presets
are contract: change either format and a test names it. `AiDescriptionAutoConfigurationTest` covers
the legacy paths (`provider=claude`, `provider=grok`, mixed case, unknown), a declared map with a
partially unusable catalog, the "all unusable fails startup" rule and a supplied runtime-settings
implementation.
