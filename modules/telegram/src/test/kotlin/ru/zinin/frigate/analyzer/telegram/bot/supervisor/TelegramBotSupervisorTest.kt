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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
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

    // [AUTO-7] Ticking clock helpers shared by Tasks 3 and 4. A fixed clock would report
    //          duration=0 for `Duration.between(attemptStart, Instant.now(clock))`, which
    //          would silently break Task 4's stable-attempt threshold check.
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
            // [A8] Use ticking clock so future tests adding longer attempts won't silently get
            //      duration=0 on a fixed clock.
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

    @Test
    fun `attempt that ran past STABLE_THRESHOLD resets backoff on next failure`() =
        runTest {
            val supervisor = newSupervisor(StandardTestDispatcher(testScheduler))

            // Drive currentBackoff up to 40s with two quick failures, then a long
            // attempt that fails after STABLE_THRESHOLD (60s) and resets the state.
            var attempts = 0
            // [PLAN-FIX] getMe() returns ExtendedBot, not Unit. The plan's `3 -> Unit` does not
            //            compile because the `when` expression must yield an ExtendedBot or
            //            Nothing. We substitute a relaxed mockk<ExtendedBot>() to "succeed"
            //            without affecting semantics — supervisor only checks that the call
            //            did not throw.
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
            // [D2] No mockkStatic — supervisor now takes TelegramLongPollingRunner. Inject a
            //      hand-rolled fake that captures its onUpdate block, runs for 61s, then returns
            //      a Throwable to simulate the polling crash. The fake is constructed per-test
            //      so there's no inter-test leakage. (Obsoletes A4 + A2 teardown.)
            val runner =
                object : TelegramLongPollingRunner {
                    override suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable? {
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
            advanceTimeBy(5_000 + 10_000 + 61_000 + 1) // = 76_001 ms, just after runner exits
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
            // [PLAN-FIX] The plan used `coEvery { bot.getMe() } throws RuntimeException(...)`,
            //            but `advanceTimeBy(35_000) + runCurrent()` advances the test scheduler
            //            into iteration 4 (the 3rd delay completes at exactly t=35_000 and
            //            runCurrent fires the continuation, then the loop bumps backoff to 40s
            //            and starts attempt 4). With "always fails", that 4th attempt also
            //            throws RuntimeException → consecutiveFailures becomes 4 (not 3).
            //            Mirror the retry-progression test (Task 3): throw CancellationException
            //            on the 4th call so the loop exits cleanly without bumping the counter,
            //            preserving the plan's documented (3, 40_000) end-state.
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
            assertEquals(3L, supervisor.consecutiveFailures)
            assertEquals(40_000L, supervisor.currentBackoff.toMillis())

            job.cancelAndJoin()
        }

    private companion object {
        const val INITIAL_BACKOFF_MS = 5_000L
    }
}
