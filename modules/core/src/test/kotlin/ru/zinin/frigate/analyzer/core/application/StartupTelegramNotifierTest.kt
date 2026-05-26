package ru.zinin.frigate.analyzer.core.application

import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertTrue

class StartupTelegramNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val messageResolver = mockk<MessageResolver>()
    private val gitProperties = mockk<GitProperties>()
    private val buildProperties = mockk<BuildProperties>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T15:14:00Z"), ZoneOffset.UTC)

    private val notifier =
        StartupTelegramNotifier(
            telegramNotificationService = telegramNotificationService,
            messageResolver = messageResolver,
            gitProperties = gitProperties,
            buildProperties = buildProperties,
            clock = clock,
        )

    @BeforeEach
    fun setUp() {
        clearMocks(
            telegramNotificationService,
            messageResolver,
            gitProperties,
            buildProperties,
        )
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

    /**
     * Stub sendOwnerMessage and return a slot that captures the (language -> text) builder.
     * The service mock never invokes the builder during the call itself, so tests exercise
     * it explicitly with the language the real service would pass at runtime.
     */
    private fun stubSendOwnerMessageAndCaptureBuilder(): CapturingSlot<(String) -> String> {
        val builderSlot = slot<(String) -> String>()
        coEvery { telegramNotificationService.sendOwnerMessage(capture(builderSlot)) } just Runs
        return builderSlot
    }

    @Test
    fun `onReady passes localized startup message to sendOwnerMessage for ru language`() {
        every { messageResolver.get("startup.notification.message", "ru") } returns "🟢 Frigate Analyzer запущен"
        every { gitProperties.commitId } returns "abc1234567890def"
        every { buildProperties.version } returns "1.2.3"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        val builderSlot = stubSendOwnerMessageAndCaptureBuilder()

        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
        val text = builderSlot.captured.invoke("ru")
        assertTrue(text.contains("Frigate Analyzer запущен"))
        assertTrue(text.contains("Version: 1.2.3"))
        assertTrue(text.contains("Commit: abc12345"))
        assertTrue(text.contains("Build time: 2026-05-20T10:00:00Z"))
        assertTrue(text.contains("Started: 2026-05-23T15:14:00Z"))
    }

    @Test
    fun `onReady builder resolves english key when invoked with en language`() {
        every { messageResolver.get("startup.notification.message", "en") } returns "🟢 Frigate Analyzer started"
        every { gitProperties.commitId } returns "abc1234567890def"
        every { buildProperties.version } returns "1.2.3"
        every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
        val builderSlot = stubSendOwnerMessageAndCaptureBuilder()

        notifier.onReady()
        awaitStartupNotification()

        val text = builderSlot.captured.invoke("en")
        assertTrue(text.contains("Frigate Analyzer started"))
    }

    @Test
    fun `onReady swallows exception from sendOwnerMessage`() {
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws RuntimeException("boom")

        // Should not throw:
        notifier.onReady()
        awaitStartupNotification()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
    }

    @Test
    fun `onReady uses unknown placeholder when build and git properties are null`() {
        every { messageResolver.get("startup.notification.message", "ru") } returns "🟢 Frigate Analyzer запущен"
        every { gitProperties.commitId } returns null
        every { buildProperties.version } returns null
        every { buildProperties.time } returns null
        val builderSlot = stubSendOwnerMessageAndCaptureBuilder()

        notifier.onReady()
        awaitStartupNotification()

        val text = builderSlot.captured.invoke("ru")
        assertTrue(text.contains("Version: <unknown>"))
        assertTrue(text.contains("Commit: <unknown>"))
        assertTrue(text.contains("Build time: <unknown>"))
    }

    @Test
    fun `onReady swallows CancellationException from timeout`() {
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
