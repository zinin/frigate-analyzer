package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

    // ----- computeHealth branches -----

    @Test
    fun `computeHealth returns DOWN when supervisor not started`() {
        val supervisor = newSupervisor()
        val health = supervisor.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertEquals("supervisor not active", health.details["reason"])
    }
}
