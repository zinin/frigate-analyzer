package ru.zinin.frigate.analyzer.telegram.bot.handler.cancel

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.answers.AnswerCallbackQuery
import dev.inmo.tgbotapi.requests.edit.reply_markup.EditChatMessageReplyMarkup
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ActiveExportRegistry
import ru.zinin.frigate.analyzer.telegram.bot.handler.export.ExportCoroutineScope
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.Locale
import java.util.UUID
import kotlin.test.assertTrue

class CancelExportHandlerTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val bot: TelegramBot = mockk(relaxed = true)
    private val authFilter: AuthorizationFilter = mockk(relaxed = true)
    private val userService: TelegramUserService = mockk(relaxed = true)
    private val scope = ExportCoroutineScope()
    private val registry = ActiveExportRegistry(scope)

    private val handler = CancelExportHandler(bot, registry, scope, authFilter, userService, msg)

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        // Match ActiveExportRegistryTest / ExportExecutorTest pattern — the happy-path test below
        // triggers registry.attachCancellable(...), which launches coroutines on the real
        // Dispatchers.IO inside ExportCoroutineScope. Without shutdown() those coroutines would leak
        // across subsequent tests on CI.
        scope.shutdown()
    }

    @Test
    fun `handle on noop prefix answers silently without side effects`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.NOOP_PREFIX}${UUID.randomUUID()}"
                    every { it.id } returns CallbackQueryId("cbq-1")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER

            handler.handle(cb)

            // No registry lookup, no keyboard edit — only ack.
            coVerify { bot.execute(match<AnswerCallbackQuery> { it.callbackQueryId == CallbackQueryId("cbq-1") }) }
            coVerify(exactly = 0) { bot.execute(any<EditChatMessageReplyMarkup>()) }
        }

    @Test
    fun `handle for unknown exportId responds with not-active message`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val unknown = UUID.randomUUID()
            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$unknown"
                    every { it.id } returns CallbackQueryId("cbq-2")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            val answerSlot = slot<AnswerCallbackQuery>()
            coVerify { bot.execute(capture(answerSlot)) }
            assertTrue(
                answerSlot.captured.text!!.contains("already finished or unavailable"),
                "actual: ${answerSlot.captured.text}",
            )
        }

    @Test
    fun `handle for cancel happy path marks registry cancelling, cancels job, calls cancellable`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val exportId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val job = Job()
            registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, recordingId, job)
            // `attachCancellable` dispatches the cancel via `exportScope.launch` on Dispatchers.IO —
            // a real dispatcher, not controlled by runTest's virtual time. `delay(50)` under runTest
            // is virtual (0 ms real), so it can't wait for an IO thread. CompletableDeferred +
            // withTimeout(5s) gives deterministic sync — mirrors ActiveExportRegistryTest pattern.
            val cancellableCalled = CompletableDeferred<Unit>()
            registry.attachCancellable(exportId, CancellableJob { cancellableCalled.complete(Unit) })

            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
                    every { it.id } returns CallbackQueryId("cbq-3")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            // Registry state flipped
            val entry = registry.get(exportId)
            assertTrue(entry != null && entry.state == ActiveExportRegistry.State.CANCELLING)
            // Coroutine Job is cancelled
            assertTrue(job.isCancelled)
            // Deterministic wait for the fire-and-forget cancellable.cancel() launched on Dispatchers.IO.
            withTimeout(5_000) { cancellableCalled.await() }
        }

    @Test
    fun `handle for second cancel click returns already-cancelling`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val exportId = UUID.randomUUID()
            registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, UUID.randomUUID(), Job())
            registry.markCancelling(exportId)

            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
                    every { it.id } returns CallbackQueryId("cbq-4")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            val answerSlot = slot<AnswerCallbackQuery>()
            coVerify { bot.execute(capture(answerSlot)) }
            assertTrue(
                answerSlot.captured.text!!.contains("already in progress"),
                "actual: ${answerSlot.captured.text}",
            )
        }

    @Test
    fun `handle for chat mismatch treats as not-active`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val exportId = UUID.randomUUID()
            // Note: 222L below is a DIFFERENT chat than the caller's 111L — triggers the mismatch branch.
            registry.tryStartQuickExport(exportId, 222L, ExportMode.ANNOTATED, UUID.randomUUID(), Job())

            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
                    every { it.id } returns CallbackQueryId("cbq-5")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            val answerSlot = slot<AnswerCallbackQuery>()
            coVerify { bot.execute(capture(answerSlot)) }
            assertTrue(
                answerSlot.captured.text!!.contains("already finished or unavailable"),
                "actual: ${answerSlot.captured.text}",
            )
            // Registry state unchanged
            assertTrue(registry.get(exportId)!!.state == ActiveExportRegistry.State.ACTIVE)
        }

    @Test
    fun `handle for unauthorized user responds with unauthorized message`() =
        runTest {
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@bob"))
                }
            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}${UUID.randomUUID()}"
                    every { it.id } returns CallbackQueryId("cbq-6")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Bob",
                            username = Username("@bob"),
                        )
                }
            coEvery { authFilter.getRole("bob") } returns null
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            val answerSlot = slot<AnswerCallbackQuery>()
            coVerify { bot.execute(capture(answerSlot)) }
            assertTrue(
                answerSlot.captured.text!!.contains("not authorized"),
                "actual: ${answerSlot.captured.text}",
            )
        }

    @Test
    fun `handle answers with format error on malformed cancel data`() =
        runTest {
            // Guards iter-2 codex TEST-2: design §8 requires test for malformed callback data.
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}not-a-valid-uuid"
                    every { it.id } returns CallbackQueryId("cbq-malformed")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"

            handler.handle(cb)

            val answerSlot = slot<AnswerCallbackQuery>()
            coVerify { bot.execute(capture(answerSlot)) }
            assertTrue(
                answerSlot.captured.text!!.contains("invalid", ignoreCase = true) ||
                    answerSlot.captured.text!!.contains("cancel parameter", ignoreCase = true),
                "actual: ${answerSlot.captured.text}",
            )
        }

    @Test
    fun `handle survives editMessageReplyMarkup failure and still cancels the job`() =
        runTest {
            // Guards iter-2 codex TEST-2: design §8 requires test for Telegram API errors during
            // edit keyboard. `runCatching` in handler must not let the error abort the cancellation.
            val chatId = ChatId(RawChatId(111L))
            val msgMock =
                mockk<ContentMessage<MessageContent>>(relaxed = true).also {
                    every { it.chat } returns PrivateChatImpl(id = chatId, username = Username("@alice"))
                }
            val exportId = UUID.randomUUID()
            val job = Job()
            registry.tryStartQuickExport(exportId, 111L, ExportMode.ANNOTATED, UUID.randomUUID(), job)
            val cb =
                mockk<MessageDataCallbackQuery>(relaxed = true).also {
                    every { it.data } returns "${CancelExportHandler.CANCEL_PREFIX}$exportId"
                    every { it.id } returns CallbackQueryId("cbq-edit-fail")
                    every { it.message } returns msgMock
                    every { it.user } returns
                        CommonUser(
                            id = ChatId(RawChatId(123L)),
                            firstName = "Alice",
                            username = Username("@alice"),
                        )
                }
            coEvery { authFilter.getRole("alice") } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "en"
            // Simulate the edit keyboard call failing (e.g. Telegram API error / message deleted).
            coEvery { bot.execute(any<EditChatMessageReplyMarkup>()) } throws RuntimeException("api down")

            handler.handle(cb)

            // Despite the edit failure, registry state MUST flip and the job MUST be cancelled.
            val entry = registry.get(exportId)
            assertTrue(entry != null && entry.state == ActiveExportRegistry.State.CANCELLING)
            assertTrue(job.isCancelled)
        }

    @Test
    fun `parseExportId returns UUID for valid cancel data`() {
        val id = UUID.randomUUID()
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.CANCEL_PREFIX}$id")
        assertTrue(parsed == id)
    }

    @Test
    fun `parseExportId returns UUID for valid noop data`() {
        val id = UUID.randomUUID()
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.NOOP_PREFIX}$id")
        assertTrue(parsed == id)
    }

    @Test
    fun `parseExportId returns null for invalid UUID`() {
        val parsed = CancelExportHandler.parseExportId("${CancelExportHandler.CANCEL_PREFIX}not-a-uuid")
        assertTrue(parsed == null)
    }
}
