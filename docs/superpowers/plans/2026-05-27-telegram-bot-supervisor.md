# Telegram Bot Supervisor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Telegram bot survive transient network failures by reconnecting automatically with bounded exponential backoff, and expose the reconnect state via Spring Actuator.

**Architecture:** Extract polling lifecycle from `FrigateAnalyzerBot` into a new `TelegramBotSupervisor` that owns the polling scope, runs a supervised retry-loop with 5s→60s exponential backoff, and tracks state for a `TelegramBotHealthIndicator` that exports UP/OUT_OF_SERVICE/DOWN. `FrigateAnalyzerBot` becomes a "registrar of routes" — its public methods are called by the supervisor on each (re)connect.

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.0.6 (Actuator), kotlinx.coroutines, ktgbotapi 33.1.0, JUnit 5, mockk, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`

**Module:** `modules/telegram`

---

## File Structure

**New files:**

| File | Responsibility |
|---|---|
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt` | Polling lifecycle, retry-loop, backoff state, health-state fields, `computeHealth(now)`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicator.kt` | Thin Spring Actuator adapter — delegates to `supervisor.computeHealth(Instant.now(clock))`. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt` | Unit tests for retry-loop behaviour and `computeHealth` branches. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicatorTest.kt` | Unit test that indicator delegates to supervisor. |

**Modified files:**

| File | Responsibility change |
|---|---|
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` | Loses polling `@PostConstruct`/`@PreDestroy`/`botScope`. Exposes `registerRoutes(ctx)`, `registerDefaultCommands()`, `registerOwnerCommandsIfPossible()` as public suspend methods. Gets a small dedicated `eventScope` for the existing `onOwnerActivated` listener. |
| `.claude/rules/telegram.md` | Adds two component rows; adds a short "Bot supervision" subsection. |

---

## Task 1: Refactor FrigateAnalyzerBot to expose methods (no behavior change)

**Goal:** Pure structural refactor. The bot continues to start polling via its own `@PostConstruct` until Task 9. We only reshape the methods so the (yet-to-exist) supervisor can call them.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

**Why no test step:** there is no existing test for `FrigateAnalyzerBot`. The compiler is our safety net; behaviour is verified by the manual smoke step at the end.

- [ ] **Step 1.1: Convert `registerRoutes` from member-extension to regular method**

Find the existing private member-extension declaration at line 121:

```kotlin
@Suppress("LongMethod", "CyclomaticComplexMethod")
private suspend fun BehaviourContext.registerRoutes() {
    sortedHandlers.forEach { handler ->
        ...
    }
    ...
}
```

Replace with a regular method that takes `BehaviourContext` as a parameter and delegates via `with`:

```kotlin
@Suppress("LongMethod", "CyclomaticComplexMethod")
suspend fun registerRoutes(context: BehaviourContext) =
    with(context) {
        // Note: `bot` inside this block resolves to BehaviourContext.bot (same TelegramBot
        // instance as this@FrigateAnalyzerBot.bot via constructor injection). The implicit
        // receiver here is BehaviourContext. [A12]
        sortedHandlers.forEach { handler ->
            ...
        }
        ...
    }
```

Keep the body byte-for-byte the same (everything between the outer braces is now inside `with(context) { … }`). All inner `onCommand { … }`, `onDataCallbackQuery { … }`, `onContentMessage { … }` calls work unchanged because they are extensions on `BehaviourContext`, which `with(context)` brings into scope.

- [ ] **Step 1.2: Update the `start()` call site to match**

In `start()` (currently lines 108–111):

```kotlin
bot
    .buildBehaviourWithLongPolling {
        registerRoutes()
    }.join()
```

Change to:

```kotlin
bot
    .buildBehaviourWithLongPolling {
        registerRoutes(this)
    }.join()
```

- [ ] **Step 1.3: Raise visibility of `registerDefaultCommands`**

Find the existing declaration (currently line 311):

```kotlin
private suspend fun registerDefaultCommands() {
    ...
}
```

Drop `private`:

```kotlin
suspend fun registerDefaultCommands() {
    ...
}
```

Body unchanged.

- [ ] **Step 1.4: Add new public method `registerOwnerCommandsIfPossible`**

Add this method right after `registerDefaultCommands()` (i.e. before `registerOwnerCommands(chatId)`):

```kotlin
suspend fun registerOwnerCommandsIfPossible() {
    try {
        // Case-insensitive lookup so owner-command registration survives a casing
        // difference between `TELEGRAM_OWNER` env and the DB-stored username.
        val owner =
            userService
                .findByUsernameIgnoreCase(properties.owner)
                ?.takeIf { it.status == UserStatus.ACTIVE }
        if (owner?.chatId != null) {
            registerOwnerCommands(owner.chatId)
        }
    } catch (e: CancellationException) {
        // [A3] Rethrow cancellation so the supervisor's shutdown is responsive — `Exception`
        //      catches CancellationException in Kotlin coroutines.
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Failed to look up owner for command registration" }
    }
}
```

**Step 1.4a: Also rethrow `CancellationException` in the existing `registerDefaultCommands` and `registerOwnerCommands` methods** so the supervisor's cancel can propagate through `setMyCommands` suspend points (FrigateAnalyzerBot.kt:311 and :332). Same shape: `catch (e: CancellationException) { throw e }` before the existing `catch (e: Exception)`.

- [ ] **Step 1.5: Replace the inline owner-lookup block in `start()` with a call to the new method**

The block at lines 94–106 currently reads:

```kotlin
try {
    // Case-insensitive lookup so owner-command registration survives a casing
    // difference between `TELEGRAM_OWNER` env and the DB-stored username.
    val owner =
        userService
            .findByUsernameIgnoreCase(properties.owner)
            ?.takeIf { it.status == UserStatus.ACTIVE }
    if (owner?.chatId != null) {
        registerOwnerCommands(owner.chatId)
    }
} catch (e: Exception) {
    logger.warn(e) { "Failed to look up owner for command registration" }
}
```

Replace it with a single call:

```kotlin
registerOwnerCommandsIfPossible()
```

- [ ] **Step 1.6: Add `eventScope` for `onOwnerActivated`**

Find the existing `botScope` field at line 78:

```kotlin
private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

Add a second scope directly below it:

```kotlin
private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

Change `onOwnerActivated` (currently around line 305) from `botScope.launch` to `eventScope.launch`,
**keeping the existing `registerOwnerCommands(event.chatId)` call** (per [D5] decision —
reviewers argued correctly that `event.chatId` is the authoritative source from the event publisher,
making a re-lookup via DB redundant):

```kotlin
@EventListener
fun onOwnerActivated(event: OwnerActivatedEvent) {
    eventScope.launch {
        registerOwnerCommands(event.chatId)  // [D5] keep direct chatId — no DB round-trip
    }
}
```

`registerOwnerCommandsIfPossible()` (added in Step 1.4) is only called from the supervisor's
reconnect loop, where no `chatId` is available and a DB lookup is the only option.

Update `stop()` (currently around line 353) to also cancel the event scope:

```kotlin
@PreDestroy
fun stop() {
    logger.info { "Stopping Telegram bot..." }
    botScope.cancel()
    eventScope.cancel()
    logger.info { "Telegram bot stopped" }
}
```

- [ ] **Step 1.7: Build to verify the refactor compiles cleanly**

Delegate to build-runner agent: `./gradlew :frigate-analyzer-telegram:compileKotlin :frigate-analyzer-telegram:compileTestKotlin`

Expected: BUILD SUCCESSFUL. If ktlint fires, run `./gradlew ktlintFormat` and retry.

- [ ] **Step 1.8: Run the existing telegram test suite**

Delegate to build-runner agent: `./gradlew :frigate-analyzer-telegram:test`

Expected: all tests pass. The refactor changed method shapes but no behaviour.

- [ ] **Step 1.9: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "refactor(telegram): expose bot routing methods for upcoming supervisor

- registerRoutes converted from member-extension to regular method taking
  BehaviourContext, so the supervisor can call it without with(...)-nesting
- registerDefaultCommands visibility raised to public (unchanged body)
- New registerOwnerCommandsIfPossible bundles the owner lookup + register
  block so a single helper covers all callers
- onOwnerActivated migrates to a dedicated eventScope so it stays alive
  when the polling botScope is replaced by the supervisor (Task 9)

No behavior change — start()/stop() still drive polling."
```

---

## Task 2: TelegramBotSupervisor scaffold + first test (computeHealth → DOWN when not started)

**Goal:** Create the supervisor class file with state fields, constants, lifecycle stubs, and a `computeHealth` that returns `DOWN` (branch 1 — supervisor not active). One test pins down the contract.

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

- [ ] **Step 2.1: Write the failing test for branch 1 (supervisor not active)**

Create test file with this content:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import dev.inmo.tgbotapi.bot.TelegramBot
import ru.zinin.frigate.analyzer.telegram.bot.FrigateAnalyzerBot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotSupervisorTest {
    private val bot = mockk<TelegramBot>(relaxed = true)
    private val frigateAnalyzerBot = mockk<FrigateAnalyzerBot>(relaxed = true)
    // [AUTO-2] `runner` is the D2 adapter dependency. Default is a relaxed mock; individual
    //          tests override with a hand-rolled fake when they need to drive specific
    //          polling-loop behaviour (Task 4's stable-attempt test).
    private val defaultRunner = mockk<TelegramLongPollingRunner>(relaxed = true)
    private val now = Instant.parse("2026-05-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun newSupervisor(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
        runner: TelegramLongPollingRunner = defaultRunner,
    ) = TelegramBotSupervisor(
        runner = runner,
        bot = bot,
        frigateAnalyzerBot = frigateAnalyzerBot,
        clock = clock,
        dispatcher = dispatcher,
    )

    // ----- computeHealth branches -----

    @Test
    fun `computeHealth returns DOWN when supervisor not started`() {
        val supervisor = newSupervisor()
        val health = supervisor.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertEquals("supervisor not active", health.details["reason"])
    }
}
```

- [ ] **Step 2.2: Run the test to verify it fails (class does not exist)**

Delegate to build-runner agent:
`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: FAIL — unresolved reference `TelegramBotSupervisor`.

- [ ] **Step 2.3: Create the supervisor scaffold**

Create `TelegramBotSupervisor.kt` with this content:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.FrigateAnalyzerBot
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private val STABLE_THRESHOLD: Duration = Duration.ofSeconds(60)
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(5)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L
private val SHUTDOWN_JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramBotSupervisor(
    // [AUTO-2] runner is the D2 adapter dependency, FIRST constructor parameter per design §3.2
    private val runner: TelegramLongPollingRunner,
    private val bot: TelegramBot,
    private val frigateAnalyzerBot: FrigateAnalyzerBot,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.IO.limitedParallelism(1),
) {
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("telegram-bot-supervisor"))
    // [A6] Production default is Dispatchers.IO.limitedParallelism(1) for parity with
    //      WatchRecordsTask. Long-polling is I/O-bound and the supervisor is single-threaded
    //      by design. Constructor takes `dispatcher` for testability (StandardTestDispatcher).

    @Volatile internal var supervisorJob: Job? = null

    @Volatile internal var startupAt: Instant? = null

    @Volatile internal var lastAttemptAt: Instant? = null

    @Volatile internal var lastPollingStartAt: Instant? = null

    @Volatile internal var lastStableAt: Instant? = null

    @Volatile internal var lastFailure: Throwable? = null

    @Volatile internal var lastFailureAt: Instant? = null

    @Volatile internal var consecutiveFailures: Long = 0

    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF

    @PostConstruct
    fun start() {
        // Filled in Task 7.
    }

    @PreDestroy
    fun shutdown() {
        // Filled in Task 7.
        scope.cancel()
    }

    fun computeHealth(now: Instant): Health {
        val builder = baseBuilder()
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }
        // Remaining branches filled in Task 6.
        return builder.outOfService().withDetail("reason", "not implemented yet").build()
    }

    private fun baseBuilder(): Health.Builder =
        Health
            .Builder()
            .withDetail("startupAt", startupAt?.toString() ?: "never")
            .withDetail("lastAttemptAt", lastAttemptAt?.toString() ?: "never")
            .withDetail("lastPollingStartAt", lastPollingStartAt?.toString() ?: "never")
            .withDetail("lastStableAt", lastStableAt?.toString() ?: "never")
            .withDetail("lastFailureAt", lastFailureAt?.toString() ?: "never")
            .withDetail("consecutiveFailures", consecutiveFailures)
            .withDetail("currentBackoffMs", currentBackoff.toMillis())
            .also { b ->
                lastFailure?.let {
                    b.withDetail(
                        "lastFailure",
                        "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
                    )
                }
            }
}
```

- [ ] **Step 2.4: Run the test to verify it passes**

Delegate to build-runner agent:
`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: PASS (`computeHealth returns DOWN when supervisor not started`).

- [ ] **Step 2.5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "feat(telegram): TelegramBotSupervisor scaffold with branch-1 health

Class file + first health-state branch (supervisor not active → DOWN).
@PostConstruct/@PreDestroy bodies are stubs; runSupervised, the remaining
health branches and the cutover land in later tasks.

Not yet wired — FrigateAnalyzerBot still owns polling."
```

---

## Task 2.5: TelegramLongPollingRunner adapter [D2]

**Goal:** Introduce the `TelegramLongPollingRunner` adapter (interface + `KtgBotApiLongPollingRunner`
impl) so the supervisor doesn't talk to `bot.buildBehaviourWithLongPolling` directly. This closes
the test-mocking fragility (no more `mockkStatic` on a library top-level function) and gives the
supervisor a `Throwable?` return for clean-vs-failed discrimination.

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramLongPollingRunner.kt`

**Content:** See spec §3.3. The interface + production impl together fit ~25 lines. No unit test
for the runner — it is a tiny wrapper whose only branches (clean return vs caught exception) are
exercised end-to-end via the supervisor's behavioural tests.

**Test-side implication:** subsequent supervisor tests construct the supervisor with a
hand-rolled fake runner (`object : TelegramLongPollingRunner { override suspend fun run(...) =
... }`), NOT with `mockkStatic`. This obsoletes the [A4] deferred fix entirely.

**Commit:**
```
feat(telegram): TelegramLongPollingRunner adapter

Isolates ktgbotapi's buildBehaviourWithLongPolling so the supervisor
can be tested without mockkStatic on a library top-level function
(reviewers flagged the class name + 3-vs-6 parameter count as fragile).
Production impl uses coroutineScope { ... } so the polling job is a
child of our scope; structured concurrency propagates child failures
through join(). Adapter returns Throwable? — null on clean exit.
```

---

## Task 3: runSupervised — retry-on-failure with exponential backoff

**Goal:** Implement the supervised retry-loop body. After a failure the loop waits `currentBackoff`, doubles it (capped at `MAX_BACKOFF`), and retries. Cancellation propagates cleanly. Successful attempts (in this task: any non-throwing iteration) reset `currentBackoff` only via Task 4 logic; for now we just count failures.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

**Background note for the implementer:** `bot.buildBehaviourWithLongPolling { … }` is an extension function on `TelegramBot` returning a `Job`. To make it testable without a real Telegram connection, we mock it via `mockkStatic`. The actual extension entry point is `dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt`. We stub `bot.buildBehaviourWithLongPolling(...) ` to return a `Job` we control.

- [ ] **Step 3.1: Add the failing test for retry progression**

Append to `TelegramBotSupervisorTest.kt` (inside the class):

```kotlin
// ----- runSupervised retry-loop -----

@Test
fun `runSupervised retries with exponential backoff after getMe failures`() =
    runTest {
        // [A8] Use ticking clock from Task 4 so future tests adding longer attempts
        //      won't silently get duration=0 on a fixed clock.
        val supervisor = newSupervisorWithTickingClock(testScheduler)
        var attempts = 0
        coEvery { bot.getMe() } coAnswers {
            attempts++
            when (attempts) {
                1 -> throw RuntimeException("boom1")
                2 -> throw RuntimeException("boom2")
                3 -> throw RuntimeException("boom3")
                else -> throw CancellationException("test done — stop after first success path")
            }
        }

        val job = launch { supervisor.runSupervised() }
        // Advance enough to absorb 3 failure delays: 5 + 10 + 20 = 35 s (next would be 40)
        advanceTimeBy(35_000)
        runCurrent()
        // We've executed: attempt 1 fail, delay 5s, attempt 2 fail, delay 10s, attempt 3 fail, delay 20s
        // currentBackoff after each failure: 5→10, 10→20, 20→40
        assertEquals(40_000L, supervisor.currentBackoff.toMillis())
        assertEquals(3L, supervisor.consecutiveFailures)

        job.cancelAndJoin()
    }
```

Add the necessary imports at the top of the file (alphabetical, alongside the existing ones):

```kotlin
import io.mockk.coEvery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
```

- [ ] **Step 3.2: Run the test to verify it fails**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.runSupervised retries with exponential backoff after getMe failures"`

Expected: FAIL — `runSupervised` does not exist.

- [ ] **Step 3.3: Implement `runSupervised`**

Add to `TelegramBotSupervisor.kt` (alongside `computeHealth`):

```kotlin
internal suspend fun runSupervised() {
    currentBackoff = INITIAL_BACKOFF
    while (currentCoroutineContext().isActive) {
        val attemptStart = Instant.now(clock)         // [A9] consistent with WatchRecordsTask
        lastAttemptAt = attemptStart
        lastPollingStartAt = null  // [A5] clear stale stamp so health branch 2 won't match
                                   //      on a previous (failed) attempt's polling start.
        try {
            bot.getMe()
            frigateAnalyzerBot.registerDefaultCommands()
            frigateAnalyzerBot.registerOwnerCommandsIfPossible()
            lastPollingStartAt = Instant.now(clock)
            logger.info { "Telegram bot polling started" }  // [A11] observable reconnect log
            // [D2] Adapter returns Throwable? — null on clean exit, otherwise the cause from
            //      structured-concurrency propagation. We rethrow non-null to fall into catch.
            val cause = runner.run { frigateAnalyzerBot.registerRoutes(this) }
            if (cause != null) {
                throw cause
            }
            // Clean return from runner is unusual — library normally throws on disconnect.
            // Log at WARN with duration so a fast bogus return is distinguishable from a
            // long-running clean disconnect. (Spec §10 risk mitigation.) [A10]
            val attemptDuration = Duration.between(attemptStart, Instant.now(clock))
            logger.warn {
                "long-polling runner returned cleanly after $attemptDuration; reconnecting"
            }
            onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
            // [A1+D3] If the clean return was past STABLE_THRESHOLD, onAttemptEnded reset
            //         currentBackoff to INITIAL_BACKOFF — keep that. If it was a fast bogus
            //         return, onAttemptEnded already bumped consecutiveFailures, so grow
            //         currentBackoff exactly like in the catch branch.
            if (attemptDuration < STABLE_THRESHOLD) {
                currentBackoff = nextBackoff(currentBackoff)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "Telegram bot bootstrap/polling failed; " +
                    "next backoff=${currentBackoff.toMillis()}ms"
            }
            onAttemptEnded(success = false, attemptStart = attemptStart, failure = e)
            // nextBackoff only on failure — and after onAttemptEnded which may have reset
            // currentBackoff to INITIAL_BACKOFF for a stable-fail; in that case nextBackoff
            // takes 5s → 10s for the *second* failure (correct progression). [A1]
            currentBackoff = nextBackoff(currentBackoff)
        }
        delay(currentBackoff.toMillis())
    }
}

private fun onAttemptEnded(success: Boolean, attemptStart: Instant, failure: Throwable?) {
    // Full body lands in Task 4. For now: just count failures and record the throwable
    // so the retry-progression test in Task 3 can assert on consecutiveFailures.
    if (!success) {
        consecutiveFailures++
        lastFailure = failure
        lastFailureAt = clock.instant()
    }
}

private fun nextBackoff(current: Duration): Duration =
    minOf(current.multipliedBy(2), MAX_BACKOFF)
```

Add the imports at the top of `TelegramBotSupervisor.kt`:

```kotlin
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
```

- [ ] **Step 3.4: Run the test to verify it passes**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.runSupervised retries with exponential backoff after getMe failures"`

Expected: PASS.

If the test fails with a `MockKException` complaining about `bot.buildBehaviourWithLongPolling`, that's fine — the failing path never reaches that call (getMe throws first). The exception about `bot.buildBehaviourWithLongPolling` would only fire on a success path, which Task 4 covers.

- [ ] **Step 3.5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "feat(telegram): supervisor retry-loop with exponential backoff

runSupervised retries on any non-cancellation exception with
5s/10s/20s/40s/60s/60s... progression. onAttemptEnded is a stub
that only counts failures — the stable-attempt reset arrives in Task 4."
```

---

## Task 4: onAttemptEnded — stable threshold resets backoff

**Goal:** Complete `onAttemptEnded`. An attempt that ran ≥ `STABLE_THRESHOLD` is treated as a stable run: backoff is reset to `INITIAL_BACKOFF`, `consecutiveFailures` becomes 1 (this is the *first* fresh failure after stable), and `lastStableAt` is set to `now`. An attempt that crashed faster than `STABLE_THRESHOLD` keeps the running counter and growing backoff.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

- [ ] **Step 4.1: Add the failing tests for stable/unstable attempts**

Append to `TelegramBotSupervisorTest.kt`:

```kotlin
@Test
fun `attempt that ran past STABLE_THRESHOLD resets backoff on next failure`() =
    runTest {
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))

        // Drive currentBackoff up to 40s with two quick failures, then a long
        // attempt that fails after STABLE_THRESHOLD (60s) and resets the state.
        var attempts = 0
        coEvery { bot.getMe() } coAnswers {
            attempts++
            when (attempts) {
                1, 2 -> throw RuntimeException("quick fail #$attempts")
                3 -> Unit // pretend getMe + register succeeded; polling runs below
                else -> throw CancellationException("done")
            }
        }
        // [D2] No mockkStatic — supervisor now takes TelegramLongPollingRunner. Inject a
        //      hand-rolled fake that captures its onUpdate block, runs for 61s, then returns
        //      a Throwable to simulate the polling crash. The fake is constructed per-test
        //      so there's no inter-test leakage. (Obsoletes A4 + A2 teardown.)
        val runner = object : TelegramLongPollingRunner {
            override suspend fun run(
                onUpdate: suspend BehaviourContext.() -> Unit,
            ): Throwable? {
                delay(61_000)
                return RuntimeException("connection dropped after stable run")
            }
        }
        // [AUTO-3] Pass the fake runner into the supervisor via the constructor (Task 2.3
        //          updated to take `runner` as first parameter per AUTO-2). No mockkStatic —
        //          the [D2] adapter pattern obsoletes that approach entirely.
        val supervisorWithFakeRunner = newSupervisorWithTickingClock(testScheduler, runner = runner)

        // [AUTO-13] Timing reckoning under [AUTO-19] (delay BEFORE bump):
        //   t=0:   attempt 1 → getMe fails ("quick fail #1"). delay(currentBackoff=5s). bump→10.
        //   t=5:   attempt 2 → getMe fails. delay(10s). bump→20.
        //   t=15:  attempt 3 → getMe OK, runner.run starts; delay(61s).
        //   t=76:  runner.run returns RTE. Catch: onAttemptEnded(success=false, duration=61s ≥
        //          STABLE) → reset currentBackoff=5s, consecutiveFailures=1. Tail: delay(5s).
        //          bump→10. Iteration 4 starts at t=81.
        //   We assert at t≈76 (just past the runner-end) BEFORE the tail delay completes.
        val job = launch { supervisorWithFakeRunner.runSupervised() }
        advanceTimeBy(5_000 + 10_000 + 61_000 + 1)   // = 76_001 ms, just after runner exits
        runCurrent()

        // [AUTO-19] After the reset, currentBackoff is briefly INITIAL_BACKOFF (5s) before the
        //           tail bumps it. Because the tail delay starts immediately after
        //           onAttemptEnded, the test scheduler at t=76_001 is INSIDE that delay and
        //           the post-delay bump has not yet run — so we observe the reset value.
        assertEquals(INITIAL_BACKOFF_MS, supervisorWithFakeRunner.currentBackoff.toMillis())
        assertEquals(1L, supervisorWithFakeRunner.consecutiveFailures)

        job.cancelAndJoin()
    }

@Test
fun `attempt that crashed before STABLE_THRESHOLD does not reset backoff`() =
    runTest {
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
        coEvery { bot.getMe() } throws RuntimeException("always fails")

        val job = launch { supervisor.runSupervised() }
        advanceTimeBy(5_000 + 10_000 + 20_000) // three quick failures, backoff grows 5→10→20→40
        runCurrent()
        assertEquals(3L, supervisor.consecutiveFailures)
        assertEquals(40_000L, supervisor.currentBackoff.toMillis())

        job.cancelAndJoin()
    }

private companion object {
    const val INITIAL_BACKOFF_MS = 5_000L
}
```

Add the new imports at the top:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
```

(With the [D2] adapter approach, `mockkStatic`/`unmockkStatic`/`@AfterEach` are no longer
needed for these tests — `TelegramLongPollingRunner` is mocked via a per-test fake object.)

- [ ] **Step 4.2: Run tests to verify they fail**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: the new tests FAIL (the stable-reset assertions are wrong because `onAttemptEnded` doesn't differentiate yet). The previously-passing tests still PASS.

- [ ] **Step 4.3: Implement the real `onAttemptEnded`** + declare `SilentPollingFailure`

In `TelegramBotSupervisor.kt`, first add the marker class **at the bottom of the file** (private,
so it does not pollute the supervisor's public API):

```kotlin
// [AUTO-5 / D3] Marker exception written into `lastFailure` when the long-polling runner returns
//               cleanly faster than STABLE_THRESHOLD. Distinguishable in health-details under
//               `lastFailure: "SilentPollingFailure: clean return after PT30S"`, so an operator
//               can tell a silent failure (revoked token, library swallowed error) from a real
//               crash.
private class SilentPollingFailure(message: String) : RuntimeException(message)
```

Then replace the stub body:

```kotlin
private fun onAttemptEnded(success: Boolean, attemptStart: Instant, failure: Throwable?) {
    val duration = Duration.between(attemptStart, Instant.now(clock))
    if (success && duration >= STABLE_THRESHOLD) {
        // [D3] Clean polling exit AFTER a stable run — counts as success.
        consecutiveFailures = 0
        currentBackoff = INITIAL_BACKOFF
        lastStableAt = Instant.now(clock)
    } else if (success) {
        // [D3] Clean return faster than STABLE_THRESHOLD — library likely swallowed an error
        //      (revoked token, etc.). Treat as fast-fail so consecutiveFailures grows and
        //      health eventually crosses HEALTH_STALENESS into DOWN.
        lastFailure = SilentPollingFailure("clean return after $duration")
        lastFailureAt = Instant.now(clock)
        consecutiveFailures++
    } else {
        lastFailure = failure
        lastFailureAt = clock.instant()
        if (duration >= STABLE_THRESHOLD) {
            // Worked long enough to count as a stable run.
            // lastStableAt = now (the moment of crash), not attemptStart: an attempt that
            // ran for an hour should leave a fresh stable timestamp so HEALTH_STALENESS
            // is measured from "just now", not from "1 hour ago".
            consecutiveFailures = 1
            currentBackoff = INITIAL_BACKOFF
            lastStableAt = clock.instant()
        } else {
            consecutiveFailures++
        }
    }
}
```

- [ ] **Step 4.4: Adapt the clock for stable-attempt test**

Problem: in the test, `clock` is fixed at `now = 2026-05-27T12:00:00Z` and never advances, so `Duration.between(attemptStart, clock.instant())` is always 0 — the supervisor cannot tell that 61 seconds passed.

Replace the fixed clock with a "tick"-clock that follows the test scheduler. Add this near `newSupervisor`:

```kotlin
private fun tickingClock(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler): Clock {
    val origin = Instant.parse("2026-05-27T12:00:00Z")
    return object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant =
            origin.plusMillis(testScheduler.currentTime)
    }
}

private fun newSupervisorWithTickingClock(
    testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
) = TelegramBotSupervisor(
    bot = bot,
    frigateAnalyzerBot = frigateAnalyzerBot,
    clock = tickingClock(testScheduler),
    dispatcher = StandardTestDispatcher(testScheduler),
)
```

In the test `attempt that ran past STABLE_THRESHOLD resets backoff on next failure`, replace:

```kotlin
val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
```

with:

```kotlin
val supervisor = newSupervisorWithTickingClock(testScheduler)
```

- [ ] **Step 4.5: Run tests to verify they pass**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: PASS.

- [ ] **Step 4.6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "feat(telegram): stable-attempt threshold resets supervisor backoff

Attempts that ran ≥ STABLE_THRESHOLD (60s) reset currentBackoff to
INITIAL_BACKOFF and treat the next failure as 'first fresh' — protects
long-running pollings from inheriting a stale backoff."
```

---

## Task 5: Cancellation handling test

**Goal:** Pin down that `CancellationException` does not bump `consecutiveFailures`, does not record `lastFailure`, and propagates out of `runSupervised` cleanly.

**Files:**
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

The implementation already handles cancellation (Task 3 added `catch (e: CancellationException) { throw e }`). This task only adds the regression test.

- [ ] **Step 5.1: Add the cancellation test**

Append to `TelegramBotSupervisorTest.kt`:

```kotlin
@Test
fun `cancellation propagates cleanly and leaves no failure bookkeeping`() =
    runTest {
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
        coEvery { bot.getMe() } coAnswers { awaitCancellation() }

        val job = launch { supervisor.runSupervised() }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals(0L, supervisor.consecutiveFailures)
        assertNull(supervisor.lastFailure)
        assertNull(supervisor.lastFailureAt)
    }
```

Add the imports:

```kotlin
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertNull
```

- [ ] **Step 5.2: Run the test to verify it passes**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.cancellation propagates cleanly and leaves no failure bookkeeping"`

Expected: PASS.

- [ ] **Step 5.3: Commit**

```bash
git add modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "test(telegram): pin cancellation contract on supervisor

Cancellation must not increment consecutiveFailures or record a lastFailure
— a clean cancel is not a failure event for health purposes."
```

---

## Task 6: computeHealth — remaining 6 branches

**Goal:** Implement branches 2–7 from `§5` of the spec, with one test per branch. Branch 1 is already covered.

> **[D1] Branch order changed.** Per spec §5 (updated), branch 2 is "live stable polling → UP"
> — checked **before** the startup-grace and backoff branches. The test names and code blocks
> below were written against the previous ordering and must be aligned with the new spec table.
> Concretely, the test currently called *"branch 6 — polling stable past STABLE_THRESHOLD → UP"*
> should be renamed *"branch 2 — live stable polling → UP"* and run first, and the UP condition
> requires both `lastPollingStartAt >= STABLE_THRESHOLD` AND `lastPollingStartAt > lastFailureAt`
> (or `lastFailureAt == null`). The remaining tests keep their semantics but their branch
> numbers shift: old 2→new 3, old 3→new 4, old 4→new 5, old 5→new 6, old 7→new 7. Add one new
> test: *"branch 2 stale lastPollingStartAt from a previous failure does not yield UP"* — sets
> `lastFailureAt > lastPollingStartAt` and asserts the result falls through to backoff branches.


**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

**Helper for tests:** the supervisor exposes `internal var` state fields, so tests can construct a supervisor, set the fields, attach a fake live `supervisorJob` (so branch 1 doesn't fire), and call `computeHealth(now)` directly.

- [ ] **Step 6.1: Add a test helper that simulates "supervisor running"**

Insert this helper near the top of `TelegramBotSupervisorTest.kt` (inside the class):

```kotlin
/**
 * Returns a supervisor with a dummy alive supervisorJob so computeHealth doesn't return
 * the branch-1 DOWN. Caller can then set internal state fields and assert on computeHealth.
 */
private fun supervisorWithLiveJob(): TelegramBotSupervisor {
    val sup = newSupervisor()
    val dummyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    sup.supervisorJob = dummyScope.launch { awaitCancellation() }
    return sup
}
```

- [ ] **Step 6.2: Write the 6 failing tests for branches 2–7**

Append:

```kotlin
// Branch names below match the spec §5 ordering after [D1] reorder. Use descriptive names so
// future reorderings don't desync test/spec numbering (per AUTO-1 + MiniMax CO3 / Kimi 16).

@Test
fun `computeHealth — live stable polling yields UP`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minusSeconds(120)
    sup.lastPollingStartAt = now.minusSeconds(90)     // > STABLE_THRESHOLD (60s)
    sup.consecutiveFailures = 0L
    val h = sup.computeHealth(now)
    assertEquals(Status.UP, h.status)
    assertEquals("healthy", h.details["reason"])
    sup.supervisorJob?.cancel()
}

// [AUTO-11] Core motivation of [D1]: after recovery with sticky consecutiveFailures, a fresh
//           stable polling must still report UP without waiting for onAttemptEnded to fire.
@Test
fun `computeHealth — live stable polling with sticky consecutiveFailures still yields UP`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minus(Duration.ofMinutes(2))   // older stable; still within freshness
    sup.lastPollingStartAt = now.minusSeconds(70)         // > STABLE_THRESHOLD
    sup.lastFailureAt = now.minus(Duration.ofMinutes(3))  // older than pollStart → invariant ok
    sup.consecutiveFailures = 4L                          // sticky from before recovery
    val h = sup.computeHealth(now)
    assertEquals(Status.UP, h.status)
    sup.supervisorJob?.cancel()
}

// [AUTO-10] Invariant `lastPollingStartAt > lastFailureAt` must reject UP if a newer failure
//           was recorded after the polling stamp (i.e. attempt already crashed but iteration
//           hasn't entered the new loop body yet — stale-stamp window).
@Test
fun `computeHealth — stale lastPollingStartAt after newer failure does not yield UP`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minusSeconds(120)
    sup.lastPollingStartAt = now.minusSeconds(90)        // > STABLE_THRESHOLD, but...
    sup.lastFailureAt = now.minusSeconds(30)             // ...newer failure invalidates UP
    sup.consecutiveFailures = 1L
    val h = sup.computeHealth(now)
    assertTrue(h.status != Status.UP, "expected fall-through to backoff branches, was UP")
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — startup failure threshold reached → DOWN`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minusSeconds(30)            // inside grace
    sup.consecutiveFailures = 5L                    // == STARTUP_FAILURE_THRESHOLD
    val h = sup.computeHealth(now)
    assertEquals(Status.DOWN, h.status)
    assertTrue((h.details["reason"] as String).startsWith("startup failed"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — startup grace expired → DOWN`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofMinutes(3))  // past STARTUP_GRACE (2m)
    sup.consecutiveFailures = 1L
    val h = sup.computeHealth(now)
    assertEquals(Status.DOWN, h.status)
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — still in grace, never stable → OUT_OF_SERVICE`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minusSeconds(30)
    sup.consecutiveFailures = 2L                     // < STARTUP_FAILURE_THRESHOLD
    val h = sup.computeHealth(now)
    assertEquals(Status.OUT_OF_SERVICE, h.status)
    assertTrue((h.details["reason"] as String).startsWith("connecting"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — failing past HEALTH_STALENESS → DOWN`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minus(Duration.ofMinutes(10))   // > HEALTH_STALENESS (5m)
    sup.consecutiveFailures = 3L
    val h = sup.computeHealth(now)
    assertEquals(Status.DOWN, h.status)
    assertTrue((h.details["reason"] as String).startsWith("stale"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — failing but recent stable → OUT_OF_SERVICE`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minusSeconds(30)            // < HEALTH_STALENESS
    sup.consecutiveFailures = 2L
    val h = sup.computeHealth(now)
    assertEquals(Status.OUT_OF_SERVICE, h.status)
    assertTrue((h.details["reason"] as String).startsWith("in backoff"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth — just (re)connected, polling under STABLE_THRESHOLD → OUT_OF_SERVICE`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofHours(1))
    sup.lastStableAt = now.minusSeconds(30)
    sup.lastPollingStartAt = now.minusSeconds(30)     // < STABLE_THRESHOLD
    sup.consecutiveFailures = 0L
    val h = sup.computeHealth(now)
    assertEquals(Status.OUT_OF_SERVICE, h.status)
    assertTrue((h.details["reason"] as String).startsWith("connecting"))
    sup.supervisorJob?.cancel()
}
```

- [ ] **Step 6.3: Run tests to verify they fail**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: the new tests FAIL (`computeHealth` currently returns `OUT_OF_SERVICE` "not implemented yet" for all but branch 1).

- [ ] **Step 6.4: Implement the remaining `computeHealth` branches**

Replace the stub `computeHealth` body with the full implementation:

```kotlin
fun computeHealth(now: Instant): Health {
    val builder = baseBuilder()

    // BRANCH 1: supervisor not active → DOWN
    val job = supervisorJob
    if (job == null || !job.isActive) {
        return builder.down().withDetail("reason", "supervisor not active").build()
    }

    // [AUTO-1 / D1] BRANCH 2: live stable polling → UP (checked FIRST after liveness so cold
    //               start AND recovery-with-sticky-consecutiveFailures correctly report UP).
    //               Invariant `lastPollingStartAt > lastFailureAt` guards against a stale stamp
    //               from a previously-crashed attempt (companion to [A5] which nulls the field
    //               at iteration start AND [AUTO-20] which nulls it after runner.run exits).
    val pollStart = lastPollingStartAt
    val failedAt = lastFailureAt
    if (pollStart != null &&
        (failedAt == null || pollStart.isAfter(failedAt)) &&
        Duration.between(pollStart, now) >= STABLE_THRESHOLD
    ) {
        return builder.up().withDetail("reason", "healthy").build()
    }

    val stable = lastStableAt
    val started = startupAt
    val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

    // BRANCH 3: never stable, threshold OR grace expired → DOWN
    if (stable == null &&
        (consecutiveFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
    ) {
        return builder
            .down()
            .withDetail(
                "reason",
                "startup failed: $consecutiveFailures attempts / $sinceStartup",
            ).build()
    }

    // BRANCH 4: never stable, still in grace → OUT_OF_SERVICE
    if (stable == null) {
        return builder
            .outOfService()
            .withDetail("reason", "connecting... attempts=$consecutiveFailures")
            .build()
    }

    // BRANCH 5: in backoff with stale stable point → DOWN
    if (consecutiveFailures > 0 && Duration.between(stable, now) > HEALTH_STALENESS) {
        return builder
            .down()
            .withDetail(
                "reason",
                "stale: failing for ${Duration.between(stable, now)} since lastStable=$stable",
            ).build()
    }

    // BRANCH 6: transient backoff (recent stable) → OUT_OF_SERVICE
    if (consecutiveFailures > 0) {
        return builder
            .outOfService()
            .withDetail(
                "reason",
                "in backoff (failures=$consecutiveFailures, backoff=${currentBackoff.toMillis()}ms)",
            ).build()
    }

    // BRANCH 7: just (re)connected, polling < STABLE_THRESHOLD → OUT_OF_SERVICE
    return builder
        .outOfService()
        .withDetail("reason", "connecting... (just (re)started, <STABLE_THRESHOLD)")
        .build()
}
```

- [ ] **Step 6.5: Run tests to verify they pass**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: all 9 supervisor tests PASS (branches 1–7 + retry + cancellation).

- [ ] **Step 6.6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "feat(telegram): supervisor computeHealth — 7-branch state machine

UP / OUT_OF_SERVICE / DOWN derived from supervisor liveness, startup
grace, consecutiveFailures, lastStableAt staleness, and current polling
uptime. Priority-ordered, first-match wins per spec §5."
```

---

## Task 7: Lifecycle — `@PostConstruct` start + bounded-join `@PreDestroy`

**Goal:** Implement `start()` to launch `runSupervised` on `scope`, and `shutdown()` with a `runBlocking { withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT) { supervisorJob?.join() } }` pattern so Spring's default 30 s shutdown doesn't interrupt mid-cleanup.

> **[D4] Cutover-safety constraint.** Until Task 9 removes `FrigateAnalyzerBot.start()`,
> activating the supervisor's `@PostConstruct` would cause two concurrent long-polling
> connections to the same Telegram bot (409 Conflict). To avoid this between-tasks window,
> **the supervisor's `@PostConstruct fun start()` must remain a no-op stub through Task 7**
> (everything else — `runSupervised`, `stopAndJoin`, the runBlocking shutdown — is implemented
> normally and exercised via the tests in this task). The real `scope.launch { runSupervised() }`
> line is added in Task 9 as part of the atomic cutover, immediately *after* removing
> `FrigateAnalyzerBot.start()`. Tests for `start()` in this task should call `runSupervised()`
> directly (not via `start()`) — see Step 7.1 adjustment.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

- [ ] **Step 7.1: Add a test for `runSupervised` + the test-friendly `stopAndJoin`** [D4]

`runBlocking` inside `shutdown()` deadlocks `StandardTestDispatcher`, so we provide a `suspend internal fun stopAndJoin()` that mirrors `shutdown()` shape from a suspending context (same approach as `WatchRecordsTask.stopAndJoin`). Since `start()` is a stub until Task 9, the test launches `runSupervised()` directly on the supervisor's `scope` to mirror what `start()` will do later.

Append to the test file:

```kotlin
@Test
fun `runSupervised on scope, stopAndJoin cancels it cleanly`() =
    runTest {
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
        coEvery { bot.getMe() } coAnswers { awaitCancellation() }

        // start() is a stub through Task 7 [D4] — launch runSupervised directly to mirror
        // what the populated start() does in Task 9.
        supervisor.supervisorJob = supervisor.scope.launch { supervisor.runSupervised() }
        runCurrent()
        assertNotNull(supervisor.supervisorJob, "supervisorJob should be set")
        assertTrue(supervisor.supervisorJob!!.isActive, "supervisorJob should be active")

        supervisor.stopAndJoin()
        assertEquals(false, supervisor.supervisorJob!!.isActive)
    }
```

(This test needs `supervisor.scope` exposed as `internal`. Update the field visibility in
`TelegramBotSupervisor.kt` from `private val scope = ...` to `internal val scope = ...`.)

Add the import (if not already present):

```kotlin
import org.junit.jupiter.api.Assertions.assertNotNull
```

- [ ] **Step 7.2: Run the test to verify it fails (stopAndJoin missing, startupAt missing)**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.start launches supervisor coroutine, stopAndJoin cancels it cleanly"`

Expected: FAIL — `stopAndJoin` does not exist, `start()` is a stub.

- [ ] **Step 7.3: Implement `shutdown`, `stopAndJoin`. Leave `start()` as a no-op stub** [D4]

The real `start()` body lands in Task 9 (Step 9.0a) as part of the atomic cutover. Until then
the test exercises `runSupervised()` directly (Step 7.1 already wires this).

```kotlin
@PostConstruct
fun start() {
    // [D4] Stub through Task 7. The real launch is added in Task 9 atomically with the
    //      removal of FrigateAnalyzerBot.start() to avoid two concurrent pollers.
    logger.info { "TelegramBotSupervisor.start() stub — populated in Task 9 cutover." }
}

@PreDestroy
fun shutdown() {
    logger.info { "Shutting down Telegram bot supervisor..." }
    supervisorJob?.cancel()
    // Bound the join — worst case loop is parked in delay(MAX_BACKOFF=60s) and won't
    // observe cancel until that wakes; Spring's default 30s shutdown would interrupt
    // us otherwise. After timeout, scope.cancel() forces termination.
    runBlocking {
        withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT.toMillis()) {
            // runCatching swallows a CancellationException that may propagate from
            // join() — otherwise withTimeoutOrNull would see the cancel and treat it
            // as a timeout, causing a false "did not exit within Ns" log. [A7]
            runCatching { supervisorJob?.join() }
            true
        }
    }
    val cleanShutdown = supervisorJob?.isCompleted == true
    if (!cleanShutdown) {
        logger.warn {
            "Supervisor did not exit within ${SHUTDOWN_JOIN_TIMEOUT.toSeconds()}s; forcing"
        }
    }
    scope.cancel()
    logger.info { "Telegram bot supervisor stopped" }
}

internal suspend fun stopAndJoin() {
    supervisorJob?.cancel()
    supervisorJob?.join()
    scope.cancel()
}
```

Add the imports:

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
```

- [ ] **Step 7.4: Run the test to verify it passes**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.start launches supervisor coroutine, stopAndJoin cancels it cleanly"`

Expected: PASS.

- [ ] **Step 7.5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt
git commit -m "feat(telegram): supervisor lifecycle (@PostConstruct + bounded shutdown)

start() launches runSupervised on the supervisor scope; shutdown()
cancels the job and waits up to SHUTDOWN_JOIN_TIMEOUT=30s for clean
exit before forcing scope.cancel(). stopAndJoin() mirrors shutdown
shape for tests (runBlocking inside StandardTestDispatcher deadlocks)."
```

---

## Task 8: TelegramBotHealthIndicator

**Goal:** Thin Spring Actuator adapter that delegates to `supervisor.computeHealth(Instant.now(clock))`.

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicator.kt`
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicatorTest.kt`

- [ ] **Step 8.1: Write the failing test**

Create the test file:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TelegramBotHealthIndicatorTest {
    private val supervisor = mockk<TelegramBotSupervisor>()
    private val now = Instant.parse("2026-05-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val indicator = TelegramBotHealthIndicator(supervisor, clock)

    @Test
    fun `health delegates to supervisor computeHealth with current instant`() {
        val expected =
            Health
                .Builder()
                .up()
                .withDetail("reason", "healthy")
                .build()
        every { supervisor.computeHealth(now) } returns expected

        val actual = indicator.health()

        assertEquals(Status.UP, actual.status)
        assertEquals("healthy", actual.details["reason"])
    }
}
```

- [ ] **Step 8.2: Run the test to verify it fails**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotHealthIndicatorTest"`

Expected: FAIL — `TelegramBotHealthIndicator` does not exist.

- [ ] **Step 8.3: Create the indicator**

Create `TelegramBotHealthIndicator.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

// @Profile("!test") guards FrigateAnalyzerApplicationTests.actuatorHealth(): in test profile
// telegram.enabled=false, the supervisor is not created, and computeHealth would return
// branch-1 DOWN, breaking the aggregated /actuator/health. Same pattern as
// WatchRecordsTaskHealthIndicator (core/task/WatchRecordsTaskHealthIndicator.kt).
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

- [ ] **Step 8.4: Run the test to verify it passes**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotHealthIndicatorTest"`

Expected: PASS.

- [ ] **Step 8.5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicator.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotHealthIndicatorTest.kt
git commit -m "feat(telegram): TelegramBotHealthIndicator exposes supervisor state

Thin adapter; @Profile('!test') mirrors WatchRecordsTaskHealthIndicator
so test profile (telegram disabled) doesn't aggregate to DOWN."
```

---

## Task 9: Atomic cutover — remove polling from FrigateAnalyzerBot AND activate supervisor's start()

**Goal:** Now that the supervisor + indicator exist and are tested with `runSupervised()` invoked
directly, perform the atomic cutover: remove the polling-owning code from `FrigateAnalyzerBot`
**and** populate the supervisor's `@PostConstruct fun start()` body in a single change. The bot
stays as the routing component; supervisor drives lifecycle. **[D4] These two edits MUST land in
one commit to avoid the two-pollers window flagged in review.**

**New Step 9.0 (atomic ordering):** apply both edits below to the working tree, then build, then
commit — do NOT commit them separately.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt` [AUTO-4 — was missing; Step 9.0a edits this file]

- [ ] **Step 9.0a: Populate `TelegramBotSupervisor.start()` body** [D4]

Edit `TelegramBotSupervisor.kt`. Replace the no-op stub from Task 7 with the real launch:

```kotlin
@PostConstruct
fun start() {
    if (supervisorJob != null) {
        logger.warn { "TelegramBotSupervisor.start() invoked twice; ignoring duplicate." }
        return
    }
    logger.info { "Starting Telegram bot supervisor..." }
    startupAt = Instant.now(clock)
    supervisorJob = scope.launch { runSupervised() }
}
```

- [ ] **Step 9.1: Remove the polling `@PostConstruct fun start()`**

In `FrigateAnalyzerBot.kt`, delete the entire `start()` method (currently around lines 83–118), including the `@PostConstruct` annotation:

```kotlin
@PostConstruct
fun start() {
    logger.info { "Starting Telegram bot with long polling..." }
    botScope.launch {
        try {
            val botInfo = bot.getMe()
            logger.info { "Bot started: ${botInfo.username} (${botInfo.firstName})" }
            registerDefaultCommands()
            registerOwnerCommandsIfPossible()
            bot
                .buildBehaviourWithLongPolling {
                    registerRoutes(this)
                }.join()
        } catch (e: CancellationException) {
            logger.info { "Telegram bot long polling cancelled" }
        } catch (e: Exception) {
            logger.error(e) { "Error in Telegram bot long polling" }
        }
    }
}
```

(Note: after Task 1, this method calls `registerRoutes(this)` and `registerOwnerCommandsIfPossible()` as shown. The exact lines may differ slightly — delete the whole `start()` block including its annotation.)

- [ ] **Step 9.2: Update `stop()` to only cancel `eventScope`**

The current method (after Task 1) cancels both `botScope` and `eventScope`. Now that `botScope` is gone (next step), reduce to:

```kotlin
@PreDestroy
fun stop() {
    logger.info { "Stopping Telegram bot event scope..." }
    eventScope.cancel()
    logger.info { "Telegram bot event scope stopped" }
}
```

- [ ] **Step 9.3: Remove the `botScope` field**

Delete the line:

```kotlin
private val botScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

Keep `eventScope`.

- [ ] **Step 9.4: Remove now-unused imports**

After the changes above, these imports are likely unused (verify with the IDE or `./gradlew ktlintCheck`):

- `dev.inmo.tgbotapi.extensions.api.bot.getMe` (now used by supervisor instead)
- `dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling`
- `jakarta.annotation.PostConstruct`
- `kotlinx.coroutines.CancellationException`
- `kotlinx.coroutines.launch` (only if no other launches remain — `eventScope.launch` still uses it; keep if needed)

Remove only the ones that are actually unused. Leave any import that is still referenced.

- [ ] **Step 9.5: Build the module to catch compile errors and ktlint nits**

Delegate to build-runner agent: `./gradlew :frigate-analyzer-telegram:build`

Expected: BUILD SUCCESSFUL. If ktlint fires, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 9.6: Run full project build to catch any cross-module breakage**

Delegate to build-runner agent: `./gradlew build`

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9.7: Commit**

```bash
# [AUTO-4] BOTH files must be staged in the same commit per [D4] atomic cutover. Step 9.0a
#          populates TelegramBotSupervisor.start() while Steps 9.1-9.3 remove
#          FrigateAnalyzerBot.start(); committing them separately would leave a no-poller
#          (only stub) or two-poller (409 Conflict) window.
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt
git commit -m "refactor(telegram): hand polling lifecycle to TelegramBotSupervisor

FrigateAnalyzerBot no longer owns the polling @PostConstruct/@PreDestroy
or botScope. The supervisor now drives bootstrap (getMe, registerDefault,
registerOwnerIfPossible) and reconnect on failure. The event-scoped
@PreDestroy on FrigateAnalyzerBot stays for onOwnerActivated.

Closes #34."
```

---

## Task 10: Document the new components in `.claude/rules/telegram.md`

**Goal:** Add the two new components to the component table and a short subsection on bot supervision.

**Files:**
- Modify: `.claude/rules/telegram.md`

- [ ] **Step 10.1: Add two rows to the Components table**

Find the existing table in `.claude/rules/telegram.md` (under `## Components`). Insert two new rows immediately after the `FrigateAnalyzerBot` row:

```markdown
| TelegramBotSupervisor | `telegram/bot/supervisor/` | Polling lifecycle — supervised retry-loop with 5s→60s exponential backoff; owns botScope, drives FrigateAnalyzerBot bootstrap on each (re)connect. |
| TelegramBotHealthIndicator | `telegram/bot/supervisor/` | Spring Actuator `HealthIndicator`; delegates to `supervisor.computeHealth(now)`. `@Profile("!test")` to avoid breaking aggregated /actuator/health in tests. |
```

- [ ] **Step 10.2: Update the `FrigateAnalyzerBot` row to reflect its new scope**

Find the existing row:

```markdown
| FrigateAnalyzerBot | `telegram/bot/` | Thin orchestrator: lifecycle, routing, auth, command menus |
```

Replace with:

```markdown
| FrigateAnalyzerBot | `telegram/bot/` | Routes registrar + auth/command menus. Polling lifecycle is owned by TelegramBotSupervisor; this class exposes `registerRoutes(ctx)`, `registerDefaultCommands()`, `registerOwnerCommandsIfPossible()` for the supervisor to call. |
```

- [ ] **Step 10.3: Add a "Bot supervision" subsection**

Append this section after the existing `## Bot Architecture` block:

```markdown
## Bot Supervision

`TelegramBotSupervisor` runs the polling loop with bounded exponential backoff
(`INITIAL_BACKOFF=5s` → `MAX_BACKOFF=60s`, capped). An attempt that ran at least
`STABLE_THRESHOLD=60s` resets backoff on the next failure (long-running pollings
do not inherit stale backoff). Cancellation propagates cleanly without bumping
failure counters.

`TelegramBotHealthIndicator` exposes `telegramBotSupervisor` in `/actuator/health`
with one of:

- **UP** — polling has run uninterrupted for ≥ `STABLE_THRESHOLD`.
- **OUT_OF_SERVICE** — startup grace (≤ `STARTUP_GRACE=2m`), transient backoff
  (recent stable run within `HEALTH_STALENESS=5m`), or just (re)connected
  (< `STABLE_THRESHOLD`).
- **DOWN** — supervisor not running, startup failed
  (`STARTUP_FAILURE_THRESHOLD=5` or grace expired), or no stable polling for
  > `HEALTH_STALENESS=5m`.

All thresholds are hardcoded constants in `TelegramBotSupervisor.kt` — by intent,
matching the policy of `WatchRecordsTask` (single-deployment project, no operator
tuning). Does NOT trigger automatic restart — operator must monitor health and
act manually. See `.claude/rules/pipeline.md` §"Health" for the rationale.
```

- [ ] **Step 10.4: Commit**

```bash
git add .claude/rules/telegram.md
git commit -m "docs(rules): document TelegramBotSupervisor + HealthIndicator

Add component rows + 'Bot supervision' subsection covering backoff
thresholds, health-state semantics, and the explicit no-auto-restart
policy (consistent with WatchRecordsTask)."
```

---

## Final verification (manual, not a TDD step)

- [ ] **Final 1: Full build green**

Delegate to build-runner agent: `./gradlew build`

Expected: BUILD SUCCESSFUL, all tests pass, no ktlint failures.

- [ ] **Final 2: Sanity smoke (optional, requires live Telegram token)**

If a dev Telegram token is available, start the app with `application.telegram.enabled=true` and confirm:

- `/actuator/health` reports `telegramBotSupervisor: UP` after ~70 s of uptime.
- Killing the network (e.g. block Telegram API in iptables) within ~5 s flips it to `OUT_OF_SERVICE` with `reason: in backoff (...)`.
- Restoring the network and waiting another ~70 s flips it back to `UP`.

If no token is available, skip this step.

- [ ] **Final 3: Open PR**

Per project workflow, drop any plan/spec docs from the branch before opening the PR:

```bash
git rm docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md \
       docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md
git commit -m "chore: drop plan + spec docs before opening PR"
```

Then push and open the PR via `gh pr create` (or the project's usual workflow).
