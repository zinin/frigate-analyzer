package ru.zinin.frigate.analyzer.core.application

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

class StartupTelegramNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val gitProperties = mockk<GitProperties>()
    private val buildProperties = mockk<BuildProperties>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T15:14:00Z"), ZoneOffset.UTC)

    private val notifier =
        StartupTelegramNotifier(
            telegramNotificationService = telegramNotificationService,
            gitProperties = gitProperties,
            buildProperties = buildProperties,
            clock = clock,
        )

    @BeforeEach
    fun setUp() {
        clearMocks(telegramNotificationService, gitProperties, buildProperties)
    }

    @AfterEach
    fun tearDown() {
        notifier.shutdown()
    }

    private fun awaitStartupNotification() =
        runBlocking {
            notifier.scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    @Test
    fun `onReady sends owner message with version commit buildTime and started`() {
        every { gitProperties.commitId } returns "abc1234567890def"
        every { buildProperties.version } returns "1.2.3"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } just Runs

        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) {
            telegramNotificationService.sendOwnerMessage(
                match { text ->
                    text.contains("Frigate Analyzer запущен") &&
                        text.contains("Version: 1.2.3") &&
                        text.contains("Commit: abc12345") &&
                        text.contains("Build time: 2026-05-20T10:00:00Z") &&
                        text.contains("Started: 2026-05-23T15:14:00Z")
                },
            )
        }
    }

    @Test
    fun `onReady swallows exception from sendOwnerMessage`() {
        every { gitProperties.commitId } returns "abc1234"
        every { buildProperties.version } returns "1.0.0"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws RuntimeException("boom")

        // Should not throw:
        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
    }

    @Test
    fun `onReady uses unknown placeholder when build and git properties are null`() {
        every { gitProperties.commitId } returns null
        every { buildProperties.version } returns null
        every { buildProperties.time } returns null
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } just Runs

        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) {
            telegramNotificationService.sendOwnerMessage(
                match { text ->
                    text.contains("Version: <unknown>") &&
                        text.contains("Commit: <unknown>") &&
                        text.contains("Build time: <unknown>")
                },
            )
        }
    }

    @Test
    fun `onReady swallows CancellationException from timeout`() {
        every { gitProperties.commitId } returns "abc1234"
        every { buildProperties.version } returns "1.0.0"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        // We throw a plain CancellationException — kotlinx.coroutines.TimeoutCancellationException
        // extends it but has an internal constructor, so we can't instantiate it directly.
        // The fire-and-forget launch isolates the cancellation to the child coroutine;
        // it must never surface out of onReady().
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws
            CancellationException("simulated timeout")

        // Must not throw:
        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
    }
}
