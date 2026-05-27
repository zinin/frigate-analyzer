package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import ru.zinin.frigate.analyzer.telegram.bot.FrigateAnalyzerBot
import ru.zinin.frigate.analyzer.telegram.bot.supervisor.TelegramBotSupervisor.SupervisorState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotSupervisorTest {
    private val bot = mockk<TelegramBot>(relaxed = true)
    private val frigateAnalyzerBot = mockk<FrigateAnalyzerBot>(relaxed = true)

    // `runner` is the adapter dependency. Default is a relaxed mock; individual tests override
    // with a hand-rolled fake when they need to drive specific polling-loop behaviour (see the
    // stable-attempt test below).
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

    // Ticking clock helper. A fixed clock would report duration=0 for
    // `Duration.between(attemptStart, Instant.now(clock))`, which would silently break the
    // stable-attempt threshold check.
    private fun tickingClock(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): Clock {
        val origin = Instant.parse("2026-05-27T12:00:00Z")
        return object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId) = this

            override fun instant(): Instant = origin.plusMillis(scheduler.currentTime)
        }
    }

    private fun newSupervisorWithTickingClock(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        runner: TelegramLongPollingRunner = defaultRunner,
    ) = TelegramBotSupervisor(
        runner = runner,
        bot = bot,
        frigateAnalyzerBot = frigateAnalyzerBot,
        clock = tickingClock(scheduler),
        dispatcher = StandardTestDispatcher(scheduler),
    )

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

    // ----- computeHealth branches -----

    @Test
    fun `computeHealth returns DOWN when supervisor not started`() {
        val supervisor = newSupervisor()
        val health = supervisor.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertEquals("supervisor not active", health.details["reason"])
    }

    // ----- runSupervised retry-loop -----

    @Test
    fun `runSupervised retries with exponential backoff after getMe failures`() =
        runTest {
            // Use ticking clock so future tests adding longer attempts won't silently get
            // duration=0 on a fixed clock.
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
            val s = supervisor.stateForTesting
            assertEquals(40_000L, s.currentBackoff.toMillis())
            assertEquals(3L, s.consecutiveFailures)

            job.cancelAndJoin()
        }

    @Test
    fun `attempt that ran past STABLE_THRESHOLD resets backoff on next failure`() =
        runTest {
            // Drive currentBackoff up to 40s with two quick failures, then a long
            // attempt that fails after STABLE_THRESHOLD (60s) and resets the state.
            var attempts = 0
            // getMe() returns ExtendedBot. We substitute a relaxed mockk<ExtendedBot>() to
            // "succeed" without affecting semantics — supervisor only checks that the call did
            // not throw.
            val fakeMe = mockk<dev.inmo.tgbotapi.types.chat.ExtendedBot>(relaxed = true)
            coEvery { bot.getMe() } coAnswers {
                attempts++
                when (attempts) {
                    1, 2 -> throw RuntimeException("quick fail #$attempts")

                    3 -> fakeMe

                    // pretend getMe + register succeeded; polling runs below
                    else -> throw CancellationException("done")
                }
            }
            // Inject a hand-rolled fake runner that runs for 61s, then returns a Throwable to
            // simulate the polling crash. Constructed per-test so there's no inter-test leakage.
            val runner =
                object : TelegramLongPollingRunner {
                    override suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable? {
                        delay(61_000)
                        return RuntimeException("connection dropped after stable run")
                    }
                }
            val supervisorWithFakeRunner = newSupervisorWithTickingClock(testScheduler, runner = runner)

            // Timing under the supervisor's bump-BEFORE-delay retry shape (post-CRITICAL-2: tail
            // does ONE getAndUpdate that returns the pre-bump snapshot driving the upcoming
            // delay; the bumped value is what's persisted in `state` during the delay):
            //   t=0:   attempt 1 → getMe fails ("quick fail #1"). Tail bumps currentBackoff 5→10
            //          (persisted) and delays(5s — the pre-bump value).
            //   t=5:   attempt 2 → getMe fails. Tail bumps 10→20 (persisted) and delays(10s).
            //   t=15:  attempt 3 → getMe OK, runner.run starts; delay(61s) inside runner.
            //   t=76:  runner.run returns RTE. Catch: onAttemptEnded(success=false, duration=61s ≥
            //          STABLE) → reset currentBackoff=5s, consecutiveFailures=1. Tail: bump 5→10
            //          (persisted) and delays(5s — the pre-bump). Iteration 4 starts at t=81.
            //   We assert at t≈76 (just past the runner-end) AFTER the tail's getAndUpdate has
            //   run (persisted=10s) but BEFORE the tail delay has elapsed.
            val job = launch { supervisorWithFakeRunner.runSupervised() }
            advanceTimeBy(5_000 + 10_000 + 61_000 + 1) // = 76_001 ms, just after runner exits
            runCurrent()

            // Reset-then-bump pattern: onAttemptEnded reset currentBackoff to INITIAL_BACKOFF (5s).
            // The atomic tail getAndUpdate then immediately bumps it to 10s (the persisted post-bump
            // value), while the delay() runs against the returned pre-bump 5s value. The test reads
            // the persisted value here, so observes 10s. The reset is still verifiable: without it,
            // the persisted value after this point would be 60s (the prior 40s bumped + capped at
            // MAX_BACKOFF), not 10s.
            val s = supervisorWithFakeRunner.stateForTesting
            assertEquals(2 * INITIAL_BACKOFF_MS, s.currentBackoff.toMillis())
            assertEquals(1L, s.consecutiveFailures)

            job.cancelAndJoin()
        }

    @Test
    fun `attempt that crashed before STABLE_THRESHOLD does not reset backoff`() =
        runTest {
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
            // Throw CancellationException on the 4th call so the loop exits cleanly without
            // bumping the counter. A naive "always throws RuntimeException" mock would let
            // advanceTimeBy(35_000) + runCurrent() spill into a 4th attempt (the 3rd delay
            // completes at t=35_000, runCurrent fires the continuation, the loop bumps backoff
            // to 40s and starts attempt 4) — consecutiveFailures would become 4 instead of 3.
            var attempts = 0
            coEvery { bot.getMe() } coAnswers {
                attempts++
                if (attempts <= 3) {
                    throw RuntimeException("quick fail #$attempts")
                } else {
                    throw CancellationException("test done after 3 fast failures")
                }
            }

            val job = launch { supervisor.runSupervised() }
            advanceTimeBy(5_000 + 10_000 + 20_000) // three quick failures, backoff grows 5→10→20→40
            runCurrent()
            val s = supervisor.stateForTesting
            assertEquals(3L, s.consecutiveFailures)
            assertEquals(40_000L, s.currentBackoff.toMillis())

            job.cancelAndJoin()
        }

    @Test
    fun `runner clean exit before STABLE_THRESHOLD records SilentPollingFailure`() =
        runTest {
            // When runner returns null (clean exit) faster than STABLE_THRESHOLD, the supervisor
            // records a SilentPollingFailure marker and bumps consecutiveFailures. Common cause:
            // revoked bot token or library-swallowed error that exits polling without raising.
            val fakeMe = mockk<dev.inmo.tgbotapi.types.chat.ExtendedBot>(relaxed = true)
            coEvery { bot.getMe() } returns fakeMe
            val runner =
                object : TelegramLongPollingRunner {
                    override suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable? {
                        delay(10_000) // 10s < STABLE_THRESHOLD (60s)
                        return null
                    }
                }
            val supervisor = newSupervisorWithTickingClock(testScheduler, runner = runner)

            // t=0: getMe OK, runner.run starts; delay(10s) inside runner.
            // t=10s: runner returns null. onAttemptEnded(success=true, duration=10s < STABLE) →
            //        SilentPollingFailure branch: bump consecutiveFailures, write lastFailure.
            //        Tail delay(5s) starts.
            // Assert at t=10_001, 1ms into the tail delay (before next iteration begins).
            val job = launch { supervisor.runSupervised() }
            advanceTimeBy(10_001)
            runCurrent()

            val s = supervisor.stateForTesting
            assertEquals(1L, s.consecutiveFailures)
            assertNotNull(s.lastFailure)
            assertEquals("SilentPollingFailure", s.lastFailure!!.javaClass.simpleName)
            assertNotNull(s.lastFailureAt)

            job.cancelAndJoin()
        }

    @Test
    fun `cancellation propagates cleanly and leaves no failure bookkeeping`() =
        runTest {
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
            coEvery { bot.getMe() } coAnswers { awaitCancellation() }

            val job = launch { supervisor.runSupervised() }
            runCurrent()
            job.cancel()
            job.join()

            val s = supervisor.stateForTesting
            assertEquals(0L, s.consecutiveFailures)
            assertNull(s.lastFailure)
            assertNull(s.lastFailureAt)
        }

    // ----- onAttemptEnded transition tests -----
    // Per review iter-1 SUGGESTION-2: deterministic per-branch snapshot-equality assertions on
    // `onAttemptEnded`. The fixed `clock` returns `now` from `Instant.now(clock)`, so transitions
    // are deterministic. Each test asserts the FULL post-transition snapshot in a single
    // `assertEquals` — this catches any accidental field modification on top of the expected ones.

    @Test
    fun `onAttemptEnded — success stable resets counters, bumps lastStableAt, preserves sticky lastFailure`() {
        // Fixture: in-progress backoff (3 failures, 40s backoff) with a prior failure recorded.
        // attemptStart is chosen so duration = (now - attemptStart) > STABLE_THRESHOLD (60s).
        // After onAttemptEnded(success=true, duration >= STABLE):
        //   - consecutiveFailures reset to 0
        //   - currentBackoff reset to INITIAL_BACKOFF (5s)
        //   - lastStableAt = now (the clock fixture)
        //   - STICKY: lastFailure / lastFailureAt UNCHANGED (per CRITICAL-4)
        //   - All other fields preserved from the prior state.
        val attemptStart = now.minusSeconds(120) // duration = 120s > STABLE_THRESHOLD (60s)
        val initialFixture =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(10)),
                lastAttemptAt = attemptStart,
                consecutiveFailures = 3,
                currentBackoff = Duration.ofSeconds(40),
                lastFailure = RuntimeException("prior failure"),
                lastFailureAt = now.minus(Duration.ofMinutes(2)),
            )
        val sup = supervisorWithLiveJob()
        sup.stateForTesting = initialFixture
        sup.onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
        assertEquals(
            initialFixture.copy(
                consecutiveFailures = 0,
                currentBackoff = INITIAL_BACKOFF_DURATION,
                lastStableAt = now,
            ),
            sup.stateForTesting,
        )
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `onAttemptEnded — success fast records SilentPollingFailure and bumps consecutiveFailures`() {
        // Fixture: prior stable run + 2 sticky failures. attemptStart is chosen so duration = 10s
        // < STABLE_THRESHOLD (60s). After onAttemptEnded(success=true, duration < STABLE):
        //   - lastFailure = SilentPollingFailure("clean return after PT10S")
        //   - lastFailureAt = now
        //   - consecutiveFailures = 3 (was 2; bumped by 1)
        //   - All other fields preserved (notably currentBackoff — only the tail in
        //     runSupervised bumps that).
        val attemptStart = now.minusSeconds(10) // duration = 10s < STABLE_THRESHOLD
        val initialFixture =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(10)),
                lastAttemptAt = attemptStart,
                lastStableAt = now.minus(Duration.ofMinutes(5)),
                consecutiveFailures = 2,
                currentBackoff = Duration.ofSeconds(20),
                lastFailure = RuntimeException("prior failure"),
                lastFailureAt = now.minus(Duration.ofMinutes(1)),
            )
        val sup = supervisorWithLiveJob()
        sup.stateForTesting = initialFixture
        sup.onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
        val after = sup.stateForTesting
        // SilentPollingFailure is private — compare via class name + message.
        val recordedFailure = after.lastFailure!!
        assertEquals("SilentPollingFailure", recordedFailure.javaClass.simpleName)
        assertEquals("clean return after PT10S", recordedFailure.message)
        // Now full-snapshot-equality on all other fields by replacing lastFailure with the actual
        // instance (we just validated its identity above).
        assertEquals(
            initialFixture.copy(
                lastFailure = recordedFailure,
                lastFailureAt = now,
                consecutiveFailures = 3,
            ),
            after,
        )
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `onAttemptEnded — failure after stable resets backoff, sets lastStableAt to now`() {
        // Fixture: prior stable run + 4 sticky failures. attemptStart chosen so duration > STABLE.
        // After onAttemptEnded(success=false, duration >= STABLE):
        //   - lastFailure = the supplied failure
        //   - lastFailureAt = now
        //   - consecutiveFailures = 1 (reset to 1, not bumped — this branch counts as a fresh
        //     "first failure after stable run")
        //   - currentBackoff = INITIAL_BACKOFF
        //   - lastStableAt = now (the moment of crash, per existing semantics)
        //   - All other fields preserved.
        val attemptStart = now.minusSeconds(120) // duration = 120s > STABLE_THRESHOLD
        val initialFixture =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(10)),
                lastAttemptAt = attemptStart,
                lastStableAt = now.minus(Duration.ofMinutes(5)),
                consecutiveFailures = 4,
                currentBackoff = Duration.ofSeconds(40),
                lastFailure = RuntimeException("prior failure"),
                lastFailureAt = now.minus(Duration.ofMinutes(1)),
            )
        val sup = supervisorWithLiveJob()
        sup.stateForTesting = initialFixture
        val freshFailure = RuntimeException("crash after stable run")
        sup.onAttemptEnded(success = false, attemptStart = attemptStart, failure = freshFailure)
        assertEquals(
            initialFixture.copy(
                lastFailure = freshFailure,
                lastFailureAt = now,
                consecutiveFailures = 1,
                currentBackoff = INITIAL_BACKOFF_DURATION,
                lastStableAt = now,
            ),
            sup.stateForTesting,
        )
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `onAttemptEnded — failure fast bumps consecutiveFailures, preserves currentBackoff`() {
        // Fixture: 2 prior failures, currentBackoff=20s. attemptStart chosen so duration < STABLE.
        // After onAttemptEnded(success=false, duration < STABLE):
        //   - lastFailure = the supplied failure
        //   - lastFailureAt = now
        //   - consecutiveFailures = 3 (bumped by 1)
        //   - currentBackoff UNCHANGED (it's the tail bump in runSupervised that changes it).
        //   - lastStableAt UNCHANGED.
        //   - All other fields preserved.
        val attemptStart = now.minusSeconds(10) // duration = 10s < STABLE_THRESHOLD
        val initialFixture =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(10)),
                lastAttemptAt = attemptStart,
                lastStableAt = now.minus(Duration.ofMinutes(5)),
                consecutiveFailures = 2,
                currentBackoff = Duration.ofSeconds(20),
                lastFailure = RuntimeException("prior failure"),
                lastFailureAt = now.minus(Duration.ofMinutes(1)),
            )
        val sup = supervisorWithLiveJob()
        sup.stateForTesting = initialFixture
        val freshFailure = RuntimeException("quick fail")
        sup.onAttemptEnded(success = false, attemptStart = attemptStart, failure = freshFailure)
        assertEquals(
            initialFixture.copy(
                lastFailure = freshFailure,
                lastFailureAt = now,
                consecutiveFailures = 3,
            ),
            sup.stateForTesting,
        )
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — live stable polling yields UP`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minusSeconds(120),
                lastPollingStartAt = now.minusSeconds(90), // > STABLE_THRESHOLD (60s)
                consecutiveFailures = 0L,
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.UP, h.status)
        assertEquals("healthy", h.details["reason"])
        sup.supervisorJob?.cancel()
    }

    // After recovery with sticky consecutiveFailures, a fresh stable polling must still report
    // UP without waiting for onAttemptEnded to fire.
    @Test
    fun `computeHealth — live stable polling with sticky consecutiveFailures still yields UP`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minus(Duration.ofMinutes(2)), // older stable; still within freshness
                lastPollingStartAt = now.minusSeconds(70), // > STABLE_THRESHOLD
                lastFailureAt = now.minus(Duration.ofMinutes(3)), // older than pollStart → invariant ok
                consecutiveFailures = 4L, // sticky from before recovery
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.UP, h.status)
        sup.supervisorJob?.cancel()
    }

    // Invariant `lastPollingStartAt > lastFailureAt` must reject UP if a newer failure was
    // recorded after the polling stamp (i.e. attempt already crashed but iteration hasn't
    // entered the new loop body yet — stale-stamp window).
    @Test
    fun `computeHealth — stale lastPollingStartAt after newer failure does not yield UP`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minusSeconds(120),
                lastPollingStartAt = now.minusSeconds(90), // > STABLE_THRESHOLD, but...
                lastFailureAt = now.minusSeconds(30), // ...newer failure invalidates UP
                consecutiveFailures = 1L,
            )
        val h = sup.computeHealth(now)
        assertTrue(h.status != Status.UP, "expected fall-through to backoff branches, was UP")
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — startup failure threshold reached → DOWN`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minusSeconds(30), // inside grace
                consecutiveFailures = 5L, // == STARTUP_FAILURE_THRESHOLD
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.DOWN, h.status)
        assertTrue((h.details["reason"] as String).startsWith("startup failed"))
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — startup grace expired → DOWN`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(3)), // past STARTUP_GRACE (2m)
                consecutiveFailures = 1L,
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.DOWN, h.status)
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — still in grace, never stable → OUT_OF_SERVICE`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minusSeconds(30),
                consecutiveFailures = 2L, // < STARTUP_FAILURE_THRESHOLD
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, h.status)
        assertTrue((h.details["reason"] as String).startsWith("connecting"))
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — failing past HEALTH_STALENESS → DOWN`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minus(Duration.ofMinutes(10)), // > HEALTH_STALENESS (5m)
                consecutiveFailures = 3L,
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.DOWN, h.status)
        assertTrue((h.details["reason"] as String).startsWith("stale"))
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — failing but recent stable → OUT_OF_SERVICE`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minusSeconds(30), // < HEALTH_STALENESS
                consecutiveFailures = 2L,
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, h.status)
        assertTrue((h.details["reason"] as String).startsWith("in backoff"))
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth — just (re)connected, polling under STABLE_THRESHOLD → OUT_OF_SERVICE`() {
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofHours(1)),
                lastStableAt = now.minusSeconds(30),
                lastPollingStartAt = now.minusSeconds(30), // < STABLE_THRESHOLD
                consecutiveFailures = 0L,
            )
        val h = sup.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, h.status)
        assertTrue((h.details["reason"] as String).startsWith("connecting"))
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `computeHealth sanitizes bot token in lastFailure detail`() {
        // Ktor request exceptions embed the full URL `https://api.telegram.org/bot<TOKEN>/method`
        // in their message. baseBuilder() must redact the token before surfacing it in
        // /actuator/health.
        val sup = supervisorWithLiveJob()
        sup.stateForTesting =
            SupervisorState(
                startupAt = now.minus(Duration.ofMinutes(1)),
                lastStableAt = now.minusSeconds(30),
                consecutiveFailures = 1L,
                lastFailure =
                    RuntimeException(
                        "Client request(GET https://api.telegram.org/bot12345:ABCdef-xyz_token/getMe) invalid: 401",
                    ),
                lastFailureAt = now.minusSeconds(15),
            )
        val h = sup.computeHealth(now)
        val detail = h.details["lastFailure"] as String
        assertTrue(detail.contains("bot[REDACTED]"), "token must be redacted; got: $detail")
        assertTrue(!detail.contains("ABCdef-xyz_token"), "raw token must not appear: $detail")
        assertTrue(!detail.contains("12345:"), "bot id+colon prefix of token must not appear: $detail")
        // Diagnostic context preserved.
        assertTrue(detail.contains("getMe"), "method name should remain visible: $detail")
        assertTrue(detail.contains("401"), "HTTP status should remain visible: $detail")
        sup.supervisorJob?.cancel()
    }

    @Test
    fun `start() is idempotent — second call is ignored`() {
        // Documents the Spring @PostConstruct contract: a second start() call returns early
        // without launching a duplicate coroutine. Production code relies on Spring invoking
        // @PostConstruct exactly once; this test pins the guard against future refactors.
        coEvery { bot.getMe() } coAnswers { awaitCancellation() }
        val supervisor = newSupervisor()
        supervisor.start()
        val firstJob = supervisor.supervisorJob
        assertNotNull(firstJob, "first start() should set supervisorJob")
        supervisor.start() // second call — must be ignored
        assertSame(firstJob, supervisor.supervisorJob, "second start() must not replace supervisorJob")
        firstJob?.cancel()
    }

    @Test
    fun `runSupervised on scope, stopAndJoin cancels it cleanly`() =
        runTest {
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
            coEvery { bot.getMe() } coAnswers { awaitCancellation() }

            // Launch runSupervised directly so the test can drive scheduling explicitly,
            // mirroring what start() does in production (start() launches runSupervised on the
            // supervisor scope).
            supervisor.supervisorJob = supervisor.scope.launch { supervisor.runSupervised() }
            runCurrent()
            assertNotNull(supervisor.supervisorJob, "supervisorJob should be set")
            assertTrue(supervisor.supervisorJob!!.isActive, "supervisorJob should be active")

            supervisor.stopAndJoin()
            assertEquals(false, supervisor.supervisorJob!!.isActive)
        }

    @Test
    fun `computeHealth returns DOWN after stopAndJoin completes`() =
        runTest {
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
            coEvery { bot.getMe() } coAnswers { awaitCancellation() }

            supervisor.supervisorJob = supervisor.scope.launch { supervisor.runSupervised() }
            runCurrent()
            assertTrue(supervisor.supervisorJob!!.isActive)

            supervisor.stopAndJoin()

            val health = supervisor.computeHealth(now)
            assertEquals(Status.DOWN, health.status)
            assertEquals("supervisor not active", health.details["reason"])
        }

    @Test
    fun `cancellation during backoff delay leaves no failure bookkeeping`() =
        runTest {
            // Counterpart to `cancellation propagates cleanly...`: that test cancels inside
            // bot.getMe(); this one cancels while the supervisor is parked in delay(currentBackoff)
            // between failed attempts. The tail delay path must also propagate cancellation
            // without bumping consecutiveFailures or writing lastFailure.
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))
            coEvery { bot.getMe() } throws RuntimeException("attempt fails")

            val job = launch { supervisor.runSupervised() }
            runCurrent()
            // After the first attempt fails, supervisor is in delay(5s). consecutiveFailures=1.
            val beforeCancel = supervisor.stateForTesting
            assertEquals(1L, beforeCancel.consecutiveFailures)
            val failureBeforeCancel = beforeCancel.lastFailure

            // Cancel while supervisor is parked in the backoff delay.
            job.cancelAndJoin()

            // The cancellation must not bump the counter or replace lastFailure.
            val afterCancel = supervisor.stateForTesting
            assertEquals(1L, afterCancel.consecutiveFailures)
            assertSame(failureBeforeCancel, afterCancel.lastFailure)
        }

    private companion object {
        const val INITIAL_BACKOFF_MS = 5_000L
        val INITIAL_BACKOFF_DURATION: Duration = Duration.ofMillis(INITIAL_BACKOFF_MS)
    }
}
