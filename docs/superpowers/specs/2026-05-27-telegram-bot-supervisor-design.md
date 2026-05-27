# Telegram Bot Supervisor — Resilient Long-Polling

**Status:** Design approved · ready for implementation plan
**Issue:** https://github.com/zinin/frigate-analyzer/issues/34
**Branch:** `fix/telegram-bot-supervisor`
**Date:** 2026-05-27

## 1. Problem

`FrigateAnalyzerBot.start()` (`modules/telegram/.../bot/FrigateAnalyzerBot.kt:83-117`) catches
exceptions in its bootstrap-and-polling block but does **not** retry. A single transient failure
(socket timeout on `bot.getMe()`, network blip during `buildBehaviourWithLongPolling().join()`)
leaves the bot permanently unresponsive until the JVM is restarted. Production incident: socket
timeout at 17:02:09 on first `getMe()` call → 15 hours of silence despite proxy recovery.

## 2. Goal

Make the Telegram bot survive transient network/API failures by reconnecting automatically with
bounded exponential backoff, and expose the reconnect state via Spring Actuator so an operator
can detect a sustained outage without tailing logs.

Non-goals:

- Automatic JVM restart on `DOWN` (matches existing `WatchRecordsTask` policy — operator action).
- Micrometer metrics (project has none).
- Externally configurable thresholds (project policy: hardcoded constants — see
  `.claude/rules/pipeline.md` §"Supervision"; same rationale here, single-deployment app).
- Heartbeat-poll via `getMe` (stable polling uptime is a sufficient signal).
- Jitter in backoff (single-instance bot, no thundering herd).

## 3. Architecture

Three components, separation of concerns analogous to
`WatchRecordsTask` + `WatchRecordsLoop` + `WatchRecordsTaskHealthIndicator`:

```
FrigateAnalyzerBot              TelegramBotSupervisor             TelegramBotHealthIndicator
─────────────────────           ─────────────────────             ──────────────────────────
• registerRoutes (public)       • @PostConstruct / @PreDestroy   • @Profile("!test")
• registerDefaultCommands       • botScope + supervisorJob       • health() →
• registerOwnerCommandsIf...    • runSupervised retry-loop          supervisor.computeHealth(now)
• onOwnerActivated (own scope)  • backoff + health state
```

### 3.1 FrigateAnalyzerBot — what changes

- **Removed:** `@PostConstruct fun start()`, `@PreDestroy fun stop()`, `botScope` field used for
  long-polling.
- **Method-signature changes (visibility/shape so supervisor can drive them):**
  - `suspend fun registerRoutes(context: BehaviourContext)` — *was* `private suspend fun BehaviourContext.registerRoutes()`. Converted from member-extension to a regular method that takes
    `BehaviourContext` as an explicit parameter and delegates to `with(context) { … }` internally.
    This lets the supervisor call it cleanly as
    `bot.buildBehaviourWithLongPolling { frigateAnalyzerBot.registerRoutes(this) }` without the
    `with(...) { with(...) { … } }` nesting required to invoke a member-extension from another
    class.
  - `suspend fun registerDefaultCommands()` — visibility raised from `private` to public; body
    unchanged (existing per-language `setMyCommands` loop + WARN-log on failure).
  - `suspend fun registerOwnerCommandsIfPossible()` — *new* public method. Merges the existing
    owner-lookup block from `start()` lines 96–106 with the call to the still-private
    `registerOwnerCommands(chatId)`. Catches its own exceptions (WARN-log, no propagation) so a
    DB blip during owner lookup does not trigger supervisor backoff.
- **`onOwnerActivated` EventListener:** stays in `FrigateAnalyzerBot`. It is an event-driven owner
  registration, not part of polling lifecycle, and entangling it with `botScope` would couple two
  concerns. It gets its own small scope:
  - `private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`
  - `@PreDestroy fun stopEventScope() = eventScope.cancel()`.

### 3.2 TelegramBotSupervisor — new component

Owns: polling lifecycle, retry-loop, backoff state, health state.

Location: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`.

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramBotSupervisor(
    private val bot: TelegramBot,
    private val frigateAnalyzerBot: FrigateAnalyzerBot,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineName("telegram-bot-supervisor"),
    )

    @Volatile internal var supervisorJob: Job? = null
    @Volatile internal var startupAt: Instant? = null
    @Volatile internal var lastAttemptAt: Instant? = null
    @Volatile internal var lastPollingStartAt: Instant? = null
    @Volatile internal var lastStableAt: Instant? = null   // last time polling >= STABLE_THRESHOLD
    @Volatile internal var lastFailure: Throwable? = null
    @Volatile internal var lastFailureAt: Instant? = null
    @Volatile internal var consecutiveFailures: Long = 0
    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF

    @PostConstruct fun start() { /* launch runSupervised */ }
    @PreDestroy fun shutdown() { /* bound-join, then cancel scope */ }
    internal suspend fun stopAndJoin() { /* test helper */ }
    internal suspend fun runSupervised() { /* see §4 */ }
    fun computeHealth(now: Instant): Health { /* see §5 */ }
}
```

### 3.3 TelegramBotHealthIndicator — new component

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@Profile("!test")
class TelegramBotHealthIndicator(
    private val supervisor: TelegramBotSupervisor,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health = supervisor.computeHealth(Instant.now(clock))
}
```

Rationale for `@Profile("!test")`: same as `WatchRecordsTaskHealthIndicator` — in tests the
supervisor never starts (`telegram.enabled=false` by default and/or no `TelegramBot` bean), so
without this guard `/actuator/health` would aggregate to `DOWN` and break
`FrigateAnalyzerApplicationTests.actuatorHealth()`.

## 4. Supervisor loop

```kotlin
internal suspend fun runSupervised() {
    currentBackoff = INITIAL_BACKOFF
    while (currentCoroutineContext().isActive) {
        val attemptStart = clock.instant()
        lastAttemptAt = attemptStart
        try {
            bot.getMe()                                          // transient → retried
            frigateAnalyzerBot.registerDefaultCommands()         // best-effort (own try inside)
            frigateAnalyzerBot.registerOwnerCommandsIfPossible() // best-effort (own try inside)
            lastPollingStartAt = clock.instant()
            bot.buildBehaviourWithLongPolling {
                frigateAnalyzerBot.registerRoutes(this)
            }.join()
            onAttemptEnded(success = true, attemptStart, failure = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "Telegram bot bootstrap/polling failed; backoff=${currentBackoff.toMillis()}ms"
            }
            onAttemptEnded(success = false, attemptStart, failure = e)
        }
        delay(currentBackoff.toMillis())
        currentBackoff = nextBackoff(currentBackoff)
    }
}

private fun onAttemptEnded(success: Boolean, attemptStart: Instant, failure: Throwable?) {
    val duration = Duration.between(attemptStart, clock.instant())
    if (success) {
        // Clean polling exit (rare; library normally throws on disconnect).
        consecutiveFailures = 0
        currentBackoff = INITIAL_BACKOFF
        lastStableAt = clock.instant()
    } else {
        lastFailure = failure
        lastFailureAt = clock.instant()
        if (duration >= STABLE_THRESHOLD) {
            // Worked long enough to count as a stable run — first fresh failure, reset backoff.
            // lastStableAt = now (the moment of crash), not attemptStart: an attempt that ran
            // for an hour should leave a fresh stable timestamp so HEALTH_STALENESS (5 min) is
            // measured from "just now", not from "1 hour ago".
            consecutiveFailures = 1
            currentBackoff = INITIAL_BACKOFF
            lastStableAt = clock.instant()
        } else {
            consecutiveFailures++
        }
    }
}

private fun nextBackoff(c: Duration): Duration = minOf(c.multipliedBy(2), MAX_BACKOFF)
```

### 4.1 Why `bot.getMe()` is inside the retry block

The reported incident died on `getMe()`. Excluding it from the retry scope would leave the bug
unfixed. The call is idempotent and cheap (≤1 KB response), so retrying it on every reconnect
is harmless.

### 4.2 Why `registerDefaultCommands` / `registerOwnerCommandsIfPossible` keep their own try/catch

Both call `setMyCommands` multiple times (per language). A `setMyCommands` 429 or 500 should not
trigger the supervisor's backoff — it would mean every command-table change costs 5–60s of
unrelated downtime. The existing pattern (try inside the method, log WARN, continue) is preserved.
Supervisor backoff is triggered only by `getMe` failure and `buildBehaviourWithLongPolling` exit.

### 4.3 Why measure attempt duration in the catch branch

Tracking `lastStableAt` in a parallel "stability watcher" coroutine is precise but complicates the
state machine and the tests. Measuring `Duration.between(attemptStart, now)` post-mortem gives the
same answer for the use cases that matter (was the polling alive long enough to be considered
stable?) with a simpler loop. The trade-off is that `lastStableAt` is updated only on attempt end,
not in real time during a still-running attempt — health uses `lastPollingStartAt` for the "is
running stably *right now*" check (see §5, branch 6).

## 5. Health states

`computeHealth(now: Instant): Health` — priority-ordered, first match wins:

| # | Condition | Status | `reason` detail |
|---|---|---|---|
| 1 | `supervisorJob == null \|\| !supervisorJob.isActive` | DOWN | `supervisor not active` |
| 2 | `lastStableAt == null && (consecutiveFailures >= STARTUP_FAILURE_THRESHOLD \|\| sinceStartup > STARTUP_GRACE)` | DOWN | `startup failed: N attempts / X` |
| 3 | `lastStableAt == null` (still in startup grace) | OUT_OF_SERVICE | `connecting... attempts=N` |
| 4 | `consecutiveFailures > 0 && (now - lastStableAt) > HEALTH_STALENESS` | DOWN | `stale: failing for X since lastStable=...` |
| 5 | `consecutiveFailures > 0` | OUT_OF_SERVICE | `in backoff (failures=N, backoff=Xms)` |
| 6 | `lastPollingStartAt != null && (now - lastPollingStartAt) >= STABLE_THRESHOLD` | UP | `healthy` |
| 7 | otherwise | OUT_OF_SERVICE | `connecting... (just (re)started, <STABLE_THRESHOLD)` |

Builder always exports the following details (in addition to `reason`):

- `lastPollingStartAt`, `lastStableAt`, `lastFailureAt` (or `"never"`)
- `lastFailure` (`"<Class>: <message-truncated-to-500>"`)
- `consecutiveFailures`, `currentBackoffMs`, `startupAt`

### 5.1 Why branch 6 uses `lastPollingStartAt`, not `lastStableAt`

`lastStableAt` is updated only at attempt end (§4.3). During a live, stable attempt we want UP, not
"connecting…". `lastPollingStartAt` is set immediately after the bootstrap succeeds and remains
fixed while the attempt runs, so `now − lastPollingStartAt >= STABLE_THRESHOLD` correctly answers
"is this attempt already past the stable-uptime threshold". When the attempt fails, branch 4 or 5
takes over (consecutiveFailures > 0).

### 5.2 Why HEALTH_STALENESS is 5 min for the bot (vs. 2 min in WatchRecordsTask)

`WatchRecordsTask` expects events (file system notifications) constantly during normal operation,
so a 2-minute silence is meaningful. The Telegram bot is event-driven — a quiet chat may produce
zero traffic for hours, and the supervisor itself has no per-iteration heartbeat. 5 min covers
several full backoff cycles (5→10→20→40→60→60→60→60 ≈ 4.5 min) so genuinely transient outages
do not flap into DOWN.

## 6. Constants (hardcoded — project policy)

```kotlin
private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private val STABLE_THRESHOLD: Duration = Duration.ofSeconds(60)
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(5)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L
private val SHUTDOWN_JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)
```

| Constant | Meaning |
|---|---|
| `INITIAL_BACKOFF` | Pause after first failure before retry. Small enough to recover from a momentary blip without long downtime, large enough not to rate-limit ourselves. |
| `MAX_BACKOFF` | Ceiling for the exponential `×2` growth. Progression caps at `5→10→20→40→60→60…`. |
| `STABLE_THRESHOLD` | An attempt that runs at least this long is "stable": resets backoff on the next failure and flips health to UP. |
| `HEALTH_STALENESS` | If we are still in backoff and have not had a stable attempt for this long, flip to DOWN. |
| `STARTUP_GRACE` | After process start, allow this long for the very first connect before declaring startup failure. |
| `STARTUP_FAILURE_THRESHOLD` | OR-condition for startup failure: if this many consecutive failures occur before any stable attempt, declare DOWN even during the grace window (caught: invalid token, dead proxy). |
| `SHUTDOWN_JOIN_TIMEOUT` | Bound the `@PreDestroy` `join()` on the supervisor coroutine so Spring's default 30-second shutdown does not interrupt us mid-cleanup. |

## 7. Lifecycle and shutdown

Pattern copied from `WatchRecordsTask.shutdown()`:

```kotlin
@PreDestroy
fun shutdown() {
    logger.info { "Shutting down Telegram bot supervisor..." }
    supervisorJob?.cancel()
    val joined = runBlocking {
        withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT.toMillis()) {
            supervisorJob?.join()
            true
        }
    }
    if (joined == null) {
        logger.warn {
            "Supervisor did not exit within ${SHUTDOWN_JOIN_TIMEOUT.toSeconds()}s; forcing"
        }
    }
    scope.cancel()
    logger.info { "Telegram bot supervisor stopped" }
}
```

`@PostConstruct fun start()` mirrors `FrigateAnalyzerBot.start()` today (not
`ApplicationReadyEvent`) — the only bean dependency is `TelegramBot`, available at construction
time, and `FrigateAnalyzerBot` itself is already wired via constructor injection.

## 8. Tests

### 8.1 TelegramBotSupervisorTest (new)

Fixtures: `runTest { ... }` with `StandardTestDispatcher`, fixed `Clock`, mocked `TelegramBot`,
mocked `FrigateAnalyzerBot`.

- `retries after exception` — `bot.getMe()` throws 3 times then succeeds; verify polling runs and
  backoff progression `5s → 10s → 20s`.
- `stable attempt resets backoff` — first attempt runs ≥ `STABLE_THRESHOLD`, then fails;
  `consecutiveFailures == 1`, `currentBackoff == INITIAL_BACKOFF`, `lastStableAt` updated.
- `quick crash grows backoff` — repeated crashes < `STABLE_THRESHOLD`; `consecutiveFailures`
  increments, `currentBackoff` grows monotonically until `MAX_BACKOFF`.
- `cancellation propagates cleanly` — `supervisorJob.cancel()`; no extra failure-bookkeeping, no
  backoff delay logged.
- `registration failures do not trigger backoff` — `registerDefaultCommands` throws inside its own
  try/catch; supervisor continues to `buildBehaviourWithLongPolling`; `consecutiveFailures == 0`.
- `computeHealth — each of the 7 branches`:
  - supervisor stopped → DOWN
  - never stable, threshold hit → DOWN
  - never stable, in grace → OUT_OF_SERVICE
  - failing for > HEALTH_STALENESS since lastStableAt → DOWN
  - failing but recent stable → OUT_OF_SERVICE
  - currently polling > STABLE_THRESHOLD, no failures → UP
  - currently polling < STABLE_THRESHOLD → OUT_OF_SERVICE

### 8.2 TelegramBotHealthIndicatorTest (new)

Mirror of `WatchRecordsTaskHealthIndicatorTest`: mock supervisor, call `indicator.health()`,
verify it delegates to `supervisor.computeHealth(clock.now)`.

### 8.3 No new integration tests

The polling is mocked at the `TelegramBot` boundary; no `WireMock` for Telegram API is set up in
the project today and we are not introducing one for a behavioural test that the unit tests
already cover.

## 9. Files

```
modules/telegram/src/main/kotlin/.../bot/supervisor/
  TelegramBotSupervisor.kt              [new]
  TelegramBotHealthIndicator.kt         [new]

modules/telegram/src/main/kotlin/.../bot/
  FrigateAnalyzerBot.kt                 [edit: remove start/stop/botScope, raise visibility,
                                         add registerOwnerCommandsIfPossible, add eventScope]

modules/telegram/src/test/kotlin/.../bot/supervisor/
  TelegramBotSupervisorTest.kt          [new]
  TelegramBotHealthIndicatorTest.kt     [new]

.claude/rules/telegram.md                [edit: add supervisor + indicator rows, supervision note]
```

## 10. Risks and open questions

- **`buildBehaviourWithLongPolling().join()` exits cleanly without exception?** The library
  uses `runCatching` in the polling loop (see `.claude/rules/telegram-timeout-bug.md`); some
  errors may be swallowed and produce a clean return. In that case the loop counts it as success
  and immediately reconnects with `INITIAL_BACKOFF` — acceptable behaviour. If the swallowed
  error is permanent (e.g., revoked token), we will reconnect endlessly with `INITIAL_BACKOFF`
  and never flip to DOWN. Mitigation: log every clean return at WARN so this is observable in
  logs. Out of scope to detect at runtime — would require parsing library internals.
- **`@PostConstruct` on supervisor vs. `ApplicationReadyEvent`:** sticking with the current
  bot's `@PostConstruct` model for minimum churn. If a future module needs the bot to be live
  before `ApplicationReadyEvent` fires, this is already the case today.
- **`registerOwnerCommands` after reconnect:** every reconnect re-registers commands. This is
  idempotent on Telegram's side, but adds N×languages × 2 (default + owner) API calls per
  reconnect cycle. At MAX_BACKOFF=60s that is at most ~10 extra calls/minute, well below any
  rate limit. Acceptable.

## 11. Out of scope (deferred to separate work)

- Micrometer metrics around reconnect counts / backoff distribution.
- Configurable thresholds via `TelegramProperties`.
- Auto-restart on sustained DOWN (process exit / autoheal sidecar).
- Heartbeat polling via `getMe` as an explicit live-check.
- Jitter in backoff.
