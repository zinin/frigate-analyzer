# /status REST + Telegram command — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace REST `/statistics` with `/status` (recordings + cameras + detect servers) and add OWNER-only Telegram `/status` command rendering the same snapshot in HTML.

**Architecture:** New `StatusService` is the single source of truth for system snapshot — used both by `StatusController` (JSON) and `StatusCommandHandler` (HTML via `StatusMessageFormatter`). Camera signal status is read from `SignalLossMonitorTask.snapshotStates()` (in-memory state machine). When `signal-loss.enabled=false`, cameras section is empty + `monitoringEnabled=false`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3 + WebFlux, R2DBC, Kotlin Coroutines, ktgbotapi (HTMLParseMode), JUnit 5, AssertJ, MockK.

**Spec:** `docs/superpowers/specs/2026-05-25-status-telegram-design.md`
**Branch:** `feat/status-command`

---

## File Structure

| Action | Path | Responsibility |
|---|---|---|
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/CameraStatusDto.kt` | `CameraStatusDto` + `CameraState` enum (HEALTHY/OFFLINE) |
| Create | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatusResponse.kt` | `StatusResponse` + `CamerasSection` |
| Delete | `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt` | Old wrapper — move `RecordingsStatistics`/`CameraStatistics`/`DetectServerStatistics`/`ServerLoad`/`ServerStatus` into new files before deleting |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTask.kt` | Add `fun snapshotStates(): Map<String, CameraSignalState>` |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt` | Build `StatusResponse` from repo + load-balancer + monitor snapshot, with sorting |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusController.kt` | REST `GET /status` |
| Delete | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatisticsController.kt` | Replaced by StatusController |
| Modify | `modules/telegram/src/main/resources/messages_en.properties` | New `status.*` and `command.status.description` keys |
| Modify | `modules/telegram/src/main/resources/messages_ru.properties` | Same keys in Russian |
| Create | `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt` | Render `StatusResponse` to HTML with `<pre>` tables |
| Create | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt` | OWNER-only `/status` handler (in `core`, not `telegram` — `telegram` does not depend on `core`) |
| Modify | `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt` | Disable `WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS` for ISO-8601 contract |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt` | Unit tests for collect logic |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt` | Integration test GET /status with explicit ISO-8601 assertions |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/SignalLossMonitorTaskSnapshotTest.kt` | snapshotStates() defensive-copy + mutation-after-snapshot tests |
| Create | `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt` | HTML format + escape + padding + i18n + tz + id-non-duplication |
| Create | `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt` | Command metadata + collaboration |

**Reused unchanged:** `RecordingEntityRepository` (all 5 stats methods), `DetectServerLoadBalancer.getAllServersStatistics()`, `RecordingsStatistics`, `CameraStatistics`, `DetectServerStatistics`, `ServerLoad`, `ServerStatus`, `SignalLossMessageFormatter` (for `formatDuration`), `MessageResolver`, `AuthorizationFilter`, `HelpCommandHandler` (auto-picks up owner commands).

---

### Task 1: Move existing model classes out of StatisticsResponse.kt

✅ Done — see commit: `2c87cac`

---

### Task 2: Create CameraStatusDto and CameraState enum

✅ Done — see commit: `68f12e6`

---

### Task 3: Create StatusResponse and CamerasSection

✅ Done — see commit: `6ec5c86`

---

### Task 4: Add snapshotStates() to SignalLossMonitorTask

✅ Done — see commit: `d83f865`

---

### Task 5: Create StatusService

✅ Done — see commit: `37a3ff3`

---

### Task 6: Create StatusController

✅ Done — see commit: `55044a9 (+ fixup 3283a9a)`

---

### Task 7: Add i18n keys for /status

✅ Done — see commit: `e1dc62c`

---

### Task 8: Delete StatisticsController and StatisticsResponse

✅ Done — see commit: `4179ac2`

---

### Task 9: Create StatusMessageFormatter

✅ Done — see commit: `d43b6df`

---

### Task 10: Create StatusCommandHandler (in `core` module)

✅ Done — see commit: `9826dc8`

Note: also added `implementation(libs.ktgbotapi)` to `modules/core/build.gradle.kts` because `:frigate-analyzer-telegram` declares ktgbotapi as `implementation` (not `api`) — types not transitively visible to `core`. Targeted addition at the consumer side was preferred over widening `telegram`'s declaration to `api(libs.ktgbotapi)` (latter would have leaked the dep to `ai-description` etc.).
---

### Task 10b: Configure ObjectMapper for ISO-8601 (Instant/Duration)

✅ Done — see commit: `e1b6792`
---

### Task 11: Full build + ktlint

✅ Done — no commits.

`./gradlew ktlintFormat` produced no changes (all files already ktlint-clean from per-task discipline). `./gradlew build` finished with BUILD SUCCESSFUL in 2m 33s — 94 actionable tasks, zero failures, ~217 tests passing in `:frigate-analyzer-core`. Docker (testcontainers) was available, so `StatusControllerTest` ran and passed.
---

### Task 12: Manual sanity check

Verify the feature end-to-end against a running app. This is **non-optional** per project rule: "If you can't test the UI, say so explicitly rather than claiming success."

- [ ] **Step 1: Start the app locally**

Outside the agentic flow. Run with telegram + signal-loss enabled, pointing at a dev DB:

```bash
./gradlew :frigate-analyzer-core:bootRun
```

- [ ] **Step 2: REST sanity check**

```bash
curl -sS http://localhost:8080/frigate-analyzer/status | jq .
```

Expected response structure:
- `recordings.total`, `recordings.processed`, `recordings.unprocessed`, `recordings.byCameras[]`, `recordings.processingRatePerMinute`
- `cameras.monitoringEnabled = true`, `cameras.items[]` (sorted OFFLINE first, then by camId)
- `detectServers[]` (sorted DEAD first, then by id)

Also verify the old endpoint returns 404:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8080/frigate-analyzer/statistics
# expected: 404
```

- [ ] **Step 3: Telegram sanity check**

In the Telegram chat with the bot, as OWNER:

1. Send `/status`. Expect a single HTML-formatted message with the four sections (Recordings, By camera, Cameras, Detect servers). `<pre>` blocks should render in monospaced font with aligned columns.
2. Confirm camera with simulated signal loss appears with 🔴 and "offline X" + last-seen time in the user's timezone (set via `/timezone` first if needed).
3. As a non-owner USER, send `/status`. Expect "This command is available to the owner only." reply.

- [ ] **Step 4: Help check**

Send `/help` as OWNER. Verify `/status` appears under "👑 Owner commands:" section.

- [ ] **Step 5: Signal-loss disabled check**

Restart app with `SIGNAL_LOSS_ENABLED=false`. Send `/status`. Expect the Cameras section to show "Monitoring disabled (set SIGNAL_LOSS_ENABLED=true to enable)".

- [ ] **Step 6: Report**

Stop the app. If any step failed, fix and re-run from Step 1. If all six steps passed, proceed to Task 13.

---

### Task 13: Code review

✅ Done — see review commits: `5327ba4` (auto-fixes), `d0866df` (decisions).

Two passes of full-branch external code review against `master..HEAD`:
- **First pass** (Opus, single-agent): **Ready to merge**, 0 Critical, 0 Important, 9 Minor (M1–M9). Per plan directive, only Critical/High auto-fixed; Minor left as follow-ups.
- **Second pass** (5-agent parallel: claude, codex, ccs-glm, ollama-deepseek, ollama-minimax; ollama-kimi skipped — stalled): 5/5 verdict **Ready to merge**, 0 Critical. Auto-fixes: `@Operation method="GET"` cleanup, `docs/incidents` runbook `/statistics`→`/status`. Decisions applied: real wire-format ISO-8601 test in `StatusControllerTest` (mocked `StatusService`), `JacksonConfiguration` KDoc-warning about dual Jackson stack, conditional date prefix for OFFLINE last-seen when `offlineFor >= 24h`. Two follow-up issues filed:
  - `docs/issues/2026-05-25-recordings-counts-mask-errors.md` — Recordings counts mask errors (inherited from `/statistics`).
  - `docs/issues/2026-05-25-dual-jackson-stack.md` — legacy `com.fasterxml.jackson` + new `tools.jackson` coexistence.

Each per-task implementation in this session also passed independent spec-compliance ✅ + code-quality ✅ reviews (both Opus).

Note: per global CLAUDE.md, before creating a PR `git rm` all files under `docs/superpowers/` and commit — those documents stay on the branch in git history but must NOT appear in the PR diff.

---

## Self-Review (updated after review iter-1)

### Iter-1 review impact summary

Auto-fixes applied from external review (22 issues):

- CRITICAL: olsonCode → `TelegramUserService.getUserZone()` (Task 10); placeholder-index renumbering across `status.cameras.line.*` / `status.servers.line.*` keys + code (Task 7, 9); handler moved to `core` module (Task 10); `order = 8` (Task 10); Jackson `WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS` disabled (Task 10b); `messages_ru.properties` encoding note corrected to UTF-8 (Task 7); REST `/status` explicitly documented as public (design).
- CONCERN: ISO-8601 assertions added via dedicated `JacksonConfigurationTest` (Task 6 Step 1a); defensive-copy mutation test added (Task 4 Step 3a); router-level try/catch documented (Task 10 — no local error handling needed); test gaps closed (id-non-duplication test, OFFLINE-after-clock-skew via `coerceAtLeast`); `Clock` injected into handler for `now` consistency (Task 10); per-module compileKotlin in Task 8; broader grep scope in Task 8.
- SUGGESTION: env-var form in `status.cameras.disabled` text; `status.empty` i18n key replacing hardcoded `"(none)"`; `coerceAtLeast(Duration.ZERO)` in formatter fallback.

Deferred to disputed-issue discussion (see iter-1 review log): WebTestClient strategy, 4096-char Telegram limit, HTML double-escape audit scope, formatter test strategy (mocks vs real bundle), `lastUpdatedAt` field addition.

### Spec coverage

| Spec requirement | Task | Status |
|---|---|---|
| Replace REST `/statistics` with `/status` | Task 6 (create), Task 8 (delete) | ✓ |
| Add `cameras` field with HEALTHY/OFFLINE/lastSeenAt/offlineFor | Task 2 + Task 3 + Task 5 | ✓ |
| Telegram OWNER-only `/status` command | Task 10 | ✓ |
| HTML format with `<pre>` tables | Task 9 | ✓ |
| `SignalLossMonitorTask.snapshotStates()` | Task 4 | ✓ |
| `StatusService` shared between REST and Telegram | Task 5 + 6 + 10 | ✓ |
| Sorting: OFFLINE first, DEAD first | Task 5 (`compareBy` predicates) | ✓ |
| i18n ru+en keys `status.*` | Task 7 | ✓ |
| `signal-loss.enabled=false` → `monitoringEnabled=false` + disabled marker | Task 5 (logic) + Task 9 (render) + Task 12 step 5 (sanity) | ✓ |
| HTML escape camId / id | Task 9 (`escape()` helper + test) | ✓ |
| Use `user.olsonCode` for time formatting | Task 10 + Task 9 (test for tz) | ✓ |
| `command.status.description` in i18n | Task 7 | ✓ |
| Tests: StatusServiceTest, StatusControllerTest, snapshotStates test, StatusMessageFormatterTest, StatusCommandHandlerTest | Tasks 4–10 | ✓ |
| No new env vars or DB migrations | n/a (out of scope) | ✓ |

### Placeholder scan

- No "TBD", "TODO", "fill in later", "implement appropriately" found.
- Every code step contains full code, not "similar to above".
- Task 10 Step 4 has a fallback for ktgbotapi `reply` signature variation — this is documented contingency, not a placeholder.

### Type consistency

- `StatusService.collect(): StatusResponse` — consistent across Tasks 5, 6, 10.
- `StatusMessageFormatter.format(snapshot, language, zone, now)` — consistent in Tasks 9 and 10.
- `CameraStatusDto(camId, state, lastSeenAt, offlineFor)` — consistent in Tasks 2, 5, 9, 10.
- `SignalLossMonitorTask.snapshotStates(): Map<String, CameraSignalState>` — consistent in Tasks 4 and 5.
- `CamerasSection(monitoringEnabled, items)` — consistent in Tasks 3, 5, 9.
- i18n keys defined in Task 7 are used verbatim in Task 9 formatter — names match (`status.cameras.line.online`, `status.cameras.line.offline`, `status.servers.line.alive`, `status.servers.line.dead`, etc.).

No inconsistencies found.
