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

**Important:** `StatusCommandHandler` lives in `modules/core` (NOT `modules/telegram`). The
`telegram` module does NOT depend on `core`, but `core → telegram`, so the handler is placed in
`core` to import `StatusService`. `CommandHandler` interface is imported from `telegram`.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt`

- [ ] **Step 1: Write failing test StatusCommandHandlerTest.kt**

```kotlin
package ru.zinin.frigate.analyzer.core.bot.handler

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StatusCommandHandlerTest {
    private val statusService = mockk<StatusService>()
    private val formatter = mockk<StatusMessageFormatter>()
    private val userService = mockk<TelegramUserService>()
    private val clock = Clock.fixed(Instant.parse("2026-04-25T10:00:00Z"), ZoneOffset.UTC)
    private val handler = StatusCommandHandler(statusService, formatter, userService, clock)

    @Test
    fun `handler has correct command metadata`() {
        assertThat(handler.command).isEqualTo("status")
        assertThat(handler.requiredRole).isEqualTo(UserRole.OWNER)
        assertThat(handler.ownerOnly).isTrue()
        assertThat(handler.order).isEqualTo(8)
    }
}
```

Behavior-level integration is exercised in `StatusMessageFormatterTest` and `StatusServiceTest`.
We do not unit-test the actual `reply()` call because it goes through ktgbotapi's
`BehaviourContext`, which is hard to mock in isolation. Manual sanity check covers this —
see Task 12. Note we also do NOT add an error-path test asserting `common.error.generic`:
`FrigateAnalyzerBot.registerRoutes()` wraps handler dispatch in try/catch at the router level
(see existing handlers like `VersionCommandHandler` which have no local try/catch).

- [ ] **Step 2: Run test, expect compile failure**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusCommandHandlerTest
```

Expected: FAIL — `StatusCommandHandler` unresolved.

- [ ] **Step 3: Implement StatusCommandHandler in `core/bot/handler`**

```kotlin
package ru.zinin.frigate.analyzer.core.bot.handler

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatter
import java.time.Clock
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusCommandHandler(
    private val statusService: StatusService,
    private val formatter: StatusMessageFormatter,
    private val userService: TelegramUserService,
    private val clock: Clock,
) : CommandHandler {
    override val command: String = "status"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 8

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        val snapshot = statusService.collect()
        val zone = userService.getUserZone(message.chat.id.chatId.long)
        val language = user?.languageCode ?: "en"
        val text =
            formatter.format(
                snapshot = snapshot,
                language = language,
                zone = zone,
                now = Instant.now(clock),
            )
        sendTextMessage(
            message.chat,
            text,
            parseMode = HTMLParseMode,
            replyParameters = ReplyParameters(message.metaInfo),
        )
    }
}
```

User zone comes from `TelegramUserService.getUserZone(chatId)` — same pattern used by
`TimezoneCommandHandler` and `ExportCommandHandler`. `TelegramUserDto` does NOT contain
`olsonCode`, only `languageCode`. `order = 8` is the first unused slot after Notifications=7
(value 6 is taken by `LanguageCommandHandler`). `Clock` is injected so tests see the same
`now` as `StatusService`.

**Send-API choice:** `sendTextMessage(chat, ..., replyParameters = ReplyParameters(message.metaInfo))`
is the same pattern used by `TimezoneCommandHandler` and `TelegramNotificationSender` (verified to
work with the project's ktgbotapi version). The simpler `reply(message, text, parseMode = ...)`
overload is **not used anywhere in the project with `parseMode`** — `VersionCommandHandler` uses
`reply(message, text)` without parseMode. To avoid a runtime-only contingency, we adopt the
known-good API up front.

- [ ] **Step 4: Run test, expect pass**

Delegate to build-runner agent:

```
./gradlew :frigate-analyzer-core:test --tests StatusCommandHandlerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandlerTest.kt
git commit -m "feat(core): add /status command handler (OWNER only, in core module)"
```

---

### Task 10b: Configure ObjectMapper for ISO-8601 (Instant/Duration)

`JacksonConfiguration.objectMapper()` does NOT currently disable
`WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS`, so `Instant`/`Duration` in
`StatusResponse` would be serialised as numeric timestamps instead of the ISO-8601 strings
promised by the design. Fix:

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt`

- [ ] **Step 1: Update JacksonConfiguration**

```kotlin
package ru.zinin.frigate.analyzer.core.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper =
        JsonMapper
            .builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .findAndAddModules()
            .build()
}
```

- [ ] **Step 2: JacksonConfigurationTest already added in Task 6 Step 1a** — run it now:

```
./gradlew :frigate-analyzer-core:test --tests JacksonConfigurationTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfigurationTest.kt
git commit -m "fix(core): serialise Instant/Duration as ISO-8601 strings (not numeric timestamps)"
```

---

### Task 11: Full build + ktlint

Verify the whole project compiles and tests pass.

- [ ] **Step 1: Run ktlint format** — delegate to build-runner agent:

```
./gradlew ktlintFormat
```

Expected: success. Stage any formatting changes.

- [ ] **Step 2: Stage any formatting changes**

```bash
git status --short
git add -u
```

If there are changes (e.g. import reordering), commit:

```bash
git commit -m "style: apply ktlintFormat to /status changes"
```

If nothing changed, skip the commit step.

- [ ] **Step 3: Full build** — delegate to build-runner agent:

```
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

If failures occur:
- Compile errors → fix and re-run
- Test failures → fix the underlying issue (not the test) and re-run
- ktlint check failures → run `./gradlew ktlintFormat` and re-stage

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

Per project CLAUDE.md: "After implementation: run superpowers:code-reviewer agent first. Fix critical comments, repeat until clean."

- [ ] **Step 1: Run code review**

Invoke `superpowers:requesting-code-review` skill targeting the branch diff against `master`. Address all CRITICAL and HIGH-severity findings inline (apply fixes, re-run affected tests, commit).

- [ ] **Step 2: Re-run build after fixes** — delegate to build-runner agent:

```
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Final status**

```bash
git log --oneline master..HEAD
git diff --stat master..HEAD
```

Verify the branch contains a coherent set of commits ready for PR review.

Note: per global CLAUDE.md, before creating a PR you must `git rm` files in `docs/superpowers/` and commit — those design/plan docs are not meant to appear in the PR diff.

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
