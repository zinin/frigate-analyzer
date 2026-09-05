---
paths: "**/application.yaml,**/application*.yml,**/application*.properties"
---

# Configuration Reference

All settings in `modules/core/src/main/resources/application.yaml`.

## Core Settings

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_PORT` | 8080 | Server port |
| `TEMP_FOLDER` | /tmp/frigate-analyzer/ | Extracted frames storage |
| `FFMPEG_PATH` | /usr/bin/ffmpeg | ffmpeg binary path |
| `FFPROBE_PATH` | /usr/bin/ffprobe | ffprobe binary path; read by `VideoProbe` before an export is re-encoded to fit the Telegram limit. The Alpine image installs it together with ffmpeg |

## Records Watcher

Settings under `application.records-watcher` in `application.yaml`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `FRIGATE_RECORDS_FOLDER` | /mnt/data/frigate/recordings/ | Frigate recordings path |
| `DISABLE_FIRST_SCAN` | true | The startup scan is an opt-in backfill (first install, index recovery); set to `false` to run it once. With it left at `true`, recordings written while the application was down are never indexed — see the downtime note below. |
| `WATCH_PERIOD` | P1D | ISO-8601 duration, how far back to watch directories |
| `FIRST_SCAN_PERIOD` | = `WATCH_PERIOD`, truncated to whole days | ISO-8601 duration, how far back the startup scan indexes files. Whole days **in UTC** (Frigate names date directories by UTC): `P0D` = today only, `P1D` = today and yesterday; an explicitly set sub-day value is rejected at startup instead of being silently truncated. Defaults to `WATCH_PERIOD` and follows it from whichever source sets it — raising `WATCH_PERIOD` widens the startup backfill too, but only across a whole-day boundary. The inherited default is truncated through `toDays()`, so a `WATCH_PERIOD` of `PT36H` (which `watchCutoff` has always read as one day) yields `P1D` here rather than failing startup, widening neither window. Every indexed file becomes a `recordings` row and enters the detection pipeline — and `P1D` means today **and** yesterday, so at three cameras and 10-second segments (8 640 files per camera per day) that is ~52 000 files. Note the validation asymmetry: `WATCH_PERIOD` must stay ≥ `P1D`; "today only" is expressible only here. |
| `WATCH_CLEANUP_INTERVAL` | PT1H | How often to clean up expired watch keys |

**Downtime gap.** With the scan disabled — the default — recordings written while the application was down are never indexed: the watcher only sees files created after it registers, and nothing catches up afterwards. Recovering such a gap is a deliberate two-variable operation: set `DISABLE_FIRST_SCAN=false` **and** `FIRST_SCAN_PERIOD` to at least the length of the outage, restart, then revert both. Size the window by UTC **dates**, not hours: `P1D` reaches back to yesterday only, so a ~30-hour outage recovers fully under `P1D` when it straddles two UTC dates and needs `P2D` when it straddles three. The backfill replays that whole window through the detection pipeline and therefore through Telegram notifications.

Every `RecordsWatcherProperties` field except `folder` has a Kotlin default (`folder` has none — it is the one value that must come from configuration), and `first-scan-period` was appended as the **last** constructor parameter — never call the constructor positionally, only with named arguments.

`first-scan-period` in `application.yaml` deliberately has an **empty** default (`${FIRST_SCAN_PERIOD:}`): an empty value binds to `null`, so the Kotlin default `firstScanPeriod = Duration.ofDays(watchPeriod.toDays())` takes over and sees the already-resolved `watch-period` — from env, a profile yaml, a CLI argument or a relaxed name alike. The line itself still has to exist, otherwise the short `FIRST_SCAN_PERIOD` variable would not bind at all.

The reference form (`${FIRST_SCAN_PERIOD:${application.records-watcher.watch-period}}`) does not work here: relaxed name mapping is guaranteed for `@ConfigurationProperties` binding but not for placeholder resolution, and `RecordsWatcherPropertiesBindingTest` showed such a reference returning `P1D` from the yaml itself instead of the `APPLICATION_RECORDSWATCHER_WATCHPERIOD` override. `reappear-gap` below uses the reference form safely only because its target property has no hyphen to strip; do not copy that form into a property whose name contains one.

## Actuator

| Variable | Default | Purpose |
|----------|---------|---------|
| `HEALTH_SHOW_DETAILS` | always | `management.endpoint.health.show-details`. With `never` (Spring Boot's own default) `/actuator/health` returns a bare status, and `WatchRecordsTask.computeHealth`'s `reason`, `registeredDirs` and `lastSuccessfulRegistrationAt` are discarded — which is why a 9-minute registration stall had to be diagnosed from logs. `always` exposes them to anyone who can reach the published port. `registeredDirs` is only a count (`registeredDirs.size`, an `Int`), but filesystem paths still leak two other ways: `lastFailure` carries the exception message verbatim (first 500 chars), which normally names the path that failed, and Spring's own `diskSpace` contributor publishes a filesystem path of its own — the JVM working directory (`/application` in this image), not the data directory. Accepted for a single deployment behind a closed perimeter; switch to `never`/`when-authorized` otherwise. The loosened default (Spring's own is `never`) was put to the repository owner during the external review of `perf/watch-records-registration` and accepted on 2026-08-03: what leaks is paths and timestamps, not credentials, and `/actuator/env` stays closed because `management.endpoints.web.exposure` is not overridden. That acceptance is conditional on the published port staying inside the LAN — there is no authentication of any kind here, so if `HOST_PORT` is ever forwarded outside it, bind the publication to loopback (`127.0.0.1:${HOST_PORT}:8080` in `docker-compose.yml`) rather than relying on this row. Spring's relaxed binding also honours `MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS`; set only one of the two variables. |

## Database

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | frigate_analyzer | Database name |
| `DB_USER` | frigate_analyzer_rw | Username |
| `DB_PASS` | frigate_analyzer_rw | Password |

## HTTP Client

| Variable | Default | Purpose |
|----------|---------|---------|
| `CONNECTION_TIMEOUT` | 10s | Connection timeout |
| `READ_TIMEOUT` | 30s | Read timeout |
| `WRITE_TIMEOUT` | 30s | Write timeout |
| `RESPONSE_TIMEOUT` | 30s | Response timeout |

## Detection

| Variable | Default    | Purpose |
|----------|------------|---------|
| `DETECT_DEFAULT_CONFIDENCE` | 0.6        | Confidence threshold |
| `DETECT_DEFAULT_IMG_SIZE` | 2016       | Default image size |
| `DETECT_DEFAULT_MODEL` | yolo26s.pt | Default YOLO model |
| `DETECT_GOOD_MODEL` | yolo26x.pt | High-quality YOLO model |
| `DETECT_RETRY_DELAY` | 500ms      | Retry delay on failure |
| `DETECT_FRAME_TIMEOUT` | 60s        | Single frame detection timeout |
| `DETECT_FRAME_EXTRACTION_TIMEOUT` | 5m         | Frame extraction timeout |
| `DETECT_VISUALIZE_TIMEOUT` | 60s        | Visualization timeout |
| `DETECT_HEALTH_CHECK_TIMEOUT` | 5s         | Health check timeout |
| `DETECT_HEALTH_CHECK_INTERVAL` | 30s        | Health check interval |

### Frame Extraction

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_SCENE_THRESHOLD` | 0.05 | Scene change threshold |
| `DETECT_MIN_INTERVAL` | 1.0 | Min interval between frames (sec) |
| `DETECT_MAX_FRAMES` | 50 | Max frames per recording |
| `DETECT_FRAME_QUALITY` | 85 | Extracted frame JPEG quality |

### Remote Visualization

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_MAX_DET` | 100 | Max detections to visualize |
| `DETECT_LINE_WIDTH` | 2 | Bounding box line width |
| `DETECT_SHOW_LABELS` | true | Show class labels |
| `DETECT_SHOW_CONF` | true | Show confidence scores |
| `DETECT_VISUALIZE_QUALITY` | 90 | Output JPEG quality |

### Video Visualization (Annotation)

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECT_VIDEO_VISUALIZE_TIMEOUT` | 45m | Full annotation job timeout. Must be < QuickExport annotated outer timeout (50m), otherwise generic error replaces the dedicated annotation-timeout message |
| `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT` | 10s | HTTP timeout for POST /jobs/{id}/cancel on vision server. Tolerant of all errors. |
| `DETECT_VIDEO_VISUALIZE_POLL_INTERVAL` | 3s | Annotation job status poll interval |
| `DETECT_VIDEO_VISUALIZE_MAX_DET` | 100 | Max detections per frame |
| `DETECT_VIDEO_VISUALIZE_DETECT_EVERY` | 1 | Detect every N frames |
| `DETECT_VIDEO_VISUALIZE_LINE_WIDTH` | 2 | Bounding box line width |
| `DETECT_VIDEO_VISUALIZE_SHOW_LABELS` | true | Show class labels |
| `DETECT_VIDEO_VISUALIZE_SHOW_CONF` | true | Show confidence scores |

## Detection Filter

| Variable | Default | Purpose |
|----------|---------|---------|
| `DETECTION_FILTER_ENABLED` | true | Enable/disable filtering |
| `DETECTION_FILTER_CLASSES` | person,car,motorcycle,truck,bicycle,cat,dog,bird,backpack,horse,sheep,cow,bear,elephant,zebra,giraffe | Allowed object classes |

## Pipeline

| Variable | Default | Purpose |
|----------|---------|---------|
| `PIPELINE_FRAME_BUFFER_SIZE` | 500 | Channel buffer size |
| `PIPELINE_FRAME_MIN_CONSUMERS` | 1 | Min consumer coroutines |
| `PIPELINE_FRAME_PRODUCERS_COUNT` | 6 | Producer coroutines |
| `PIPELINE_IDLE_DELAY` | 1s | Producer idle delay |
| `PIPELINE_ERROR_DELAY` | 5s | Producer error delay |
| `PIPELINE_BATCH_SIZE` | 10 | Recording batch size |

## Local Visualization

| Variable | Default | Purpose |
|----------|---------|---------|
| `LOCAL_VIZ_LINE_WIDTH` | 2 | Bounding box line width |
| `LOCAL_VIZ_QUALITY` | 90 | Output JPEG quality |
| `LOCAL_VIZ_REFERENCE_HEIGHT` | 720 | Reference height for scaling |
| `LOCAL_VIZ_MIN_FONT_SCALE` | 0.5 | Min font scale factor |
| `LOCAL_VIZ_MAX_FONT_SCALE` | 2.2 | Max font scale factor |
| `LOCAL_VIZ_BASE_FONT_SCALE` | 2.0 | Base font scale factor |
| `LOCAL_VIZ_BASE_FONT_SIZE` | 16 | Base font size (px) |
| `LOCAL_VIZ_LABEL_PADDING` | 4 | Label padding (px) |
| `LOCAL_VIZ_MAX_FRAMES` | 10 | Max frames to visualize. Validated `1..50` — 50 is Telegram's media cap for one rich message, above which frames cannot be delivered at all. Also caps `APP_AI_DESCRIPTION_MAX_FRAMES` via `minOf` in `RecordingProcessingFacade`. |

## AI Description

Settings under `application.ai.description` in `application.yaml`. Enables AI-generated short and detailed descriptions of detections via Claude Code CLI or Grok Build CLI. Requires `APP_AI_DESCRIPTION_ENABLED=true`. Both provider sections bind on every deployment, so their defaults must stay valid whatever the presets declare.

### Presets

Which model runs is decided by a **preset**, not by `APP_AI_DESCRIPTION_PROVIDER`. Presets are a map under `application.ai.description.presets`, declared in the mounted `application-docker.yaml` (see `docker/deploy/application-docker.yaml.example`); the map in `modules/core/src/main/resources/application.yaml` is empty, and it is the one setting with no `APP_…` placeholder of its own. `DescriptionPresetDeclarations` reads it through the same `Binder` Spring binds `@ConfigurationProperties` with, so relaxed-bound environment variables, the `presets[id]` bracket form and placeholders are all seen — the bean condition and the catalog can never disagree about what is declared.

| Property | Env | Default | Validation |
|----------|-----|---------|------------|
| `presets.<id>` | — | empty map | id matches `[a-z0-9][a-z0-9-]{0,31}` — it travels in Telegram `callback_data`, which holds 64 bytes for everything |
| `presets.<id>.provider` | — | — | `claude` or `grok`; anything else fails startup |
| `presets.<id>.model` | — | — | must not be blank |
| `presets.<id>.effort` | — | empty | empty, or `low`/`medium`/`high`/`xhigh`/`max`; non-empty only for `provider: grok` |
| `default-preset` | `APP_AI_DESCRIPTION_DEFAULT_PRESET` | empty | must be a declared id when the map is non-empty; with an **empty** map only a startup WARN, so it can be set in `.env` before the map exists in yaml |

```yaml
application:
  ai:
    description:
      default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:}
      presets:
        grok-fast:   { provider: grok,   model: grok-4.6,   effort: low }
        grok-deep:   { provider: grok,   model: grok-4.6,   effort: xhigh }
        byok-luna:   { provider: grok,   model: codex-luna, effort: "" }
        claude-opus: { provider: claude, model: opus }
```

`default-preset` decides only **until the owner's first pick in `/ai`**; after that the stored id
wins and changing `default-preset` in yaml has no effect. The startup log names the whole catalog
with values, and `ActivePresetResolver` logs the active preset and its source again **every time that
line changes** — a switch in `/ai`, or a fallback taking over — so "which model is running" stays
answerable from the log. The catch to know: that line is written when a preset is next resolved (the
following description call, or opening `/ai`), not at the moment of the click, and the write itself
only reaches the log as `AppSettings: 'ai.description.preset.active' set by <owner>` at INFO with the
value at DEBUG.

A preset whose provider is not configured (no token, a directory that could not be created, no
factory for that provider) stays in the catalog, is marked in `/ai` and cannot be selected; startup
fails only when **every** declared preset is unusable. An existing directory that is merely not
writable — the root-owned `grok-home` the README warns about — is **not** in that set: creation
succeeds, the factory only WARNs, and the preset stays selectable and fails later at call time. `ANTHROPIC_MODEL`, when set, displaces the declared `model` of
every claude preset — the catalog logs a WARN naming the displaced pair, and `/ai` shows the model
that will actually be used.

**Legacy single-preset path.** While the map is empty, `APP_AI_DESCRIPTION_PROVIDER` plus that provider's own section synthesizes exactly one preset (id = provider name, `model`/`effort` from `claude.model`, `grok.model`, `grok.effort`), so an existing `.env` keeps working unchanged. The value is normalized (`trim().lowercase()`), matching the case-insensitive `@ConditionalOnProperty` it replaced. With a non-empty map `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT` and `CLAUDE_MODEL` are unused, and a non-blank `APP_AI_DESCRIPTION_PROVIDER` is logged as a WARN so a typo in a variable that stopped mattering stays visible.

**Runtime keys in `app_settings`** (written by `/ai`, `updated_by` = the owner's username):

| Key | Type | Absent means | Notes |
|-----|------|--------------|-------|
| `ai.description.preset.active` | string, a preset id | `default-preset`, else the first usable preset | An id that is unknown or unavailable falls back with a WARN and stays stored |
| `ai.description.enabled` | boolean | `true` | Runtime off-switch; `APP_AI_DESCRIPTION_ENABLED=false` still wins, the feature beans do not exist then |

`AppSettingsService` caches per process, so direct SQL on these two keys is invisible until a restart, and a write invalidates only the writing process's cache — see `database.md`.

### Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_AI_DESCRIPTION_ENABLED` | false | Master flag for AI description. When `false`, no model calls, no placeholders, no edit jobs, and none of the feature beans exist. |
| `APP_AI_DESCRIPTION_DEFAULT_PRESET` | empty | `default-preset` — see above. |
| `APP_AI_DESCRIPTION_PROVIDER` | claude | **Legacy**, single-preset path only: `claude` or `grok`, used while the `presets` map is empty. An unknown value then leaves the deployment without an agent — a WARN at startup and every recording goes out without description blocks. |
| `APP_AI_DESCRIPTION_LANGUAGE` | en | Reply language. `ru` or `en`. |
| `APP_AI_DESCRIPTION_SHORT_MAX` | 200 | Max characters of the short description (the `<p>` above the frames). |
| `APP_AI_DESCRIPTION_DETAILED_MAX` | 1500 | Max characters of the detailed description (the `<details>` body). |
| `APP_AI_DESCRIPTION_MAX_FRAMES` | 10 | Max frames forwarded to the model per recording. Validated `1..50`, but the effective value is `minOf(this, LOCAL_VIZ_MAX_FRAMES)`. |
| `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` | 0 | Longest frame side in pixels before the model call; `0` sends frames at camera resolution. Validated `0` or `256..8192`. Vision endpoints bill by image area, and some gateways drop an image above their own limit without saying so — the LiteLLM gateway in front of DKS-Vision ignores anything wider than 1568 px and the model answers "frame unavailable". Resizing happens once per request in `VisionCallExecutor`, before the provider attempt, so both providers get it. |
| `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` | 30s | Max wait for a free concurrency slot. |
| `APP_AI_DESCRIPTION_TIMEOUT` | 60s | Per-call describe timeout (including internal retries). Must cover the slowest **declared** preset: `grok-4.6` at `effort=xhigh` takes ~48 s, leaving nothing for either retry (transport needs 10 s of budget plus a 5 s pause, invalid-response 5 s — the latter starts and then dies on the outer timeout, reporting `Timeout` instead of `InvalidResponse`). Such a preset gets a startup WARN recommending `120s` and a 🐢 mark in `/ai`. |
| `APP_AI_DESCRIPTION_MAX_CONCURRENT` | 2 | Max simultaneous description requests. Two `xhigh` calls hold both default slots for ~48 s, after which a third recording gives up on `APP_AI_DESCRIPTION_QUEUE_TIMEOUT`. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED` | true | Enable sliding-window throttle on AI description invocations. When `false`, every recording with AI enabled gets a description request. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_MAX` | 30 | Max invocations within the sliding window. Counter increments when a slot is granted; failed model calls (transport errors, retries) do not refund the slot. Raised from 10 so descriptions still have budget once the judge sits in front. |
| `CLAUDE_MAX_BUFFER_SIZE` | 16MB | Spring `DataSize`; max size of one JSON message the SDK accepts from the CLI (`CLIOptions.maxBufferSize`). In `stream-json` mode the CLI echoes every frame the model reads back as a base64 `tool_result`, so the SDK's own 1 MiB default overflows on a ~750 KB frame: the line is dropped with an ERROR log, and only the final answer being dropped would break the description. Must fit in an `Int`. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW` | 1h | Sliding-window length. Spring Boot `Duration` simple format takes a single suffix (`30s`, `15m`, `1h`); for compound durations use ISO-8601 (`PT2H30M`). When the limit is exceeded, the recording goes to Telegram without description blocks — no placeholders, no edit-job, no Claude call. |

### Grok provider (any preset with `provider: grok`, or the legacy `APP_AI_DESCRIPTION_PROVIDER=grok`)

| Variable | Default | Purpose |
|----------|---------|---------|
| `GROK_MODEL` | grok-4.6 | **Legacy**, single-preset path only: a declared grok preset carries its own `model`. Model id, or the name of a `[model.<name>]` BYOK entry from `GROK_HOME/config.toml`. Must not be blank even on a claude-only deployment — the Grok section always binds. |
| `GROK_PASS_THROUGH_ENV` | empty | Comma-separated names of JVM environment variables handed to the child `grok` verbatim. The child environment is built from scratch and inherits only PATH/HOME/locale and `GROK_*`/`XAI_*`, so a BYOK `env_key` named outside those prefixes (`MY_GATEWAY_KEY`) reaches `grok` only when listed here. Everything unlisted stays out, `DB_PASS` and `TELEGRAM_BOT_TOKEN` included. |
| `GROK_EFFORT` | low | **Legacy**, single-preset path only: a declared grok preset carries its own `effort`. Reasoning effort passed as `--effort`. Empty = the flag is not passed, which BYOK models without reasoning levels need. grok-4.6 accepts `low`, `medium`, `high`, `xhigh` and rejects anything else before calling the model (exit 1); BYOK models may also accept `max` when their `config.toml` declares it. `high` costs ~5x the wall-clock of `low` on grok-4.6 for a frame description and adds little. |
| `GROK_CLI_PATH` | (empty) | Explicit binary; empty = `grok` from `PATH`. |
| `GROK_HOME` | `<TEMP_FOLDER>/grok-home` | Grok's own directory: `auth.json`, optional `config.toml`, sessions. Inspected and swept only when a declared preset uses grok — compose sets the variable and mounts the volume unconditionally. The same variable drives a manual `grok login` inside `docker compose exec`, so `docker-compose.yml` sets it to the mounted `./grok-home`. Must be writable by uid 1000: the refresh token rotates and is written back. `GrokHomeSweeper` empties `sessions/` and `logs/` hourly. |
| `GROK_WORKING_DIR` | `<TEMP_FOLDER>/grok-cwd` | Empty directory passed as `--cwd`; Grok reads `AGENTS.md`, `CLAUDE.md`, `.claude/rules` and `.grok` from there, so keep it empty. |
| `GROK_HTTP_PROXY` / `GROK_HTTPS_PROXY` / `GROK_NO_PROXY` | (empty) | Passed to the `grok` process as `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` when set. |

First sign-in: `mkdir -p grok-home && sudo chown 1000:1000 grok-home`, `docker compose up -d`, then
`docker compose exec frigate-analyzer grok login --device-code`. Never copy `auth.json` from another
machine. When the credentials stop working the owner receives one Telegram message per outage with
the command to run, and another when descriptions work again.

## AI Judge

Settings under `application.ai.judge` in `application.yaml` — a **sibling** of
`application.ai.description`, not nested inside it. The judge reuses the description preset catalog
(`application.ai.description.presets`); it does not declare a catalog of its own. Requires
`APP_AI_DESCRIPTION_ENABLED=true` (`AiJudgeGuard` fails startup otherwise). When `false`, none of
the judge beans exist and `RecordingProcessingFacade` sends as today.

A fast, cheap preset is the point (`claude-sonnet` or `grok-fast`, not opus). Every candidate that
reaches the judge is written to `notification_verdicts` (no cleanup). Fail-open: a model or context
failure sends the notification unjudged.

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_AI_JUDGE_ENABLED` | false | Master flag. When `false`, no judge beans, no `/ai` judge block, the facade sends itself. |
| `APP_AI_JUDGE_DEFAULT_PRESET` | empty | Preset used until the owner picks one in `/ai`. Empty = the descriptions `default-preset` (or the catalog fallback). Must be a declared, usable id when set. |
| `APP_AI_JUDGE_QUEUE_TIMEOUT` | 30s | Max wait for a free judge concurrency slot. |
| `APP_AI_JUDGE_TIMEOUT` | 60s | Per-call judge timeout including the executor's retries. Size it for the **judge** preset, not the slowest description preset. |
| `APP_AI_JUDGE_MAX_CONCURRENT` | 2 | Max simultaneous judge calls. Separate semaphore from descriptions. Validated `1..10`. |
| `APP_AI_JUDGE_MAX_IN_FLIGHT` | 32 | Candidates the judge may hold at once across all cameras — a memory ceiling, not a throughput knob. Each candidate pins its annotated frames **and** the originals held by the description supplier, so size it by camera resolution. Beyond the ceiling the recording is sent unjudged (`FAILOVER` / `TRANSPORT`) by the calling coroutine, which is also what back-pressures the pipeline. Validated `1..512`. |
| `APP_AI_JUDGE_MAX_FRAMES` | 4 | Annotated frames forwarded to the judge, first N of the visualization ranking then chronological. Validated `1..10`. |
| `APP_AI_JUDGE_MAX_IMAGE_SIDE` | 1280 | Longest frame side before the judge call; `0` = as-is. Validated `0` or `256..8192`. |
| `APP_AI_JUDGE_RATE_LIMIT_ENABLED` | true | Sliding-window throttle on judge invocations. |
| `APP_AI_JUDGE_RATE_LIMIT_MAX` | 200 | Protective ceiling. Beyond it candidates are sent unjudged (`FAILOVER` / `RATE_LIMITED`). |
| `APP_AI_JUDGE_RATE_LIMIT_WINDOW` | 1h | Sliding-window length (same Duration syntax as descriptions). |
| `APP_AI_JUDGE_MAX_SNOOZE` | PT30M | Ceiling on `snooze_minutes` from the model. Positive duration. |
| `APP_AI_JUDGE_STATIC_WINDOW` | P7D | How far back the static-score query looks for the same class in the same place. |
| `APP_AI_JUDGE_STATIC_IOU` | 0.4 | IoU threshold of that query, `0..1`. |
| `APP_AI_JUDGE_HISTORY_WINDOW` | PT6H | `±` window of `notification_verdicts` included in the prompt context. |
| `APP_AI_JUDGE_HISTORY_LIMIT` | 10 | Max verdict rows in that context, `1..50`. |
| `APP_AI_JUDGE_ZONE` | empty | IANA zone for local times in the prompt. Empty = the owner's zone from `/timezone`, then the JVM zone. The container runs in UTC, so leaving this empty without `/timezone` puts UTC into the prompt. An invalid id fails startup. |
| `application.ai.judge.cameras.<cam>.notes` | empty | Owner notes about a camera's scene, yaml only (`application-docker.yaml`). Passed into the context as-is; no env var. |

**Runtime keys in `app_settings`** (written by `/ai`, `updated_by` = the owner's username):

| Key | Type | Absent means |
|-----|------|--------------|
| `ai.judge.preset.active` | string, a preset id | `APP_AI_JUDGE_DEFAULT_PRESET`, else the descriptions fallback |
| `ai.judge.enabled` | boolean | `true` — runtime off-switch; `APP_AI_JUDGE_ENABLED=false` still wins |

Same per-process cache as the description keys — see `database.md`.

## Video Export

Settings under `application.export.compress` in `application.yaml`. They tune the re-encode that
`TelegramVideoFitter` (core, `video/`) runs when a merged export exceeds 45 MiB — see
`telegram-export.md`, "Size limit". The 45 MiB budget and the 50 MB acceptance limit are constants
(`FitLimits.TELEGRAM`), not settings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `EXPORT_COMPRESS_PRESET` | fast | libx264 preset name (`ultrafast` … `placebo`, validated at startup): speed versus compression on the host CPU |
| `EXPORT_COMPRESS_CRF` | 23 | libx264 quality target (0–51); the bitrate cap from the budget still applies |
| `EXPORT_COMPRESS_MIN_BITS_PER_PIXEL` | 0.1 | Smallest bits-per-pixel a candidate height (1080/720/540 plus the source height, never above it) may have before the next smaller one is tried. Also caps the work handed to libx264 — the budget divided by this value — so lowering it for quality moves the encode towards the ffmpeg timeout |

## Telegram

| Variable | Default | Purpose |
|----------|---------|---------|
| `TELEGRAM_ENABLED` | true | Enable/disable bot |
| `TELEGRAM_BOT_TOKEN` | - | Bot token |
| `TELEGRAM_OWNER` | - | Owner username (without @) |
| `TELEGRAM_QUEUE_CAPACITY` | 100 | Notification queue size |
| `TELEGRAM_SEND_VIDEO_TIMEOUT` | 3m | Timeout for sending video |
| `TELEGRAM_PROXY_HOST` | (empty) | SOCKS5 proxy host. Empty = no proxy |
| `TELEGRAM_PROXY_PORT` | 1080 | SOCKS5 proxy port |

See `.claude/rules/telegram.md` for full Telegram module details.

## Signal Loss Detection

Settings under `application.signal-loss` in `application.yaml`. The detector polls the database for the most recent recording timestamp per camera and notifies Telegram on signal loss / recovery. Active when `SIGNAL_LOSS_ENABLED=true`; requires `TELEGRAM_ENABLED=true` (enforced at startup by `SignalLossTelegramGuard`).

| Variable | Default | Purpose |
|----------|---------|---------|
| `SIGNAL_LOSS_ENABLED` | true | Master flag. Default `true` is set by this YAML — production has the feature on. The bean is gated by `@ConditionalOnProperty(matchIfMissing=false)`, so test contexts that don't load this YAML and leave the property unset keep the feature off (existing integration tests are unaffected). |
| `SIGNAL_LOSS_THRESHOLD` | 3m | If `now - lastRecording > THRESHOLD` (strict) the signal is considered lost. |
| `SIGNAL_LOSS_POLL_INTERVAL` | 30s | Detector tick period. Must be smaller than `SIGNAL_LOSS_THRESHOLD`. |
| `SIGNAL_LOSS_ACTIVE_WINDOW` | 24h | Window of "active" cameras. **Must be set to at least Frigate's recording retention.** Cameras whose last recording is older are not monitored. Validation at startup also enforces `activeWindow > threshold + startupGrace` so a camera lost just before boot does not fall out of the window before the late-alert tick fires. |
| `SIGNAL_LOSS_STARTUP_GRACE` | 5m | After startup, alerts are deferred for this duration. If a camera was already dark at boot, the first tick after grace ends fires a (late) LOSS alert provided the gap still holds. Shorten to surface boot-time outages faster; lengthen if Frigate restarts and you want to avoid spurious LOSS during its own warm-up. |

## Notifications

Settings under `application.notifications` in `application.yaml`. Object tracker suppresses duplicate recording notifications when an object remains in view across consecutive recordings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `NOTIFICATIONS_TRACK_TTL` | 30m | Track stays "active" this long after last detection. Match → updateLastSeen → no spam. |
| `NOTIFICATIONS_TRACK_IOU_THRESHOLD` | 0.3 | IoU threshold for cross-recording matching of (class, bbox). |
| `NOTIFICATIONS_TRACK_INNER_IOU` | 0.5 | IoU threshold for clustering same-class detections within one recording. |
| `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` | 0.3 | Ignore low-confidence detections before clustering/tracking. |
| `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS` | 3600000 | `@Scheduled` cleanup job period in milliseconds. |
| `NOTIFICATIONS_TRACK_CLEANUP_RETENTION` | 1h | DELETE rows with `last_seen_at < now() - retention`. Larger than TTL. |
| `NOTIFICATIONS_TRACK_REAPPEAR_GAP` | = TTL | Matched track absent longer than this → notify as `REAPPEARED`. Must be > 0 and <= TTL; defaulting to TTL makes it a no-op (the comparison is strict, and TTL also bounds `findActive`). |
| `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` | (empty) | Comma-separated classes allowed to notify as `REAPPEARED`. Empty = all classes (no-op). Matching is case-insensitive and trims; blank entries are ignored, but a list of nothing but blanks fails at binding. Does **not** affect `NEW_OBJECTS` — a class left out still notifies the first time it is seen. Unrelated to `DETECTION_FILTER_CLASSES`. |
| `NOTIFICATIONS_COOLDOWN_REAPPEAR` | PT0S | Minimum distance between two `REAPPEARED` notifications for one camera; extra ones are suppressed with reason `COOLDOWN`. `PT0S` (default) disables it. Measured on `recordTimestamp`, so a backlog spanning more than the cooldown is not collapsed into a single notification. Does not gate `NEW_OBJECTS`, and never skips the tracker. The window is keyed by camera alone and is class-agnostic — any class that reaches `REAPPEARED` arms it for every class on that camera, so pair this with `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` unless a flickering bicycle muting a person's return is acceptable. |

Two things about that row surprise operators the first time and are worth stating outright:

- **`sinceLast` in the suppress line can be negative** (`sinceLast=PT-11S`). The distance is compared
  by absolute value, and the queue drains newest-first, so the recording being judged is often *older*
  than the one that anchored the window. A minus sign there is normal, not a bug.
- **A recording far older than the anchor notifies again.** The anchor holds the newest announced
  `recordTimestamp` per camera, and anything outside the window on either side counts as its own
  event. Older recordings never move the anchor, so while a large backlog is being drained this can
  produce a run of notifications in quick succession, one per recording beyond the window — an hour
  of backlog at a one-minute cadence under `PT5M` collapses only its newest five minutes and sends
  the remaining ~55 individually. That is the deliberate trade-off which keeps a backlog from
  collapsing into a single notification, which is what a wall-clock cooldown would do.

### Tuning REAPPEAR_GAP under a long TTL

A long TTL suppresses static noise (parked car, fixed false positive) but also swallows real traffic:
tracks accumulate until any new detection overlaps one, so nothing is ever "new" again. `REAPPEAR_GAP`
restores notifications without shortening the TTL, by using absence rather than location.

It works because the two cases separate cleanly when recordings are continuous: a static object is
re-detected at the recording cadence, while a person who left and came back has a gap of hours. Set
it above the largest gap a *static* object shows (detector flakiness), below the smallest gap a real
visitor shows. Measure both from `detections` before picking a value — though the tracker's own
`maxAbsence` supersedes that recipe, and `### Reading the tracker's debug line` below covers how to
collect it. The value must also exceed the camera's normal processing cadence: a wall-clock pause
between evaluations longer than `REAPPEAR_GAP` reads as an interruption and restarts the watch
window, so a gap smaller than the cadence would keep the feature permanently blind.

Gaps in *processing* are excluded automatically, and measuring `detections` would never reveal them.
After a restart, a deploy, a stalled pipeline or a camera signal loss, the first recordings processed
span the whole interruption — the queue is drained newest-first — so every static object would read
as having come back. The tracker only counts an absence that began while it was already watching that
camera, so reappearances stay silent for exactly `REAPPEAR_GAP` after such an interruption (the
window restarts at the first recording processed, and a track must then be absent for more than the
gap *inside* the new window before it can notify — with `PT1H`, the feature is blind for an hour
after every restart); the suppressed ones appear in its debug line as `unobserved=N`.

Known limitation: the interruption detector's threshold IS `REAPPEAR_GAP`, so an in-process stall
*shorter* than the gap (e.g. 55 min under `PT1H`, detection servers down without a restart) does not
close the window. The first recording after such a stall measures an absence whose middle nobody
observed, and a static object whose absence straddles the stall can notify falsely — one extra
notification, in line with the tracker's fail-open bias. A separate, shorter interruption threshold
would trade these rare false positives for blind windows after every minor hiccup; revisit only if
production shows the pattern.

Raising TTL on its own will not start: `cleanup-retention >= ttl` is validated in
`ObjectTrackerProperties.init` and defaults to 1h, so the container fails at binding time with a
`cleanup-retention` message. (A `reappear-gap` message cannot appear in that scenario — the gap
follows TTL by default; it fires only when `NOTIFICATIONS_TRACK_REAPPEAR_GAP` is explicitly set
above TTL.) The three move together:

```
NOTIFICATIONS_TRACK_TTL=PT12H
NOTIFICATIONS_TRACK_REAPPEAR_GAP=PT1H
NOTIFICATIONS_TRACK_CLEANUP_RETENTION=PT48H
```

Per-user toggles for recording detections and camera signal-loss alerts are stored in `telegram_users.notifications_recording_enabled` / `notifications_signal_enabled` (default `true`). Global toggles in `app_settings`: `notifications.recording.global_enabled`, `notifications.signal.global_enabled`. OWNER manages globals via `/notifications`.

### When REAPPEAR_GAP alone cannot help

A production run under `TTL=PT12H`, `REAPPEAR_GAP=PT1H` across three cameras produced 9
reappearance notifications overnight against 5156 suppressions. Only one was worth sending. The
other eight came from two mechanisms the gap cannot separate:

- **Static objects flickering.** A bicycle, a parked car, a cow standing still. The detector loses
  them at night and finds them again at dawn; the absence crosses any reasonable gap. Raising the
  threshold far enough to cover a whole night (~`PT10H`) makes it meet `TTL`, at which point the
  feature is off by construction. An 8-hour absence of a motionless bicycle is *the same duration*
  as a person who left in the evening and came back in the morning — no single threshold splits
  them. `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` splits them by class instead.
- **Bursts from one pass.** Under a long TTL the frame accumulates stale tracks of the same class
  along a walkway; a person crossing it matches them one after another, and each of those tracks
  has been untouched for hours, so each produces its own reappearance. Every one of them clears any
  threshold, so the gap has no effect at all. `NOTIFICATIONS_COOLDOWN_REAPPEAR` collapses them.

Raising `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` against the flicker makes things **worse**: the weak
night-time detections it discards are what kept the static object's absences short, so dropping
them lengthens every absence and produces more reappearances, not fewer.

### Reading the tracker's debug line

`ObjectTrackerServiceImpl` logs one line per interesting recording at DEBUG:

```
ObjectTracker: cam=cam2 new=0 matched=3 reappeared=[person:PT3H12M] classFiltered=[cow:PT8H2M] unobserved=0 stale=107 maxAbsence=PT8H2M (recording=<uuid>)
```

The format changed in this release and **breaks existing greps**: `reappeared=` used to carry a count
(`reappeared=1`), so a pattern like `reappeared=[1-9]` now matches nothing. `classFiltered=` and
`maxAbsence=` are new fields, and `classFiltered=` sits *between* `reappeared=` and `unobserved=`, so
anchored patterns like `reappeared=(\d+) unobserved=` break for that second, independent reason.

- `maxAbsence` — the largest absence among **all** matched tracks, including those that stayed
  below `REAPPEAR_GAP`. This is the number to tune the gap against: it shows where the boundary
  currently runs and what lowering the threshold would start catching. `n/a` when nothing matched —
  or when the only matches were out-of-order recordings, whose negative distances are not absences.
  It is accumulated *before* the watch-window guard, so it also absorbs the absences reported as
  `unobserved` — after a ten-hour processing interruption it reads `PT10H` next to genuinely
  sub-threshold minutes, and a non-zero `unobserved=N` on the same line is the sign to discard it.
  That sign only fires for interruptions **longer than `REAPPEAR_GAP`**: `unobserved` is counted
  inside the same `absence > REAPPEAR_GAP` guard, so a stall shorter than the gap inflates
  `maxAbsence` silently, with `unobserved=0`. Under `PT1H` a fifty-minute stall prints
  `maxAbsence=PT50M unobserved=0` on an otherwise quiet recording — exactly the kind of line the
  TRACE collection below is made of, and read at face value it argues *against* the lowering it
  should be arguing for. Catch those by the gap between consecutive TRACE lines, not by `unobserved`.
- `reappeared=[class:duration]` — the reappearances the tracker recorded, each with its own absence.
  A cooldown-suppressed one still appears here: the gate drops the announcement, not the bookkeeping.
- `classFiltered=[class:duration]` — absences past the gap that `REAPPEAR_CLASSES` kept quiet. The
  line is emitted for these too, so a filtered deployment still shows what it is suppressing.
- `unobserved=N` — absences discarded because the tracker was not watching when they began.

The line stays off ordinary recordings at DEBUG: it is emitted only when something new appeared,
something reappeared, something was filtered, or something was unobserved.

None of it is visible at the default level. Both the bundled `log4j2.yaml` and `log4j2.yaml.example`
ship `ru.zinin` at `info`, so a stock deployment logs nothing of the above. Turn the two classes up
explicitly — `application-docker.yaml` is gitignored, so this has to be done per host and cannot be
assumed to be in place:

```yaml
logging:
  level:
    ru.zinin.frigate.analyzer.service.impl.ObjectTrackerServiceImpl: DEBUG
    ru.zinin.frigate.analyzer.service.impl.NotificationDecisionServiceImpl: DEBUG
```

Keep it to those two loggers. `ru.zinin: DEBUG` across the board buries the tracker line under
per-frame detection output, which is the opposite of what the tuning pass needs.

**Raising or lowering the gap need different data, and DEBUG only carries one of them.** The line is
emitted at DEBUG only when something happened — a new track, a reappearance, a class-filtered one, an
unobserved absence. Deciding to *raise* the threshold works from those: every reappearance that fired
carries its own duration. Deciding to *lower* it does not, because the absences that stayed below the
gap live on ordinary recordings, and those are exactly the ones DEBUG leaves out — over the
production night, 5156 of them without a single line.

Switch the tracker to `TRACE` to get them:

```yaml
logging:
  level:
    ru.zinin.frigate.analyzer.service.impl.ObjectTrackerServiceImpl: TRACE
```

Every recording with at least one detection at or above the confidence floor then emits the same line,
`maxAbsence` included — on the order of a few thousand lines a night for three cameras. Recordings
with no detections at all, or none surviving the floor, short-circuit before the summary and stay
silent even at TRACE, so gaps in the sequence are not a misconfiguration. Leave it on for one night,
take the distribution of `maxAbsence` over the quiet recordings, and turn it back down; there is no
reason to run it continuously.

Collect a night of these lines before choosing values, then set the two noise knobs from what they
show:

```
NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person
NOTIFICATIONS_COOLDOWN_REAPPEAR=PT5M
```

The class list reads straight off `reappeared=[class:duration]`. The cooldown does not: it is sized
against the *burst*, not the absence, and neither logger above prints a `recordTimestamp` to measure
one from. A burst is a single pass through the frame, so it spans the handful of consecutive
recordings the object stays visible for — start at a few minutes, then read the `sinceLast=` values
off the `Decision: suppress (cooldown)` lines, which only appear once the cooldown is already on.
