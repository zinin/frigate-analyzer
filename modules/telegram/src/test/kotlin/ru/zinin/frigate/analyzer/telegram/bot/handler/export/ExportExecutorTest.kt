package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportExecutorTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )

    // Track every scope the tests create so @AfterEach can clean them up even if a test fails
    // before reaching its own `scope.shutdown()`. Consistent with QuickExportHandlerTest pattern —
    // prevents coroutine leaks across tests.
    private val createdScopes = ConcurrentLinkedQueue<ExportCoroutineScope>()

    private fun newScope(): ExportCoroutineScope = ExportCoroutineScope().also { createdScopes.add(it) }

    @AfterEach
    fun tearDown() {
        createdScopes.forEach { runCatching { it.shutdown() } }
        createdScopes.clear()
    }

    @Test
    fun `cancelled export — final editMessageText with null keyboard + registry released`() =
        runTest {
            val bot: TelegramBot = mockk(relaxed = true)
            val videoExportService: VideoExportService = mockk(relaxed = true)
            val properties =
                mockk<TelegramProperties>(relaxed = true).also {
                    every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
                }
            val scope = newScope()
            val registry = ActiveExportRegistry(scope)
            val executor = ExportExecutor(bot, videoExportService, properties, msg, registry, scope)

            val chatId = ChatId(RawChatId(42L))

            // Capture bot requests — matching extension functions against IdChatIdentifier
            // inline-class hierarchies trips mockk's matcher logic (packRef null), so we verify via
            // the underlying bot.execute(...) requests instead (same fallback pattern as
            // CancelExportHandlerTest and QuickExportHandlerTest's cancellation test).
            // SendTextMessage returns a ContentMessage — default `mockk(relaxed = true)` yields
            // a plain java.lang.Object that crashes on the checkcast into `statusMessage`.
            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                val req = firstArg<Request<*>>()
                capturedRequests.add(req)
                if (req is SendTextMessage) {
                    // sendTextMessage → ContentMessage. editMessageText (used later on
                    // statusMessage) accesses `.chat` and passes it down to tgbotapi's inline-class
                    // chat identifiers. A plain relaxed mock returns default `Object`s which blow
                    // up inside BusinessChatId.getBusinessConnectionId. Back the chat with a real
                    // PrivateChatImpl so the inline-class path is exercised cleanly.
                    mockk<ContentMessage<MessageContent>>(relaxed = true).apply {
                        every { chat } returns PrivateChatImpl(id = chatId, firstName = "Test")
                    }
                } else {
                    mockk(relaxed = true)
                }
            }

            coEvery {
                videoExportService.exportVideo(any(), any(), any(), any(), any(), any())
            } coAnswers { awaitCancellation() }

            val outcome =
                ExportDialogOutcome.Success(
                    Instant.parse("2026-02-16T12:00:00Z"),
                    Instant.parse("2026-02-16T12:05:00Z"),
                    "cam1",
                    ExportMode.ANNOTATED,
                )

            val executeJob = scope.launch { executor.execute(chatId, ZoneOffset.UTC, outcome, "en") }

            // Wait for registry to have the entry. Use a bounded-timeout loop rather than a fixed
            // `repeat(50)` with `delay(10)` — the loop exits as soon as the entry appears, so fast
            // CI runs aren't blocked, and the 5s ceiling handles slow CI without flakiness.
            val exportId =
                withTimeout(5_000) {
                    var id: UUID? = null
                    while (id == null) {
                        id = firstActiveExport(registry)
                        if (id == null) delay(10)
                    }
                    id
                }

            // Simulate cancel click. Capture the export-job reference BEFORE cancelling — once
            // release() runs (via finally + invokeOnCompletion), registry.get(exportId) returns null.
            val exportJob = registry.get(exportId)!!.job
            registry.markCancelling(exportId)
            exportJob.cancel()
            // Wait for the export-job's finally block (including registry.release()) to run. We
            // CANNOT rely on executeJob.join() — execute() is fire-and-forget after iter-3
            // CRITICAL-11, so executeJob completes almost instantly after job.start(), long before
            // the export-job's body (which awaitCancellation's on the mock) gets to its finally
            // block. exportJob.join() blocks until body + finally + all invokeOnCompletion
            // handlers have run (iter-4 codex DESIGN-1). executeJob is then joined to cleanly end
            // the launcher coroutine.
            exportJob.join()
            executeJob.join()

            // Final editMessageText should have been called with the cancelled text and null
            // keyboard. Verify via captured requests (see comment at capture site for why we avoid
            // the coVerify+match pattern).
            val editRequests = capturedRequests.filterIsInstance<EditChatMessageText>()
            val cancelledEdit =
                editRequests.find {
                    it.text.contains("cancelled", ignoreCase = true) ||
                        it.text.contains("Отменён")
                }
            assertNotNull(
                cancelledEdit,
                "Expected a cancelled editMessageText, got: ${editRequests.map { it.text }}",
            )
            assertNull(cancelledEdit.replyMarkup, "Cancelled edit must clear the reply markup")
            // Registry released — guaranteed by exportJob.join() above (not a timing assertion).
            assertNull(registry.get(exportId))

            scope.shutdown()
        }

    @Test
    fun `second parallel execute — returns DuplicateChat and sends concurrent message`() =
        runTest {
            // Guards iter-2 ccs/glm BUG-10: the dedup path for /export was previously an empty stub.
            val bot: TelegramBot = mockk(relaxed = true)
            val videoExportService: VideoExportService = mockk(relaxed = true)
            val properties =
                mockk<TelegramProperties>(relaxed = true).also {
                    every { it.sendVideoTimeout } returns Duration.ofSeconds(30)
                }
            val scope = newScope()
            val registry = ActiveExportRegistry(scope)
            val executor = ExportExecutor(bot, videoExportService, properties, msg, registry, scope)

            val chatId = ChatId(RawChatId(42L))

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                val req = firstArg<Request<*>>()
                capturedRequests.add(req)
                if (req is SendTextMessage) {
                    // sendTextMessage → ContentMessage. editMessageText (used later on
                    // statusMessage) accesses `.chat` and passes it down to tgbotapi's inline-class
                    // chat identifiers. A plain relaxed mock returns default `Object`s which blow
                    // up inside BusinessChatId.getBusinessConnectionId. Back the chat with a real
                    // PrivateChatImpl so the inline-class path is exercised cleanly.
                    mockk<ContentMessage<MessageContent>>(relaxed = true).apply {
                        every { chat } returns PrivateChatImpl(id = chatId, firstName = "Test")
                    }
                } else {
                    mockk(relaxed = true)
                }
            }

            // First export suspends forever — holds the chat slot in the registry.
            coEvery {
                videoExportService.exportVideo(any(), any(), any(), any(), any(), any())
            } coAnswers { awaitCancellation() }

            val outcome =
                ExportDialogOutcome.Success(
                    Instant.parse("2026-02-16T12:00:00Z"),
                    Instant.parse("2026-02-16T12:05:00Z"),
                    "cam1",
                    ExportMode.ANNOTATED,
                )

            val firstJob = scope.launch { executor.execute(chatId, ZoneOffset.UTC, outcome, "en") }
            // Wait for first export to register.
            withTimeout(2_000) {
                while (registry.snapshotForTest().isEmpty()) delay(10)
            }

            // Second execute must see DuplicateChat and send "concurrent" message instead.
            executor.execute(chatId, ZoneOffset.UTC, outcome, "en")

            val expectedConcurrent = msg.get("export.error.concurrent", "en")
            val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
            assertTrue(
                sendTextRequests.any { it.text.contains(expectedConcurrent) },
                "Expected concurrent message ('$expectedConcurrent'), got: ${sendTextRequests.map { it.text }}",
            )

            firstJob.cancel()
            firstJob.join()
            scope.shutdown()
        }

    private fun firstActiveExport(registry: ActiveExportRegistry): UUID? = registry.snapshotForTest().keys.firstOrNull()
}
