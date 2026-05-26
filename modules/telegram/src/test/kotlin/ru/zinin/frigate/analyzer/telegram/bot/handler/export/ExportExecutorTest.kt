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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    // Test-backed ExportCoroutineScope wired to the active runTest's scheduler. Without this the
    // export coroutines would run on Dispatchers.IO while the test body runs on virtual time —
    // the two-clock race made these tests flaky on CI (see GH run 26456936612). The internal
    // delegate-taking ctor accepts any CoroutineScope; UnconfinedTestDispatcher runs eagerly inline,
    // so the registry is populated synchronously by the time `scope.launch { ... }` returns.
    private fun TestScope.newTestScope(): ExportCoroutineScope =
        ExportCoroutineScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler))).also {
            createdScopes.add(it)
        }

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
            val scope = newTestScope()
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

            // UnconfinedTestDispatcher runs eagerly inline, so by this point executor.execute()
            // has already populated the registry and the lazy job has reached awaitCancellation().
            val exportId = firstActiveExport(registry)!!

            // Simulate cancel click. Capture the export-job reference BEFORE cancelling — once
            // release() runs (via finally + invokeOnCompletion), registry.get(exportId) returns null.
            val exportJob = registry.get(exportId)!!.job
            registry.markCancelling(exportId)
            exportJob.cancel()
            // exportJob.join() blocks until body + finally + all invokeOnCompletion handlers have
            // run. With UnconfinedTestDispatcher the cancellation cleanup (including the final
            // editMessageText and registry.release) runs inline, so this returns immediately.
            // executeJob.join() then cleanly ends the launcher coroutine.
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
            val scope = newTestScope()
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

            // UnconfinedTestDispatcher runs scope.launch eagerly inline — by the time firstJob is
            // assigned, the first export has already populated the registry and suspended at
            // awaitCancellation. No polling needed.
            val firstJob = scope.launch { executor.execute(chatId, ZoneOffset.UTC, outcome, "en") }

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
