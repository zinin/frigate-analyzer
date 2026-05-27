package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
}
