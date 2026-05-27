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
    } catch (e: Exception) {
        logger.warn(e) { "Failed to look up owner for command registration" }
    }
}
```

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

Change `onOwnerActivated` (currently around line 305) from `botScope.launch` to `eventScope.launch`:

```kotlin
@EventListener
fun onOwnerActivated(event: OwnerActivatedEvent) {
    eventScope.launch {
        registerOwnerCommandsIfPossible()  // (was: registerOwnerCommands(event.chatId))
    }
}
```

Note: we replace the direct `registerOwnerCommands(event.chatId)` call with `registerOwnerCommandsIfPossible()` so we re-resolve the owner from the DB on activation. `OwnerActivatedEvent.chatId` is no longer needed by this handler; it remains in the event class because other listeners (if any) may still use it. Do not remove the field.

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
    private val now = Instant.parse("2026-05-27T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun newSupervisor(dispatcher: CoroutineDispatcher = StandardTestDispatcher()) =
        TelegramBotSupervisor(
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
    private val bot: TelegramBot,
    private val frigateAnalyzerBot: FrigateAnalyzerBot,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("telegram-bot-supervisor"))

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
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
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
        val attemptStart = clock.instant()
        lastAttemptAt = attemptStart
        try {
            bot.getMe()
            frigateAnalyzerBot.registerDefaultCommands()
            frigateAnalyzerBot.registerOwnerCommandsIfPossible()
            lastPollingStartAt = clock.instant()
            bot
                .buildBehaviourWithLongPolling {
                    frigateAnalyzerBot.registerRoutes(this)
                }.join()
            // Clean return from join() is unusual — library normally throws on disconnect.
            // Log at WARN so this is observable; we'll still treat it as success (counter
            // reset). If it turns out to be a permanent silent failure we'd see this WARN
            // looping in production logs. (Spec §10 risk mitigation.)
            logger.warn { "buildBehaviourWithLongPolling returned without exception; reconnecting" }
            onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "Telegram bot bootstrap/polling failed; " +
                    "next backoff=${currentBackoff.toMillis()}ms"
            }
            onAttemptEnded(success = false, attemptStart = attemptStart, failure = e)
        }
        delay(currentBackoff.toMillis())
        currentBackoff = nextBackoff(currentBackoff)
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
        mockkStatic("dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt")
        coEvery {
            bot.buildBehaviourWithLongPolling(any(), any(), any(), any(), any(), any())
        } coAnswers {
            // Simulate a polling Job that runs for 61s then dies — counted as stable.
            val job = Job()
            launch {
                delay(61_000)
                job.completeExceptionally(RuntimeException("connection dropped after stable run"))
            }
            job
        }

        val job = launch { supervisor.runSupervised() }
        // 5s wait + retry + 10s wait + retry + 61s polling (stable) + failure → reset
        advanceTimeBy(5_000 + 10_000 + 61_000 + 1)
        runCurrent()

        assertEquals(INITIAL_BACKOFF_MS, supervisor.currentBackoff.toMillis())
        assertEquals(1L, supervisor.consecutiveFailures)

        job.cancelAndJoin()
        unmockkStatic("dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt")
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
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
```

- [ ] **Step 4.2: Run tests to verify they fail**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest"`

Expected: the new tests FAIL (the stable-reset assertions are wrong because `onAttemptEnded` doesn't differentiate yet). The previously-passing tests still PASS.

- [ ] **Step 4.3: Implement the real `onAttemptEnded`**

In `TelegramBotSupervisor.kt`, replace the stub body:

```kotlin
private fun onAttemptEnded(success: Boolean, attemptStart: Instant, failure: Throwable?) {
    val duration = Duration.between(attemptStart, clock.instant())
    if (success) {
        // Clean polling exit (rare — the library normally throws on disconnect).
        consecutiveFailures = 0
        currentBackoff = INITIAL_BACKOFF
        lastStableAt = clock.instant()
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
@Test
fun `computeHealth branch 2 — startup failure threshold reached → DOWN`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minusSeconds(30)            // inside grace
    sup.consecutiveFailures = 5L                    // == STARTUP_FAILURE_THRESHOLD
    val h = sup.computeHealth(now)
    assertEquals(Status.DOWN, h.status)
    assertTrue((h.details["reason"] as String).startsWith("startup failed"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth branch 2 — startup grace expired → DOWN`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minus(Duration.ofMinutes(3))  // past STARTUP_GRACE (2m)
    sup.consecutiveFailures = 1L
    val h = sup.computeHealth(now)
    assertEquals(Status.DOWN, h.status)
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth branch 3 — still in grace, never stable → OUT_OF_SERVICE`() {
    val sup = supervisorWithLiveJob()
    sup.startupAt = now.minusSeconds(30)
    sup.consecutiveFailures = 2L                     // < STARTUP_FAILURE_THRESHOLD
    val h = sup.computeHealth(now)
    assertEquals(Status.OUT_OF_SERVICE, h.status)
    assertTrue((h.details["reason"] as String).startsWith("connecting"))
    sup.supervisorJob?.cancel()
}

@Test
fun `computeHealth branch 4 — failing past HEALTH_STALENESS → DOWN`() {
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
fun `computeHealth branch 5 — failing but recent stable → OUT_OF_SERVICE`() {
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
fun `computeHealth branch 6 — polling stable past STABLE_THRESHOLD → UP`() {
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

@Test
fun `computeHealth branch 7 — just (re)connected, polling under STABLE_THRESHOLD → OUT_OF_SERVICE`() {
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

    // BRANCH 1: supervisor not active
    val job = supervisorJob
    if (job == null || !job.isActive) {
        return builder.down().withDetail("reason", "supervisor not active").build()
    }

    val stable = lastStableAt
    val started = startupAt
    val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

    // BRANCH 2: never stable, threshold OR grace expired → DOWN
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

    // BRANCH 3: never stable, still in grace → OUT_OF_SERVICE
    if (stable == null) {
        return builder
            .outOfService()
            .withDetail("reason", "connecting... attempts=$consecutiveFailures")
            .build()
    }

    // BRANCH 4: in backoff with stale stable point → DOWN
    if (consecutiveFailures > 0 && Duration.between(stable, now) > HEALTH_STALENESS) {
        return builder
            .down()
            .withDetail(
                "reason",
                "stale: failing for ${Duration.between(stable, now)} since lastStable=$stable",
            ).build()
    }

    // BRANCH 5: transient backoff (recent stable) → OUT_OF_SERVICE
    if (consecutiveFailures > 0) {
        return builder
            .outOfService()
            .withDetail(
                "reason",
                "in backoff (failures=$consecutiveFailures, backoff=${currentBackoff.toMillis()}ms)",
            ).build()
    }

    // BRANCH 6: polling stable past STABLE_THRESHOLD → UP
    val pollStart = lastPollingStartAt
    if (pollStart != null && Duration.between(pollStart, now) >= STABLE_THRESHOLD) {
        return builder.up().withDetail("reason", "healthy").build()
    }

    // BRANCH 7: just (re)connected
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

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisor.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/supervisor/TelegramBotSupervisorTest.kt`

- [ ] **Step 7.1: Add a test for the test-friendly `stopAndJoin`**

`runBlocking` inside `shutdown()` deadlocks `StandardTestDispatcher`, so we provide a `suspend internal fun stopAndJoin()` that mirrors `shutdown()` shape from a suspending context (same approach as `WatchRecordsTask.stopAndJoin`).

Append to the test file:

```kotlin
@Test
fun `start launches supervisor coroutine, stopAndJoin cancels it cleanly`() =
    runTest {
        val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
        coEvery { bot.getMe() } coAnswers { awaitCancellation() }

        supervisor.start()
        runCurrent()
        assertNotNull(supervisor.supervisorJob, "start() should launch a supervisorJob")
        assertTrue(supervisor.supervisorJob!!.isActive, "supervisorJob should be active")

        supervisor.stopAndJoin()
        assertEquals(false, supervisor.supervisorJob!!.isActive)
    }
```

Add the import (if not already present):

```kotlin
import org.junit.jupiter.api.Assertions.assertNotNull
```

- [ ] **Step 7.2: Run the test to verify it fails (stopAndJoin missing, startupAt missing)**

`./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisorTest.start launches supervisor coroutine, stopAndJoin cancels it cleanly"`

Expected: FAIL — `stopAndJoin` does not exist, `start()` is a stub.

- [ ] **Step 7.3: Implement `start`, `shutdown`, `stopAndJoin`**

Replace the stubs in `TelegramBotSupervisor.kt`:

```kotlin
@PostConstruct
fun start() {
    if (supervisorJob != null) {
        logger.warn { "TelegramBotSupervisor.start() invoked twice; ignoring duplicate." }
        return
    }
    logger.info { "Starting Telegram bot supervisor..." }
    startupAt = clock.instant()
    supervisorJob = scope.launch { runSupervised() }
}

@PreDestroy
fun shutdown() {
    logger.info { "Shutting down Telegram bot supervisor..." }
    supervisorJob?.cancel()
    // Bound the join — worst case loop is parked in delay(MAX_BACKOFF=60s) and won't
    // observe cancel until that wakes; Spring's default 30s shutdown would interrupt
    // us otherwise. After timeout, scope.cancel() forces termination.
    val joined =
        runBlocking {
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

## Task 9: Cut over — remove polling from FrigateAnalyzerBot

**Goal:** Now that the supervisor + indicator exist and tested, remove the polling-owning code from `FrigateAnalyzerBot`. The bot stays as the routing component; supervisor drives lifecycle.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

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
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
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
